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
import xsbti.api.DefinitionType
import MerkleHashesStability.analyzed

class LibraryAncestorsSpec extends UnitSpec {
  private val apis = APIs(
    Map(
      "p.D" -> analyzed("p.D", DefinitionType.ClassDef, Array("lib.O$L"), "d"),
      "p.E" -> analyzed("p.E", DefinitionType.ClassDef, Array("lib.M"), "e")
    ),
    Map.empty
  )

  "descendants" should "match a library class nested in an object by its binary name" in {
    LibraryAncestors.descendants(Set(BinaryName("lib.O$L")), apis) shouldBe Set("p.D")
  }

  "BinaryName.key" should "name this subproject's classes by source name, others by binary name" in {
    val relations =
      Relations.empty.addClasses(VirtualFileRef.of("O.scala"), Seq("p.O.A" -> "p.O$A"))
    val key = BinaryName.key(relations)
    key(BinaryName("p.O$A")) shouldBe "p.O.A"
    key(BinaryName("q.O$A")) shouldBe "q.O$A"
  }
}
