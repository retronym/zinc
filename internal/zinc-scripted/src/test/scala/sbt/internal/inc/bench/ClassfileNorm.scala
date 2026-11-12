/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.internal.inc
package bench

import java.io.{ PrintWriter, StringWriter }
import java.security.MessageDigest
import java.util.Map as JMap

import scala.util.control.NonFatal

import org.objectweb.asm.{
  Attribute,
  ClassReader,
  ClassVisitor,
  ClassWriter,
  FieldVisitor,
  Handle,
  Label,
  MethodVisitor,
  Opcodes,
  RecordComponentVisitor
}
import org.objectweb.asm.signature.{ SignatureReader, SignatureWriter }
import org.objectweb.asm.util.{ Textifier, TextifierSupport, TraceClassVisitor }

/**
 * Compares classfiles through a ladder of normalisations, to say how two classfiles differ
 * rather than only that they do. scalac's output varies with the compilation batch in ways that
 * mean nothing to a JVM: the order of the constant pool, the fresh names of synthetic locals,
 * whether a type variable is called `A` or `A$`. Each [[Level]] ignores one more of these.
 *
 * The normalised digests are taken from a rendering of the class as text that has no
 * constant-pool indices in it. Instructions, operands, line numbers, frames, exception tables,
 * annotations, inner classes, flags and the order of members are all in that rendering, so a
 * difference in any of them is a difference at every level.
 *
 * Attributes that ASM doesn't know are rendered as their bytes, except two. `ScalaInlineInfo`
 * holds constant-pool indices, so it is decoded and its references rendered as the strings they
 * point at. `TASTY`, the UUID of a Scala 3 class's `.tasty` file, is left out of the normalised
 * digests and reported as [[Digests.tastyUuid]], so that a caller can compare the `.tasty` files
 * instead.
 */
