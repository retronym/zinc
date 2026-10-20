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

class LibraryAncestorsSpec extends UnitSpec {
  "binaryNameCandidates" should "list a Scala 2 nested name's binary names" in {
    LibraryAncestors.binaryNameCandidates("a.O.C") shouldBe List("a.O.C", "a.O$C", "a$O$C")
  }

  it should "keep a Scala 3 nested name as stored, then its binary names" in {
    LibraryAncestors.binaryNameCandidates("a.O$.C") shouldBe
      List("a.O$.C", "a.O.C", "a.O$C", "a$O$C")
  }

  "canonical" should "name the members of objects as Scala 2 does" in {
    LibraryAncestors.canonical("a.O$.P$.C") shouldBe "a.O.P.C"
  }
}
