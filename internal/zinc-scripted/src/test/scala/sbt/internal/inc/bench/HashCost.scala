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

import xsbt.api.{ APIUtil, HashAPI, NameHashing }

/**
 * Times the hashing Zinc does per compiled class on an analysis stored with full APIs
 * (`apiDebug`): what moving the hashing into the bridge would take off Zinc.
 *
 * {{{ zincScripted/Test/runMain sbt.internal.inc.bench.HashCost <inc_compile.zip> [reps] }}}
 */
object HashCost {
  def main(args: Array[String]): Unit = {
    val store = FileAnalysisStore.binary(Paths.get(args(0)).toFile)
    val analysis = store.get.get.getAnalysis.asInstanceOf[Analysis]
    val reps = args.lift(1).fold(5)(_.toInt)
    val classes = analysis.apis.internal.values.toVector
      .flatMap(c => Vector(c.api().classApi(), c.api().objectApi()))
    def time(name: String)(f: => Unit): Unit = {
      val ms = (1 to reps).map { _ =>
        val t0 = System.nanoTime(); f; (System.nanoTime() - t0) / 1e6
      }
      println(f"$name%-14s ${ms.sorted.apply(reps / 2)}%8.1f ms (median of $reps)")
    }
    println(s"${classes.size} class sides")
    time("HashAPI")(classes.foreach(HashAPI(_)))
    time("NameHashing")(classes.foreach(new NameHashing(false).nameHashes(_)))
    time("minimize")(classes.foreach(APIUtil.minimize))
  }
}
