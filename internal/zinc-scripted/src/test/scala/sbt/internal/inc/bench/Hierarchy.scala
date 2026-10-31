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

import java.nio.file.Paths

import scala.collection.mutable

import xsbti.api.DefinitionType

/**
 * Lists a module's classes from its stored analysis, as tab-separated lines of class name, kind
 * (`trait`, `abstract`, `class`, `object`), the number of transitive descendants within the
 * module, the number of direct ones, the number of classes that refer to it by member reference,
 * and the source file relative to the build, which is how
 * `bin/catalyst-edits.py` picks the targets of its edits.
 *
 * {{{
 * sbt "zincScripted/Test/runMain sbt.internal.inc.bench.Hierarchy \
 *   /tmp/bench/spark/sql/catalyst/target/inc_compile.zip /tmp/hierarchy.tsv"
 * }}}
 */
object Hierarchy {
  def main(args: Array[String]): Unit = {
    val Array(analysisFile, out) = args
    val analysis = FileAnalysisStore
      .binary(Paths.get(analysisFile).toFile)
      .get
      .get
      .getAnalysis
      .asInstanceOf[Analysis]
    val parents = analysis.relations.inheritance.internal
    val direct = mutable.HashMap.empty[String, Set[String]].withDefaultValue(Set.empty)
    for ((sub, sup) <- parents.all if sub != sup) direct(sup) += sub
    val memo = mutable.HashMap.empty[String, Set[String]]
    def descendants(c: String): Set[String] = memo.getOrElse(
      c, {
        memo(c) = Set.empty
        val ds = direct(c).flatMap(d => descendants(d) + d)
        memo(c) = ds
        ds
      }
    )
    val lines =
      for {
        (name, ac) <- analysis.apis.internal.toSeq.sortBy(_._1)
        src <- analysis.relations.definesClass(name).headOption.toSeq
      } yield {
        val cls = ac.api.classApi
        val kind = cls.definitionType match {
          case DefinitionType.Trait                                => "trait"
          case DefinitionType.Module                               => "object"
          case DefinitionType.PackageModule                        => "object"
          case DefinitionType.ClassDef if cls.modifiers.isAbstract => "abstract"
          case DefinitionType.ClassDef                             => "class"
        }
        val file = src.id.stripPrefix("${BASE}/")
        val clients = analysis.relations.memberRef.internal.reverse(name).size
        s"$name\t$kind\t${descendants(name).size}\t${direct(name).size}\t$clients\t$file"
      }
    Compat.writeString(Paths.get(out), lines.mkString("", "\n", "\n"))
  }
}
