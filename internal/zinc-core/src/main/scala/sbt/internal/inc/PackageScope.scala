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
import xsbti.compile.{ IncOptions, MiniSetup }

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

  /**
   * Names that no reference resolves through a package's scope to a member that a package object
   * adds: constructors, the members of `Any` and `AnyRef`, which every package object has.
   */
  private val ignoredNames = Set(
    "<init>",
    "$init$",
    "package",
    "==",
    "!=",
    "##",
    "equals",
    "hashCode",
    "toString",
    "getClass",
    "isInstanceOf",
    "asInstanceOf",
    "$isInstanceOf",
    "$asInstanceOf",
    "eq",
    "ne",
    "synchronized",
    "wait",
    "notify",
    "notifyAll",
    "clone",
    "finalize"
  )

  private def ignored(name: String): Boolean =
    ignoredNames(name) || name.contains(';') || name.endsWith("$package")

  val Key = "packageScope"

  /**
   * Whether a change to a package's members reaches the classes of every package. By default it
   * reaches the classes that see the package (see [[Reach]]), which needs a bridge that records
   * wildcard imports of packages and chained package clauses as the used name `a.b._`; `global`
   * is for one that does not.
   */
  def global(options: IncOptions): Boolean =
    options.extra().getOrDefault(Key, "").trim == "global"

  /**
   * Whether the compiler of `setup` puts the package objects of a type's prefix packages in its
   * implicit scope: Scala 2, unless `-Xsource:3-cross` or `-Xsource-features` (`_`,
   * `package-prefix-implicits`, or a `v2.13.13` or later group) drops them. Scala 3 does not
   * (only under `-source:3.0-migration`). As on develop (retronym/zinc#47).
   */
  def packagePrefixImplicits(setup: MiniSetup): Boolean =
    packagePrefixImplicits(setup.compilerVersion, setup.options.scalacOptions.toSeq)

  def packagePrefixImplicits(compilerVersion: String, scalacOptions: Seq[String]): Boolean =
    def drops(option: String) =
      option == "-Xsource:3-cross" || option.startsWith("-Xsource-features:") &&
        option.stripPrefix("-Xsource-features:").split(',').exists { f =>
          f == "_" || f == "package-prefix-implicits" || f.startsWith("v2.13.")
        }
    compilerVersion.startsWith("2.") && !scalacOptions.exists(drops) ||
    compilerVersion.startsWith("3.") && scalacOptions.exists(_.contains("3.0-migration"))
  end packagePrefixImplicits

  /** The used name a class records for a wildcard import of `pkg`, or an outer clause `package pkg`. */
  def wildcardImport(pkg: String): String = pkg + "._"

  /**
   * The classes that see the members of a package: its own classes (not those of a nested package,
   * which see it only through a chained clause), those in a source that records `pkg._` (a
   * wildcard import of it, or `package pkg; package sub`; names at the top of a source are
   * recorded on one class of it), and Java classes, whose imports are not recorded.
   */
  final class Reach(relations: Relations, isScalaClass: String => Boolean, global: Boolean):
    private lazy val allClasses: Set[String] = relations.classes._2s.toSet

    private lazy val importers: Map[String, Set[String]] =
      relations.names.iterator
        .flatMap { (className, used) =>
          used.iterator.map(_.name).filter(_.endsWith("._")).map(n => n.dropRight(2) -> className)
        }
        .toList
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet.flatMap(c =>
          relations.definesClass(c).flatMap(relations.classNames) + c
        ))
        .toMap

    /** The package of a class: its name without the enclosing classes and the simple name. */
    def packageOfClass(className: String): String =
      var p = parent(className)
      while p.nonEmpty && allClasses(p) do p = parent(p)
      p

    private def parent(name: String): String =
      name.lastIndexOf('.') match
        case -1 => ""
        case i  => name.substring(0, i)

    /** Whether `className`, a Scala class unless `java`, sees the members of `pkg`. */
    def apply(pkg: String, java: Boolean = true)(className: String): Boolean =
      global || pkg.isEmpty || packageOfClass(className) == pkg ||
        importers.get(pkg).exists(_(className)) || (java && !isScalaClass(className))

    /**
     * The classes whose implicit searches may involve a type under `pkg`: Scala 2 adds the package
     * objects of a type's prefix packages to its implicit scope. Approximated by the classes with a
     * member-reference or inheritance dependency on a class in `pkg` or a nested package.
     */
    def implicitSeers(pkg: String): Set[String] =
      if global then allClasses
      else
        def under(c: String) = pkg.isEmpty || c.startsWith(pkg + ".")
        val rels = List(
          relations.memberRef.internal,
          relations.memberRef.external,
          relations.inheritance.internal,
          relations.inheritance.external
        )
        rels.iterator.flatMap(_.all.collect { case (from, to) if under(to) => from }).toSet
  end Reach

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
        val added = (namesAfter -- namesBefore).filterNot(ignored)
        val implicitsChanged = implicitsBefore != implicitsAfter
        if added.isEmpty && !implicitsChanged then None
        else Some(Change(p, added, implicitsChanged))
      }
  end changes

  /**
   * The classes that the changes reach: the users of an added name that see the package, and for
   * a changed implicit every Scala class that sees the package, or depends on a class under it.
   */
  def invalidated(
      changes: List[Change],
      relations: Relations,
      reach: Reach,
      packagePrefixImplicits: Boolean = true
  ): Set[String] =
    if changes.isEmpty then Set.empty
    else
      val byName = usersOf(
        changes.flatMap(c => c.added.map(_ -> packageOf(c.packageObject))),
        relations,
        reach
      )
      val packages = changes.filter(_.implicitsChanged).map(c => packageOf(c.packageObject))
      val byImplicit =
        if packages.isEmpty then Set.empty
        else
          relations.classes._2s.filter(c => packages.exists(reach(_, java = false)(c))).toSet ++
            (if packagePrefixImplicits then packages.flatMap(reach.implicitSeers) else Set.empty)
      byName ++ byImplicit

  /** The users of each name that see the package it is added to. */
  def usersOf(added: Iterable[(String, String)], relations: Relations, reach: Reach): Set[String] =
    val packagesOf = added.groupMap(_._1)(_._2)
    if packagesOf.isEmpty then Set.empty
    else
      relations.names.iterator.collect {
        case (className, used)
            if used.exists(u => packagesOf.get(u.name).exists(_.exists(reach(_)(className)))) =>
          className
      }.toSet

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

  /**
   * A top-level class added to a package whose package objects already have a member of its name,
   * with those package objects: the converse of [[clashes]]. The members are composed, so an
   * inherited member counts.
   */
  def addedClassClashes(
      changes: collection.Map[String, AncestorChange],
      api: String => Option[AnalyzedClass],
      packageObjects: Iterable[String],
      relations: Relations,
      compiled: Set[String]
  ): Set[String] =
    val added = changes.valuesIterator.collect {
      case c
          if (c.before eq APIs.emptyAnalyzedClass) && (c.after ne APIs.emptyAnalyzedClass) &&
            !isPackageObject(c.className) && c.after.api().classApi().topLevel &&
            relations.definesClass(c.className).nonEmpty =>
        c.className
    }.toList
    if added.isEmpty then Set.empty
    else
      val after = (name: String) =>
        changes.get(name) match
          case Some(c) => Some(c.after).filter(_ ne APIs.emptyAnalyzedClass)
          case None    => api(name)
      val byPackage = packageObjects.groupBy(packageOf)
      added.iterator.flatMap { cls =>
        val name = cls.substring(cls.lastIndexOf('.') + 1)
        byPackage
          .getOrElse(packageOf(cls), Nil)
          .filter(p => members(p, after)._1(name))
          .filterNot(p => compiled(p) && compiled(cls))
          .flatMap(p => List(cls, p))
      }.toSet
  end addedClassClashes

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
