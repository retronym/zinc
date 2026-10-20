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

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import xsbti.VirtualFileRef
import xsbti.compile.{
  APIChange as XAPIChange,
  InitialChanges as XInitialChanges,
  InvalidationProfiler as XInvalidationProfiler,
  RunProfiler as XRunProfiler
}

/**
 * Where one IncBench step's time went, and what Zinc invalidated, as JSON fields for its line:
 * scalac's time per phase from its profiler, Zinc's own steps from [[PhaseTimings]], and each
 * module's incremental run from the invalidation profiler.
 */
object StepProfile:

  /** The scalac options that make each compiler run append its per-phase profile to `file`. */
  def scalacOptions(file: String): String = s"-Yprofile-enabled -Yprofile-destination $file"

  final case class Phase(name: String, nanos: Long, allocatedBytes: Long)

  /**
   * The phases of every compiler run that appended to `file` since the last call, summed by
   * phase in the order the compiler ran them. The file is emptied, so the next step starts
   * afresh.
   */
  def drainScalacProfile(file: Path): Seq[Phase] =
    if !Files.exists(file) then Nil
    else
      val lines = Files.readAllLines(file).asScala.toVector
      Files.write(file, Array.emptyByteArray)
      parseScalacProfile(lines)

  /**
   * Parses the CSV that scalac and Scala 3 write for `-Yprofile-destination`, which share a
   * format: Scala 3 ported scalac's profiler, though it writes `MAIN` where scalac writes `main`.
   */
  def parseScalacProfile(lines: Seq[String]): Seq[Phase] =
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

  /**
   * The profiler replaces each comma in a phase name with a space, which turns the `, ` between
   * the phases of a Scala 3 `MegaPhase{a, b}` into two spaces. Puts the commas back.
   */
  private def phaseName(csv: String): String =
    if csv.startsWith("MegaPhase{") then csv.replace("  ", ", ") else csv

  /** Sums phases by name across modules, keeping the first module's order. */
  def mergePhases(phases: Seq[Phase]): Seq[Phase] =
    phases.map(_.name).distinct.map { n =>
      val ps = phases.filter(_.name == n)
      Phase(n, ps.map(_.nanos).sum, ps.map(_.allocatedBytes).sum)
    }

  private val Timing = """(\w+)=([0-9.]+)ms""".r

  /** Zinc's own steps, summed over the `[zinc-timing]` lines of every module, in milliseconds. */
  def zincTimings(lines: Seq[String]): Seq[(String, Double)] =
    val parsed =
      lines.flatMap(l => Timing.findAllMatchIn(l).map(m => m.group(1) -> m.group(2).toDouble))
    parsed.map(_._1).distinct.map(k => k -> parsed.filter(_._1 == k).map(_._2).sum)

  /** At most this many changed classes, and names per class, are listed for a cycle. */
  final val MaxChanges = 50
  final val MaxNames = 10

  final case class Change(className: String, reason: String, names: Seq[String])
  final case class Cycle(
      initialSources: Int,
      invalidatedClasses: Int,
      invalidatedByPackageObjects: Int,
      sources: Int,
      recompiledClasses: Int,
      nextInvalidations: Int,
      compileNanos: Long,
      changes: Seq[Change]
  )
  final case class Run(
      added: Int,
      removed: Int,
      modified: Int,
      binaryDependencies: Int,
      externalChanges: Seq[Change],
      cycles: Seq[Cycle]
  )

  /**
   * Records each incremental run of a module, its initial changes and its invalidation cycles,
   * through the invalidation profiler Zinc calls from `ExternalHooks`.
   */
  final class RunRecorder extends XInvalidationProfiler:
    private val runs = new ConcurrentLinkedQueue[Run]

    /** The runs recorded since the last call, oldest first. */
    def drain(): Seq[Run] = Iterator.continually(runs.poll()).takeWhile(_ != null).toVector

    def profileRun(): XRunProfiler = new XRunProfiler:
      private var initial = Run(0, 0, 0, 0, Nil, Nil)
      private var compileNanos = 0L
      private val cycles = Vector.newBuilder[Cycle]

      def timeCompilation(startNanos: Long, durationNanos: Long): Unit =
        compileNanos = durationNanos

      def registerInitial(changes: XInitialChanges): Unit =
        val src = changes.getInternalSrc
        initial = Run(
          src.getAdded.size,
          src.getRemoved.size,
          src.getChanged.size,
          changes.getLibraryDeps.size,
          changes.getExternal.toSeq.map(change),
          Nil
        )

      def registerEvent(kind: String, inputs: Array[String], outputs: Array[String], reason: String)
          : Unit = ()

      def registerCycle(
          invalidatedClasses: Array[String],
          invalidatedPackageObjects: Array[String],
          initialSources: Array[VirtualFileRef],
          invalidatedSources: Array[VirtualFileRef],
          recompiledClasses: Array[String],
          changesAfterRecompilation: Array[XAPIChange],
          nextInvalidations: Array[String],
          shouldCompileIncrementally: Boolean
      ): Unit =
        cycles += Cycle(
          initialSources.length,
          invalidatedClasses.length,
          invalidatedPackageObjects.length,
          invalidatedSources.length,
          recompiledClasses.length,
          nextInvalidations.length,
          compileNanos,
          changesAfterRecompilation.toSeq.map(change)
        )

      def registerRun(): Unit = runs.add(initial.copy(cycles = cycles.result()))

    private def change(c: XAPIChange): Change =
      val reason = c match
        case _: APIChangeDueToMacroDefinition      => "API change due to macro definition."
        case _: APIChangeDueToAnnotationDefinition => "API change due to annotation definition."
        case _: TraitPrivateMembersModified        =>
          "API change due to existence of private trait members in modified class."
        case _ => "Standard API name change in modified class."
      Change(c.getModifiedClass, reason, c.getModifiedNames.asScala.toSeq.map(_.getName))
  end RunRecorder

  /** One module's incremental runs since the last step, oldest first, as JSON. */
  def runsJson(module: String, runs: Seq[Run]): Seq[String] =
    def changes(cs: Seq[Change]): String =
      cs.take(MaxChanges)
        .map { c =>
          val names = c.names.take(MaxNames).map(esc)
          s"""{"class":${esc(c.className)},"reason":${esc(c.reason)},""" +
            s""""names":[${names.mkString(",")}],"namesTotal":${c.names.size}}"""
        }
        .mkString("[", ",", "]")
    runs.map { run =>
      val cycles = run.cycles.zipWithIndex.map { case (c, i) =>
        s"""{"cycle":${i + 1},"initialSources":${c.initialSources},""" +
          s""""invalidatedClasses":${c.invalidatedClasses},""" +
          s""""invalidatedByPackageObjects":${c.invalidatedByPackageObjects},""" +
          s""""sources":${c.sources},""" +
          s""""recompiledClasses":${c.recompiledClasses},""" +
          s""""nextInvalidations":${c.nextInvalidations},""" +
          s""""compileMillis":${c.compileNanos / 1000000},""" +
          s""""apiChanges":${c.changes.size},"changes":${changes(c.changes)}}"""
      }
      s"""{"module":${esc(module)},"added":${run.added},"removed":${run.removed},""" +
        s""""modified":${run.modified},"binaryDependencies":${run.binaryDependencies},""" +
        s""""externalChanges":${changes(run.externalChanges)},""" +
        s""""cycles":[${cycles.mkString(",")}]}"""
    }
  end runsJson

  def json(phases: Seq[Phase], zinc: Seq[(String, Double)], runs: Seq[String]): String =
    val ps = phases.map(p =>
      s"""{"phase":${esc(p.name)},"millis":${p.nanos /
          1000000},"allocatedBytes":${p.allocatedBytes}}"""
    )
    val zs = zinc.map { case (k, v) =>
      s"""${esc(k)}:${BigDecimal(v).setScale(1, BigDecimal.RoundingMode.HALF_UP)}"""
    }
    val rs = runs.mkString(",")
    s""""scalacPhases":[${ps.mkString(",")}],"zinc":{${zs.mkString(",")}},"zincRuns":[$rs]"""

  def esc(s: String): String =
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
end StepProfile
