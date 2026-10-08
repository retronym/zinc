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
import java.security.MessageDigest

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import sbt.internal.util.ManagedLogger
import sbt.io.IO
import sbt.util.{ Level, LoggerContext }
import sjsonnew.shaded.scalajson.ast.unsafe.*
import sjsonnew.support.scalajson.unsafe.Parser

/**
 * Differential test of incremental compilation: for a base program and an edit, the classfiles
 * of an incremental build must equal those of a clean build of the edited program, and a build
 * that fails must fail both ways.
 *
 * The programs come from the Lean model of Zinc (`lake exe conformance` in
 * retronym/talks/zinc-incrementality/lean), one JSON line per base program with its single-class
 * edits. Each class becomes one Scala file; a member that overrides a concrete one gets
 * `override`, decided against the program the class was written in, so an edit changes exactly
 * one file. Layouts: `single` (one subproject) and `split` (`A` and `M` upstream, the rest
 * downstream).
 *
 * Per case: build the base (reused while consecutive cases share it), apply the edit, build
 * incrementally and compare with a clean build of the edited sources (cached by content), then
 * revert and compare with the base. A divergence in the revert rebuilds the base from scratch.
 * Results go to `--out` as JSON lines.
 *
 * Cases run in [[ConformanceOrder]]'s order (`--order covering|reversed|enum`), so bugs show
 * early; `--sample N` runs the first N of a shard. `--print-order` writes the order and stops.
 *
 * {{{
 * sbt "publishBridges; zincScripted/Test/runMain sbt.internal.inc.bench.Conformance \
 *   --cases flat.jsonl --dir /tmp/conf --sample 100 --out /tmp/conf.jsonl"
 * }}}
 */
