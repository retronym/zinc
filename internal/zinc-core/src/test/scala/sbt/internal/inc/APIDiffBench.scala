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

import java.lang.management.ManagementFactory

/**
 * Times `APIDiff.DiffUtil.mkColoredCodeDiff` against [[ReferenceDiffUtil]] on synthetic API text.
 * Run with `zincCore/Test/runMain sbt.internal.inc.APIDiffBench [members...]`.
 */
object APIDiffBench {
  private val threads =
    ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def api(members: Int, extra: Int => String): String = {
    val sb = new StringBuilder("class C extends java.lang.Object {\n")
    for (i <- 0 until members) {
      sb ++= s"  def m$i(x: scala.Int, y: java.lang.String): scala.collection.immutable.List[scala.Int]\n"
      sb ++= extra(i)
    }
    sb ++= "}"
    sb.toString
  }

  private def measure(name: String, f: () => String): Unit = {
    var n = 0
    val warmEnd = System.nanoTime() + 2000000000L
    while (System.nanoTime() < warmEnd) {
      f()
      n += 1
    }
    val iters = math.max(3, n)
    val tid = Thread.currentThread().getId
    val a0 = threads.getThreadAllocatedBytes(tid)
    val t0 = System.nanoTime()
    for (_ <- 0 until iters) f()
    val t1 = System.nanoTime()
    val a1 = threads.getThreadAllocatedBytes(tid)
    val ms = (t1 - t0) / 1e6 / iters
    val mb = (a1 - a0) / 1e6 / iters
    println(f"  $name%-10s $ms%10.3f ms/op $mb%10.2f MB/op")
  }

  def main(args: Array[String]): Unit = {
    val sizes = if (args.isEmpty) Seq(10, 50, 200) else args.toSeq.map(_.toInt)
    for (members <- sizes) {
      val before = api(members, _ => "")
      val after = api(
        members,
        i => if (i == members / 2) "  def added(z: scala.Long): scala.Unit\n" else ""
      ).replace("def m1(x: scala.Int", "def m1(x: scala.Long")
      val expected = ReferenceDiffUtil.mkColoredCodeDiff(after, before, printDiffDel = true)
      val actual = APIDiff.DiffUtil.mkColoredCodeDiff(after, before, printDiffDel = true)
      assert(actual == expected, s"output differs for $members members")
      println(s"members=$members chars=${before.length}")
      measure("reference", () => ReferenceDiffUtil.mkColoredCodeDiff(after, before, true))
      measure("current", () => APIDiff.DiffUtil.mkColoredCodeDiff(after, before, true))
    }
  }
}
