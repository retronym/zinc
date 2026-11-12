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

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path, Paths, StandardCopyOption, StandardOpenOption }
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import sbt.io.IO
import sbt.util.Level

import IncBench.{ Runner, StepResult }

/**
 * Replays a sequence of states of a build, such as the commits of a git history, compiling each
 * incrementally from the one before and comparing the result with a clean build of the same
 * state.
 *
 * `--build` is a frozen build as IncBench's `--build` reads it, which the caller changes between
 * steps: sources, the jars in `lib/`, `incOptions.properties`, `build.json`. `--work` gets one
 * copy of it per `--mode`, each with its own outputs and analysis, and one more, `clean`, that is
 * rebuilt from scratch at every step. A mode is a name and the `incOptions` it adds, so that one
 * Zinc compares, say, `hashing=names` with `hashing=merkle` on the same steps, against the same
 * clean build.
 *
 * After the initial clean build of every copy, each `step NAME` on stdin brings the copies up to
 * date with the build, compiles every mode incrementally, one after the other in an order that
 * rotates, rebuilds `clean`, and appends to `steps.jsonl` in `--work` one line for the clean
 * build and one per mode. `shutdown` or the end of stdin stops it. Each step is answered on
 * stdout with whether its clean build compiled; without a `--mode`, that is all a step does,
 * which finds the states of a history that compile at all.
 *
 * A mode's line carries what it compiled and how long it took, as IncBench reports a step, and
 * its verdict against the clean build (see [[Session.judge]]). The clean line carries the
 * sources whose classfiles differ from the previous step's clean build or that changed
 * themselves: the least any incremental compile had to recompile. Classfiles that differ are
 * copied from both sides to `diffs/` in `--work`, with a textual diff.
 *
 * {{{
 * IncHistory --build /tmp/steward --work /tmp/replay --mode names --mode merkle=hashing=merkle
 * }}}
 */
