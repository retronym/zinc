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

package sbt.zincoracle

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path }
import java.security.MessageDigest
import java.util.Optional

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.eclipsesource.json.{ Json, JsonArray, JsonObject }
import sbt.internal.inc.{ Analysis, ZincUtil }
import sbt.internal.inc.oracle.ClassDirDiff
import sbt.util.Logger
import xsbti.{ FileConverter, Position, Problem, Reporter, Severity, VirtualFile }
import xsbti.compile.{ CompileAnalysis, Inputs, PreviousResult }

/**
 * Compiles a module's inputs again, into a scratch directory with no previous analysis, and
 * compares the result with what the incremental compile left in the module's output.
 */
object Verify {

  /** Where the build keeps what `incVerify` writes. */
  def home(buildRoot: File): File =
    sys.props.get("inc.oracle.home").fold(new File(buildRoot, "target/inc-oracle"))(new File(_))

  /** Whether this sbt is a replay's child, where a finding is recorded and the replay goes on. */
  def replaying: Boolean = sys.props.contains("inc.oracle.home")

  def currentStep(buildRoot: File): String = {
    val f = new File(home(buildRoot), "current-step")
    if (f.isFile) new String(Files.readAllBytes(f.toPath), UTF_8).trim else "verify"
  }

  private final class Collecting extends Reporter {
    private val all = mutable.ArrayBuffer.empty[Problem]
    def reset(): Unit = all.clear()
    def hasErrors(): Boolean = all.exists(_.severity == Severity.Error)
    def hasWarnings(): Boolean = all.exists(_.severity == Severity.Warn)
    def printSummary(): Unit = ()
    def problems(): Array[Problem] = all.toArray
    def log(problem: Problem): Unit = all += problem
    def comment(pos: Position, msg: String): Unit = ()
    def errors: Seq[String] = all.filter(_.severity == Severity.Error).map(_.message).toList
  }

  private def sha1(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).map("%02x".format(_)).mkString

  private def files(root: Path): Map[String, Path] =
    if (!Files.isDirectory(root)) Map.empty
    else {
      val s = Files.walk(root)
      try s.iterator.asScala.filter(Files.isRegularFile(_)).map(p =>
          root.relativize(p).toString.replace(File.separatorChar, '/') -> p
        ).toMap
      finally s.close()
    }

  private def delete(root: Path): Unit =
    if (Files.exists(root)) {
      val s = Files.walk(root)
      try s.iterator.asScala.toList.reverse.foreach(Files.delete)
      finally s.close()
    }

  private def readMap(f: Path): Option[Map[String, String]] =
    if (!Files.isRegularFile(f)) None
    else
      Some(Files.readAllLines(f, UTF_8).asScala.flatMap { l =>
        l.split("\t", 2) match {
          case Array(k, v) => Some(k -> v)
          case _           => None
        }
      }.toMap)

  private def writeMap(f: Path, m: Map[String, String]): Unit = {
    Files.createDirectories(f.getParent)
    Files.write(f, m.toSeq.sorted.map { case (k, v) => s"$k\t$v" }.mkString("", "\n", "\n").getBytes(UTF_8))
  }

  private def array(values: Iterable[String]): JsonArray = {
    val a = new JsonArray
    values.foreach(a.add)
    a
  }

  /**
   * The sources whose outputs the incremental compile wrote since the last `incVerify`, and the
   * number of Zinc cycles if the analysis records them. Zinc deletes and rewrites the outputs of
   * every source it compiles, so a changed modification time is a compile; the times from last
   * time are kept beside the scratch output. The analysis cannot say: sbt 2 hands a task no
   * analysis from before the compile and normalises the timestamps in the one after.
   */
  private def compiledSince(
      scratch: Path,
      after: CompileAnalysis,
      classes: Path,
      converter: FileConverter
  ): (Set[String], Option[Int]) = {
    val a = after.asInstanceOf[Analysis]
    val file = scratch.resolve("inc-products.tsv")
    val before = readMap(file).getOrElse(Map.empty)
    val now = files(classes).map { case (k, p) =>
      k -> s"${Files.getLastModifiedTime(p).toMillis}/${Files.size(p)}"
    }
    val written = now.collect { case (k, stamp) if !before.get(k).contains(stamp) => k }.toSet
    val sources = a.relations.allSources.filter { s =>
      a.relations.products(s).exists { p =>
        val path = converter.toPath(p).toAbsolutePath.normalize
        path.startsWith(classes) && written(classes.relativize(path).toString.replace(File.separatorChar, '/'))
      }
    }.map(_.id).toSet
    val compilations = after.readCompilations.getAllCompilations.length
    val cycles = before.get("<compilations>").map(n => compilations - n.toInt).filter(_ > 0)
    writeMap(file, now + ("<compilations>" -> compilations.toString))
    (sources, if (before.isEmpty && compilations > 0) Some(compilations) else cycles)
  }

