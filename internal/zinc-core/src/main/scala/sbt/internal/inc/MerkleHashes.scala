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
import xsbti.UseScope
import xsbti.api.{ AnalyzedClass, NameHash, Parameterized, Type }

/**
 * A class's stored API omits the members it inherits from classes of its own subproject. Within
 * that subproject the invalidator walks the hierarchy instead; another subproject only sees the
 * stored API (the analysis it looks up carries no relations), so it is given hashes composed from
 * the class's and its ancestors' own hashes, taking the ancestors from the stored linearization.
 * Ancestors in other subprojects come from their own analyses: a class does not recompile when
 * an upstream ancestor changes, so the members it materialised from it may be stale.
 * An ancestor contributes its extraHash rather than its apiHash: that covers its class side only,
 * since members of a companion object are not inherited. Its implicit members are added from both
 * sides, since a companion's implicits are in the implicit scope of its descendants.
 */
private[inc] object MerkleHashes {
  def composed(
      analysis: Analysis,
      className: String,
      elsewhere: String => Option[AnalyzedClass] = _ => None
  ): Option[AnalyzedClass] =
    analysis.apis.internal.get(className).map { own =>
      val ancestors = linearization(own)
        .filter(_ != className)
        .flatMap(a => analysis.apis.internal.get(a).orElse(elsewhere(a)))
      if (ancestors.isEmpty) own.withApiHash((own.apiHash, header(own)).hashCode)
      else {
        val all = own +: ancestors
        val nameHashes = all
          .flatMap(c => c.nameHashes.iterator.map(h => (h.name, h.scope) -> ((c.name, h.hash))))
          .groupBy(_._1)
          .map { case (k, v) => k -> v.map(_._2) }
          .iterator
          .map { case ((name, scope), hashes) => NameHash.of(name, scope, hashes.hashCode) }
          .toArray
        val apiHash =
          (own.apiHash +: header(own) +: ancestors.map(c => (c.name, c.extraHash, implicits(c))))
            .hashCode
        own
          .withApiHash(apiHash)
          .withNameHashes(nameHashes)
      }
    }

  /**
   * An ancestor's implicit members, its companion's included. The companions of a class's base
   * classes are in the implicit scope of its type, so a client whose implicit search found an
   * instance there, or would now find a better one, depends on them without naming the ancestor.
   */
  private def implicits(c: AnalyzedClass): Int =
    c.nameHashes.iterator
      .filter(_.scope == UseScope.Implicit)
      .map(h => (h.name, h.hash))
      .toVector
      .sorted
      .hashCode

  /**
   * What HashAPI leaves out of a top-level class's hash: its own modifiers, access and
   * annotations. A subclass in another subproject reads them (e.g. `final`), and with pipelining
   * there is no bytecode hash to catch the change instead.
   */
  private def header(c: AnalyzedClass): Int =
    List(c.api().classApi(), c.api().objectApi())
      .map(cl => (cl.modifiers.raw, cl.access, cl.annotations.toSeq, cl.definitionType))
      .hashCode

  /**
   * The ancestors a class's stored API lists, as class names, in linearization order. An
   * object's ancestors only count when there is no class side: a subclass inherits from the class.
   */
  def linearization(c: AnalyzedClass): Vector[String] = {
    val classParents = c.api().classApi().structure.parents
    val parents =
      if (classParents.nonEmpty) classParents else c.api().objectApi().structure.parents
    parents.iterator.flatMap(typeName).toVector.distinct
  }

  /** The class a parent type names, dropping type arguments. */
  def typeName(t: Type): Option[String] = t match {
    case p: Parameterized => typeName(p.baseType)
    case t                => Discovery.simpleName(t)
  }
}
