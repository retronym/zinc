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

import xsbti.api.*
import MerkleHashesStability.{ analyzed, parent }

class MerkleHashesCacheSpec extends UnitSpec:
  private def analysis = Analysis.empty.copy(apis =
    APIs(
      Map(
        "p.A" -> analyzed("p.A", DefinitionType.ClassDef, Array(parent("p.B"), parent("q.C")), "a"),
        "p.B" -> analyzed("p.B", DefinitionType.Trait, Array.empty, "b")
      ),
      Map.empty
    )
  )
  private val c1 = analyzed("q.C", DefinitionType.Trait, Array.empty, "c")
  private val c2 = analyzed("q.C", DefinitionType.Trait, Array.empty, "c2")
  private def lookup(c: AnalyzedClass): String => Option[AnalyzedClass] =
    case "q.C" => Some(c)
    case _     => None
  private def hashes(c: AnalyzedClass) =
    (c.apiHash, c.nameHashes.map(h => (h.name, h.scope, h.hash)).toSet)

  it should "reuse a composed class while its ancestors elsewhere are the same" in:
    val a = analysis
    val first = MerkleHashes.composed(a, "p.A", lookup(c1)).get
    MerkleHashes.composed(a, "p.A", lookup(c1)).get shouldBe theSameInstanceAs(first)

  it should "compose again when an ancestor elsewhere changes" in:
    val a = analysis
    val first = MerkleHashes.composed(a, "p.A", lookup(c1)).get
    val second = MerkleHashes.composed(a, "p.A", lookup(c2)).get
    hashes(second) should not be hashes(first)
    hashes(second) shouldBe hashes(MerkleHashes.composed(analysis, "p.A", lookup(c2)).get)
    hashes(MerkleHashes.composed(a, "p.A", _ => None).get) shouldBe
      hashes(MerkleHashes.composed(analysis, "p.A", _ => None).get)
end MerkleHashesCacheSpec
