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

/**
 * How Zinc treats a class's library ancestors: classes on the classpath with no analysis. So far,
 * only how their names are rendered.
 */
private[inc] object LibraryAncestors:
  /**
   * A class name with the members of objects named as Scala 2 names them: Scala 3 keeps the
   * object's class in the name of a class nested in it, and in a reference to it (`a.O$.C`).
   */
  def canonical(name: String): String = name.replace("$.", ".")

  /**
   * The names a class rendered as `name` may have: the name itself, which is how this subproject's
   * classes are keyed, then its binary names, `a.b.C.D` being `a.b.C$D` if C is a class.
   */
  def binaryNameCandidates(name: String): List[String] =
    val c = canonical(name)
    val binary = Iterator
      .iterate(c)(n =>
        n.lastIndexOf('.') match
          case -1 => n
          case i  => n.substring(0, i) + "$" + n.substring(i + 1)
      )
      .take(c.count(_ == '.') + 1)
      .toList
    if c == name then binary else name :: binary

  /** A binary class name as [[MerkleHashes.typeName]] renders a reference to the class. */
  private[inc] def sourceName(binaryName: String): String =
    binaryName.stripSuffix("$").replace('$', '.')
end LibraryAncestors