  /** The outputs of a set of sources, as paths below the classes directory. */
  private def productsOf(analysis: CompileAnalysis, sources: Set[String], classes: Path, converter: FileConverter): Set[String] = {
    val a = analysis.asInstanceOf[Analysis]
    a.relations.allSources.filter(s => sources(s.id)).flatMap(a.relations.products).flatMap { p =>
      val path = converter.toPath(p)
      if (path.startsWith(classes)) Some(classes.relativize(path).toString.replace(File.separatorChar, '/')) else None
    }.toSet
  }

  /**
   * One module and configuration. `incremental` is the analysis of the incremental compile, or
   * None if it failed. Writes `<step>/<project>-<config>.json` under the build's `inc-oracle`
   * directory and returns it.
   */
  def run(
      project: String,
      config: String,
      buildRoot: File,
      scratchBase: File,
      incremental: Option[CompileAnalysis],
      upstreamOk: Boolean,
      inputs: => Inputs,
      fallbackConverter: FileConverter,
      log: Logger
  ): JsonObject = {
    val step = currentStep(buildRoot)
    val file = home(buildRoot).toPath.resolve("results").resolve(step).resolve(s"$project-$config.json")
    Files.createDirectories(file.getParent)
    if (!upstreamOk) {
      val skipped = new JsonObject().add("step", step).add("project", project).add("config", config)
        .add("verdict", "upstream-failed")
      Files.write(file, skipped.toString.getBytes(UTF_8))
      log.info(s"[incVerify] $project / $config: not checked, a module it depends on does not compile")
      return skipped
    }
    val options = inputs.options
    val converter = if (options.converter.isPresent) options.converter.get else fallbackConverter
    val incDir = options.classesDirectory.toAbsolutePath.normalize
    val scratch = scratchBase.toPath.toAbsolutePath.normalize
    val cleanDir = scratch.resolve("classes")

    val previousDigests = readMap(scratch.resolve("clean-digests.tsv"))
    val previousSources = readMap(scratch.resolve("clean-sources.tsv"))
    val previousDifferences = readMap(scratch.resolve("differences.tsv")).getOrElse(Map.empty)
    delete(cleanDir)
    Files.createDirectories(cleanDir)

    val reporter = new Collecting
    val classpath: Array[VirtualFile] = options.classpath.map { e =>
      if (converter.toPath(e).toAbsolutePath.normalize == incDir) converter.toVirtualFile(cleanDir) else e
    }
    val cleanInputs = inputs
      .withOptions(
        options.withClassesDirectory(cleanDir).withClasspath(classpath).withEarlyOutput(Optional.empty[xsbti.compile.Output]())
      )
      .withSetup(
        inputs.setup
          .withReporter(reporter)
          .withCacheFile(scratch.resolve("cache").toFile)
          .withProgress(Optional.empty[xsbti.compile.CompileProgress]())
          .withEarlyAnalysisStore(Optional.empty[xsbti.compile.AnalysisStore]())
          .withIncrementalCompilerOptions(inputs.setup.incrementalCompilerOptions.withPipelining(false))
      )
      .withPreviousResult(
        PreviousResult.of(Optional.empty[CompileAnalysis](), Optional.empty[xsbti.compile.MiniSetup]())
      )
    val started = System.nanoTime
    val clean: Either[String, CompileAnalysis] =
      try Right(ZincUtil.defaultIncrementalCompiler.compile(cleanInputs, Logger.Null).analysis)
      catch {
        case _: xsbti.CompileFailed => Left(reporter.errors.take(5).mkString("; "))
      }
    val cleanMillis = (System.nanoTime - started) / 1000000

    val out = new JsonObject
    out.add("step", step).add("project", project).add("config", config)
    out.add("incOk", incremental.isDefined).add("cleanOk", clean.isRight).add("cleanMillis", cleanMillis)

    val (compiled, cycles) =
      incremental.fold((Set.empty[String], Option.empty[Int]))(compiledSince(scratch, _, incDir, converter))
    out.add("compiled", array(compiled.toSeq.sorted))
    cycles.foreach(out.add("cycles", _))

    val verdict = (incremental, clean) match {
      case (None, Left(_)) => "same-fail"
      case (Some(_), Left(errors)) =>
        out.add("cleanErrors", errors)
        "missed-error"
      case (None, Right(_)) => "spurious-error"
      case (Some(inc), Right(cleanAnalysis)) =>
        val written = productsOf(inc, compiled, incDir, converter)
        val incOrEmpty = if (Files.isDirectory(incDir)) incDir else Files.createDirectories(scratch.resolve("empty"))
        val diffs = ClassDirDiff.compare(incOrEmpty, cleanDir).asScala
        val rows = new JsonArray
        var worst = "same"
        val rank = Seq("same", "noise", "compiler", "stale")
        val now = mutable.Map.empty[String, String]
        diffs.foreach { d =>
          val level = d.level.orElse(null)
          val kind =
            if (level != null) "noise"
            else if (d.kind == ClassDirDiff.Kind.DIFFERS && written(d.path)) "compiler"
            else "stale"
          val signature = s"$kind/${d.kind.label}/$level"
          val carried = !written(d.path) && previousDifferences.get(d.path).contains(signature)
          now(d.path) = signature
          if (rank.indexOf(kind) > rank.indexOf(worst)) worst = kind
          val row = new JsonObject().add("class", s"$project/${d.path}").add("kind", d.kind.label)
          if (level != null) row.add("level", level)
          row.add("verdict", kind).add("fresh", !carried).add("carried", carried).add("pickleOnly", d.pickleOnly)
          rows.add(row)
        }
        writeMap(scratch.resolve("differences.tsv"), now.toMap)
        out.add("differences", rows).add("products", files(cleanDir).size)

        val digests = files(cleanDir).map { case (k, p) => k -> sha1(Files.readAllBytes(p)) }
        val cleanA = cleanAnalysis.asInstanceOf[Analysis]
        val sources: Map[String, String] =
          cleanA.stamps.sources.map { case (s, stamp) => s.id -> stamp.writeStamp }.toMap
        for (oldDigests <- previousDigests; oldSources <- previousSources) {
          val changedProducts = (digests.keySet ++ oldDigests.keySet).filter(k => digests.get(k) != oldDigests.get(k))
          val bySource = cleanA.relations.allSources.filter { s =>
            cleanA.relations.products(s).exists { p =>
              val path = converter.toPath(p)
              path.startsWith(cleanDir) && changedProducts(cleanDir.relativize(path).toString.replace(File.separatorChar, '/'))
            }
          }.map(_.id).toSet
          val edited = sources.collect { case (s, stamp) if !oldSources.get(s).contains(stamp) => s }.toSet
          out.add("necessary", array((bySource ++ edited).toSeq.sorted))
        }
        writeMap(scratch.resolve("clean-digests.tsv"), digests)
        writeMap(scratch.resolve("clean-sources.tsv"), sources)
        out.add("sources", sources.size)
        worst
    }
    out.add("verdict", verdict)

    Files.write(file, out.toString.getBytes(UTF_8))
    if (isFinding(verdict)) {
      val dir = home(buildRoot).toPath.resolve("findings").resolve(step).resolve(s"$project-$config")
      Finding.write(dir, out, incDir, cleanDir)
      log.error(s"[incVerify] a report without source code is in $dir/summary.md; the classfiles beside it are from your build")
    }
    val summary = s"$project / $config: $verdict"
    verdict match {
      case "same" | "same-fail" => log.info(s"[incVerify] $summary (${compiled.size} sources compiled incrementally)")
      case "noise" =>
        log.info(s"[incVerify] $summary: differences from the clean build are explained by normalisation")
      case "compiler" =>
        log.warn(s"[incVerify] $summary: recompiled classes differ from the clean build's (the compiler's output depends on what is compiled together)")
      case _ => log.error(s"[incVerify] $summary")
    }
    out
  }

  def isFinding(verdict: String): Boolean =
    verdict == "stale" || verdict == "missed-error" || verdict == "spurious-error"

  def read(f: File): JsonObject = Json.parse(new String(Files.readAllBytes(f.toPath), UTF_8)).asObject
}
