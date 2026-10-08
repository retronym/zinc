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

import java.nio.file.{ Files, Path, Paths }

import sbt.io.IO
import sbt.util.Level

/**
 * Measures incremental compilation of edit scenarios on a multi-module build, driving Zinc
 * through the scripted [[IncHandler]] so that it uses this checkout's Zinc and compiler bridge.
 *
 * For each edit: apply it, compile the last module (and so every module), record per module the
 * rounds, the classes recompiled and the wall time, then revert it and compile again. Results go
 * to stdout and `--out` as JSON lines, so two checkouts compare by diffing their output.
 *
 * {{{
 * sbt "publishBridges; zincScripted3/Test/runMain sbt.internal.inc.bench.IncBench \
 *   --dir /tmp/bench --modules 4 --depth 5 --fan-out 2 --reps 3 --out /tmp/bench.jsonl"
 * }}}
 */
object IncBench:
  final case class Options(
      dir: Path = Paths.get("target/incbench"),
      modules: Int = 4,
      depth: Int = 5,
      fanOut: Int = 2,
      padding: Int = 20,
      rootIsTrait: Boolean = false,
      scalaVersion: String = "2.13.x",
      reps: Int = 3,
      incOptions: Map[String, String] = Map.empty,
      out: Option[Path] = None,
      label: String = "",
  )

  def parse(args: List[String], o: Options = Options()): Options = args match
    case Nil                          => o
    case "--dir" :: v :: rest         => parse(rest, o.copy(dir = Paths.get(v)))
    case "--modules" :: v :: rest     => parse(rest, o.copy(modules = v.toInt))
    case "--depth" :: v :: rest       => parse(rest, o.copy(depth = v.toInt))
    case "--fan-out" :: v :: rest     => parse(rest, o.copy(fanOut = v.toInt))
    case "--padding" :: v :: rest     => parse(rest, o.copy(padding = v.toInt))
    case "--trait" :: rest            => parse(rest, o.copy(rootIsTrait = true))
    case "--scala" :: v :: rest       => parse(rest, o.copy(scalaVersion = v))
    case "--reps" :: v :: rest        => parse(rest, o.copy(reps = v.toInt))
    case "--out" :: v :: rest         => parse(rest, o.copy(out = Some(Paths.get(v))))
    case "--label" :: v :: rest       => parse(rest, o.copy(label = v))
    case "--inc-option" :: kv :: rest =>
      val Array(k, v) = kv.split("=", 2)
      parse(rest, o.copy(incOptions = o.incOptions + (k -> v)))
    case other :: _ => sys.error(s"Unknown argument $other")

  def main(args: Array[String]): Unit =
    val o = parse(args.toList)
    val corpus =
      SyntheticCorpus(o.modules, o.depth, o.fanOut, o.padding, o.rootIsTrait, o.scalaVersion)
    val dir = o.dir.toAbsolutePath
    IO.delete(dir.toFile)
    corpus.write(dir)
    val moduleNames = (0 until o.modules).map(i => s"m$i")
    if o.incOptions.nonEmpty then
      val props = o.incOptions.map((k, v) => s"$k = $v").mkString("", "\n", "\n")
      moduleNames.foreach(m =>
        Files.writeString(dir.resolve(m).resolve("incOptions.properties"), props)
      )
    val shape =
      s""""label":"${o.label}","modules":${o.modules},"depth":${o.depth},"fanOut":${o.fanOut},""" +
        s""""padding":${o.padding},"trait":${o.rootIsTrait},"classes":${corpus.classes.size}"""
    val runner = new Runner(dir, moduleNames, o.scalaVersion)
    val lines = Vector.newBuilder[String]
    def emit(line: String): Unit =
      println(line)
      lines += line
    emit(s"""{$shape,"step":"clean",${runner.compileAll().json}}""")
    for edit <- corpus.edits; rep <- 1 to o.reps do
      val file = dir.resolve(edit.file)
      val original = Files.readString(file)
      Files.writeString(file, edit.content)
      emit(s"""{$shape,"step":"${edit.name}","rep":$rep,${runner.compileAll().json}}""")
      Files.writeString(file, original)
      emit(s"""{$shape,"step":"${edit.name}-revert","rep":$rep,${runner.compileAll().json}}""")
    o.out.foreach(p => Files.write(p, lines.result().mkString("", "\n", "\n").getBytes("UTF-8")))
    runner.finish()
  end main

  final case class ModuleResult(name: String, rounds: Int, recompiled: Int, analysisBytes: Long)

  final case class StepResult(wallMillis: Long, modules: Seq[ModuleResult]):
    def json: String =
      val ms = modules.map(m =>
        s"""{"module":"${m.name}","rounds":${m.rounds},"recompiled":${m.recompiled},""" +
          s""""analysisBytes":${m.analysisBytes}}"""
      )
      s""""wallMillis":$wallMillis,"rounds":${modules.map(_.rounds).sum},""" +
        s""""recompiled":${modules.map(_.recompiled).sum},"modules":[${ms.mkString(",")}]"""

  final class Runner(dir: Path, moduleNames: Seq[String], scalaVersion: String):
    private val cacheDir = Files.createTempDirectory("incbench-cache")
    private val handler =
      new IncHandler(dir, cacheDir, UnitSpec.newLogger(Level.Warn), compileToJar = false)
    private var state: handler.State = handler.initialState

    def compileAll(): StepResult =
      val start = System.currentTimeMillis()
      state = handler.apply(s"${moduleNames.last}/compile", Nil, state)
      val wall = System.currentTimeMillis() - start
      StepResult(wall, moduleNames.map(result(_, start)))

    private def result(module: String, since: Long): ModuleResult =
      val p = handler.lookupProject(module)
      val analysis = p.prev().analysis.get.asInstanceOf[Analysis]
      val rounds = analysis.compilations.allCompilations.filter(_.getStartTime >= since)
      val starts = rounds.map(_.getStartTime).toSet
      val recompiled = analysis.apis.internal.count((_, c) => starts(c.compilationTimestamp))
      ModuleResult(module, rounds.size, recompiled, Files.size(p.cacheFile))

    def finish(): Unit =
      handler.finish(state)
      IO.delete(cacheDir.toFile)
  end Runner
end IncBench
