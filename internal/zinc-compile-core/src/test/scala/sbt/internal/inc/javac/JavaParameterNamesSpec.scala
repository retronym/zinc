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

package sbt
package internal
package inc
package javac

import java.nio.file.Path

import xsbti.compile.IncToolOptionsUtil
import sbt.io.IO
import sbt.util.LoggerContext

/**
 * Tests that local javac reports the source names of method and constructor parameters, which
 * class files only carry under `javac -parameters`.
 */
class JavaParameterNamesSpec extends UnitSpec:

  "Local javac parameter-name analysis" should "record method parameter names" in names { n =>
    assert(n("p.A")("f(int,java.lang.String)") === Seq("width", "label"))
  }

  it should "record constructor parameter names" in names { n =>
    assert(n("p.A")("<init>(long)") === Seq("seed"))
  }

  it should "erase generic and array parameter types" in names { n =>
    assert(n("p.A")("g(java.lang.Object,java.lang.String[])") === Seq("t", "rest"))
  }

  it should "use binary class names and canonical parameter type names for nested classes" in names {
    n => assert(n("p.A$Inner")("h(p.A.Inner)") === Seq("other"))
  }

  private val fixtures: Seq[(String, String)] = Seq(
    "p/A.java" ->
      """package p;
        |public class A {
        |  public A(long seed) {}
        |  public static String f(int width, String label) { return label; }
        |  public <T> void g(T t, String... rest) {}
        |  public static class Inner { public void h(Inner other) {} }
        |}
        |""".stripMargin
  )

  private def names(check: Map[String, Map[String, Seq[String]]] => Unit): Unit =
    IO.withTemporaryDirectory(tmp => check(compileAndCollect(tmp.toPath)))

  private def compileAndCollect(tmp: Path): Map[String, Map[String, Seq[String]]] =
    val compiler = new LocalJavaCompiler(
      Option(javax.tools.ToolProvider.getSystemJavaCompiler)
        .getOrElse(sys.error("This test requires a JDK, not a JRE."))
    )
    val srcDir = tmp.resolve("src")
    val sources: Seq[Path] = fixtures.map {
      case (name, content) =>
        val f = srcDir.resolve(name)
        IO.write(f.toFile, content)
        f
    }
    val outDir = tmp.resolve("classes")
    IO.createDirectory(outDir.toFile)

    val log = LoggerContext.globalContext.logger("JavaParameterNamesSpec", None, None)
    val reporter = new ManagedLoggedReporter(10, log)
    val options =
      if scala.util.Properties.isJavaAtLeast("21") then Array("-proc:none") else Array.empty[String]
    val facts = compiler.runWithSourceFacts(
      sources.map(PlainVirtualFile(_)).toArray,
      options,
      CompileOutput(outDir),
      IncToolOptionsUtil.defaultIncToolOptions(),
      reporter,
      log
    )
    assert(facts.success, "javac compilation of the fixtures failed")
    facts.parameterNames
  end compileAndCollect
end JavaParameterNamesSpec