object ClassfileNorm:

  /** What a comparison ignores. Each level ignores what the ones before it do, and one more thing. */
  enum Level:
    /** Nothing: the bytes are equal. */
    case Raw

    /** The order of the constant pool, and so the indices into it. */
    case Pool

    /**
     * And the names in `LocalVariableTable` and `LocalVariableTypeTable`. Slots, descriptors,
     * ranges and the code that uses them still count.
     */
    case Locals

    /**
     * And trailing `$`s on the names of type variables in generic signatures: scalac names a
     * type variable `A` or `A$` depending on the batch (scala/scala#11289, #11319). Type
     * variables are not otherwise renamed, so `A` and `B` still differ.
     */
    case TypeVars
  end Level

  /**
   * A classfile's digest at each [[Level]], as 16 hex digits of SHA-256, and the UUID in its
   * `TASTY` attribute if it has one. The UUID is part of `raw` only.
   */
  final case class Digests(
      raw: String,
      pool: String,
      locals: String,
      typeVars: String,
      tastyUuid: Option[String]
  ):
    def at(level: Level): String = level match
      case Level.Raw      => raw
      case Level.Pool     => pool
      case Level.Locals   => locals
      case Level.TypeVars => typeVars
  end Digests

  def digests(bytes: Array[Byte]): Digests =
    val (pool, uuid) = render(bytes, localNames = true, typeVarSuffix = true, tasty = false)
    val (locals, _) = render(bytes, localNames = false, typeVarSuffix = true, tasty = false)
    val (typeVars, _) = render(bytes, localNames = false, typeVarSuffix = false, tasty = false)
    Digests(sha(bytes), sha(pool), sha(locals), sha(typeVars), uuid)

  /**
   * The lowest level at which the two are equal, or `None` if they differ at every level.
   * `tastyUuid` is not compared above [[Level.Raw]].
   */
  def level(a: Digests, b: Digests): Option[Level] =
    Level.values.find(l => a.at(l) == b.at(l))

  /**
   * The class as text, for a reader: what [[Level.Pool]] compares, and the `TASTY` attribute.
   * It has no constant-pool indices in it.
   */
  def text(bytes: Array[Byte]): String =
    render(bytes, localNames = true, typeVarSuffix = true, tasty = true)._1

  /** The lines of [[text]] that differ, those of `a` after `-` and those of `b` after `+`. */
  def diff(a: Array[Byte], b: Array[Byte], max: Int = 40): Seq[String] =
    lineDiff(text(a).linesIterator.toVector, text(b).linesIterator.toVector).take(max)

  /** `signature` without the trailing `$`s of its type variables' names. */
  def stripTypeVarSuffix(signature: String, isType: Boolean): String =
    def strip(name: String): String =
      val stripped = name.reverse.dropWhile(_ == '$').reverse
      if stripped.isEmpty then name else stripped
    val writer = new SignatureWriter:
      override def visitFormalTypeParameter(name: String): Unit =
        super.visitFormalTypeParameter(strip(name))
      override def visitTypeVariable(name: String): Unit =
        super.visitTypeVariable(strip(name))
    try
      val reader = SignatureReader(signature)
      if isType then reader.acceptType(writer) else reader.accept(writer)
      writer.toString
    catch case NonFatal(_) => signature

  private[bench] def lineDiff(a: Vector[String], b: Vector[String]): Seq[String] =
    val prefix = a.lazyZip(b).takeWhile(_ == _).size
    val suffix =
      a.drop(prefix).reverseIterator.zip(b.drop(prefix).reverseIterator).takeWhile(_ == _).size
    val x = a.slice(prefix, a.size - suffix)
    val y = b.slice(prefix, b.size - suffix)
    if x.size.toLong * y.size > MaxLcsCells then x.map("-" + _) ++ y.map("+" + _)
    else
      val lcs = Array.ofDim[Int](x.size + 1, y.size + 1)
      for
        i <- x.indices.reverse
        j <- y.indices.reverse
      do
        lcs(i)(j) =
          if x(i) == y(j) then lcs(i + 1)(j + 1) + 1
          else math.max(lcs(i + 1)(j), lcs(i)(j + 1))
      val out = Vector.newBuilder[String]
      var i = 0
      var j = 0
      while i < x.size || j < y.size do
        if i < x.size && j < y.size && x(i) == y(j) then
          i += 1
          j += 1
        else if j == y.size || (i < x.size && lcs(i + 1)(j) >= lcs(i)(j + 1)) then
          out += "-" + x(i)
          i += 1
        else
          out += "+" + y(j)
          j += 1
      out.result()
    end if
  end lineDiff

  private final val MaxLcsCells = 4000000L
  private final val InlineInfo = "ScalaInlineInfo"
  private final val TastyAttribute = "TASTY"

  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString.take(16)

  private def sha(text: String): String = sha(text.getBytes("UTF-8"))

  private def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  private def render(
      bytes: Array[Byte],
      localNames: Boolean,
      typeVarSuffix: Boolean,
      tasty: Boolean
  ): (String, Option[String]) =
    val printer = Text()
    val canon = Canon(TraceClassVisitor(null, printer, null), localNames, typeVarSuffix, tasty)
    ClassReader(bytes).accept(canon, Array[Attribute](InlineInfoPrototype()), 0)
    val out = StringWriter()
    val writer = PrintWriter(out)
    printer.print(writer)
    writer.flush()
    (out.toString, canon.tastyUuid)

  /** An attribute whose content is already text with no constant-pool indices in it. */
  private final class Rendered(name: String, val content: String)
      extends Attribute(name)
      with TextifierSupport:
    override def textify(out: java.lang.StringBuilder, labelNames: JMap[Label, String]): Unit =
      out.append(" : ").append(content).append('\n')

  private def rendered(attribute: Attribute): Rendered = attribute match
    case r: Rendered => r
    case _           => Rendered(attribute.`type`, hex(contentOf(attribute)))

  private def contentOf(attribute: Attribute): Array[Byte] =
    Attribute.write(attribute, ClassWriter(0), null, 0, -1, -1)

  /**
   * Reads scalac's `ScalaInlineInfo`, whose strings are references to the constant pool, into
   * text that has the strings themselves. A version it doesn't know stays as bytes.
   */
  private final class InlineInfoPrototype extends Attribute(InlineInfo):
    override protected def read(
        reader: ClassReader,
        offset: Int,
        length: Int,
        buffer: Array[Char],
        codeOffset: Int,
        labels: Array[Label]
    ): Attribute =
      def raw = hex(Array.tabulate(length)(i => reader.readByte(offset + i).toByte))
      var next = offset
      def byte(): Int =
        val r = reader.readByte(next)
        next += 1
        r
      def short(): Int =
        val r = reader.readUnsignedShort(next)
        next += 2
        r
      def utf8(): String =
        val r = reader.readUTF8(next, buffer)
        next += 2
        r
      def decode(): String =
        val out = StringBuilder()
        val flags = byte()
        out.append(s"version 1, flags $flags")
        if (flags & 2) != 0 then out.append(s", self ${utf8()}")
        if (flags & 4) != 0 then out.append(s", sam ${utf8()}${utf8()}")
        for _ <- 0 until short() do out.append(s"\n    ${utf8()}${utf8()} ${byte()}")
        if next != offset + length then throw IllegalArgumentException("trailing bytes")
        out.toString
      val content =
        if length == 0 || reader.readByte(offset) != 1 then raw
        else
          next += 1
          try decode()
          catch case NonFatal(_) => raw
      Rendered(InlineInfo, content)
    end read
  end InlineInfoPrototype

  /**
   * Passes a class on to `next` with its unknown attributes [[rendered]], and without what the
   * level being computed ignores.
   */
  private final class Canon(
      next: ClassVisitor,
      localNames: Boolean,
      typeVarSuffix: Boolean,
      tasty: Boolean
  ) extends ClassVisitor(Opcodes.ASM9, next):
    var tastyUuid: Option[String] = None

    private def sig(signature: String, isType: Boolean): String =
      if typeVarSuffix || signature == null then signature
      else stripTypeVarSuffix(signature, isType)

    override def visit(
        version: Int,
        access: Int,
        name: String,
        signature: String,
        superName: String,
        interfaces: Array[String]
    ): Unit =
      super.visit(version, access, name, sig(signature, isType = false), superName, interfaces)

    override def visitAttribute(attribute: Attribute): Unit =
      if attribute.`type` == TastyAttribute then
        tastyUuid = Some(hex(contentOf(attribute)))
        if tasty then super.visitAttribute(rendered(attribute))
      else super.visitAttribute(rendered(attribute))

    override def visitRecordComponent(
        name: String,
        descriptor: String,
        signature: String
    ): RecordComponentVisitor =
      val to = super.visitRecordComponent(name, descriptor, sig(signature, isType = true))
      new RecordComponentVisitor(Opcodes.ASM9, to):
        override def visitAttribute(attribute: Attribute): Unit =
          super.visitAttribute(rendered(attribute))

    override def visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String,
        value: Object
    ): FieldVisitor =
      val to = super.visitField(access, name, descriptor, sig(signature, isType = true), value)
      new FieldVisitor(Opcodes.ASM9, to):
        override def visitAttribute(attribute: Attribute): Unit =
          super.visitAttribute(rendered(attribute))

    override def visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String,
        exceptions: Array[String]
    ): MethodVisitor =
      val to =
        super.visitMethod(access, name, descriptor, sig(signature, isType = false), exceptions)
      new MethodVisitor(Opcodes.ASM9, to):
        override def visitAttribute(attribute: Attribute): Unit =
          super.visitAttribute(rendered(attribute))
        override def visitLocalVariable(
            name: String,
            descriptor: String,
            signature: String,
            start: Label,
            end: Label,
            index: Int
        ): Unit =
          super.visitLocalVariable(
            if localNames then name else "_",
            descriptor,
            sig(signature, isType = true),
            start,
            end,
            index
          )
    end visitMethod
  end Canon

  /**
   * A `Textifier` that also says which kind of number a constant is: `LDC 1` is otherwise the
   * same line for an `Integer` and a `Long`.
   */
  private final class Text extends Textifier(Opcodes.ASM9):
    override protected def createTextifier(): Textifier = Text()

    override def visitLdcInsn(value: Object): Unit =
      super.visitLdcInsn(value)
      kinds(Seq(value))

    override def visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrap: Handle,
        arguments: Object*
    ): Unit =
      super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments*)
      kinds(arguments)

    private def kinds(values: Seq[Object]): Unit =
      if values.exists(_.isInstanceOf[Number]) then
        this.text.add(values.map(_.getClass.getSimpleName).mkString(
          s"$tab2// constants: ",
          ", ",
          "\n"
        ))
  end Text
end ClassfileNorm
