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

import org.scalacheck.Gen
import org.scalacheck.rng.Seed

class APIDiffSpec extends UnitSpec:

  behavior of "APIDiff.DiffUtil.mkColoredCodeDiff"

  private def diff(code: String, lastCode: String): String =
    APIDiff.DiffUtil.mkColoredCodeDiff(code, lastCode, printDiffDel = true)

  private def show(s: String): String =
    s.replace("\u001B[31m", "<-").replace("\u001B[32m", "<+").replace("\u001B[0m", ">")

  private val api1 =
    """class A extends java.lang.Object {
      |  def foo(x: scala.Int): java.lang.String
      |  def bar: scala.Unit
      |}""".stripMargin

  it should "reproduce identical input unchanged" in {
    assert(show(diff(api1, api1)) == api1)
  }

  it should "colour an added member" in {
    val api2 = api1.replace("  def bar", "  def baz: scala.Long\n  def bar")
    assert(show(diff(api2, api1)) ==
      "class A extends java.lang.Object {\n  def foo(x: scala.Int): java.lang.String\n  def <+baz><+:><+ ><+scala><+.><+Long><+\n><+ ><+ ><+def><+ >bar: scala.Unit\n}")
  }

  it should "colour a removed member" in {
    val api2 = api1.replace("  def foo(x: scala.Int): java.lang.String\n", "")
    assert(show(diff(api2, api1)) ==
      "class A extends java.lang.Object {<-\n><-  def><- foo(x: ><-scala><-.><-Int><-)><-:><- ><-java><-.><-lang><-.><-String>\n  def bar: scala.Unit\n}")
  }

  it should "colour a changed type" in {
    val api2 = api1.replace("x: scala.Int", "x: scala.Long")
    assert(show(diff(api2, api1)) ==
      "class A extends java.lang.Object {\n  def foo(x: scala.<+Long><-Int>): java.lang.String\n  def bar: scala.Unit\n}")
  }

  it should "colour a single token replacement" in {
    assert(show(diff("a", "b")) ==
      "<+a><-b>")
  }

  it should "handle empty inputs" in {
    assert(show(diff("", "")) ==
      "<+>")
    assert(show(diff("a b", "")) ==
      "<+a b>")
    assert(show(diff("", "a b")) ==
      "<-a b>")
  }

  private val pieces = Gen.oneOf(
    "a",
    "b",
    "ab",
    "1",
    " ",
    "\n",
    "(",
    ")",
    "[",
    ":",
    ";",
    "=>",
    ".",
    "def",
    "Int",
    "é",
    "→"
  )
  private val text: Gen[String] = Gen.listOf(pieces).map(_.take(40).mkString)
  private def edited(base: String): Gen[String] =
    for
      cut <- Gen.choose(0, base.length)
      len <- Gen.choose(0, base.length - cut)
      ins <- text.map(_.take(8))
    yield base.substring(0, cut) + ins + base.substring(cut + len)

  private def agreeOn(pairs: Gen[(String, String)]): Unit =
    for i <- 0 until 2000 do
      val (code, lastCode) = pairs.pureApply(Gen.Parameters.default, Seed(i.toLong))
      val expected = ReferenceDiffUtil.mkColoredCodeDiff(code, lastCode, printDiffDel = true)
      assert(show(diff(code, lastCode)) == show(expected), s"code=$code lastCode=$lastCode")

  it should "agree with the reference implementation on unrelated inputs" in {
    agreeOn(Gen.zip(text, text))
  }

  it should "agree with the reference implementation on edited inputs" in {
    agreeOn(for
      base <- text
      e1 <- edited(base)
      e2 <- edited(e1)
    yield (base, e2))
  }
end APIDiffSpec
