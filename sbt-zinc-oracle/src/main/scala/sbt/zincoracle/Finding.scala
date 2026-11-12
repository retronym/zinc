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

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path, StandardCopyOption }

import scala.collection.JavaConverters._

import com.eclipsesource.json.JsonObject
import sbt.internal.inc.oracle.ClassfileNorm

/**
 * What a user can attach to a bug report after a finding: a summary that names classes, verdicts
 * and versions but holds no source, and beside it the classfiles of both sides and their
 * difference, which do come from the user's build and are theirs to share or not.
 */
object Finding {
  private val Evidence = 20

  def write(dir: Path, result: JsonObject, incremental: Path, clean: Path): Unit = {
    Files.createDirectories(dir)
    val verdict = result.get("verdict").asString
    val lines = Seq.newBuilder[String]
    lines += s"# Incremental compilation differs from a clean build: $verdict"
    lines += ""
    lines += s"- Module: `${result.get("project").asString}`, configuration `${result.get("config").asString}`"
    lines += s"- sbt ${sys.props.getOrElse("sbt.version", "unknown")}, Java ${sys.props("java.version")}"
    lines += s"- Sources compiled incrementally: ${result.get("compiled").asArray.size}"
    lines += ""
    verdict match {
      case "missed-error" =>
        lines += "The incremental compile succeeded. A clean compile of the same sources fails, so a source that needed recompiling was not recompiled."
        lines += ""
        lines += s"Clean build's errors (first five): ${Option(result.get("cleanErrors")).fold("")(_.asString)}"
      case "spurious-error" =>
        lines += "The incremental compile failed. A clean compile of the same sources succeeds."
      case _ =>
        lines += "Classes in the incremental output that the last compile did not write, and that differ from a clean build's in a way no normalisation explains, or that the clean build does not have, or lacks:"
        lines += ""
        lines += "| Class | Kind | Verdict | Signature only |"
        lines += "|---|---|---|---|"
        val rows = result.get("differences").asArray.values.asScala.map(_.asObject).filter(_.get("level") == null)
        rows.foreach { r =>
          lines += s"| `${r.get("class").asString}` | ${r.get("kind").asString} | ${r.get("verdict").asString} | ${if (r.get("pickleOnly").asBoolean) "yes" else "no"} |"
        }
        val prefix = result.get("project").asString + "/"
        rows.take(Evidence).foreach { r =>
          val path = r.get("class").asString.stripPrefix(prefix)
          val (a, b) = (incremental.resolve(path), clean.resolve(path))
          def keep(from: Path, side: String): Unit =
            if (Files.isRegularFile(from)) {
              val to = dir.resolve(side).resolve(path)
              Files.createDirectories(to.getParent)
              Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
          keep(a, "incremental")
          keep(b, "clean")
          if (path.endsWith(".class") && Files.isRegularFile(a) && Files.isRegularFile(b)) {
            val diff = ClassfileNorm.diff(Files.readAllBytes(a), Files.readAllBytes(b))
            Files.write(dir.resolve(path.replace('/', '.') + ".diff"), diff)
          }
        }
    }
    lines += ""
    lines += "This file names classes but contains no source. The `incremental/` and `clean/` directories and the `.diff` files beside it hold bytecode from the build."
    Files.write(dir.resolve("summary.md"), lines.result().mkString("", "\n", "\n").getBytes(UTF_8))
  }
}
