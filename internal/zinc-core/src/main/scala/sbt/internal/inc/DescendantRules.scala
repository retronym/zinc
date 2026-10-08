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

import xsbt.api.HashAPI
import xsbti.api.{ AnalyzedClass, ClassLike, DefinitionType }
import xsbti.compile.IncOptions

/**
 * The old and new APIs of a class whose API changed, as seen by [[DescendantRule]]s.
 *
 * `headerChanged` is true when anything a descendant's own type check reads from the class
 * itself, rather than from its members, changed: parents, type parameters, self type, kind,
 * modifiers, access or annotations.
 */
private[inc] final case class AncestorChange(
    className: String,
    before: AnalyzedClass,
    after: AnalyzedClass,
    modifiedNames: ModifiedNames
):
  val headerChanged: Boolean =
    sides(before).zip(sides(after)).exists((a, b) => !AncestorChange.sameHeader(a, b))
  val modifiedNameStrings: Set[String] = modifiedNames.names.map(_.name)

  private def sides(c: AnalyzedClass): List[ClassLike] =
    List(c.api().classApi(), c.api().objectApi())

private[inc] object AncestorChange:
  /**
   * Compared by hash: equal API types need not be `equals`, as types with refinements hold lazy
   * parts, which made every class look as if its header had changed.
   */
  private def sameHeader(a: ClassLike, b: ClassLike): Boolean = header(a) == header(b)

  private def header(c: ClassLike): HashAPI.Hash =
    HashAPI { h =>
      h.hashString(c.definitionType.toString)
      h.hashModifiers(c.modifiers)
      h.hashAccess(c.access)
      h.hashAnnotations(c.annotations)
      h.hashTypeParameters(c.typeParameters)
      h.hashType(c.selfType, includeDefinitions = false)
      h.hashTypes(c.structure.parents, includeDefinitions = false)
    }

/**
 * What a [[DescendantRule]] may read: the relations and the APIs of the current analysis.
 * Members inherited from classes of this subproject are not materialised, so the view computes
 * them from the inheritance relation and each class's own declarations.
 */
private[inc] final class HierarchyView(
    relations: Relations,
    val api: String => Option[AnalyzedClass]
):
  private val parents = relations.inheritance.internal

  /**
   * Ancestors through this subproject's inheritance relation, plus those in the stored
   * linearization, which also names ancestors in upstream subprojects.
   */
  def ancestors(className: String): Set[String] =
    IncrementalCommon.transitiveDeps(parents.forward(className), sbt.util.Logger.Null, false)(
      parents.forward
    ) ++ api(className).toList.flatMap(MerkleHashes.linearization) - className

  def usedNames(className: String): Set[String] =
    relations.names.toMultiMap.get(className).fold(Set.empty[String])(_.map(_.name).toSet)

  def classLikes(className: String): List[ClassLike] =
    api(className).toList.flatMap(c => List(c.api().classApi(), c.api().objectApi()))

  def declaredNames(className: String): Set[String] =
    classLikes(className).iterator.flatMap(_.structure.declared.iterator.map(_.name)).toSet

  /** Names a class has from library or upstream ancestors: its name hashes minus its decls. */
  def externallyInheritedNames(className: String): Set[String] =
    api(className).fold(Set.empty[String])(_.nameHashes.iterator.map(_.name).toSet) --
      declaredNames(className) - simpleName(className)

  def allNames(className: String): Set[String] =
    api(className).fold(Set.empty[String])(_.nameHashes.iterator.map(_.name).toSet)

  def isConcrete(className: String): Boolean =
    api(className).exists { c =>
      val cls = c.api().classApi()
      cls.definitionType match
        case DefinitionType.ClassDef => !cls.modifiers.isAbstract && !isEmpty(cls)
        case _                       => false
    } || isObject(className)

  def isObject(className: String): Boolean =
    api(className).exists(c => !isEmpty(c.api().objectApi()))

  /**
   * Whether the object side of `className` has `ancestor` in its linearization. A case class's
   * companion object does not, so it gets no forwarders for the class's inherited members.
   */
  def objectInherits(className: String, ancestor: String): Boolean =
    api(className).exists { c =>
      val obj = c.api().objectApi()
      !isEmpty(obj) &&
      obj.structure.parents.iterator
        .flatMap(MerkleHashes.typeName)
        .contains(ancestor)
    }

  def isTopLevel(className: String): Boolean = classLikes(className).exists(_.topLevel)

  def isTrait(className: String): Boolean =
    api(className).exists(_.api().classApi().definitionType == DefinitionType.Trait)

  private def isEmpty(c: ClassLike): Boolean =
    c.structure.parents.isEmpty && c.structure.declared.isEmpty && c.modifiers.raw == 0 &&
      c.annotations.isEmpty

  private def simpleName(className: String): String =
    className.substring(className.lastIndexOf('.') + 1)
end HierarchyView

/** Why `descendant` must recompile after `change`, or None. */
private[inc] trait DescendantRule:
  def name: String
  def apply(view: HierarchyView, descendant: String, change: AncestorChange): Option[String]

