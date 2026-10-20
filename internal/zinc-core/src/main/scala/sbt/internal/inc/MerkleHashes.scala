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
import xsbti.api.{
  AnalyzedClass,
  DefinitionType,
  Id,
  NameHash,
  Parameterized,
  Path,
  PathComponent,
  Projection,
  SafeLazyProxy,
  Singleton,
  This,
  Type
}

import scala.util.hashing.MurmurHash3

/**
 * A class's stored API omits the members it inherits from classes of its own subproject. Within
 * that subproject the invalidator walks the hierarchy instead; another subproject only sees the
 * stored API (the analysis it looks up carries no relations but the external inheritance one), so
 * it is given hashes composed from the class's and its ancestors' own hashes, taking the ancestors
 * from the stored linearization. A parent reached through an object, `extends Inst.B` with
 * `object Inst extends Outer`, is stored as `Inst.B`, which no class is called; the external
 * inheritance relation of the class and its ancestors here has its class, `Outer$B`, which is
 * added after the stored ancestors along with those its own stored linearization lists.
 * Ancestors in other subprojects come from their own analyses: a class does not recompile when
 * an upstream ancestor changes, so the members it materialised from it may be stale.
 * An ancestor contributes its extraHash rather than its apiHash: that covers its class side only,
 * since members of a companion object are not inherited. Its implicit members are added from both
 * sides, since a companion's implicits are in the implicit scope of its descendants.
 *
 * A trait also gets, per name, a hash of the contributions of itself and its trait ancestors only,
 * under the name with [[ForwarderSuffix]]. A class mixing the trait in has forwarders for exactly
 * those members, and for the private fields of those traits, which their extraHashes cover and
 * which are hashed under the bare suffix. A change that reaches the trait only through a class
 * ancestor moves the composed hash but none of these. No client uses these names.
 */
