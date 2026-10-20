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

package sbt.inc.consistent

import java.io.{ File, FileInputStream }
import java.util.Arrays
import org.scalatest.funsuite.AnyFunSuite
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.inc.{ Analysis, AnalysisCompression, FileAnalysisStore }
import sbt.io.{ IO, Using }
import xsbti.compile.{ AnalysisContents, AnalysisStore }
import xsbti.compile.analysis.ReadWriteMappers

// $ cp $HOME/work/scala-modules/scala/target/library/zinc/inc_compile.zip test-data/library.zip
// $ cp $HOME/work/scala-modules/scala/target/reflect/zinc/inc_compile.zip test-data/reflect.zip
// $ cp $HOME/work/scala-modules/scala/target/compiler/zinc/inc_compile.zip test-data/compiler.zip
class ConsistentAnalysisFormatIntegrationSuite extends AnyFunSuite:
  val data =
    Seq("compiler.zip", "library.zip", "reflect.zip").map(f => new File("../../../test-data", f))

  test("Consistent output") {
    for d <- data do
      assert(d.exists())
      val api = read(FileAnalysisStore.text(d))
      val f1 = write("cbin1.zip", api)
      val f2 = write("cbin2.zip", api)
      assert(Arrays.equals(IO.readBytes(f1), IO.readBytes(f2)), s"same output for $d")
  }

  test("Roundtrip") {
    for d <- data do
      assert(d.exists())
      val api = read(FileAnalysisStore.text(d))
      val f1 = write("cbin1.zip", api)
      val api2 = read(ConsistentFileAnalysisStore.binary(f1, ReadWriteMappers.getEmptyMappers))
      val f2 = write("cbin2.zip", api2)
      assert(Arrays.equals(IO.readBytes(f1), IO.readBytes(f2)), s"same output for $d")
  }

  test("Unsorted roundtrip") {
    for d <- data do
      assert(d.exists())
      val api = read(FileAnalysisStore.text(d))
      val f1 = write("cbin1.zip", api)
      val api2 = read(ConsistentFileAnalysisStore.binary(f1, ReadWriteMappers.getEmptyMappers))
      val f2 = write("cbin2.zip", api2, sort = false)
      val api3 = read(ConsistentFileAnalysisStore.binary(f2, ReadWriteMappers.getEmptyMappers))
      val f3 = write("cbin3.zip", api3)
      assert(Arrays.equals(IO.readBytes(f1), IO.readBytes(f3)), s"same output for $d")
  }

  test("compression ratio") {
    for d <- data do
      assert(d.exists())
      val api = read(FileAnalysisStore.text(d))
      val file = write("cbin1.zip", api)
      val uncompressedSize = Using.gzipInputStream(new FileInputStream(file)) { in =>
        val content = IO.readBytes(in)
        content.length
      }
      val compressedSize = d.length()
      val compressionRatio = compressedSize.toDouble / uncompressedSize.toDouble
      assert(compressionRatio < 0.85)
      // compression rate for each data: 0.8185090254676337, 0.7247774786370688, 0.8346021341469837
  }

  test("sbt.analysis.compression sets the deflate level of both stores") {
    def withLevel[A](level: String)(body: => A): A =
      val old = System.getProperty(AnalysisCompression.Property)
      System.setProperty(AnalysisCompression.Property, level)
      try body
      finally
        if old == null then System.clearProperty(AnalysisCompression.Property)
        else System.setProperty(AnalysisCompression.Property, old)
        ()
    for d <- data do
      val api = read(FileAnalysisStore.text(d))
      def sizes(level: String) = withLevel(level) {
        val text = File.createTempFile("analysis", ".zip")
        FileAnalysisStore.text(text).set(api)
        val consistent = write("cbin-level.zip", api)
        read(FileAnalysisStore.text(text))
        (text.length, consistent.length)
      }
      val (text0, consistent0) = sizes("0")
      val (text9, consistent9) = sizes("9")
      assert(text0 > 2 * text9, s"text store, $d")
      assert(consistent0 > 2 * consistent9, s"consistent store, $d")
  }

  test("sbt.analysis.compression rejects a level out of range") {
    System.setProperty(AnalysisCompression.Property, "12")
    try assertThrows[IllegalArgumentException](AnalysisCompression.level)
    finally
      System.clearProperty(AnalysisCompression.Property)
      ()
  }

  def read(store: AnalysisStore): AnalysisContents =
    val api = store.unsafeGet()
    // Force loading of companion file and check that the companion data is present:
    assert(api.getAnalysis.asInstanceOf[Analysis].apis.internal.head._2.api() != null)
    assert(api.getMiniSetup.storeApis())
    api

  def write(name: String, api: AnalysisContents, sort: Boolean = true): File =
    val out = new File(IO.temporaryDirectory, name)
    if out.exists() then IO.delete(out)
    ConsistentFileAnalysisStore.binary(out, ReadWriteMappers.getEmptyMappers, sort).set(api)
    out
end ConsistentAnalysisFormatIntegrationSuite