object Conformance:
  final case class Options(
      cases: Path = Paths.get("cases.jsonl"),
      dir: Path = Paths.get("target/conformance"),
      sample: Option[Int] = None,
      seed: Long = 0L,
      order: String = "covering",
      printOrder: Option[Path] = None,
      only: Set[String] = Set.empty,
      shard: (Int, Int) = (0, 1),
      edits: Set[String] = Set.empty,
      stop: Boolean = false,
      pause: Long = 0L,
      layouts: Seq[String] = Seq("single", "split"),
      scalaVersion: String = "2.13.x",
      incOptions: Map[String, String] = Map.empty,
      out: Option[Path] = None,
      logLevel: Option[Level.Value] = None,
  )

  def parse(args: List[String], o: Options = Options()): Options = args match
    case Nil                          => o
    case "--cases" :: v :: rest       => parse(rest, o.copy(cases = Paths.get(v)))
    case "--dir" :: v :: rest         => parse(rest, o.copy(dir = Paths.get(v)))
    case "--sample" :: v :: rest      => parse(rest, o.copy(sample = Some(v.toInt)))
    case "--seed" :: v :: rest        => parse(rest, o.copy(seed = v.toLong))
    case "--order" :: v :: rest       => parse(rest, o.copy(order = v))
    case "--print-order" :: v :: rest => parse(rest, o.copy(printOrder = Some(Paths.get(v))))
    case "--only" :: v :: rest        => parse(rest, o.copy(only = v.split(",").toSet))
    case "--shard" :: v :: rest       =>
      val Array(i, n) = v.split("/").map(_.toInt)
      parse(rest, o.copy(shard = (i, n)))
    case "--layouts" :: v :: rest => parse(rest, o.copy(layouts = v.split(",").toSeq))
    case "--scala" :: v :: rest   => parse(rest, o.copy(scalaVersion = v))
    case "--out" :: v :: rest     => parse(rest, o.copy(out = Some(Paths.get(v))))
    case "--edits" :: v :: rest   =>
      parse(rest, o.copy(edits = v.split(",").map(_.replace("_", " ")).toSet))
    case "--pause" :: v :: rest       => parse(rest, o.copy(pause = v.toLong))
    case "--stop" :: rest             => parse(rest, o.copy(stop = true))
    case "--debug" :: rest            => parse(rest, o.copy(logLevel = Some(Level.Debug)))
    case "--inc-option" :: kv :: rest =>
      val Array(k, v) = kv.split("=", 2)
      parse(rest, o.copy(incOptions = o.incOptions + (k -> v)))
    case other :: _ => sys.error(s"Unknown argument $other")

  /** A class of a program, as the Lean model dumps it. */
  final case class Cls(
      name: String,
      kind: String,
      tparams: Seq[String],
      parents: Seq[(String, String)],
      decls: Seq[(String, String, Boolean)],
      body: Seq[(String, String)],
  )

  type Prog = Seq[Cls]

  /** The cfg as named factors: the dump's `factors`, or else the fields of `cfg`. */
  type Factors = Seq[(String, String)]

  /**
   * An edit: either a whole program (`prog`, from the Lean model) or the files it changes
   * (`files`, a file's new source or `None` to delete it).
   */
  final case class EditCase(
      cls: String,
      cfg: String,
      factors: Factors,
      prog: Prog,
      model: String,
      files: Map[String, Option[String]] = Map.empty,
  )

  /**
   * A base program, as a `prog` or as source `files`. A file's tier places it: 0 in a `macros`
   * subproject upstream of everything, 1 upstream in the `split` layout, 2 downstream.
   * `scalacOptions` apply to every subproject.
   */
  final case class Base(
      space: String,
      id: String,
      cfg: String,
      factors: Factors,
      prog: Prog,
      edits: Seq[EditCase],
      files: Map[String, String] = Map.empty,
      tiers: Map[String, Int] = Map.empty,
      scalacOptions: String = "",
      groups: Map[String, String] = Map.empty,
  ):
    def tier(file: String): Int = tiers.getOrElse(
      file,
      if file == "A.scala" || file == "M.scala" then 1 else 2
    )

    /** The sources of the base, or of the program after `edit`. */
    def sources(edit: Option[EditCase]): Map[String, String] =
      if prog.nonEmpty then
        val header = s"package ${Render.pkg}\n\n"
        Render.files(prog, edit).toSeq
          .groupBy((c, _) => groups.getOrElse(c, s"$c.scala"))
          .map { (file, cs) =>
            val srcs = cs.sortBy(_._1).map(_._2)
            file -> (srcs.head +: srcs.tail.map(_.stripPrefix(header))).mkString("\n")
          }
      else
        edit.fold(files)(e =>
          e.files.foldLeft(files) {
            case (acc, (f, Some(src))) => acc.updated(f, src)
            case (acc, (f, None))      => acc - f
          }
        )
  end Base

  private def str(v: JValue): String = v match
    case JString(s) => s
    case JNumber(n) => n
    case other      => sys.error(s"not a string: $other")

  private def arr(v: JValue): Seq[JValue] = v match
    case JArray(a) => a.toSeq
    case other     => sys.error(s"not an array: $other")

  private def obj(v: JValue): Map[String, JValue] = v match
    case JObject(fs) => fs.iterator.map(f => f.field -> f.value).toMap
    case other       => sys.error(s"not an object: $other")

  private def prog(v: JValue): Prog = arr(v).map { c =>
    val f = obj(c)
    Cls(
      str(f("name")),
      str(f("kind")),
      arr(f("tparams")).map(str),
      arr(f("parents")).map(p =>
        arr(p) match
          case Seq(a, b) => (str(a), str(b))
      ),
      arr(f("decls")).map(d =>
        arr(d) match
          case Seq(n, t, JBoolean(b)) => (str(n), str(t), b)
      ),
      arr(f("body")).map(s =>
        arr(s) match
          case Seq(a, b) => (str(a), str(b))
      ),
    )
  }

  def readCases(p: Path): Vector[Base] =
    Files.readAllLines(p).asScala.toVector.filter(_.trim.nonEmpty).map { line =>
      val f = obj(Parser.parseUnsafe(line))
      def rest(e: Map[String, JValue]) =
        Seq("modelRecompiled", "modelClean", "modelErrs")
          .flatMap(k =>
            e.get(k).map(v => s""""$k":${sjsonnew.support.scalajson.unsafe.CompactPrinter(v)}""")
          )
          .mkString(",")
      def factors(g: Map[String, JValue]): Factors = g.get("factors") match
        case Some(JObject(fs)) => fs.toSeq.map(x => x.field -> str(x.value))
        case _                 =>
          g.get("cfg").map(str).getOrElse("").split(" ").toSeq.zipWithIndex.map((v, i) =>
            s"c$i" -> v
          )
      def files(g: Map[String, JValue]): Map[String, Option[String]] = g.get("files") match
        case Some(JObject(fs)) =>
          fs.toSeq.map(x =>
            x.field ->
              (x.value match
                case JNull => None
                case v     => Some(str(v)))
          ).toMap
        case _ => Map.empty
      Base(
        f.get("space").map(str).getOrElse(""),
        str(f("id")),
        f.get("cfg").map(str).getOrElse(""),
        factors(f),
        f.get("prog").fold(Nil)(prog),
        arr(f("edits")).map { e =>
          val g = obj(e)
          EditCase(
            g.get("cls").map(str).getOrElse(""),
            g.get("cfg").map(str).getOrElse(""),
            factors(g),
            g.get("prog").fold(Nil)(prog),
            rest(g),
            files(g),
          )
        },
        files(f).collect { case (k, Some(v)) => k -> v },
        f.get("tiers") match
          case Some(JObject(ts)) => ts.toSeq.map(x => x.field -> str(x.value).toInt).toMap
          case _                 =>
            Map.empty
        ,
        f.get("scalacOptions").map(str).getOrElse(""),
        f.get("groups") match
          case Some(JObject(gs)) => gs.toSeq.map(x => x.field -> str(x.value)).toMap
          case _                 =>
            Map.empty
        ,
      )
    }

  /** Rendering a program as Scala sources. */
  object Render:
    val pkg = "conf"

    private def ancestors(p: Prog, c: String): Seq[String] =
      val byName = p.map(x => x.name -> x).toMap
      val seen = mutable.LinkedHashSet.empty[String]
      def go(n: String): Unit = byName.get(n).foreach(_.parents.foreach { (q, _) =>
        if seen.add(q) then go(q)
      })
      go(c)
      seen.toSeq

    private def default(ty: String): String = ty match
      case "Int"    => "1"
      case "String" => "\"\""
      case t        => s"null.asInstanceOf[$t]"

    private def tps(ps: Seq[String]) = if ps.isEmpty then "" else ps.mkString("[", ", ", "]")

    private def receiver(p: Prog, r: String): (String, String) =
      p.find(_.name == r) match
        case Some(c) if c.kind == "object" => ("", s"$r.type")
        case Some(c) if c.tparams.nonEmpty =>
          ("[U]", s"$r${c.tparams.map(_ => "U").mkString("[", ", ", "]")}")
        case _ => ("", r)

    /** The source of class `c` as written in program `p`. */
    def source(p: Prog, c: Cls): String =
      val byName = p.map(x => x.name -> x).toMap
      val anc = ancestors(p, c.name)
      def concreteAbove(n: String) =
        anc.exists(a => byName.get(a).exists(_.decls.exists(d => d._1 == n && !d._3)))
      val ext = c.parents.zipWithIndex.map { case ((q, t), i) =>
        val targs = if byName.get(q).exists(_.tparams.nonEmpty) then s"[$t]" else ""
        (if i == 0 then " extends " else " with ") + q + targs
      }.mkString
      val decls = c.decls.map { (n, ty, deferred) =>
        if deferred then s"  def $n: $ty\n"
        else
          val ov = if concreteAbove(n) then "override " else ""
          s"  ${ov}def $n: $ty = ${default(ty)}\n"
      }
      val body = c.body.zipWithIndex.map { case ((r, n), i) =>
        if r == c.name then s"  def use$i = this.$n\n"
        else
          val (mtp, rt) = receiver(p, r)
          s"  def use$i$mtp(x: $rt) = x.$n\n"
      }
      s"package $pkg\n\n${c.kind} ${c.name}${tps(c.tparams)}$ext {\n${decls.mkString}${body.mkString}}\n"
    end source

    /** Each class's file, rendered against the program the class was last written in. */
    def files(base: Prog, edit: Option[EditCase]): Map[String, String] =
      base.map { c =>
        val src = edit match
          case Some(e) if e.cls == c.name => source(e.prog, e.prog.find(_.name == c.name).get)
          case _                          => source(base, c)
        c.name -> src
      }.toMap
  end Render

  /** The subprojects of a layout, and the one for each tier. */
  def layout(name: String): (Seq[String], Int => String) = name match
    case "single" => (Seq("macros", "p"), t => if t == 0 then "macros" else "p")
    case "split"  => (Seq("macros", "up", "down"), Seq("macros", "up", "down")(_))
    case other    => sys.error(s"Unknown layout $other")

  final case class Result(
      ok: Boolean,
      classes: Map[String, String],
      errors: Seq[String],
      recompiled: Set[String]
  )

  private def silentLogger(): ManagedLogger =
    val name = "conformance-" + UnitSpec.generateId.incrementAndGet
    val l = LoggerContext.globalContext.logger(name, None, None)
    LoggerContext.globalContext.clearAppenders(name)
    l

  private lazy val cacheDir = Files.createTempDirectory("conformance-cache")

  /** A build directory driven through the scripted [[IncHandler]]. */
  final class Build(dir: Path, layoutName: String, o: Options):
    private val (projects, place) = layout(layoutName)
    private var handler: IncHandler = null
    private var state: Option[IncState] = None
    private var tier: String => Int = _ => 2
    private var written = Map.empty[String, Path]

    /** Writes the files that changed, and deletes those no longer there. */
    def write(files: Map[String, String]): Unit =
      for (name, f) <- written if !files.contains(name) do Files.deleteIfExists(f)
      written = files.map { (name, src) =>
        val f = dir.resolve(place(tier(name))).resolve(name)
        if !Files.exists(f) || Files.readString(f) != src then
          Files.createDirectories(f.getParent)
          Files.writeString(f, src)
        name -> f
      }

    /** Deletes everything and writes the build afresh. */
    def reset(files: Map[String, String], base: Base): Unit =
      tier = base.tier
      written = Map.empty
      if handler != null then handler.finish(state)
      IO.delete(dir.toFile)
      Files.createDirectories(dir)
      val ps = projects.zipWithIndex.map { (p, i) =>
        val deps = if i == 0 then ""
        else projects.take(i).map(q => s""""$q"""").mkString(""", "dependsOn": [""", ", ", "]")
        s"""    { "name": "$p", "scalaVersion": "${o.scalaVersion}"$deps }"""
      }
      Files.writeString(
        dir.resolve("build.json"),
        ps.mkString("{\n  \"projects\": [\n", ",\n", "\n  ]\n}\n")
      )
      val scalac =
        if base.scalacOptions.isEmpty then Map.empty
        else Map("scalac.options" -> base.scalacOptions)
      val props = (o.incOptions ++ scalac).map((k, v) => s"$k = $v").mkString("", "\n", "\n")
      projects.foreach { p =>
        Files.createDirectories(dir.resolve(p))
        Files.writeString(dir.resolve(p).resolve("incOptions.properties"), props)
      }
      write(files)
      val log = o.logLevel.fold(silentLogger())(UnitSpec.newLogger)
      handler = new IncHandler(dir, cacheDir, log, compileToJar = false)
      state = handler.initialState
    end reset

    def compile(): Result =
      if o.pause > 0 then Thread.sleep(o.pause)
      val start = System.currentTimeMillis()
      val (ok, errors) =
        try
          state = Some(handler.apply(s"${projects.last}/compile", Nil, state).get)
          (true, Nil)
        catch
          case NonFatal(e) =>
            def problems(t: Throwable): Seq[String] = t match
              case null                   => Seq(String.valueOf(e))
              case f: xsbti.CompileFailed =>
                f.problems.toSeq.map(p =>
                  s"${p.position.sourceFile.toScala.map(_.getName).getOrElse("")}: ${p.message}"
                )
              case other => problems(other.getCause)
            (false, problems(e))
      Result(
        ok,
        if ok then classes() else Map.empty,
        errors.sorted,
        if ok then recompiled(start) else Set.empty
      )
    end compile

    private def recompiled(since: Long): Set[String] =
      projects.flatMap { p =>
        handler.lookupProject(p).prev().analysis.toScala.toSeq.flatMap { a0 =>
          val a = a0.asInstanceOf[Analysis]
          val starts =
            a.compilations.allCompilations.filter(_.getStartTime >= since).map(_.getStartTime).toSet
          a.apis.internal.collect {
            case (n, c) if starts(c.compilationTimestamp) => n.stripPrefix(s"${Render.pkg}.")
          }
        }
      }.toSet

    private def classes(): Map[String, String] =
      projects.flatMap { p =>
        val root = dir.resolve(p).resolve("target").resolve("classes")
        if !Files.exists(root) then Nil
        else
          Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".class")).map { f =>
            val md = MessageDigest.getInstance("SHA-256")
            s"$p/${root.relativize(f)}" ->
              md.digest(Files.readAllBytes(f)).map("%02x".format(_)).mkString.take(16)
          }.toSeq
      }.toMap

    def finish(): Unit = if handler != null then handler.finish(state)
  end Build

  extension [A](o: java.util.Optional[A])
    private def toScala: Option[A] = if o.isPresent then Some(o.get) else None

  def main(args: Array[String]): Unit =
    val o = parse(args.toList)
    val all = readCases(o.cases)
    val bases = all
      .filter(b => o.only.isEmpty || o.only(b.id))
      .map(b => b.copy(edits = b.edits.filter(e => o.edits.isEmpty || o.edits(e.cfg))))
    val ordered = ConformanceOrder.order(bases, o.layouts, o.order, o.seed, o.shard)
    val items = o.sample.fold(ordered)(ordered.take)
    o.printOrder match
      case Some(p) =>
        val lines = items.map(it => s"${it.layout}\t${it.base.id}\t${it.edit.cfg}")
        Files.write(p, lines.mkString("", "\n", "\n").getBytes("UTF-8"))
      case None => run(o, items)
  end main

  private def run(o: Options, items: Seq[ConformanceOrder.Item]): Unit =
    val dir = o.dir.toAbsolutePath
    def emit(line: String): Unit =
      println(line)
      o.out.foreach(p =>
        Files.writeString(
          p,
          line + "\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND
        )
      )
    o.out.foreach(Files.deleteIfExists)
    var cases, diverged, baseFailed = 0
    final class Layout(name: String):
      val work = new Build(dir.resolve(s"$name-work"), name, o)
      val clean = new Build(dir.resolve(s"$name-clean"), name, o)
      val cache = mutable.Map.empty[(Map[String, String], Map[String, Int]), Result]
      def key(files: Map[String, String], base: Base) =
        (files, files.map((f, _) => f -> base.tier(f)))
      var current: Option[(String, Result)] = None
      def cleanBuild(files: Map[String, String], base: Base): Result =
        cache.getOrElseUpdate(
          key(files, base),
          { clean.reset(files, base); clean.compile().copy(recompiled = Set.empty) }
        )
      def finish(): Unit =
        work.finish(); clean.finish()
    val layouts = mutable.LinkedHashMap.empty[String, Layout]
    val failedBases = mutable.HashSet.empty[(String, String)]
    for (it, n) <- items.zipWithIndex if !failedBases(it.group) do
      val l = layouts.getOrElseUpdate(it.layout, new Layout(it.layout))
      val b = it.base
      val e = it.edit
      val baseFiles = b.sources(None)
      val r0 = l.current match
        case Some((id, r)) if id == b.id => r
        case _                           =>
          l.work.reset(baseFiles, b)
          val r = l.work.compile()
          l.cache(l.key(baseFiles, b)) = r.copy(recompiled = Set.empty)
          l.current = Some(b.id -> r)
          r
      if !r0.ok then
        baseFailed += 1
        failedBases += it.group
        emit(
          s"""{"layout":"${it.layout}","space":"${b.space}","base":"${b.id}","cfg":"${b.cfg}",""" +
            s""""verdict":"base-error","errors":${json(r0.errors)}}"""
        )
      else
        cases += 1
        val files = b.sources(Some(e))
        l.work.write(files)
        val inc = l.work.compile()
        val cl = l.cleanBuild(files, b)
        val verdict = compare(inc, cl)
        if o.stop && !agrees(verdict) then
          Console.err.println(s"stopped: incremental build in ${dir.resolve(s"${it.layout}-work")}")
          throw new IllegalStateException(s"divergence: ${b.id} ${e.cfg} $verdict")
        l.work.write(baseFiles)
        val back = l.work.compile()
        val revert = compare(back, r0)
        if !agrees(verdict) || !agrees(revert) then diverged += 1
        emit(
          s"""{"layout":"${it.layout}","space":"${b.space}","base":"${b.id}","cfg":"${b.cfg}",""" +
            s""""edit":"${e.cls}","edited":"${e.cfg}","verdict":"$verdict","revert":"$revert",""" +
            s""""recompiled":${json(inc.recompiled.toSeq.sorted)},""" +
            s""""revertRecompiled":${json(back.recompiled.toSeq.sorted)},${
                if e.model.isEmpty then "" else e.model + ","
              }""" +
            s""""cleanOk":${cl.ok},"diff":${json(diff(inc, cl))},""" +
            s""""revertDiff":${json(diff(back, r0))},""" +
            s""""incErrors":${json(inc.errors)},"cleanErrors":${json(cl.errors)},""" +
            s""""revertErrors":${json(back.errors)}}"""
        )
        if !agrees(revert) then l.current = None
      end if
      if (n + 1) % 100 == 0 then
        Console.err.println(
          s"${n + 1}/${items.size} cases, $diverged diverged, $baseFailed bases failed"
        )
    end for
    layouts.values.foreach(_.finish())
    Console.err.println(s"done: $cases cases, $diverged diverged, $baseFailed bases failed")
  end run

  def agrees(verdict: String): Boolean = verdict == "same" || verdict == "same-fail"

  def compare(inc: Result, clean: Result): String = (inc.ok, clean.ok) match
    case (true, true)   => if inc.classes == clean.classes then "same" else "bytecode"
    case (true, false)  => "missed-error"
    case (false, true)  => "spurious-error"
    case (false, false) => if inc.errors == clean.errors then "same" else "same-fail"

  def diff(a: Result, b: Result): Seq[String] =
    if !a.ok || !b.ok then Nil
    else
      (a.classes.keySet ++ b.classes.keySet).toSeq.sorted.collect {
        case k if a.classes.get(k) != b.classes.get(k) =>
          s"$k ${a.classes.contains(k)}/${b.classes.contains(k)}"
      }

  private def json(xs: Seq[String]): String =
    xs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
      .mkString("[", ",", "]")
end Conformance