private[inc] object DescendantRules:
  private def hit(names: Set[String], change: AncestorChange): Set[String] =
    names.intersect(change.modifiedNameStrings)

  private def rule(n: String)(f: (HierarchyView, String, AncestorChange) => Option[String]) =
    new DescendantRule:
      def name = n
      def apply(view: HierarchyView, descendant: String, change: AncestorChange) =
        f(view, descendant, change)

  private def onNames(names: Set[String], what: String): Option[String] =
    if names.isEmpty then None else Some(s"$what ${names.toSeq.sorted.mkString(", ")}")

  /** The descendant selects inherited members by name, e.g. `this.m` or `super.m`. */
  val uses: DescendantRule = rule("uses") { (view, d, change) =>
    onNames(hit(view.usedNames(d), change), "uses")
  }

  /** Override checks and bridges of the descendant's own declarations. */
  val overrides: DescendantRule = rule("overrides") { (view, d, change) =>
    onNames(hit(view.declaredNames(d), change), "declares")
  }

  /**
   * Members of the same name inherited along another path must be reconciled with the changed
   * one. Ancestors of the changed class are on its own path and were checked when it compiled.
   * A descendant's API materialises members from upstream subprojects, so every name of the
   * changed class is excluded from those, not only the ones it inherits.
   */
  val conflicts: DescendantRule = rule("conflicts") { (view, d, change) =>
    val p = change.className
    val otherPath = view.ancestors(d) - p -- view.ancestors(p)
    val internal = otherPath.flatMap(view.declaredNames)
    val external = view.externallyInheritedNames(d) -- view.allNames(p)
    onNames(hit(internal ++ external, change), "also inherits")
  }

  /**
   * A concrete descendant must implement every abstract member it inherits. The member may be
   * deferred in any ancestor, not only the changed one: removing an implementation from `B`
   * exposes `A.m` to `C extends B`.
   */
  val `abstract`: DescendantRule = rule("abstract") { (view, d, change) =>
    def deferred(c: AnalyzedClass) =
      List(c.api().classApi(), c.api().objectApi()).iterator
        .flatMap(_.structure.declared.iterator)
        .collect { case m if m.modifiers.isAbstract => m.name }
        .toSet
    if !view.isConcrete(d) then None
    else
      val inAncestors = view.ancestors(d).iterator.flatMap(view.api).flatMap(deferred).toSet
      val candidates = deferred(change.before) ++ deferred(change.after) ++ inAncestors
      onNames(hit(candidates, change), "must implement")
  }

  /** The descendant's own type check reads the changed class's header. */
  val header: DescendantRule = rule("header") { (_, _, change) =>
    if change.headerChanged then Some(s"header of ${change.className} changed")
    else if change.modifiedNameStrings.contains("<init>") then
      Some(s"constructor of ${change.className} changed")
    else None
  }

  /** Classes mixing in a trait get forwarders for its concrete members. */
  val `trait`: DescendantRule = rule("trait") { (view, _, change) =>
    if view.isTrait(change.className) then Some(s"mixes in trait ${change.className}") else None
  }

  /**
   * A top-level object's mirror or companion class has static forwarders for every member,
   * including those it inherits from the changed class.
   */
  val mirror: DescendantRule = rule("mirror") { (view, d, change) =>
    if view.isTopLevel(d) && view.objectInherits(d, change.className) then
      Some("static forwarders")
    else None
  }

  /** Name hashing's own fallbacks: a changed implicit member. */
  val fallbacks: DescendantRule = rule("fallbacks") { (_, _, change) =>
    onNames(change.modifiedNames.in(xsbti.UseScope.Implicit).map(_.name), "implicit")
  }

  /**
   * A descendant is also a `memberRef` client of its parent (constructor call, inherited member
   * selections), so name-hash invalidation already covers what it reads through used names. These
   * rules cover what its own compilation reads without naming it: override checks, inherited
   * conflicts, abstract members and forwarders. `header` keeps every transitive descendant's
   * stored linearization fresh, which [[MerkleHashes]] reads for other subprojects.
   */
  val default: List[DescendantRule] =
    List(overrides, conflicts, `abstract`, header, `trait`, mirror)

  /** `default` plus rules that are subsumed by `memberRef` edges. */
  val all: List[DescendantRule] =
    List(uses, overrides, conflicts, `abstract`, header, `trait`, mirror, fallbacks)

  val Key = "descendantRules"

  /**
   * `all` recompiles every descendant (Zinc's behaviour before Merkle hashing); `none` recompiles
   * none, which is unsound and only useful to show the rules are needed. `-r1,-r2` starts from
   * `strict` and drops rules; `r1,r2` enables exactly those.
   */
  def fromOptions(options: IncOptions): Option[List[DescendantRule]] =
    Option(options.extra().get(Key)).getOrElse("default").trim match
      case "all"     => None
      case "default" => Some(default)
      case "strict"  => Some(all)
      case "none"    => Some(Nil)
      case spec      =>
        val enabled = spec.split(",").iterator.map(_.trim).toSet
        val (on, off) =
          if enabled.forall(_.startsWith("-")) then
            (all.filterNot(r => enabled(s"-${r.name}")), enabled.map(_.drop(1)) -- all.map(_.name))
          else (all.filter(r => enabled(r.name)), enabled -- all.map(_.name))
        require(off.isEmpty, s"Unknown $Key: ${off.mkString(", ")}")
        Some(on)
end DescendantRules
