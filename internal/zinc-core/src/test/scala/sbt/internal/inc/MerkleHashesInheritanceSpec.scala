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
import MerkleHashesStability.analyzed

/**
 * `class C extends Inst.B` with `object Inst extends Outer` and `B` a member class of `Outer`,
 * upstream: the bridge reports C's ancestors as `p.Outer$B` and `p.Outer$A`, which composition
 * looks up upstream by those names.
 */
class MerkleHashesInheritanceSpec extends UnitSpec:
  private def analysis = Analysis.empty.copy(
    apis = APIs(
      Map(
        "p.C" -> analyzed("p.C", DefinitionType.ClassDef, Array("p.Outer$B", "p.Outer$A"), "c")
      ),
      Map.empty
    )
  )
  private def b(member: String) =
    analyzed("p.Outer.B", DefinitionType.ClassDef, Array("p.Outer$A"), member)
  private val outerA = analyzed("p.Outer.A", DefinitionType.ClassDef, Array.empty, "a")
  private def lookup(b: AnalyzedClass): BinaryName => Option[AnalyzedClass] =
    _.value match
      case "p.Outer$B" => Some(b)
      case "p.Outer$A" => Some(outerA)
      case _           => None
  private def hashes(c: AnalyzedClass) =
    (c.apiHash, c.nameHashes.map(h => (h.name, h.scope, h.hash)).toSet)

  "composed" should "take upstream ancestors by the binary names stored for them" in:
    val c = MerkleHashes.composed(analysis, "p.C", lookup(b("b"))).get
    c.nameHashes.map(_.name).toSet should contain allOf ("b", "a")
    hashes(MerkleHashes.composed(analysis, "p.C", lookup(b("b2"))).get) should not be hashes(c)

  it should "not read the external inheritance relation" in:
    val related = analysis.copy(relations =
      Relations.empty.addExternalDeps(
        VirtualFileRef.of("C.scala"),
        Seq(
          ExternalDependency.of("p.C", "p.Outer$B", null, DependencyContext.DependencyByInheritance)
        )
      )
    )
    hashes(MerkleHashes.composed(related, "p.C", lookup(b("b"))).get) shouldBe
      hashes(MerkleHashes.composed(analysis, "p.C", lookup(b("b"))).get)

  it should "take an ancestor of this subproject by its source name" in:
    val nested = analyzed("p.O.N", DefinitionType.ClassDef, Array.empty, "n")
    val own = Analysis.empty.copy(
      apis = APIs(
        Map(
          "p.D" -> analyzed("p.D", DefinitionType.ClassDef, Array("p.O$N"), "d"),
          "p.O.N" -> nested
        ),
        Map.empty
      ),
      relations = Relations.empty.addClasses(VirtualFileRef.of("O.scala"), Seq("p.O.N" -> "p.O$N"))
    )
    MerkleHashes.composed(own, "p.D").get.nameHashes.map(_.name).toSet should contain("n")
end MerkleHashesInheritanceSpec