object IncHistory:
  final case class Mode(name: String, incOptions: Map[String, String])

  final case class Options(
      build: Path = Paths.get("."),
      work: Path = Paths.get("target/inchistory"),
      modes: Vector[Mode] = Vector.empty,
      incOptions: Map[String, String] = Map.empty,
      scalaVersion: String = "2.13.x",
      profile: Boolean = false,
      logLevel: Level.Value = Level.Warn,
      maxEvidence: Int = 20,
  )

  def parse(args: List[String], o: Options = Options()): Options = args match
    case Nil                    => o
    case "--build" :: v :: rest => parse(rest, o.copy(build = Paths.get(v).toAbsolutePath))
    case "--work" :: v :: rest  => parse(rest, o.copy(work = Paths.get(v).toAbsolutePath))
    case "--scala" :: v :: rest => parse(rest, o.copy(scalaVersion = v))
    case "--debug" :: rest      => parse(rest, o.copy(logLevel = Level.Debug))
    case "--profile" :: rest    => parse(rest, o.copy(profile = true))
    case "--mode" :: v :: rest  =>
      val (name, opts) = v.split("=", 2) match
        case Array(n)    => n -> Map.empty[String, String]
        case Array(n, s) => n -> s.split(",").map(_.split("=", 2)).map(a => a(0) -> a(1)).toMap
      parse(rest, o.copy(modes = o.modes :+ Mode(name, opts)))
    case "--inc-option" :: kv :: rest =>
      val Array(k, v) = kv.split("=", 2)
      parse(rest, o.copy(incOptions = o.incOptions + (k -> v)))
    case other :: _ => sys.error(s"Unknown argument $other")

  def main(args: Array[String]): Unit =
    val session = new Session(parse(args.toList))
    def say(s: String): Unit =
      println(s)
      Console.out.flush()
    try
      say(s"""{"ready":true,"cleanOk":${session.start()}}""")
      Iterator.continually(scala.io.StdIn.readLine()).takeWhile(l => l != null && l != "shutdown")
        .foreach { line =>
          line.split(" ", 2) match
            case Array("step", name) =>
              say(s"""{"done":${Json.str(name)},"cleanOk":${session.step(name)}}""")
            case _ => sys.error(s"Unknown command $line")
        }
    finally session.close()

  /** A classfile or TASTy file a build wrote: a digest of its bytes, and when it was written. */
  final case class Product(sha: String, modified: Long)

  /** A build's products by module and path, or its compile errors. */
  type Outcome = Either[Seq[String], Map[String, Product]]

  /**
   * How a product of an incremental build differs from the clean build's. `kind` is `noise` when
   * a normalisation `level` makes them equal, `missing` or `extra` when one side lacks it, and
   * otherwise `compiler` if this step's compile wrote it and `stale` if it did not. `carried`
   * marks a difference an earlier step already reported: neither side has changed since.
   */
  final case class Difference(
      key: String,
      kind: String,
      level: String,
      fresh: Boolean,
      carried: Boolean
  ):
    def json: String =
      Json.obj(
        "class" -> Json.str(key),
        "kind" -> Json.str(kind),
        "level" -> Json.str(level),
        "fresh" -> fresh.toString,
        "carried" -> carried.toString
      )

  object Json:
    def str(s: String): String =
      "\"" + s.flatMap {
        case '"'          => "\\\""
        case '\\'         => "\\\\"
        case '\n'         => "\\n"
        case '\t'         => "\\t"
        case '\r'         => "\\r"
        case c if c < ' ' => f"\\u${c.toInt}%04x"
        case c            => c.toString
      } + "\""
    def arr(xs: Iterable[String]): String = xs.mkString("[", ",", "]")
    def strs(xs: Iterable[String]): String = arr(xs.map(str))
    def obj(fields: (String, String)*): String =
      fields.map { case (k, v) => s"${str(k)}:$v" }.mkString("{", ",", "}")

  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString.take(16)

  private def isProduct(f: Path): Boolean =
    val n = f.getFileName.toString
    n.endsWith(".class") || n.endsWith(".tasty")

  private def relative(root: Path): Seq[(String, Path)] =
    if !Files.exists(root) then Nil
    else
      val s = Files.walk(root)
      try
        s.iterator.asScala.filter(Files.isRegularFile(_)).map(f => root.relativize(f).toString -> f)
          .toVector
      finally s.close()

  /**
   * Brings `to` up to date with the build in `from`, leaving outputs alone, and returns the
   * paths that were added, changed or removed. A file is written only when its content differs,
   * so that an unchanged jar keeps its timestamp. Removals come first, so that a file renamed
   * only in case survives on a case-insensitive file system. Each project's `incOptions.properties` is the
   * build's own with `overrides` on top; `scalac.options` there is appended to the build's.
   */
  def sync(from: Path, to: Path, overrides: Map[String, String]): Seq[String] =
    def kept(rel: String) =
      !rel.split('/').contains("target") && !rel.endsWith(IncBench.ScalacProfileFile)
    val changed = Vector.newBuilder[String]
    val source = relative(from).filter((rel, _) => kept(rel)).toMap
    for (rel, f) <- relative(to) if kept(rel) && !source.contains(rel) do
      Files.delete(f)
      changed += rel
    for (rel, f) <- source.toSeq.sortBy(_._1) do
      val t = to.resolve(rel)
      val bytes =
        if rel.endsWith("incOptions.properties") then properties(f, overrides)
        else Files.readAllBytes(f)
      if !Files.exists(t) || !java.util.Arrays.equals(Files.readAllBytes(t), bytes) then
        Files.createDirectories(t.getParent)
        Files.write(t, bytes)
        changed += rel
    changed.result()

  private def properties(file: Path, overrides: Map[String, String]): Array[Byte] =
    val props = new java.util.Properties
    val in = Files.newInputStream(file)
    try props.load(in)
    finally in.close()
    for (k, v) <- overrides do
      val merged =
        if k == "scalac.options" then Option(props.getProperty(k)).fold(v)(own => s"$own $v")
        else v
      props.setProperty(k, merged)
    val out = new java.io.StringWriter
    props.store(out, null)
    out.toString.linesIterator.filterNot(_.startsWith("#")).toSeq.sorted.mkString("", "\n", "\n")
      .getBytes(UTF_8)

  private def moduleNames(dir: Path): Vector[String] =
    val json = Files.readString(dir.resolve("build.json"))
    "\\{[^{}]*\\}".r.findAllIn(json).toVector.flatMap { obj =>
      """"name"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(obj).map(_.group(1))
    }

  /** One copy of the build: a mode's, compiled incrementally, or the clean reference. */
  final private class Copy(
      val name: String,
      val dir: Path,
      val overrides: Map[String, String],
      o: Options
  ):
    var modules: Vector[String] = Vector.empty
    var runner: Runner = null
    var carried: Map[String, (String, String)] = Map.empty

    /** Syncs with the build; a change outside the sources makes the next compile reload it. */
    def update(): Seq[String] =
      val changed = sync(o.build, dir, overrides)
      if runner == null || changed.exists(c => !c.contains("/src/")) then reload()
      changed

    def reload(): Unit =
      if runner != null then runner.finish()
      modules = moduleNames(dir)
      runner = new Runner(dir, modules, o.scalaVersion, o.logLevel, compileEvery = true)

    def wipe(): Unit =
      if runner != null then runner.finish()
      runner = null
      for m <- moduleNames(dir) do IO.delete(dir.resolve(m).resolve("target").toFile)
      reload()

    def products(): Map[String, Product] =
      modules.flatMap { m =>
        relative(runner.classesDir(m)).collect {
          case (rel, f) if isProduct(f) =>
            s"$m/$rel" -> Product(sha(Files.readAllBytes(f)), Files.getLastModifiedTime(f).toMillis)
        }
      }.toMap

    def file(key: String): Path =
      val Array(m, rel) = key.split("/", 2)
      runner.classesDir(m).resolve(rel)

    /** The source of each product, and every source, as paths in the build. */
    def sources(): (Map[String, String], Set[String]) =
      val all = modules.flatMap { m =>
        runner.analysis(m).toSeq.flatMap { a =>
          val root = runner.classesDir(m)
          a.relations.allSources.toSeq.map { s =>
            val src = dir.relativize(runner.path(s)).toString
            src -> a.relations.products(s).map(p => s"$m/${root.relativize(runner.path(p))}")
          }
        }
      }
      (all.flatMap((src, ps) => ps.map(_ -> src)).toMap, all.map(_._1).toSet)

    /** The sources of the classes compiled at or after `since`, as paths in the build. */
    def compiledSince(since: Long): Set[String] =
      modules.flatMap { m =>
        runner.analysis(m).toSeq.flatMap { a =>
          val starts = a.compilations.allCompilations.map(_.getStartTime).filter(_ >= since).toSet
          a.apis.internal.collect { case (n, c) if starts(c.compilationTimestamp) => n }
            .flatMap(a.relations.definesClass)
            .map(s => dir.relativize(runner.path(s)).toString)
        }
      }.toSet
  end Copy

  final class Session(o: Options):
    private val overrides =
      Map("apiDebug" -> "false") ++ o.incOptions ++
        (if o.profile then
           Map("scalac.options" -> StepProfile.scalacOptions(
             "[basedir]/" + IncBench.ScalacProfileFile
           ))
         else Map.empty)
    private val modes =
      o.modes.map(m => new Copy(m.name, o.work.resolve(m.name), overrides ++ merged(m), o))
    private val clean = new Copy("clean", o.work.resolve("clean"), overrides, o)
    private val log = o.work.resolve("steps.jsonl")
    private var index = 0
    private var previousClean: Map[String, Product] = Map.empty

    private def merged(m: Mode): Map[String, String] =
      m.incOptions.map {
        case ("scalac.options", v) => "scalac.options" ->
            overrides.get("scalac.options").fold(v)(own => s"$own $v")
        case kv => kv
      }

    private def emit(fields: (String, String)*): Unit =
      Files.createDirectories(o.work)
      Files.write(
        log,
        (Json.obj(fields*) + "\n").getBytes(UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )

    /**
     * Builds every copy from scratch, and checks each mode's clean build against `clean`.
     * Returns whether the clean build compiled.
     */
    def start(): Boolean =
      IO.delete(o.work.toFile)
      Files.createDirectories(o.work)
      for c <- clean +: modes do
        c.update()
        c.wipe()
      val reference = build(clean)
      emitClean("base", reference._2, reference._1, Nil, Set.empty)
      for m <- modes do
        val start = System.currentTimeMillis()
        val r = m.runner.tryCompileAll()
        val verdict = judge("base", m, r.map(_ => m.products()), reference._1, start)
        emitMode("base", m, 0, r, Set.empty, verdict, Set.empty)
      previousClean = reference._1.getOrElse(Map.empty)
      reference._1.isRight

    /** Runs one step: the build has changed since the last. Returns whether it compiles clean. */
    def step(name: String): Boolean =
      index += 1
      val changed = clean.update()
      for m <- modes do m.update()
      val order = modes.indices.map(i => modes((i + index) % modes.size.max(1)))
      val results = order.zipWithIndex.map { (m, i) =>
        val start = System.currentTimeMillis()
        val r = m.runner.tryCompileAll()
        (m, i, start, r, r.map(_ => m.products()), m.compiledSince(start))
      }
      clean.wipe()
      val (reference, cleanResult) = build(clean)
      val (sourceOf, allSources): (Map[String, String], Set[String]) =
        if reference.isRight then clean.sources() else (Map.empty, Set.empty)
      val necessary = reference.fold(
        _ => Set.empty[String],
        now =>
          val moved = (now.keySet ++ previousClean.keySet).filter(k =>
            now.get(k).map(_.sha) != previousClean.get(k).map(_.sha)
          )
          (moved.flatMap(sourceOf.get) ++ changed.filter(_.contains("/src/"))).filter(allSources)
      )
      emitClean(name, cleanResult, reference, changed, necessary)
      for (m, i, start, r, outcome, compiled) <- results do
        val verdict = judge(name, m, outcome, reference, start)
        emitMode(name, m, i, r, compiled, verdict, necessary)
        if Session.Findings(verdict._1) then
          m.wipe()
          m.carried = Map.empty
          val again = m.runner.tryCompileAll().map(_ => m.products())
          val reset = judge(s"$name-reset", m, again, reference, Long.MaxValue)
          emit(
            "step" -> Json.str(name),
            "index" -> index.toString,
            "mode" -> Json.str(m.name),
            "reset" -> Json.str(reset._1)
          )
      for now <- reference do previousClean = now
      reference.isRight
    end step

    def close(): Unit = for c <- clean +: modes if c.runner != null do c.runner.finish()

    private def build(c: Copy): (Outcome, Either[Seq[String], StepResult]) =
      val r = c.runner.tryCompileAll()
      (r.map(_ => c.products()), r)

    /**
     * Compares a mode's incremental build with the clean build of the same step. When both
     * compile, each product that differs is classified (see [[Difference]]) and the verdict is
     * the worst of them: `stale` if a product this step did not write differs with no
     * normalisation to explain it, or is missing or left over; `compiler` if only products this
     * step wrote do; `noise` if a normalisation explains every difference; `same` if there is
     * none. When one fails, `missed-error` (the clean build fails), `spurious-error` (the
     * incremental one does) or `same-fail`. `stale`, `missed-error` and `spurious-error` are
     * findings against the incremental compiler. Differences an earlier step reported are
     * listed, but do not count. Both sides of a difference go to `diffs/`.
     */
    private def judge(
        step: String,
        mode: Copy,
        inc: Outcome,
        reference: Outcome,
        start: Long
    ): (String, Seq[Difference]) = (inc, reference) match
      case (Left(_), Left(_))   => ("same-fail", Nil)
      case (Right(_), Left(_))  => ("missed-error", Nil)
      case (Left(_), Right(_))  => ("spurious-error", Nil)
      case (Right(a), Right(b)) =>
        val keys = (a.keySet ++ b.keySet).toSeq.sorted.filter(k =>
          a.get(k).map(_.sha) != b.get(k).map(_.sha)
        )
        val diffs = keys.map { k =>
          val fresh = a.get(k).exists(_.modified >= start)
          val shas = (a.get(k).fold("")(_.sha), b.get(k).fold("")(_.sha))
          val carried = !fresh && mode.carried.get(k).contains(shas)
          val (kind, level) = (a.get(k), b.get(k)) match
            case (None, _) => ("missing", "")
            case (_, None) => ("extra", "")
            case _         =>
              explain(k, mode.file(k), clean.file(k), a, b) match
                case Some(l) => ("noise", l)
                case None    => (if fresh then "compiler" else "stale", "")
          Difference(k, kind, level, fresh, carried)
        }
        mode.carried = diffs.map(d =>
          d.key -> (a.get(d.key).fold("")(_.sha), b.get(d.key).fold("")(_.sha))
        ).toMap
        val counted = diffs.filterNot(_.carried)
        evidence(step, mode, counted.filter(_.kind != "noise") ++ counted.filter(_.kind == "noise"))
        val kinds = counted.map(_.kind).toSet
        val verdict =
          if kinds.exists(Set("stale", "missing", "extra")) then "stale"
          else if kinds("compiler") then "compiler"
          else if kinds("noise") then "noise"
          else "same"
        (verdict, diffs)

    /**
     * The normalisation that makes two products equal, if one does. For a classfile, the lowest
     * level of [[ClassfileNorm]]; when the classfiles also name different TASTy, the TASTy files
     * beside them must differ only in sections that hold no semantics, which is `tasty`. A TASTy
     * file is `tasty` under the same condition.
     */
    private def explain(
        key: String,
        inc: Path,
        ref: Path,
        a: Map[String, Product],
        b: Map[String, Product]
    ): Option[String] =
      def tastyBenign(i: Path, r: Path) =
        Files.exists(i) && Files.exists(r) &&
          TastyNorm.benign(
            TastyNorm.read(Files.readAllBytes(i)),
            TastyNorm.read(Files.readAllBytes(r))
          )
      def sibling(f: Path) =
        f.resolveSibling(f.getFileName.toString.takeWhile(_ != '$').stripSuffix(".class") +
          ".tasty")
      try
        if key.endsWith(".tasty") then Option.when(tastyBenign(inc, ref))("tasty")
        else
          val x = ClassfileNorm.digests(Files.readAllBytes(inc))
          val y = ClassfileNorm.digests(Files.readAllBytes(ref))
          val sameTasty = x.tastyUuid == y.tastyUuid
          ClassfileNorm.level(x, y).flatMap { l =>
            if sameTasty then Some(l.toString.toLowerCase)
            else Option.when(tastyBenign(sibling(inc), sibling(ref)))("tasty")
          }
      catch case scala.util.control.NonFatal(_) => None
    end explain

    private def evidence(step: String, mode: Copy, diffs: Seq[Difference]): Unit =
      val root = o.work.resolve("diffs").resolve(step).resolve(mode.name)
      for d <- diffs.take(o.maxEvidence) do
        val sides = Seq("inc" -> mode.file(d.key), "clean" -> clean.file(d.key))
        for (side, from) <- sides if Files.exists(from) do
          val to = root.resolve(side).resolve(d.key)
          Files.createDirectories(to.getParent)
          Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        if d.key.endsWith(".class") && sides.forall((_, f) => Files.exists(f)) then
          val lines =
            try ClassfileNorm.diff(Files.readAllBytes(sides(0)._2), Files.readAllBytes(sides(1)._2))
            catch case scala.util.control.NonFatal(e) => Seq(s"no diff: $e")
          val to = root.resolve(d.key + ".diff")
          Files.createDirectories(to.getParent)
          Files.write(to, lines.mkString("", "\n", "\n").getBytes(UTF_8))
      val logs = mode.runner.drainInvalidationLogs()
      if diffs.nonEmpty then
        for (m, lines) <- logs if lines.nonEmpty do
          Files.createDirectories(root)
          Files.write(
            root.resolve(s"invalidation-$m.log"),
            lines.mkString("", "\n", "\n").getBytes(UTF_8)
          )
    end evidence

    private def outcome(r: Either[Seq[String], StepResult]): Seq[(String, String)] = r match
      case Left(errors) => Seq("ok" -> "false", "errors" -> Json.strs(errors.take(20)))
      case Right(s)     => Seq("ok" -> "true", "result" -> s"{${s.json}}")

    private def capped(xs: Set[String]): String = Json.strs(xs.toSeq.sorted.take(200))

    private def emitClean(
        step: String,
        r: Either[Seq[String], StepResult],
        reference: Outcome,
        changed: Seq[String],
        necessary: Set[String]
    ): Unit =
      emit(
        (Seq(
          "step" -> Json.str(step),
          "index" -> index.toString,
          "mode" -> Json.str("clean"),
          "products" -> reference.fold(_ => 0, _.size).toString,
          "changed" -> Json.strs(changed.take(200)),
          "changedCount" -> changed.size.toString,
          "structural" -> changed.exists(c => !c.contains("/src/")).toString,
          "necessaryCount" -> necessary.size.toString,
          "necessary" -> capped(necessary)
        ) ++ outcome(r))*
      )

    private def emitMode(
        step: String,
        m: Copy,
        order: Int,
        r: Either[Seq[String], StepResult],
        compiled: Set[String],
        verdict: (String, Seq[Difference]),
        necessary: Set[String]
    ): Unit =
      val counted = verdict._2.filterNot(_.carried)
      emit(
        (Seq(
          "step" -> Json.str(step),
          "index" -> index.toString,
          "mode" -> Json.str(m.name),
          "order" -> order.toString,
          "verdict" -> Json.str(verdict._1),
          "compiledCount" -> compiled.size.toString,
          "compiled" -> capped(compiled),
          "necessaryNotCompiled" -> capped(necessary -- compiled),
          "carried" -> (verdict._2.size - counted.size).toString,
          "differences" -> Json.arr(counted.take(200).map(_.json)),
          "differenceCount" -> counted.size.toString
        ) ++ outcome(r))*
      )
    end emitMode
  end Session

  object Session:
    /** The verdicts that are findings against the incremental compiler. */
    val Findings: Set[String] = Set("stale", "missed-error", "spurious-error")
end IncHistory
