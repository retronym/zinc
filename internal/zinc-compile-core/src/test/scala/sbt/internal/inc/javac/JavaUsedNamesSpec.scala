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

import hedgehog.{ sbt as _, * }
import hedgehog.runner.*
import java.nio.file.Path

import xsbti.compile.IncToolOptionsUtil
import sbt.io.IO
import sbt.util.LoggerContext

/**
 * Tests the simple names a Java class looks up, and the edges from its static imports, recovered
 * from javac's attributed AST: a classfile keeps neither.
 */
object JavaUsedNamesSpec extends Properties:
  override def tests: List[Test] = List(
    example("record the simple names a class looks up", namesOf("p.b.Client", Set("Foo", "Bar"))),
    example(
      "key the names of a member class by its binary name",
      Result.all(
        List(
          namesOf("p.b.Client$Inner", Set("Baz")),
          Result.assert(!names.getOrElse("p.b.Client", Set.empty).contains("Baz")),
        )
      )
    ),
    example("record an edge to the class of a single-static import", edge("p.X")),
    example("record an edge to the class of a static on-demand import", edge("p.W")),
    example("record an edge to the class of a single-type import", edge("p.q.Qux")),
    example(
      "record no edge for an on-demand package import",
      Result.assert(!deps.getOrElse("p.b.Client", Set.empty).contains("p.q.Baz"))
    ),
  )

  private def namesOf(cls: String, expected: Set[String]): Result =
    val used = names.getOrElse(cls, Set.empty)
    Result.assert(expected.subsetOf(used)).log(s"$cls used names: $used")

  private def edge(on: String): Result =
    val from = deps.getOrElse("p.b.Client", Set.empty)
    Result.assert(from.contains(on)).log(s"p.b.Client edges: $from")

  private lazy val (deps, names) = IO.withTemporaryDirectory(tmp => compileAndCollect(tmp.toPath))

  private val fixtures: Seq[(String, String)] = Seq(
    "p/X.java" -> "package p; public class X { public static void Foo() {} }",
    "p/W.java" -> "package p; public class W { public static class Bar {} }",
    "p/q/Baz.java" -> "package p.q; public class Baz {}",
    "p/q/Qux.java" -> "package p.q; public class Qux {}",
    "p/b/Foo.java" -> "package p.b; public class Foo {}",
    "p/b/Client.java" ->
      """package p.b;
        |import static p.X.Foo;
        |import static p.W.*;
        |import p.q.*;
        |import p.q.Qux;
        |public class Client {
        |  static Object foo() { return Foo.class; }
        |  static Object bar() { return new Bar(); }
        |  static class Inner { Object baz() { return Baz.class; } }
        |}
        |""".stripMargin,
  )

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
    if !success then sys.error("javac compilation of the fixtures failed")
    (deps, names)
  end compileAndCollect
end JavaUsedNamesSpec
