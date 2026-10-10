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
 * Tests the simple names a Java class looks up, and the edges from its static imports, recovered
 * from javac's attributed AST: a classfile keeps neither.
 */
class JavaUsedNamesSpec extends UnitSpec:

  "Local javac name analysis" should "record the simple names a class looks up" in facts {
    (_, names) =>
      val used = names.getOrElse("p.b.Client", Set.empty)
      assert(used.contains("Foo"))
      assert(used.contains("Bar"))
  }

  it should "key the names of a member class by its binary name" in facts { (_, names) =>
    assert(names.getOrElse("p.b.Client$Inner", Set.empty).contains("Baz"))
    assert(!names.getOrElse("p.b.Client", Set.empty).contains("Baz"))
  }

  it should "record an edge to the class of a single-static import" in facts { (deps, _) =>
    assert(deps.getOrElse("p.b.Client", Set.empty).contains("p.X"))
  }

  it should "record an edge to the class of a static on-demand import" in facts { (deps, _) =>
    assert(deps.getOrElse("p.b.Client", Set.empty).contains("p.W"))
  }

  it should "record no edge for a type import" in facts { (deps, _) =>
    assert(!deps.getOrElse("p.b.Client", Set.empty).exists(_.startsWith("p.q")))
  }

  private val fixtures: Seq[(String, String)] = Seq(
    "p/X.java" -> "package p; public class X { public static void Foo() {} }",
    "p/W.java" -> "package p; public class W { public static class Bar {} }",
    "p/q/Baz.java" -> "package p.q; public class Baz {}",
    "p/b/Foo.java" -> "package p.b; public class Foo {}",
    "p/b/Client.java" ->
      """package p.b;
        |import static p.X.Foo;
        |import static p.W.*;
        |import p.q.*;
        |public class Client {
        |  static Object foo() { return Foo.class; }
        |  static Object bar() { return new Bar(); }
        |  static class Inner { Object baz() { return Baz.class; } }
        |}
        |""".stripMargin,
  )

  private def facts(
      check: (Map[String, Set[String]], Map[String, Set[String]]) => Unit
  ): Unit =
    IO.withTemporaryDirectory { tmp =>
      val (deps, names) = compileAndCollect(tmp.toPath)
      check(deps, names)
    }

  private def compileAndCollect(tmp: Path): (Map[String, Set[String]], Map[String, Set[String]]) =
    val compiler = new LocalJavaCompiler(
      Option(javax.tools.ToolProvider.getSystemJavaCompiler)
        .getOrElse(sys.error("This test requires a JDK, not a JRE."))
    )
    val sources: Seq[Path] = fixtures.map { (name, content) =>
      val f = tmp.resolve("src").resolve(name)
      IO.write(f.toFile, content)
      f
    }
    val outDir = tmp.resolve("classes")
    IO.createDirectory(outDir.toFile)
    val log = LoggerContext.globalContext.logger("JavaUsedNamesSpec", None, None)
    val reporter = new ManagedLoggedReporter(10, log)
    val options =
      if scala.util.Properties.isJavaAtLeast("21") then Array("-proc:none") else Array.empty[String]
    val (success, deps, names) = compiler.runWithTreeFacts(
      sources.map(PlainVirtualFile(_)).toArray,
      options,
      CompileOutput(outDir),
      IncToolOptionsUtil.defaultIncToolOptions(),
      reporter,
      log
    )
    assert(success, "javac compilation of the fixtures failed")
    (deps, names)
  end compileAndCollect
end JavaUsedNamesSpec
