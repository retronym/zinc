/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership.
 */

package sbt
package internal
package inc

import xsbti.UseScope
import xsbti.api.AnalyzedClass

/**
 * Changes to the members of a package that reach classes with no edge to the package object.
 *
 * A package object's members (Scala 3: a `$package` class's) are members of its package. A
 * member it gains can shadow what a class of the package, or of a nested one, resolved a name to,
 * and so can one it supplies to `import a.b._`; neither class records an edge to the package
 * object, since it reached the member through no symbol. An implicit it gains or loses is in the
 * lexical implicit scope of every class in the package and nested packages, so their implicit
 * searches may now be ambiguous or find another instance.
 *
 * A package object's members include those it inherits. Stored APIs list declarations only, so
 * they are composed from the package object's and its ancestors' own names, taking the ancestors
 * from the stored linearization: an ancestor in this subproject contributes its declarations, one
 * in another subproject its composed names (see [[MerkleHashes]]), and a library ancestor's
 * members are already the package object's own stubs. The members change when the package object
 * or any of its ancestors changes, without the package object recompiling.
 */
private[inc] object PackageScope:
  def isPackageObject(className: String): Boolean =
    className == "package" || className.endsWith(".package") || className.endsWith("$package")

  /** The package whose members a package object holds: `a.b` for `a.b.package` and `a.b.F$package`. */
  def packageOf(packageObject: String): String =
    packageObject.lastIndexOf('.') match
      case -1 => ""
      case i  => packageObject.substring(0, i)

  /** Names that no reference resolves through a package's scope. */
  private val ignoredNames = Set("<init>", "$init$", "package")

  final case class Change(packageObject: String, added: Set[String], implicitsChanged: Boolean)

  /**
   * How the package objects affected by `changes` changed: those that changed themselves, and
   * those with a changed ancestor.
   *
   * @param changes The classes whose API changed, before and after.
   * @param api The API of a class that did not change.
   * @param packageObjects This subproject's package objects.
   */
  def changes(
      changes: collection.Map[String, AncestorChange],
      api: String => Option[AnalyzedClass],
      packageObjects: Iterable[String]
  ): List[Change] =
    def lookup(side: AncestorChange => AnalyzedClass)(name: String) =
      changes.get(name) match
        case Some(change) => Some(side(change)).filter(_ ne APIs.emptyAnalyzedClass)
        case None         => api(name)
    val before = lookup(_.before)
    val after = lookup(_.after)
    def affected(p: String) =
      changes.contains(p) || List(before, after).exists(
        _(p).exists(c => MerkleHashes.linearization(c).exists(changes.contains))
      )
    (packageObjects.iterator ++ changes.keysIterator.filter(isPackageObject)).toSet.toList.sorted
      .filter(affected)
      .flatMap { p =>
        val (namesBefore, implicitsBefore) = members(p, before)
        val (namesAfter, implicitsAfter) = members(p, after)
        val added = namesAfter -- namesBefore -- ignoredNames
        val implicitsChanged = implicitsBefore != implicitsAfter
        if added.isEmpty && !implicitsChanged then None
        else Some(Change(p, added, implicitsChanged))
      }
  end changes

  /**
   * The classes that the changes reach: the users of an added name, and for a changed implicit
   * the classes of the package and nested packages.
   */
  def invalidated(changes: List[Change], relations: Relations): Set[String] =
    if changes.isEmpty then Set.empty
    else
      val added = changes.flatMap(_.added).toSet
      val byName =
        if added.isEmpty then Set.empty
        else
          relations.names.iterator.collect {
            case (className, used) if used.exists(u => added(u.name)) => className
          }.toSet
      val packages = changes.filter(_.implicitsChanged).map(c => packageOf(c.packageObject))
      val byImplicit =
        if packages.isEmpty then Set.empty
        else relations.classes._2s.filter(c => packages.exists(inPackage(c, _))).toSet
      byName ++ byImplicit

  /**
   * A class of the package named like an added member, with the package object. The two clash,
   * but Scala 3 reports it only when it compiles both, so they recompile together unless the last
   * cycle compiled them together.
   */
  def clashes(changes: List[Change], relations: Relations, compiled: Set[String]): Set[String] =
    changes.iterator.filter(c => relations.definesClass(c.packageObject).nonEmpty).flatMap { c =>
      val pkg = packageOf(c.packageObject)
      c.added.iterator
        .map(n => if pkg.isEmpty then n else s"$pkg.$n")
        .filter(n => relations.definesClass(n).nonEmpty)
        .filterNot(n => compiled(n) && compiled(c.packageObject))
        .flatMap(n => List(n, c.packageObject))
    }.toSet

  private def inPackage(className: String, pkg: String): Boolean =
    pkg.isEmpty || className.startsWith(pkg + ".")

  /** A package object's member names, and those of its implicit members. */
  private def members(
      packageObject: String,
      api: String => Option[AnalyzedClass]
  ): (Set[String], Set[String]) =
    api(packageObject) match
      case None      => (Set.empty, Set.empty)
      case Some(own) =>
        val hashes = (own +: MerkleHashes.linearization(own).flatMap(api)).flatMap(_.nameHashes)
        (
          hashes.iterator.map(_.name).toSet,
          hashes.iterator.filter(_.scope == UseScope.Implicit).map(_.name).toSet
        )
end PackageScope
