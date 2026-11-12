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
import java.nio.file.{ Files, StandardOpenOption }

import scala.collection.JavaConverters._

import com.eclipsesource.json.{ Json, JsonArray, JsonObject, JsonValue }
import sbt._
import sbt.internal.inc.oracle.{ Report, SbtReplay }

import ZincOraclePlugin.autoImport._

/**
 * The commands of a replay. `incReplay` starts one from the user's session. The other two run in
 * the replay's child sbt, in the clone: `incReplayRun` checks out the next commit and queues
 * `incOracleStep` for it, which compiles, verifies and writes the step's rows to `steps.jsonl`.
 */
object Steps {
  private val Rank =
    Seq("same", "noise", "compiler", "same-fail", "stale", "spurious-error", "missed-error")

  private def root(state: State): File = Project.extract(state).get(LocalRootProject / Keys.baseDirectory)

  private def strings(v: JsonValue): Seq[String] =
    if (v == null) Nil else v.asArray.values.asScala.map(_.asString).toList

  private def array(values: Iterable[String]): JsonArray = {
    val a = new JsonArray
    values.foreach(a.add)
    a
  }

  /** The two rows of a step, as IncHistory writes them: the clean build's and the mode's. */
  private def rows(step: String, index: Int, results: Seq[JsonObject], incMillis: Long): Seq[JsonObject] = {
    val checked = results.filter(_.get("verdict").asString != "upstream-failed")
    val ok = checked.filter(r => r.get("incOk").asBoolean && r.get("cleanOk").asBoolean)
    val necessary = ok.flatMap(r => strings(r.get("necessary")))
    val knowsNecessary = ok.nonEmpty && ok.forall(_.get("necessary") != null)
    val compiled = checked.flatMap(r => strings(r.get("compiled")))
    val differences = ok.flatMap(_.get("differences").asArray.values.asScala)
    val products = ok.map(_.get("products").asInt).sum
    val cycles = checked.flatMap(r => Option(r.get("cycles")).map(_.asInt))
    val verdict = checked.map(_.get("verdict").asString).sortBy(Rank.indexOf).lastOption.getOrElse("same")
    def modules(count: JsonObject => Int) = {
      val a = new JsonArray
      checked.foreach(r =>
        a.add(new JsonObject().add("module", s"${r.get("project").asString}/${r.get("config").asString}")
          .add("recompiled", count(r)))
      )
      a
    }
    val clean = new JsonObject().add("step", step).add("index", index).add("mode", "clean").add("products", products)
      .add("changed", new JsonArray).add("changedCount", 0).add("structural", false)
      .add("necessaryCount", if (knowsNecessary) necessary.size else 0).add("necessary", array(necessary.take(50)))
      .add("ok", checked.forall(_.get("cleanOk").asBoolean))
      .add("result", new JsonObject().add("wallMillis", checked.map(_.get("cleanMillis").asLong).sum).add("rounds", 1)
        .add("recompiled", products).add("classes", products)
        .add("modules", modules(r => Option(r.get("products")).fold(0)(_.asInt))))
    val shown = new JsonArray
    differences.take(50).foreach(shown.add)
    val mode = new JsonObject().add("step", step).add("index", index).add("mode", "sbt").add("order", 0)
      .add("verdict", verdict).add("compiledCount", compiled.size).add("compiled", array(compiled.take(50)))
      .add("necessaryNotCompiled", array(if (knowsNecessary) necessary.filterNot(compiled.toSet).take(50) else Nil))
      .add("carried", differences.count(_.asObject.get("carried").asBoolean)).add("differences", shown)
      .add("differenceCount", differences.size).add("ok", checked.forall(_.get("incOk").asBoolean))
      .add("result", new JsonObject().add("wallMillis", incMillis).add("rounds", if (cycles.isEmpty) 1 else cycles.sum)
        .add("recompiled", compiled.size).add("classes", products)
        .add("modules", modules(r => strings(r.get("compiled")).size)))
    Seq(clean, mode)
  }

  private val step = Command.single("incOracleStep") { (state, name) =>
    val home = Verify.home(root(state)).toPath
    Files.createDirectories(home)
    Files.write(home.resolve("current-step"), name.getBytes(UTF_8))
    val extracted = Project.extract(state)
    val started = System.nanoTime
    val (compiledState, _) = extracted.runTask(LocalRootProject / incOracleCompileAll, state)
    val incMillis = (System.nanoTime - started) / 1000000
    val (verified, _) = Project.extract(compiledState).runTask(LocalRootProject / incOracleVerifyAll, compiledState)
    val dir = home.resolve("results").resolve(name).toFile
    val results = Option(dir.listFiles).toList.flatten.filter(_.getName.endsWith(".json")).sortBy(_.getName).map(Verify.read)
    val log = home.resolve("steps.jsonl")
    val index = if (Files.exists(log)) Files.readAllLines(log, UTF_8).asScala.count(_.contains("\"mode\":\"clean\"")) else 0
    val out = rows(name, index, results, incMillis)
    Files.write(log, out.map(_.toString).mkString("", "\n", "\n").getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    val verdict = out(1).get("verdict").asString
    verified.log.info(s"[incReplay] $name: $verdict, ${out(1).get("compiledCount").asInt} sources compiled incrementally in ${incMillis} ms")
    SbtReplay.stepDone(home.getParent, name, Verify.isFinding(verdict))
    try Report.write(home.getParent)
    catch { case e: Exception => verified.log.warn(s"[incReplay] no report: $e") }
    if (Verify.isFinding(verdict)) "incOracleReset" :: verified else verified
  }

  private val reset = Command.command("incOracleReset") { state =>
    val (next, _) = Project.extract(state).runTask(LocalRootProject / incOracleCleanAll, state)
    next.log.info("[incReplay] cleaned every module after a finding, so the next step starts from a clean build")
    next
  }

  private val run = Command.command("incReplayRun") { state =>
    val out = Verify.home(root(state)).toPath.getParent
    SbtReplay.next(out) match {
      case null => state
      case next =>
        if (!next.checkedOut) SbtReplay.checkout(new File(next.repository).toPath, next.commit)
        val reload = if (next.build && !next.checkedOut) List("reload") else Nil
        reload ::: List(s"incOracleStep ${next.name}", "incReplayRun") ::: state
    }
  }

  private val replay = Command.args("incReplay", "[STEPS] [--ref REF] [--out DIR] [--sbt COMMAND]") { (state, args) =>
    val base = root(state)
    val out = new File(base, "target/inc-oracle/replay")
    val code = SbtReplay.run((Seq(base.toString, out.toString) ++ args).toArray)
    if (code == 0) state else state.fail
  }

  def commands: Seq[Command] = Seq(step, reset, run, replay)
}
