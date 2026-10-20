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

import xsbti.api.AnalyzedClass

/**
 * A class's JVM binary name, `p.O$A` for a class `A` nested in `object O`, as the bridge reports
 * a class's ancestors under Merkle hashing. A subproject names its own classes by source name
 * instead (`p.O.A`, or `p.O$.A` from Scala 3), in its APIs and relations, and other classes by
 * binary name; [[BinaryName.key]] converts. A distinct type, so that a binary name cannot be
 * looked up where a class is keyed the other way: an abstract type, a `String` at run time, as
 * Scala 3's opaque type on the develop line.
 */
private[inc] object BinaryName {
  type Type

  def apply(name: String): Type = name.asInstanceOf[Type]

  private def all(names: Array[String]): Array[Type] = names.asInstanceOf[Array[Type]]

  /**
   * How a subproject with these relations names the class: by its source name if the class is
   * its own, otherwise by its binary name.
   */
  def key(relations: Relations): Type => String =
    b => relations.productClassName.reverse(b.value).headOption.getOrElse(b.value)

  /**
   * A class's ancestors, in linearization order: the class side's, or the object's when there is
   * no class side, since a subclass inherits from the class.
   */
  def ancestors(c: AnalyzedClass): Array[Type] =
    if (
      c.moduleAncestors.isEmpty || c.ancestors.nonEmpty ||
      c.api().classApi().structure.parents.nonEmpty
    ) all(c.ancestors)
    else all(c.moduleAncestors)

  def moduleAncestors(c: AnalyzedClass): Array[Type] = all(c.moduleAncestors)

  def bothSides(c: AnalyzedClass): Iterator[Type] =
    all(c.ancestors).iterator ++ all(c.moduleAncestors).iterator
}
