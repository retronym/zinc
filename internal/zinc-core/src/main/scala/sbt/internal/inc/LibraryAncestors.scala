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

import java.nio.file.Files
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*

import xsbt.api.Discovery
import xsbti.{ FileConverter, VirtualFileRef }
import xsbti.api.{ AnalyzedClass, ClassLike, NameHash }
import xsbti.compile.IncOptions

/**
 * How Zinc treats a class's library ancestors: classes on the classpath with no analysis.
 *
 * Under `libraryAncestors=names` (the default) the bridge records only the names, access and
 * modifiers of members inherited from them, not their signatures, so a library ancestor costs
 * its descendants nothing per compile. Libraries rarely change, so their change is handled
 * coarsely instead: every class with an ancestor from a changed library recompiles, and the
 * stamps of its library ancestors are folded into its API hash and into the hash of every name it
 * has by inheritance, so that all those names change. Its clients and descendants, here and
 * downstream, are then invalidated by those names, as for any other API change. Scala clients
 * that select an inherited library member also depend on the library directly; this is for those
 * that don't record the owner, such as Java and macro clients, and for descendants whose own
 * checks read the library's members.
 *
 * Under `libraryAncestors=members` the bridge copies library members into each descendant's API
 * in full, as before, and nothing more is done here. `libraryAncestors=stubs-only` records stubs
 * but skips the invalidation: it is unsound, and only there to show what the invalidation is for.
 *
 * A precise alternative would fold in per-name hashes of the library classes themselves, computed
 * once per library version, instead of their libraries' stamps, so that only the names that
 * changed in the library change in its descendants. `withFingerprint` is the place for it.
 */
private[inc] object LibraryAncestors:
  val Key = "libraryAncestors"

  private def value(options: IncOptions) = options.extra().getOrDefault(Key, "names").trim

  /** Whether the bridge records library members as stubs. */
  def coarse(options: IncOptions): Boolean = value(options) != "members"

  /** Whether a library change invalidates by inherited names; `stubs-only` is for ablation. */
  def invalidates(options: IncOptions): Boolean = value(options) == "names"

  /**
   * The classes of the changed libraries, named as `Discovery.simpleName` renders a reference to
   * them. A jar's are read from the jar: the relations keep one representative class per jar.
   */
  def classesOf(
      changedLibraries: Iterable[VirtualFileRef],
      relations: Relations,
      converter: FileConverter
  ): Set[String] =
    changedLibraries.iterator.flatMap { lib =>
      val path = converter.toPath(lib)
      if path.toString.endsWith(".jar") && Files.isRegularFile(path) then
        val zip = new ZipFile(path.toFile)
        try
          zip.entries.asScala
            .map(_.getName)
            .filter(n => n.endsWith(".class") && !n.startsWith("META-INF/"))
            .map(n => sourceName(n.stripSuffix(".class").replace('/', '.')))
            .toList
        finally zip.close()
      else relations.libraryClassNames(lib).iterator.map(sourceName).toList
    }.toSet

  /**
   * The classes whose stored ancestors include one of `changedClasses`. They all recompile in the
   * first cycle, as for a header change: the library's members may change their bridges and
   * forwarders, and their APIs no longer show what changed.
   */
  def descendants(changedClasses: Set[String], apis: APIs): Set[String] =
    if changedClasses.isEmpty then Set.empty
    else
      apis.internal.iterator.collect {
        case (className, c) if ancestorNames(c).exists(changedClasses) => className
      }.toSet

  /**
   * A class's hashes with its library ancestors folded in: `fingerprint` (the stamps of the
   * libraries defining them) goes into the API hash and into the hash of every name the class has
   * only by inheritance. When a library changes, its descendants recompile, and these hashes then
   * show their inherited names as changed, to clients and descendants in this subproject and
   * downstream alike.
   */
  def withFingerprint(
      apiHash: Int,
      nameHashes: Array[NameHash],
      className: String,
      sides: List[ClassLike],
      fingerprint: Int
  ): (Int, Array[NameHash]) =
    val inherited = inheritedNames(className, sides)
    val folded = nameHashes.map { nh =>
      if inherited(nh.name) then NameHash.of(nh.name, nh.scope, (nh.hash, fingerprint).hashCode)
      else nh
    }
    ((apiHash, fingerprint).hashCode, folded)

  /** The names of a class's library ancestors, as `Discovery.simpleName` renders them. */
  def ancestorNames(sides: List[ClassLike]): List[String] =
    sides.flatMap(_.structure.parents.iterator.flatMap(Discovery.simpleName)).distinct

  /** Binary names a class rendered as `name` may have: `a.b.C.D` is `a.b.C$D` if C is a class. */
  def binaryNameCandidates(name: String): List[String] =
    Iterator
      .iterate(name)(n =>
        n.lastIndexOf('.') match
          case -1 => n
          case i  => n.substring(0, i) + "$" + n.substring(i + 1)
      )
      .take(name.count(_ == '.') + 1)
      .toList

  private def ancestorNames(c: AnalyzedClass): Iterator[String] = ancestorNames(sides(c)).iterator

  /** A binary class name as `Discovery.simpleName` renders a reference to the class. */
  private def sourceName(binaryName: String): String =
    binaryName.stripSuffix("$").replace('$', '.')

  /** The names a class has only by inheritance: not declared, and not its own. */
  private def inheritedNames(className: String, sides: List[ClassLike]): String => Boolean =
    val declared = sides.iterator.flatMap(_.structure.declared.iterator.map(_.name)).toSet
    val own = className.substring(className.lastIndexOf('.') + 1)
    name => !declared(name) && name != own

  private def sides(c: AnalyzedClass) =
    if c eq APIs.emptyAnalyzedClass then Nil
    else List(c.api().classApi(), c.api().objectApi())
end LibraryAncestors
