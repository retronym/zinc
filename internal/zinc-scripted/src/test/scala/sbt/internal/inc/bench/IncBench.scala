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

import sbt.internal.inc.FileAnalysisStore
import sbt.io.IO
import sbt.util.Level
import xsbt.api.APIUtil
import xsbti.api.{ Companions, SafeLazyProxy }
import xsbti.compile.AnalysisContents

/**
 * Measures incremental compilation of edit scenarios on a multi-module build, driving Zinc
 * through the scripted [[IncHandler]] so that it uses this checkout's Zinc and compiler bridge.
 *
 * For each edit: apply it, compile the last module (and so every module), record per module the
 * rounds, the classes recompiled and the wall time, then revert it and compile again. Finally a
 * `clean-build` step rebuilds everything from scratch in the warm JVM, as a reference.
 *
 * Timed runs turn `apiDebug` off: with it, Zinc diffs and logs every changed API, which costs
 * more than the compile and grows with API size. The definitions the bridge extracts are counted
 * in a separate untimed `extract` pass over a copy of the build, with `apiDebug` on. Results go
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
      build: Option[Path] = None,
      edits: Option[Path] = None,
      logLevel: Level.Value = Level.Warn,
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
    case "--build" :: v :: rest       => parse(rest, o.copy(build = Some(Paths.get(v))))
    case "--edits" :: v :: rest       => parse(rest, o.copy(edits = Some(Paths.get(v))))
    case "--debug" :: rest            => parse(rest, o.copy(logLevel = Level.Debug))
    case "--out" :: v :: rest         => parse(rest, o.copy(out = Some(Paths.get(v))))
    case "--label" :: v :: rest       => parse(rest, o.copy(label = v))
    case "--inc-option" :: kv :: rest =>
      val Array(k, v) = kv.split("=", 2)
      parse(rest, o.copy(incOptions = o.incOptions + (k -> v)))
    case other :: _ => sys.error(s"Unknown argument $other")

  def main(args: Array[String]): Unit =
    val o = parse(args.toList)
    o.build match
      case Some(build) => existing(o, build.toAbsolutePath, o.edits.get.toAbsolutePath)
      case None        => synthetic(o)

  /**
   * Runs the edits on an existing build: a `build.json` as scripted reads it, whose projects
   * point (`in`) at real source trees with their dependency jars in `lib/`. Edits come from a
   * tab-separated file of name, file (relative to the build), text to find and its replacement,
   * with `\\n` for a newline. Each project's `target` is deleted first, so both checkouts start
   * from a clean build. There is no extraction pass.
   */
  def existing(o: Options, build: Path, editsFile: Path): Unit =
    val json = Files.readString(build.resolve("build.json"))
    val projects = "\\{[^{}]*\\}".r.findAllIn(json).toVector.map { obj =>
      def field(k: String) = s""""$k"\\s*:\\s*"([^"]+)"""".r.findFirstMatchIn(obj).map(_.group(1))
      val name = field("name").get
      name -> field("in").map(Paths.get(_)).getOrElse(build.resolve(name))
    }
    val props = (Map("apiDebug" -> "false") ++ o.incOptions)
      .map((k, v) => s"$k = $v")
      .mkString("", "\n", "\n")
    for (_, base) <- projects do
      IO.delete(base.resolve("target").toFile)
      Files.writeString(base.resolve("incOptions.properties"), props)
    def unescape(t: String) = t.replace("\\n", "\n")
    val edits = Files.readAllLines(editsFile).toArray(Array.empty[String]).toVector
      .filter(l => l.trim.nonEmpty && !l.startsWith("#"))
      .map { line =>
        val Array(name, file, find, replace) = line.split("\t", 4)
        val original = Files.readString(build.resolve(file))
        require(original.contains(unescape(find)), s"$name: text not found in $file")
        Edit(name, file, original.replace(unescape(find), unescape(replace)))
      }
    val shape = s""""label":"${o.label}","build":"${build.getFileName}""""
    run(o, build, projects.map(_._1), edits, shape)
  end existing

  def synthetic(o: Options): Unit =
    val corpus =
      SyntheticCorpus(o.modules, o.depth, o.fanOut, o.padding, o.rootIsTrait, o.scalaVersion)
    val moduleNames = (0 until o.modules).map(i => s"m$i")
    def setUp(dir: Path, incOptions: Map[String, String]): Unit =
      IO.delete(dir.toFile)
      corpus.write(dir)
      val props = incOptions.map((k, v) => s"$k = $v").mkString("", "\n", "\n")
      moduleNames.foreach(m =>
        Files.writeString(dir.resolve(m).resolve("incOptions.properties"), props)
      )
    val shape =
      s""""label":"${o.label}","modules":${o.modules},"depth":${o.depth},"fanOut":${o.fanOut},""" +
        s""""padding":${o.padding},"trait":${o.rootIsTrait},"classes":${corpus.classes.size}"""
    val extractDir = o.dir.toAbsolutePath.resolveSibling(o.dir.getFileName.toString + "-extract")
    setUp(extractDir, o.incOptions + ("apiDebug" -> "true"))
    val extracted =
      val runner = new Runner(extractDir, moduleNames, o.scalaVersion)
      try runner.compileAll().total
      finally runner.finish()
    val dir = o.dir.toAbsolutePath
    setUp(dir, Map("apiDebug" -> "false") ++ o.incOptions)
    val extract =
      s"""{$shape,"step":"extract","declared":${extracted.declared},""" +
        s""""inherited":${extracted.inherited},"nameHashes":${extracted.nameHashes}}"""
    run(o, dir, moduleNames, corpus.edits, shape, Some(extract))
  end synthetic

  private def run(
      o: Options,
      dir: Path,
      moduleNames: Seq[String],
      edits: Seq[Edit],
      shape: String,
      extract: Option[String] = None
  ): Unit =
    val runner = new Runner(dir, moduleNames, o.scalaVersion, o.logLevel)
    val lines = Vector.newBuilder[String]
    def emit(line: String): Unit =
      println(line)
      lines += line
    extract.foreach(emit)
    emit(s"""{$shape,"step":"clean",${runner.compileAll().json}}""")
    for edit <- edits; rep <- 1 to o.reps do
      val file = dir.resolve(edit.file)
      val original = Files.readString(file)
      Files.writeString(file, edit.content)
      emit(s"""{$shape,"step":"${edit.name}","rep":$rep,${runner.compileAll().json}}""")
      Files.writeString(file, original)
      emit(s"""{$shape,"step":"${edit.name}-revert","rep":$rep,${runner.compileAll().json}}""")
    for rep <- 1 to o.reps do
      runner.cleanAll()
      emit(s"""{$shape,"step":"clean-build","rep":$rep,${runner.compileAll().json}}""")
    o.out.foreach(p => Files.write(p, lines.result().mkString("", "\n", "\n").getBytes("UTF-8")))
    runner.finish()
  end run

  /**
   * What a module stores. `declared` and `inherited` count the definitions in the stored APIs,
   * summed over every class and object: the full extraction when `apiDebug` is on, as in the
   * untimed `extract` pass, and only what minimization keeps otherwise. `minimizedBytes`
   * re-serialises the analysis with the APIs minimized, as Zinc stores them by default.
   */
  final case class Stored(
      classes: Int,
      declared: Int,
      inherited: Int,
      nameHashes: Int,
      analysisBytes: Long,
      minimizedBytes: Long,
  ):
    def +(o: Stored): Stored =
      Stored(
        classes + o.classes,
        declared + o.declared,
        inherited + o.inherited,
        nameHashes + o.nameHashes,
        analysisBytes + o.analysisBytes,
        minimizedBytes + o.minimizedBytes,
      )
    def json: String =
      s""""classes":$classes,"declared":$declared,"inherited":$inherited,""" +
        s""""nameHashes":$nameHashes,"analysisBytes":$analysisBytes,""" +
        s""""minimizedBytes":$minimizedBytes"""
  end Stored

  final case class ModuleResult(name: String, rounds: Int, recompiled: Int, stored: Stored)

  final case class StepResult(wallMillis: Long, modules: Seq[ModuleResult]):
    def total: Stored = modules.map(_.stored).reduce(_ + _)
    def json: String =
      val ms = modules.map(m =>
        s"""{"module":"${m.name}","rounds":${m.rounds},"recompiled":${m.recompiled},""" +
          s"""${m.stored.json}}"""
      )
      s""""wallMillis":$wallMillis,"rounds":${modules.map(_.rounds).sum},""" +
        s""""recompiled":${modules.map(_.recompiled).sum},${total.json},""" +
        s""""modules":[${ms.mkString(",")}]"""

  /** Shared by every runner: IncHandler caches the compiled bridge's path in it per JVM. */
  private lazy val cacheDir = Files.createTempDirectory("incbench-cache")

  final class Runner(
      dir: Path,
      moduleNames: Seq[String],
      scalaVersion: String,
      logLevel: Level.Value = Level.Warn
  ):
    private val handler =
      new IncHandler(dir, cacheDir, UnitSpec.newLogger(logLevel), compileToJar = false)
    private var state: handler.State = handler.initialState

    /** Deletes every module's outputs and analysis, for a full rebuild in a warm JVM. */
    def cleanAll(): Unit =
      moduleNames.foreach(m => state = handler.apply(s"$m/clean", Nil, state))

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
      ModuleResult(module, rounds.size, recompiled, stored(p, analysis))

    private def stored(p: ProjectStructure, analysis: Analysis): Stored =
      val classes = analysis.apis.internal.values.toSeq
      val sides = classes.flatMap(c => Seq(c.api().classApi(), c.api().objectApi()))
      val minimizedApis = analysis.apis.internal.map { (name, c) =>
        val companions = Companions.of(
          APIUtil.minimize(c.api().classApi()),
          APIUtil.minimize(c.api().objectApi())
        )
        name -> c.withApi(SafeLazyProxy.strict(companions))
      }
      val minimized = analysis.copy(apis = APIs(minimizedApis, analysis.apis.external))
      val tmp = Files.createTempFile("incbench", ".zip")
      try
        val contents = AnalysisContents.create(minimized, p.prev().setup.get)
        FileAnalysisStore.binary(tmp.toFile).set(contents)
        Stored(
          classes.size,
          sides.map(_.structure.declared.length).sum,
          sides.map(_.structure.inherited.length).sum,
          classes.map(_.nameHashes.length).sum,
          Files.size(p.cacheFile),
          Files.size(tmp),
        )
      finally Files.deleteIfExists(tmp)
    end stored

    def finish(): Unit = handler.finish(state)
  end Runner
end IncBench
