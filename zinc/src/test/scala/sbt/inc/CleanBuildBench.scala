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

import java.net.URLClassLoader
import java.nio.file.{ Files, Path, Paths }
import java.util.Optional
import sbt.internal.inc.*
import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.io.IO
import sbt.util.{ Level, Logger }
import scala.jdk.CollectionConverters.*
import xsbti.compile.*

/**
 * Times a from-scratch compile of a source tree in the default mode, including the write of the
 * analysis, against the clean build mode (`IncOptions.enabled = false`). The modes alternate in
 * one JVM so both see the same warm-up.
 *
 * Arguments: source directory, file listing the classpath one entry per line, work directory,
 * iterations, the modes to run (`default,clean`, or one of them to time a cold JVM), and optionally
 * extra scalac options.
 */
object CleanBuildBench extends BridgeProviderTestkit:
  override def logLevel: Level.Value = Level.Warn

  def main(args: Array[String]): Unit =
    val srcDir = Paths.get(args(0))
    val classpath = Files.readAllLines(Paths.get(args(1))).asScala.toVector
      .map(_.trim)
      .filter(l => l.startsWith("/") && l.endsWith(".jar") && !l.contains("scala-library"))
      .map(Paths.get(_))
    val work = Paths.get(args(2)).toAbsolutePath
    val iterations = args(3).toInt
    val selected = args(4).split(',').toSet
    val scalacOptions = args.drop(5).toVector
    val sv = sys.props.getOrElse("bench.scalaVersion", "2.13.16")

    val sources = Files.walk(srcDir).iterator().asScala.toVector
      .filter(p => p.toString.endsWith(".scala") || p.toString.endsWith(".java"))
      .sortBy(_.toString)
    val si = scalaInstance(sv, work, Logger.Null)
    val bridge = getCompilerBridge(work, Logger.Null, sv)
    val converter = PlainVirtualFileConverter.converter
    val output = work.resolve("classes")
    val analysisFile = work.resolve("inc_compile.zip")
    val zinc = new IncrementalCompilerImpl
    val loaderCache = new ClassLoaderCache(new URLClassLoader(Array()))
    val scalac = new AnalyzingCompiler(
      si,
      ZincUtil.constantBridgeProvider(si, bridge),
      ClasspathOptionsUtil.boot,
      _ => (),
      Some(loaderCache)
    )
    val perEntry = new PerClasspathEntryLookup:
      def analysis(cp: xsbti.VirtualFile) = Optional.empty[CompileAnalysis]
      def definesClass(cp: xsbti.VirtualFile) = Locate.definesClass(cp)

    def inputs(enabled: Boolean): Inputs =
      val setup = zinc.setup(
        perEntry,
        false,
        work.resolve("inc_compile"),
        CompilerCache.fresh,
        IncOptions.of().withEnabled(enabled),
        new ManagedLoggedReporter(100, log, VirtualFileUtil.sourcePositionMapper(converter)),
        None,
        None,
        Array.empty
      )
      zinc.inputs(
        ((output +: si.allJars.map(_.toPath).toVector) ++ classpath).map(converter.toVirtualFile).toArray,
        sources.map(converter.toVirtualFile).toArray,
        output,
        None,
        ("-nowarn" +: scalacOptions).toArray,
        Array("-nowarn"),
        100,
        Array.empty[java.util.function.Function[xsbti.Position, Optional[xsbti.Position]]],
        CompileOrder.Mixed,
        zinc.compilers(si, ClasspathOptionsUtil.boot, None, scalac),
        setup,
        zinc.emptyPreviousResult,
        Optional.empty(),
        converter,
        Stamps.timeWrapBinaryStamps(converter)
      )

    val modes = Vector("default" -> true, "clean" -> false).collect {
      case (mode, enabled) if selected(mode) => mode -> inputs(enabled)
    }
    println(s"${sources.size} sources, scala $sv, ${classpath.size} classpath jars")
    println("iter mode    compile_ms store_ms total_ms analysis_kb")
    for
      i <- 1 to iterations
      (mode, in) <- if i % 2 == 0 then modes.reverse else modes
    do
      IO.delete(output.toFile)
      Files.deleteIfExists(analysisFile)
      System.gc()
      val t0 = System.nanoTime()
      val result = zinc.compile(in, log)
      val t1 = System.nanoTime()
      if mode == "default" then
        MixedAnalyzingCompiler
          .staticCachedStore(analysisFile, useTextAnalysis = false, cacheLast = false)
          .set(AnalysisContents.create(result.analysis, result.setup))
      val t2 = System.nanoTime()
      val kb = if Files.exists(analysisFile) then Files.size(analysisFile) / 1024 else 0L
      println(
        f"$i%4d $mode%-7s ${(t1 - t0) / 1e6}%10.0f ${(t2 - t1) / 1e6}%8.0f ${(t2 - t0) / 1e6}%8.0f $kb%11d"
      )
    loaderCache.close()
end CleanBuildBench
