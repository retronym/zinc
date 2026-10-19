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

import xsbti.VirtualFileRef
import xsbti.api.*
import MerkleHashesStability.{ analyzed, parent }

class MerkleHashesInheritanceSpec extends UnitSpec:
  private def analysis(external: String*) = Analysis.empty.copy(
    apis = APIs(
      Map(
        "p.C" -> analyzed(
          "p.C",
          DefinitionType.ClassDef,
          Array(parent("p.Inst.B"), parent("p.Inst.A")),
          "c"
        )
      ),
      Map.empty
    ),
    relations = Relations.empty.addExternalDeps(
      VirtualFileRef.of("C.scala"),
      external.map(ExternalDependency.of("p.C", _, null, DependencyContext.DependencyByInheritance))
    )
  )
  private def b(member: String) =
    analyzed("p.Outer.B", DefinitionType.ClassDef, Array(parent("p.Outer.A")), member)
  private val outerA = analyzed("p.Outer.A", DefinitionType.ClassDef, Array.empty, "a")
  private def lookup(b: AnalyzedClass): String => Option[AnalyzedClass] =
    case "p.Outer.B" => Some(b)
    case "p.Outer.A" => Some(outerA)
    case _           => None
  private def hashes(c: AnalyzedClass) =
    (c.apiHash, c.nameHashes.map(h => (h.name, h.scope, h.hash)).toSet)

  "composed" should "take upstream ancestors reached through an object from the external inheritance relation" in:
    val c = MerkleHashes.composed(analysis("p.Outer$B"), "p.C", lookup(b("b"))).get
    MerkleHashes.linearization(c) shouldBe
      Vector("p.Inst.B", "p.Inst.A", "p.Outer.B", "p.Outer.A")
    c.nameHashes.map(_.name).toSet should contain allOf ("b", "a")
    hashes(MerkleHashes.composed(analysis("p.Outer$B"), "p.C", lookup(b("b2"))).get) should not be
      hashes(c)

  it should "compose the same whichever order the relation lists the ancestors in" in:
    hashes(
      MerkleHashes.composed(analysis("p.Outer$B", "p.Outer$A"), "p.C", lookup(b("b"))).get
    ) shouldBe
      hashes(MerkleHashes.composed(analysis("p.Outer$A", "p.Outer$B"), "p.C", lookup(b("b"))).get)

  it should "leave a class whose stored linearization names its ancestors as it was" in:
    val plain = Analysis.empty.copy(apis =
      APIs(
        Map(
          "p.C" -> analyzed("p.C", DefinitionType.ClassDef, Array(parent("p.Outer.B")), "c")
        ),
        Map.empty
      )
    )
    val related = plain.copy(relations =
      Relations.empty.addExternalDeps(
        VirtualFileRef.of("C.scala"),
        Seq(
          ExternalDependency.of("p.C", "p.Outer$B", null, DependencyContext.DependencyByInheritance)
        )
      )
    )
    val l = lookup(analyzed("p.Outer.B", DefinitionType.ClassDef, Array.empty, "b"))
    hashes(MerkleHashes.composed(related, "p.C", l).get) shouldBe
      hashes(MerkleHashes.composed(plain, "p.C", l).get)
end MerkleHashesInheritanceSpec
