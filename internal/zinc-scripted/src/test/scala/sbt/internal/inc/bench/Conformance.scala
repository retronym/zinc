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
import scala.util.Random
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
 * Per base: a fresh build; then per edit, apply it, build incrementally and compare with a clean
 * build of the edited sources (cached by content), then revert and compare with the base. A
 * divergence in the revert rebuilds the base from scratch. Results go to `--out` as JSON lines.
 *
 * {{{
 * sbt "publishBridges; zincScripted3/Test/runMain sbt.internal.inc.bench.Conformance \
 *   --cases flat.jsonl --dir /tmp/conf --sample 100 --out /tmp/conf.jsonl"
 * }}}
 */
object Conformance:
  final case class Options(
      cases: Path = Paths.get("cases.jsonl"),
      dir: Path = Paths.get("target/conformance"),
      sample: Option[Int] = None,
      seed: Long = 0L,
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
    case Nil                     => o
    case "--cases" :: v :: rest  => parse(rest, o.copy(cases = Paths.get(v)))
    case "--dir" :: v :: rest    => parse(rest, o.copy(dir = Paths.get(v)))
    case "--sample" :: v :: rest => parse(rest, o.copy(sample = Some(v.toInt)))
    case "--seed" :: v :: rest   => parse(rest, o.copy(seed = v.toLong))
    case "--only" :: v :: rest   => parse(rest, o.copy(only = v.split(",").toSet))
    case "--shard" :: v :: rest  =>
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

  final case class EditCase(cls: String, cfg: String, prog: Prog, model: String)

  final case class Base(space: String, id: String, cfg: String, prog: Prog, edits: Seq[EditCase])

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
      Base(
        f.get("space").map(str).getOrElse(""),
        str(f("id")),
        f.get("cfg").map(str).getOrElse(""),
        prog(f("prog")),
        arr(f("edits")).map { e =>
          val g = obj(e)
          EditCase(str(g("cls")), g.get("cfg").map(str).getOrElse(""), prog(g("prog")), rest(g))
        }
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

  /** Which subproject each class goes to. */
  def layout(name: String): (Seq[String], String => String) = name match
    case "single" => (Seq("p"), _ => "p")
    case "split"  => (Seq("up", "down"), c => if c == "A" || c == "M" then "up" else "down")
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

    def write(files: Map[String, String]): Unit =
      for (c, src) <- files do
        val f = dir.resolve(place(c)).resolve(s"$c.scala")
        if !Files.exists(f) || Files.readString(f) != src then
          Files.createDirectories(f.getParent)
          Files.writeString(f, src)

    /** Deletes everything and writes the build afresh. */
    def reset(files: Map[String, String]): Unit =
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
      val props = o.incOptions.map((k, v) => s"$k = $v").mkString("", "\n", "\n")
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
    val filtered = if o.only.isEmpty then all else all.filter(b => o.only(b.id))
    val sampled = o.sample.fold(filtered)(n => new Random(o.seed).shuffle(filtered).take(n))
    val bases = sampled.zipWithIndex.collect { case (b, i) if i % o.shard._2 == o.shard._1 => b }
    val dir = o.dir.toAbsolutePath
    val outLines = Vector.newBuilder[String]
    def emit(line: String): Unit =
      println(line)
      outLines += line
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
    for layoutName <- o.layouts do
      val work = new Build(dir.resolve(s"$layoutName-work"), layoutName, o)
      val clean = new Build(dir.resolve(s"$layoutName-clean"), layoutName, o)
      val cache = mutable.Map.empty[Map[String, String], Result]
      def cleanBuild(files: Map[String, String]): Result =
        cache.getOrElseUpdate(
          files,
          { clean.reset(files); clean.compile().copy(recompiled = Set.empty) }
        )
      for (b, bi) <- bases.zipWithIndex do
        val baseFiles = Render.files(b.prog, None)
        work.reset(baseFiles)
        val r0 = work.compile()
        cache(baseFiles) = r0.copy(recompiled = Set.empty)
        if !r0.ok then
          baseFailed += 1
          emit(s"""{"layout":"$layoutName","space":"${b.space}","base":"${b.id}","cfg":"${b.cfg}","verdict":"base-error","errors":${json(
              r0.errors
            )}}""")
        else
          for e <- b.edits if o.edits.isEmpty || o.edits(e.cfg) do
            cases += 1
            val files = Render.files(b.prog, Some(e))
            work.write(files)
            val inc = work.compile()
            val cl = cleanBuild(files)
            val verdict = compare(inc, cl)
            if o.stop && !agrees(verdict) then
              Console.err.println(
                s"stopped: incremental build in ${dir.resolve(s"$layoutName-work")}"
              )
              throw new IllegalStateException(s"divergence: ${b.id} ${e.cfg} $verdict")
            work.write(baseFiles)
            val back = work.compile()
            val revert = compare(back, r0)
            if !agrees(verdict) || !agrees(revert) then diverged += 1
            emit(
              s"""{"layout":"$layoutName","space":"${b.space}","base":"${b.id}","cfg":"${b.cfg}",""" +
                s""""edit":"${e.cls}","edited":"${e.cfg}","verdict":"$verdict","revert":"$revert",""" +
                s""""recompiled":${json(inc.recompiled.toSeq.sorted)},""" +
                s""""revertRecompiled":${json(back.recompiled.toSeq.sorted)},${e.model},""" +
                s""""cleanOk":${cl.ok},"diff":${json(diff(
                    inc,
                    cl
                  ))},"revertDiff":${json(diff(back, r0))},""" +
                s""""incErrors":${json(inc.errors)},"cleanErrors":${json(cl.errors)},""" +
                s""""revertErrors":${json(back.errors)}}"""
            )
            if !agrees(revert) then
              work.reset(baseFiles)
              work.compile()
        end if
        if (bi + 1) % 10 == 0 then
          Console.err.println(
            s"[$layoutName] ${bi + 1}/${bases.size} bases, $cases cases, $diverged diverged, $baseFailed bases failed"
          )
      end for
      work.finish()
      clean.finish()
    end for
    Console.err.println(s"done: $cases cases, $diverged diverged, $baseFailed bases failed")
  end main

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
