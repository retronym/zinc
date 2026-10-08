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

import xsbt.api.Discovery
import xsbti.api.{ AnalyzedClass, NameHash, Parameterized, Type }

/**
 * A class's stored API omits the members it inherits from classes of its own subproject. Within
 * that subproject the invalidator walks the hierarchy instead; another subproject only sees the
 * stored API (the analysis it looks up carries no relations), so it is given hashes composed from
 * the class's and its ancestors' own hashes, taking the ancestors from the stored linearization.
 * An ancestor contributes its extraHash rather than its apiHash: that covers its class side only,
 * since members of a companion object are not inherited.
 */
private[inc] object MerkleHashes:
  def composed(analysis: Analysis, className: String): Option[AnalyzedClass] =
    analysis.apis.internal.get(className).map { own =>
      val ancestors = linearization(own).filter(_ != className).flatMap(analysis.apis.internal.get)
      if ancestors.isEmpty then own
      else
        val all = own +: ancestors
        val nameHashes = all
          .flatMap(c => c.nameHashes.iterator.map(h => (h.name, h.scope) -> (c.name, h.hash)))
          .groupMap(_._1)(_._2)
          .iterator
          .map { case ((name, scope), hashes) => NameHash.of(name, scope, hashes.hashCode) }
          .toArray
        val apiHash = (own.apiHash +: ancestors.map(c => (c.name, c.extraHash))).hashCode
        own
          .withApiHash(apiHash)
          .withNameHashes(nameHashes)
    }

  /**
   * The ancestors a class's stored API lists, as class names, in linearization order. An
   * object's ancestors only count when there is no class side: a subclass inherits from the class.
   */
  private def linearization(c: AnalyzedClass): Vector[String] =
    def name(t: Type): Option[String] = t match
      case p: Parameterized => name(p.baseType)
      case t                => Discovery.simpleName(t)
    val classParents = c.api().classApi().structure.parents
    val parents =
      if classParents.nonEmpty then classParents else c.api().objectApi().structure.parents
    parents.iterator.flatMap(name).distinct.toVector
end MerkleHashes
