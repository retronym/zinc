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

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import com.eclipsesource.json.Json
import org.objectweb.asm.{ ClassWriter, Opcodes }
import org.scalatest.funsuite.AnyFunSuite
import sbt.io.IO

import sbt.internal.inc.oracle.ClassDirDiff
import sbt.internal.inc.oracle.ClassDirDiff.Kind

class ClassDirDiffSuite extends AnyFunSuite:

  /** A class of one method that returns `constant`. Each parameter varies one thing about it. */
  private def cls(
      internFirst: Seq[String] = Nil,
      constant: String = "a",
      scalaSignature: String = "pickle"
  ): Array[Byte] =
    val cw = ClassWriter(0)
    internFirst.foreach(cw.newUTF8)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "C", null, "java/lang/Object", null)
    val annotation = cw.visitAnnotation("Lscala/reflect/ScalaSignature;", true)
    annotation.visit("bytes", scalaSignature)
    annotation.visitEnd()
    val mv = cw.visitMethod(
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      "m",
      "()Ljava/lang/String;",
      null,
      null
    )
    mv.visitCode()
    mv.visitLdcInsn(constant)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
    cw.visitEnd()
    cw.toByteArray

  /** Two directories of products, the incremental build's and the clean build's. */
  private def dirs(inc: Map[String, Array[Byte]], clean: Map[String, Array[Byte]])(
      test: (Path, Path) => Unit
  ): Unit =
    val root = Files.createTempDirectory("classdirdiff")
    try
      for (side, files) <- Seq("inc" -> inc, "clean" -> clean); (rel, bytes) <- files do
        val f = root.resolve(side).resolve(rel)
        Files.createDirectories(f.getParent)
        Files.write(f, bytes)
      test(root.resolve("inc"), root.resolve("clean"))
    finally IO.delete(root.toFile)

  private def run(inc: Path, clean: Path, more: String*): (Int, Seq[String]) =
    val out = ByteArrayOutputStream()
    val args = (Seq(inc.toString, clean.toString) ++ more).toArray
    val code = ClassDirDiff.run(args, PrintStream(out, true, "UTF-8"), System.err)
    (code, out.toString("UTF-8").linesIterator.toSeq)

  private val shifted = Seq("zzz", "m", "a")

  test("equal directories have no differences") {
    val files = Map("p/C.class" -> cls(), "p/notes.txt" -> Array[Byte](1))
    dirs(files, files.updated("p/notes.txt", Array[Byte](2))): (inc, clean) =>
      assert(ClassDirDiff.compare(inc, clean).isEmpty)
      assert(run(inc, clean) == (0, Seq("0 differences: 0 explained, 0 unexplained " +
        "(0 missing, 0 extra, 0 pickle only)")))
  }

  test("a difference a normalisation explains has its level, and is no failure") {
    dirs(Map("p/C.class" -> cls()), Map("p/C.class" -> cls(internFirst = shifted))): (inc, clean) =>
      val Seq(d) = ClassDirDiff.compare(inc, clean).asScala.toSeq
      assert((d.path, d.kind, d.level.toScala) == ("p/C.class", Kind.DIFFERS, Some("pool")))
      assert(!d.unexplained && !d.pickleOnly)
      assert(run(inc, clean)._1 == 0)
  }

  test("missing, extra and unexplained differences fail") {
    val inc = Map("p/C.class" -> cls(), "p/Extra.class" -> cls(), "p/Same.class" -> cls())
    val clean =
      Map("p/C.class" -> cls(constant = "b"), "p/Gone.tasty" -> cls(), "p/Same.class" -> cls())
    dirs(inc, clean): (inc, clean) =>
      val found = ClassDirDiff.compare(inc, clean).asScala.toSeq
      assert(found.map(d => (d.path, d.kind, d.level.toScala, d.pickleOnly)) == Seq(
        ("p/C.class", Kind.DIFFERS, None, false),
        ("p/Extra.class", Kind.EXTRA, None, false),
        ("p/Gone.tasty", Kind.MISSING, None, false)
      ))
      val (code, lines) = run(inc, clean)
      assert(code == 1)
      assert(lines == Seq(
        "differs\tunexplained\tp/C.class",
        "extra\t-\tp/Extra.class",
        "missing\t-\tp/Gone.tasty",
        "3 differences: 0 explained, 3 unexplained (1 missing, 1 extra, 0 pickle only)"
      ))
  }

  test("a difference confined to the Scala signature is pickle only") {
    dirs(Map("C.class" -> cls()), Map("C.class" -> cls(scalaSignature = "elkcip"))): (inc, clean) =>
      val Seq(d) = ClassDirDiff.compare(inc, clean).asScala.toSeq
      assert(d.unexplained && d.pickleOnly)
      assert(ClassDirDiff.onlyPickle(inc.resolve("C.class"), clean.resolve("C.class")))
      assert(ClassDirDiff.explain(inc.resolve("C.class"), clean.resolve("C.class")).isEmpty)
  }

  test("--json prints an object per difference and one for the summary") {
    dirs(Map("C.class" -> cls()), Map("C.class" -> cls(internFirst = shifted))): (inc, clean) =>
      val (code, lines) = run(inc, clean, "--json")
      val Seq(d, summary) = lines.map(Json.parse(_).asObject)
      assert(code == 0)
      assert(d.get("path").asString == "C.class" && d.get("kind").asString == "differs")
      assert(d.get("level").asString == "pool" && !d.get("pickleOnly").asBoolean)
      assert(summary.get("differences").asInt == 1 && summary.get("unexplained").asInt == 0)
  }

  test("wrong arguments are a usage error") {
    assert(ClassDirDiff.run(Array("only"), System.out, PrintStream(ByteArrayOutputStream())) == 2)
  }
end ClassDirDiffSuite
