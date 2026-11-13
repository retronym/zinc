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

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite
import sbt.io.IO

import IncHistory.{ Mode, Options, Session }

class IncHistorySuite extends AnyFunSuite:

  private def write(root: Path, file: String, content: String): Unit =
    val f = root.resolve(file)
    Files.createDirectories(f.getParent)
    Files.writeString(f, content)

  private def field(line: String, name: String): String =
    s""""$name":("[^"]*"|[^,}\\]]*)""".r.findFirstMatchIn(line).fold("")(_.group(1).replace(
      "\"",
      ""
    ))

  /** A session on a build of `a` and `b`, which depends on it, and what it logged so far. */
  private def history(test: (Path, Path, Session, () => Seq[String]) => Unit): Unit =
    val root = Files.createTempDirectory("inchistory")
    try
      val build = root.resolve("build")
      val work = root.resolve("work")
      write(
        build,
        "build.json",
        """{"projects":[{"name":"a","dependsOn":[],"scalaVersion":"2.13.x"},
          |{"name":"b","dependsOn":["a"],"scalaVersion":"2.13.x"}]}""".stripMargin
      )
      for m <- Seq("a", "b") do write(build, s"$m/incOptions.properties", "pipelining = false\n")
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = 1 }\n")
      write(build, "a/src/main/scala/Other.scala", "class Other\n")
      write(build, "b/src/main/scala/B.scala", "class B { def g = new A().f }\n")
      val session =
        new Session(Options(build = build, work = work, modes = Vector(Mode("inc", Map.empty))))
      def lines() = Files.readAllLines(work.resolve("steps.jsonl")).asScala.toSeq
      try test(build, work, session, lines)
      finally session.close()
    finally IO.delete(root.toFile)
  end history

  private def of(lines: Seq[String], step: String, mode: String): String =
    lines.filter(l =>
      field(l, "step") == step && field(l, "mode") == mode && !l.contains("\"reset\"")
    ).last

  test("a body edit recompiles its source and is the same as clean"):
    history { (build, _, session, lines) =>
      assert(session.start())
      assert(field(of(lines(), "base", "inc"), "verdict") == "same")
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = 2 }\n")
      assert(session.step("s1"))
      val inc = of(lines(), "s1", "inc")
      assert(field(inc, "verdict") == "same")
      assert(field(inc, "compiledCount") == "1")
      val clean = of(lines(), "s1", "clean")
      assert(field(clean, "necessaryCount") == "1")
      assert(clean.contains("a/src/main/scala/A.scala"))
    }

  test("an API change reaches the dependent module, and the necessary set is what clean changed"):
    history { (build, _, session, lines) =>
      session.start()
      write(build, "a/src/main/scala/A.scala", "class A { def f: Long = 1L }\n")
      session.step("s1")
      val inc = of(lines(), "s1", "inc")
      assert(field(inc, "verdict") == "same")
      assert(field(inc, "compiledCount") == "2")
      assert(field(of(lines(), "s1", "clean"), "necessaryCount") == "2")
    }

  test("a step that does not compile fails on both sides, and the next one recovers"):
    history { (build, _, session, lines) =>
      session.start()
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = \"\" }\n")
      assert(!session.step("broken"))
      assert(field(of(lines(), "broken", "inc"), "verdict") == "same-fail")
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = 3 }\n")
      assert(session.step("fixed"))
      assert(field(of(lines(), "fixed", "inc"), "verdict") == "same")
    }

  test("a classfile left over in the incremental output is stale, kept as evidence, and reset"):
    history { (build, work, session, lines) =>
      session.start()
      val classes = work.resolve("inc/a/target/classes")
      Files.copy(classes.resolve("Other.class"), classes.resolve("Gone.class"))
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = 4 }\n")
      session.step("s1")
      val inc = of(lines(), "s1", "inc")
      assert(field(inc, "verdict") == "stale")
      assert(inc.contains(""""class":"a/Gone.class","kind":"extra""""))
      assert(Files.exists(work.resolve("diffs/s1/inc/inc/a/Gone.class")))
      assert(lines().exists(l => field(l, "step") == "s1" && field(l, "reset") == "same"))
      write(build, "a/src/main/scala/A.scala", "class A { def f: Int = 5 }\n")
      session.step("s2")
      assert(field(of(lines(), "s2", "inc"), "verdict") == "same")
    }

  test("a known undercompilation, the pending scripted test import-package, is a missed error"):
    history { (build, _, session, lines) =>
      write(build, "a/src/main/scala/P.scala", "package a.b\n\nclass P\n")
      write(build, "a/src/main/scala/Q.scala", "import a.b\n\nclass Q\n")
      session.start()
      write(build, "a/src/main/scala/P.scala", "package a\n\nclass P\n")
      assert(!session.step("s1"))
      val verdict = field(of(lines(), "s1", "inc"), "verdict")
      if verdict == "same-fail" then
        cancel("this Zinc recompiles the importer of a package that is gone")
      assert(verdict == "missed-error")
      assert(lines().exists(l => field(l, "step") == "s1" && field(l, "reset") == "same-fail"))
    }

  test("a class that scalac compiles differently on its own is a compiler difference, confirmed"):
    history { (build, _, session, lines) =>
      write(
        build,
        "a/src/main/scala/Semi.scala",
        """trait Semi[@specialized(Int, Long) A] {
          |  def combine(x: A, y: A): A
          |  def combineN(a: A, n: Int): A = if (n <= 1) a else combine(a, combineN(a, n - 1))
          |}
          |trait Lattice[@specialized(Int, Long) A] extends Semi[A]
          |""".stripMargin
      )
      val instance =
        """class SetLattice[A] extends Lattice[Set[A]] {
          |  def combine(x: Set[A], y: Set[A]): Set[A] = x | y
          |  def marker = %d
          |}
          |""".stripMargin
      write(build, "a/src/main/scala/SetLattice.scala", instance.format(1))
      session.start()
      write(build, "a/src/main/scala/SetLattice.scala", instance.format(2))
      session.step("s1")
      val inc = of(lines(), "s1", "inc")
      if field(inc, "verdict") == "same" then
        cancel("this compiler gives SetLattice the same members on its own")
      assert(field(inc, "verdict") == "compiler")
      assert(inc.contains(""""class":"a/SetLattice.class","kind":"compiler""""))
      assert(inc.contains(""""alone":"batch""""))
    }

  test("a module added to the build is compiled by every copy"):
    history { (build, _, session, lines) =>
      session.start()
      write(
        build,
        "build.json",
        """{"projects":[{"name":"a","dependsOn":[],"scalaVersion":"2.13.x"},
          |{"name":"b","dependsOn":["a"],"scalaVersion":"2.13.x"},
          |{"name":"c","dependsOn":["a"],"scalaVersion":"2.13.x"}]}""".stripMargin
      )
      write(build, "c/incOptions.properties", "pipelining = false\n")
      write(build, "c/src/main/scala/C.scala", "class C extends A\n")
      assert(session.step("s1"))
      val inc = of(lines(), "s1", "inc")
      assert(field(inc, "verdict") == "same")
      assert(of(lines(), "s1", "clean").contains("c/src/main/scala/C.scala"))
    }

  test("sync writes only what changed and removes what is gone"):
    val root = Files.createTempDirectory("inchistory-sync")
    try
      val (from, to) = (root.resolve("from"), root.resolve("to"))
      write(from, "m/src/main/scala/A.scala", "class A\n")
      write(from, "m/incOptions.properties", "scalac.options = -deprecation\n")
      assert(IncHistory.sync(
        from,
        to,
        Map("scalac.options" -> "-feature", "apiDebug" -> "false")
      ).size == 2)
      val props = Files.readString(to.resolve("m/incOptions.properties"))
      assert(props.contains("scalac.options=-deprecation -feature") &&
        props.contains("apiDebug=false"))
      write(to, "m/target/classes/A.class", "x")
      assert(IncHistory.sync(
        from,
        to,
        Map("scalac.options" -> "-feature", "apiDebug" -> "false")
      ).isEmpty)
      Files.delete(from.resolve("m/src/main/scala/A.scala"))
      write(from, "m/src/main/scala/B.scala", "class B\n")
      val changed = IncHistory.sync(from, to, Map.empty).toSet
      assert(changed ==
        Set("m/src/main/scala/A.scala", "m/src/main/scala/B.scala", "m/incOptions.properties"))
      assert(Files.exists(to.resolve("m/target/classes/A.class")))
      Files.delete(from.resolve("m/src/main/scala/B.scala"))
      write(from, "m/src/main/scala/b.scala", "class B\n")
      IncHistory.sync(from, to, Map.empty)
      val names = Files.list(
        to.resolve("m/src/main/scala")
      ).iterator.asScala.map(_.getFileName.toString).toSeq
      assert(names == Seq("b.scala"))
    finally IO.delete(root.toFile)
    end try
end IncHistorySuite
