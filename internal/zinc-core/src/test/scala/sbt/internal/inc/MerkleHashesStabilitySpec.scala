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

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import xsbti.UseScope
import xsbti.api.*

/**
 * Composed hashes are stored in one JVM and compared with hashes composed in the next, so they
 * must not depend on identity hash codes (a Java enum's `hashCode`, for one). This composes the
 * same analysis here and in a fresh JVM and compares the results.
 */
class MerkleHashesStabilitySpec extends UnitSpec:
  it should "compose the same hashes in another JVM" in {
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val cp =
      (Iterator
        .iterate(getClass.getClassLoader)(_.getParent)
        .takeWhile(_ != null)
        .collect { case l: URLClassLoader => l.getURLs.map(u => Paths.get(u.toURI)) }
        .flatten ++ Iterator(System.getProperty("java.class.path")))
        .mkString(File.pathSeparator)
    val process = new ProcessBuilder(java, "-cp", cp, classOf[MerkleHashesStability].getName)
      .redirectErrorStream(true)
      .start()
    val out = new String(process.getInputStream.readAllBytes(), UTF_8).trim
    (process.waitFor(), out) shouldBe ((0, MerkleHashesStability.render()))
  }

class MerkleHashesStability

object MerkleHashesStability:
  def main(args: Array[String]): Unit = println(render())

  def render(): String =
    val analysis = Analysis.empty.copy(apis =
      APIs(
        Map(
          "p.A" -> analyzed("p.A", DefinitionType.ClassDef, Array("p.B"), "a"),
          "p.B" -> analyzed("p.B", DefinitionType.Trait, Array.empty, "b"),
          "p.O" -> analyzed("p.O", DefinitionType.Module, Array.empty, "o"),
        ),
        Map.empty
      )
    )
    List("p.A", "p.B", "p.O")
      .map { name =>
        val c = MerkleHashes.composed(analysis, name).get
        val names = c.nameHashes.map(h => s"${h.name}=${h.hash}").sorted.mkString(",")
        s"$name ${c.apiHash} $names"
      }
      .mkString("\n")

  private def lzy[T <: AnyRef](t: T): Lazy[T] = SafeLazyProxy.strict(t)

  def parent(name: String): Type =
    val segments = name.split('.')
    val path = Path.of(segments.init.map(s => Id.of(s): PathComponent) :+ This.of())
    Projection.of(Singleton.of(path), segments.last)

  private def side(name: String, defType: DefinitionType, parents: Array[Type]): ClassLike =
    ClassLike.of(
      name,
      Public.of(),
      new Modifiers(false, false, false, false, false, false, false, false),
      Array.empty,
      defType,
      lzy(EmptyType.of()),
      lzy(Structure.of(lzy(parents), lzy(Array.empty), lzy(Array.empty))),
      Array.empty,
      Array.empty,
      true,
      Array.empty
    )

  /** A class whose stored ancestors, by binary name, are `ancestors`. */
  def analyzed(
      name: String,
      defType: DefinitionType,
      ancestors: Array[String],
      member: String
  ): AnalyzedClass =
    val isModule = defType == DefinitionType.Module
    val parents = ancestors.map(a => parent(a.replace('$', '.')))
    val classSide = side(name, if isModule then DefinitionType.ClassDef else defType, parents)
    val objectSide = side(name, DefinitionType.Module, Array.empty)
    val nameHashes = Array(
      NameHash.of(member, UseScope.Default, member.hashCode),
      NameHash.of("wait", UseScope.Default, 42)
    )
    AnalyzedClass.of(
      0L,
      name,
      lzy(Companions.of(classSide, objectSide)),
      1,
      nameHashes,
      false,
      2,
      "",
      0L,
      0L,
      ancestors,
      Array.empty[String]
    )
  end analyzed
end MerkleHashesStability
