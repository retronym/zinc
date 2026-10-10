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

import sbt.util.Logger

/**
 * Wall time spent in each step of one incremental compile, summed over cycles, reported as one
 * `[zinc-timing]` debug line. `compile` is the compiler run (including the bridge's phases);
 * `analysis` is building the cycle's `Analysis` from the callback, which hashes every class API
 * a tree-mode bridge sent.
 */
final class PhaseTimings {
  private val totals = new java.util.LinkedHashMap[String, Array[Long]]()

  def time[T](label: String)(body: => T): T = {
    val start = System.nanoTime()
    try body
    finally {
      val elapsed = System.nanoTime() - start
      totals.synchronized {
        val acc = totals.computeIfAbsent(label, _ => new Array[Long](2))
        acc(0) += elapsed
        acc(1) += 1
      }
    }
  }

  def render: String =
    totals.synchronized {
      val sb = new StringBuilder("[zinc-timing]")
      totals.forEach { (label, acc) =>
        sb.append(f" $label=${acc(0) / 1e6}%.1fms")
        if (acc(1) > 1) { sb.append(s"(x${acc(1)})"); () }
      }
      sb.toString
    }

  def report(log: Logger): Unit = log.debug(render)
}
