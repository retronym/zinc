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

import java.nio.file.{ Files, Path }

import scala.collection.JavaConverters._

import sbt.internal.prof.Zprof

/**
 * Where one IncBench step's time went, and what Zinc invalidated, as JSON fields for its line:
 * scalac's time per phase from its profiler, Zinc's own steps from [[PhaseTimings]], and each
 * module's incremental run from the invalidation profiler.
 */
object StepProfile {

  /** The scalac options that make each compiler run append its per-phase profile to `file`. */
  def scalacOptions(file: String): String = s"-Yprofile-enabled -Yprofile-destination $file"

  final case class Phase(name: String, nanos: Long, allocatedBytes: Long)

  /**
   * The phases of every compiler run that appended to `file` since the last call, summed by
   * phase in the order the compiler ran them. The file is emptied, so the next step starts
   * afresh.
   */
  def drainScalacProfile(file: Path): Seq[Phase] =
    if (!Files.exists(file)) Nil
    else {
      val lines = Files.readAllLines(file).asScala.toVector
      Files.write(file, Array.emptyByteArray)
      parseScalacProfile(lines)
    }

  /**
   * Parses the CSV that scalac and Scala 3 write for `-Yprofile-destination`, which share a
   * format: Scala 3 ported scalac's profiler, though it writes `MAIN` where scalac writes `main`.
   */
  def parseScalacProfile(lines: Seq[String]): Seq[Phase] = {
    // main,startNs,endNs,runId,phaseId,phaseName,purpose,taskCount,threadId,threadName,
    // runNs,idleNs,cpuTimeNs,userTimeNs,allocatedByte,heapSize
    val rows = lines
      .map(_.split(",", -1).map(_.trim))
      .filter(r => r.length >= 15 && r(0).equalsIgnoreCase("main"))
    val order = rows.map(_(5)).distinct
    order.map { name =>
      val rs = rows.filter(_(5) == name)
      Phase(phaseName(name), rs.map(_(10).toLong).sum, rs.map(_(14).toLong).sum)
    }
  }

  /**
   * The profiler replaces each comma in a phase name with a space, which turns the `, ` between
   * the phases of a Scala 3 `MegaPhase{a, b}` into two spaces. Puts the commas back.
   */
  private def phaseName(csv: String): String =
    if (csv.startsWith("MegaPhase{")) csv.replace("  ", ", ") else csv

  /** Sums phases by name across modules, keeping the first module's order. */
  def mergePhases(phases: Seq[Phase]): Seq[Phase] =
    phases.map(_.name).distinct.map { n =>
      val ps = phases.filter(_.name == n)
      Phase(n, ps.map(_.nanos).sum, ps.map(_.allocatedBytes).sum)
    }

  private val Timing = """(\w+)=([0-9.]+)ms""".r

  /** Zinc's own steps, summed over the `[zinc-timing]` lines of every module, in milliseconds. */
  def zincTimings(lines: Seq[String]): Seq[(String, Double)] = {
    val parsed =
      lines.flatMap(l => Timing.findAllMatchIn(l).map(m => m.group(1) -> m.group(2).toDouble))
    parsed.map(_._1).distinct.map(k => k -> parsed.filter(_._1 == k).map(_._2).sum)
  }

  /** At most this many changed classes, and names per class, are listed for a cycle. */
  final val MaxChanges = 50
  final val MaxNames = 10

  /** One module's incremental runs since the last step, oldest first, as JSON. */
  def runsJson(module: String, profile: Zprof.Profile, runs: Seq[Zprof.ZincRun]): Seq[String] = {
    val table = profile.getStringTableList.asScala
    def str(i: Int) = esc(table(i))
    def changes(cs: Seq[Zprof.ApiChange]): String =
      cs.take(MaxChanges)
        .map { c =>
          val names = c.getUsedNamesList.asScala.take(MaxNames).map(n => str(n.getName))
          s"""{"class":${str(c.getModifiedClass)},"reason":${esc(c.getReason)},""" +
            s""""names":[${names.mkString(",")}],"namesTotal":${c.getUsedNamesCount}}"""
        }
        .mkString("[", ",", "]")
    runs.map { run =>
      val init = run.getInitial
      val ch = init.getChanges
      // The profiler prepends each cycle, so they are newest first.
      val cycles = run.getCyclesList.asScala.reverse.zipWithIndex.map { case (c, i) =>
        val external = c.getChangesAfterRecompilationList.asScala
        s"""{"cycle":${i + 1},"initialSources":${c.getInitialSourcesCount},""" +
          s""""invalidatedClasses":${c.getInvalidatedCount},""" +
          s""""invalidatedByPackageObjects":${c.getInvalidatedByPackageObjectsCount},""" +
          s""""sources":${c.getInvalidatedSourcesCount},""" +
          s""""recompiledClasses":${c.getRecompiledClassesCount},""" +
          s""""nextInvalidations":${c.getNextInvalidationsCount},""" +
          s""""compileMillis":${c.getCompilationDurationNanos / 1000000},""" +
          s""""apiChanges":${external.size},"changes":${changes(external)}}"""
      }
      val binary = init.getBinaryDependenciesCount
      s"""{"module":${esc(module)},"added":${ch.getAddedCount},"removed":${ch.getRemovedCount},""" +
        s""""modified":${ch.getModifiedCount},"binaryDependencies":$binary,""" +
        s""""externalChanges":${changes(init.getExternalChangesList.asScala)},""" +
        s""""cycles":[${cycles.mkString(",")}]}"""
    }
  }

  def json(phases: Seq[Phase], zinc: Seq[(String, Double)], runs: Seq[String]): String = {
    val ps = phases.map(p =>
      s"""{"phase":${esc(p.name)},"millis":${p.nanos / 1000000},"allocatedBytes":${p.allocatedBytes}}"""
    )
    val zs = zinc.map { case (k, v) =>
      s"""${esc(k)}:${BigDecimal(v).setScale(1, BigDecimal.RoundingMode.HALF_UP)}"""
    }
    val rs = runs.mkString(",")
    s""""scalacPhases":[${ps.mkString(",")}],"zinc":{${zs.mkString(",")}},"zincRuns":[$rs]"""
  }

  def esc(s: String): String = {
    val b = new StringBuilder("\"")
    s.foreach {
      case '"'          => b ++= "\\\""
      case '\\'         => b ++= "\\\\"
      case '\n'         => b ++= "\\n"
      case c if c < ' ' => b ++= f"\\u${c.toInt}%04x"
      case c            => b += c
    }
    b += '"'
    b.toString
  }
}
