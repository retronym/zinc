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

package sbt.inc

import hedgehog.{ sbt as _, * }
import hedgehog.runner.*
import java.nio.file.Path
import sbt.internal.inc.Analysis
import sbt.io.IO
import xsbti.compile.CompileResult

object CleanBuildSpec extends Properties with BaseCompilerSpec:
  private val sources = Seq("Good.scala", "Foo.scala", "NestedJavaClasses.java")

  override def tests: List[Test] =
    for
      sv <- List(scalaVersion, "3.9.0")
      pipelining <- List(false, true)
    yield example(
      s"clean build writes classes but no analysis ($sv, pipelining = $pipelining)",
      cleanBuild(sv, pipelining)
    )

  private def cleanBuild(sv: String, pipelining: Boolean): Result =
    IO.withTemporaryDirectory { tempDir =>
      val setup = ProjectSetup.simple(tempDir.toPath, sources)
      val classes = setup.classesDir
      val cleanOptions = incOptions.withPipelining(pipelining).withEnabled(false)
      val clean = withCompiler(setup.createCompiler(sv, cleanOptions)) { compiler =>
        val result = compileAll(compiler, pipelining)
        Result.all(
          List(
            assertEmpty(result),
            assertExists(classes.resolve("pkg/Good.class")),
            assertExists(classes.resolve("pkg/Foo$.class")),
            assertExists(classes.resolve("NestedJavaClasses.class")),
            assertNotExists(setup.analysisPath),
            assertNotExists(setup.earlyAnalysisPath),
            compiler.earlyOutputsWritten ==== 0,
          )
        )
      }
      val next = withCompiler(setup.createCompiler(sv, incOptions.withPipelining(false))) {
        compiler =>
          val analysis = compiler.doCompileWithStore().analysis.asInstanceOf[Analysis]
          analysis.relations.allSources.map(s => Path.of(s.id).getFileName.toString) ====
            sources.toSet
      }
      clean.and(next)
    }

  private def compileAll(compiler: CompilerSetup, pipelining: Boolean): CompileResult =
    val result = compiler.doCompile()
    if pipelining then compiler.doCompileAllJava() else result

  private def assertEmpty(result: CompileResult): Result =
    result.analysis match
      case a: Analysis =>
        Result.all(
          List(
            a.relations.allSources ==== Set.empty,
            a.relations.allProducts ==== Set.empty,
            a.apis.internal ==== Map.empty,
          )
        )
      case other => Result.failure.log(s"unexpected analysis: $other")
end CleanBuildSpec
