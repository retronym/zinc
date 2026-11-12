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

import java.nio.file.Paths
import java.util.zip.ZipFile

import scala.util.Using

import org.scalatest.funsuite.AnyFunSuite

class TastyNormSuite extends AnyFunSuite:

  /** `scala.deriving.Mirror` as the Scala 3 library on the classpath has it. */
  private def mirror(extension: String): Array[Byte] =
    val jar = classOf[scala.deriving.Mirror].getProtectionDomain.getCodeSource.getLocation
    Using.resource(ZipFile(Paths.get(jar.toURI).toFile)): zip =>
      Using.resource(zip.getInputStream(zip.getEntry(s"scala/deriving/Mirror.$extension")))(
        _.readAllBytes()
      )

  private val bytes = mirror("tasty")
  private val tasty = TastyNorm.read(bytes)

  private def mutated(ranges: (Int, Int)*): TastyNorm.Tasty =
    val copy = bytes.clone()
    for (start, end) <- ranges do
      val at = (start + end) / 2
      assert(start <= at && at < end)
      copy(at) = (copy(at) ^ 0x55).toByte
    TastyNorm.read(copy)

  private def range(name: String): (Int, Int) =
    val section = TastyNorm.layout(bytes).find(_.name == name).get
    (section.start, section.end)

  private val uuid: (Int, Int) =
    val start = range(TastyNorm.Header)._2
    (start, start + 16)

  test("the UUID is the one in the class's TASTY attribute") {
    assert(tasty.uuid.length == 32)
    assert(ClassfileNorm.digests(mirror("class")).tastyUuid == Some(tasty.uuid))
  }

  test("the sections are read to the end of the file") {
    val names = tasty.sections.map(_._1)
    assert(names.take(2) == Seq(TastyNorm.Header, TastyNorm.Names))
    assert(names.contains("ASTs") && names.contains("Positions"))
    assert(TastyNorm.layout(bytes).last.end == bytes.length)
    assert(uuid._2 == range(TastyNorm.Names)._1)
  }

  test("a file is equal to itself") {
    assert(TastyNorm.differing(tasty, TastyNorm.read(bytes)).isEmpty)
    assert(TastyNorm.benign(tasty, tasty))
  }

  test("differences in the UUID and Positions are benign") {
    val other = mutated(uuid, range("Positions"))
    assert(other.uuid != tasty.uuid)
    assert(TastyNorm.differing(tasty, other) == Set("Positions"))
    assert(TastyNorm.benign(tasty, other))
  }

  test("a difference in ASTs is not benign") {
    val other = mutated(range("ASTs"))
    assert(TastyNorm.differing(tasty, other) == Set("ASTs"))
    assert(!TastyNorm.benign(tasty, other))
  }

  test("a section that only one has differs") {
    val without = tasty.copy(sections = tasty.sections.filterNot(_._1 == "ASTs"))
    assert(TastyNorm.differing(tasty, without) == Set("ASTs"))
    assert(TastyNorm.benign(tasty, tasty.copy(sections = tasty.sections.filterNot(_._1 == "Positions"))))
  }

  test("other bytes are rejected") {
    assertThrows[IllegalArgumentException](TastyNorm.read(Array[Byte](1, 2, 3, 4, 5)))
  }
end TastyNormSuite
