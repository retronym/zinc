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

import org.objectweb.asm.{ Attribute, ByteVector, ClassWriter, Label, Opcodes }
import org.scalatest.funsuite.AnyFunSuite

import ClassfileNorm.Level

class ClassfileNormSuite extends AnyFunSuite:

  private val ObjectDesc = "Ljava/lang/Object;"
  private val Erased = s"($ObjectDesc)$ObjectDesc"

  /** An attribute with the content `content` gives it, which may intern constants. */
  private class Blob(name: String, content: ClassWriter => ByteVector) extends Attribute(name):
    override protected def write(
        cw: ClassWriter,
        code: Array[Byte],
        codeLength: Int,
        maxStack: Int,
        maxLocals: Int
    ): ByteVector = content(cw)

  private def inlineInfo = Blob(
    "ScalaInlineInfo",
    cw =>
      ByteVector()
        .putByte(1)
        .putByte(1)
        .putShort(2)
        .putShort(cw.newUTF8("m"))
        .putShort(cw.newUTF8(Erased))
        .putByte(1)
        .putShort(cw.newUTF8("n"))
        .putShort(cw.newUTF8("()V"))
        .putByte(4)
  )

  private def tasty(seed: Int) =
    Blob("TASTY", _ => ByteVector().putByteArray(Array.tabulate(16)(i => (seed + i).toByte), 0, 16))

  /** A class of two methods. Each parameter varies one thing about it. */
  private def cls(
      internFirst: Seq[String] = Nil,
      attributes: Seq[Attribute] = Nil,
      local: String = "x0$3",
      signature: String = s"<A:$ObjectDesc>(TA;)TA;",
      constant: Object = "a",
      opcode: Int = Opcodes.POP,
      line: Int = 10,
      slot: Int = 6,
      scalaSignature: String = "pickle",
      nFirst: Boolean = false
  ): Array[Byte] =
    val cw = ClassWriter(0)
    internFirst.foreach: s =>
      cw.newUTF8(s)
      cw.newConst(s)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null)
    val annotation = cw.visitAnnotation("Lscala/reflect/ScalaSignature;", true)
    annotation.visit("bytes", scalaSignature)
    annotation.visitEnd()
    attributes.foreach(cw.visitAttribute)
    def m(): Unit =
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", Erased, signature, null)
      val (start, end) = (Label(), Label())
      mv.visitCode()
      mv.visitLabel(start)
      mv.visitLineNumber(line, start)
      mv.visitLdcInsn(constant)
      mv.visitInsn(opcode)
      mv.visitVarInsn(Opcodes.ALOAD, 0)
      mv.visitVarInsn(Opcodes.ASTORE, 6)
      mv.visitVarInsn(Opcodes.ALOAD, 0)
      mv.visitVarInsn(Opcodes.ASTORE, 7)
      mv.visitVarInsn(Opcodes.ALOAD, slot)
      mv.visitInsn(Opcodes.ARETURN)
      mv.visitLabel(end)
      mv.visitLocalVariable(local, ObjectDesc, "TA;", start, end, 6)
      mv.visitMaxs(2, 8)
      mv.visitEnd()
    def n(): Unit =
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "n", "()V", null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.RETURN)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    if nFirst then
      n()
      m()
    else
      m()
      n()
    cw.visitEnd()
    cw.toByteArray
  end cls

  private def level(a: Array[Byte], b: Array[Byte]): Option[Level] =
    ClassfileNorm.level(ClassfileNorm.digests(a), ClassfileNorm.digests(b))

  private val shifted = Seq("zzz", "n", "a", Erased, "m")

  test("identical bytes are equal at Raw") {
    assert(cls().sameElements(cls()))
    assert(level(cls(), cls()) == Some(Level.Raw))
  }

  test("a constant pool in another order is equal at Pool") {
    val (a, b) = (cls(), cls(internFirst = shifted))
    assert(!a.sameElements(b))
    assert(level(a, b) == Some(Level.Pool))
  }

  test("ScalaInlineInfo's references into a shifted pool are equal at Pool") {
    val (a, b) = (cls(attributes = Seq(inlineInfo)), cls(shifted, Seq(inlineInfo)))
    assert(level(a, b) == Some(Level.Pool))
    assert(ClassfileNorm.text(a).contains(s"m$Erased 1"))
    assert(ClassfileNorm.text(a) == ClassfileNorm.text(b))
    assert(level(a, cls()) == None)
  }

  test("an unknown attribute's bytes count") {
    def other(b: Int) = Blob("Other", _ => ByteVector().putByte(b))
    assert(level(cls(attributes = Seq(other(1))), cls(attributes = Seq(other(2)))) == None)
    assert(
      level(cls(attributes = Seq(other(1))), cls(shifted, Seq(other(1)))) == Some(Level.Pool)
    )
  }

  test("local variable names are equal at Locals") {
    assert(level(cls(local = "x0$3"), cls(local = "x0$13")) == Some(Level.Locals))
  }

  test("type variables that differ in a trailing $ are equal at TypeVars") {
    val dollar = s"<A$$:$ObjectDesc>(TA$$;)TA$$;"
    assert(level(cls(), cls(signature = dollar)) == Some(Level.TypeVars))
    assert(level(cls(), cls(signature = s"<B:$ObjectDesc>(TB;)TB;")) == None)
    assert(ClassfileNorm.stripTypeVarSuffix(dollar, isType = false) == s"<A:$ObjectDesc>(TA;)TA;")
    assert(ClassfileNorm.stripTypeVarSuffix("TA$$;", isType = true) == "TA;")
  }

  test("levels are cumulative") {
    val a = ClassfileNorm.digests(cls())
    val b = ClassfileNorm.digests(cls(internFirst = shifted, local = "y"))
    assert(a.raw != b.raw && a.pool != b.pool)
    assert(a.locals == b.locals && a.typeVars == b.typeVars)
  }

  test("differences in the code, its debug info, annotations or member order are differences") {
    val differing = Seq(
      "instruction" -> cls(opcode = Opcodes.NOP),
      "operand" -> cls(constant = "b"),
      "line number" -> cls(line = 11),
      "local slot" -> cls(slot = 7),
      "ScalaSignature" -> cls(scalaSignature = "elkcip"),
      "method order" -> cls(nFirst = true)
    )
    for (what, b) <- differing do assert(level(cls(), b) == None, what)
  }

  test("an int and a long constant differ") {
    val (i, l) = (cls(constant = Integer.valueOf(1)), cls(constant = java.lang.Long.valueOf(1)))
    assert(level(i, l) == None)
  }

  test("the TASTY attribute is apart from the normalised digests") {
    val a = ClassfileNorm.digests(cls(attributes = Seq(tasty(1))))
    val b = ClassfileNorm.digests(cls(attributes = Seq(tasty(2))))
    assert(ClassfileNorm.level(a, b) != Some(Level.Raw))
    assert(a.pool == b.pool && a.locals == b.locals && a.typeVars == b.typeVars)
    assert(a.tastyUuid == Some("0102030405060708090a0b0c0d0e0f10"))
    assert(a.tastyUuid != b.tastyUuid)
    assert(ClassfileNorm.digests(cls()).tastyUuid == None)
  }

  test("diff is the lines that differ") {
    val lines = ClassfileNorm.diff(cls(line = 10), cls(line = 11))
    assert(lines.map(_.replaceAll("\\s+", " ")) == Seq("- LINENUMBER 10 L0", "+ LINENUMBER 11 L0"))
    assert(ClassfileNorm.diff(cls(), cls(internFirst = shifted)).isEmpty)
    assert(ClassfileNorm.diff(cls(), cls(nFirst = true), max = 3).size == 3)
    assert(
      ClassfileNorm.lineDiff(Vector("a", "b", "c", "d"), Vector("a", "c", "e", "d")) ==
        Seq("-b", "+e")
    )
  }
end ClassfileNormSuite