private[inc] object MerkleHashes {

  /**
   * Composing every class that a subproject depends on is costly, and it happens on every
   * compile, to detect changes upstream. An analysis keeps what it composed, with the ancestors
   * from other analyses that went into it, and the result is reused while those ancestors are
   * the same instances: an `AnalyzedClass` is immutable, and an analysis is kept in memory
   * across compiles while it does not change.
   */
  def composed(
      analysis: Analysis,
      className: String,
      elsewhere: String => Option[AnalyzedClass] = _ => None
  ): Option[AnalyzedClass] =
    analysis.apis.internal.get(className).map { own =>
      analysis match {
        case a: MAnalysis =>
          val cached = a.composedCache.get(className)
          if ((cached ne null) && cached.isCurrent(elsewhere)) cached.result
          else {
            val c = compose(analysis, own, elsewhere)
            a.composedCache.put(className, c)
            c.result
          }
        case _ => compose(analysis, own, elsewhere).result
      }
    }

  /** A composed class, and the ancestors it took from outside its own analysis. */
  private[inc] final class Composed(
      externalNames: Array[String],
      externals: Array[AnalyzedClass],
      val result: AnalyzedClass
  ) {
    def isCurrent(elsewhere: String => Option[AnalyzedClass]): Boolean = {
      var i = 0
      while (i < externalNames.length) {
        if (!(elsewhere(externalNames(i)).orNull eq externals(i))) return false
        i += 1
      }
      true
    }
  }

  private def compose(
      analysis: Analysis,
      own: AnalyzedClass,
      elsewhere: String => Option[AnalyzedClass]
  ): Composed = {
    val className = own.name
    val stored = linearization(own).filter(_ != className)
    val parents = analysis.relations.inheritance.external
    val upstream = (className +: stored.filter(analysis.apis.internal.contains))
      .flatMap(parents.forward)
      .map(LibraryAncestors.sourceName)
      .distinct
      .sorted
    val known = collection.mutable.Set((className +: stored).map(LibraryAncestors.canonical): _*)
    val reached = upstream
      .flatMap(u => u +: elsewhere(u).toVector.flatMap(linearization))
      .filter(a => known.add(LibraryAncestors.canonical(a)))
    val names = stored ++ reached
    val externalNames = names.filterNot(analysis.apis.internal.contains).toArray
    val externals = externalNames.map(elsewhere(_).orNull)
    val ancestors = names.flatMap { a =>
      analysis.apis.internal.get(a).orElse {
        val i = externalNames.indexOf(a)
        Option(externals(i))
      }
    }
    new Composed(externalNames, externals, withParents(composed(own, ancestors), reached))
  }

  /**
   * A composed class lists the ancestors it was composed with that its stored linearization
   * misses, so that the descendant rules of a subproject further downstream find them too.
   */
  private def withParents(c: AnalyzedClass, names: Vector[String]): AnalyzedClass =
    if (names.isEmpty) c
    else {
      val companions = c.api()
      val classApi = companions.classApi()
      val structure = classApi.structure
      val parents = structure.parents ++ names.map(parentType)
      val added = classApi.withStructure(
        SafeLazyProxy.strict(structure.withParents(SafeLazyProxy.strict(parents)))
      )
      c.withApi(SafeLazyProxy.strict(companions.withClassApi(added)))
    }

  /** A type that [[typeName]] renders as `name`. */
  private def parentType(name: String): Type = {
    val parts = name.split('.')
    val path = parts.init.map(p => Id.of(p): PathComponent) :+ This.of()
    Projection.of(Singleton.of(Path.of(path)), parts.last)
  }

  private def composed(own: AnalyzedClass, ancestors: Vector[AnalyzedClass]): AnalyzedClass = {
    val forwarded =
      if (isTrait(own)) {
        val traits = (own +: ancestors).filter(isTrait)
        val fields = traits.map(c => (c.name, c.extraHash)).hashCode
        compose(traits, ForwarderSuffix) :+ NameHash.of(ForwarderSuffix, UseScope.Default, fields)
      } else Array.empty[NameHash]
    if (ancestors.isEmpty)
      own
        .withApiHash((own.apiHash, header(own)).hashCode)
        .withNameHashes(own.nameHashes ++ forwarded)
    else {
      val apiHash =
        (own.apiHash +: header(own) +: ancestors.map(c => (c.name, c.extraHash, implicits(c))))
          .hashCode
      own
        .withApiHash(apiHash)
        .withNameHashes(compose(own +: ancestors, "") ++ forwarded)
    }
  }

  /** Marks a name hash that covers a trait's mixin forwarders: see the class comment. */
  val ForwarderSuffix = " <forwarders>"

  /** The name a [[ForwarderSuffix]] hash covers, if `name` is one. */
  def forwarded(name: String): Option[String] =
    if (name.endsWith(ForwarderSuffix)) Some(name.dropRight(ForwarderSuffix.length)) else None

  def hasForwarderHashes(c: AnalyzedClass): Boolean =
    c.nameHashes.exists(h => forwarded(h.name).isDefined)

  private final class Mix(var hash: Int, var count: Int)

  private def compose(classes: Seq[AnalyzedClass], suffix: String): Array[NameHash] = {
    val byScope = new java.util.EnumMap[UseScope, java.util.HashMap[String, Mix]](classOf[UseScope])
    classes.foreach { c =>
      val classHash = c.name.hashCode
      c.nameHashes.foreach { h =>
        var byName = byScope.get(h.scope)
        if (byName eq null) {
          byName = new java.util.HashMap[String, Mix]
          byScope.put(h.scope, byName)
        }
        var mix = byName.get(h.name)
        if (mix eq null) {
          mix = new Mix(MurmurHash3.seqSeed, 0)
          byName.put(h.name, mix)
        }
        mix.hash = MurmurHash3.mix(MurmurHash3.mix(mix.hash, classHash), h.hash)
        mix.count += 1
      }
    }
    val result = Array.newBuilder[NameHash]
    byScope.forEach { (scope, byName) =>
      byName.forEach { (name, mix) =>
        result += NameHash.of(name + suffix, scope, MurmurHash3.finalizeHash(mix.hash, mix.count))
      }
    }
    result.result()
  }

  private def isTrait(c: AnalyzedClass): Boolean =
    c.api().classApi().definitionType == DefinitionType.Trait

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
      .map(cl => (cl.modifiers.raw, cl.access, cl.annotations.toSeq, cl.definitionType.ordinal))
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

  /**
   * The class a parent type names, dropping type arguments. A class nested in an object is a
   * projection on the object's, `Discovery.simpleName` names only top-level classes.
   */
  def typeName(t: Type): Option[String] = t match {
    case p: Parameterized => typeName(p.baseType)
    case p: Projection =>
      p.prefix match {
        case q: Projection => typeName(q).map(_ + "." + p.id)
        case _             => Discovery.simpleName(p)
      }
    case t => Discovery.simpleName(t)
  }
}
