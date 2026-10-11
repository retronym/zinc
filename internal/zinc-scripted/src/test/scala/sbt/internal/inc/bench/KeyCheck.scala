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
package bench

import scala.jdk.CollectionConverters.*

/**
 * Checks the keys a class has recorded in an Analysis against the keys the model expects, so that
 * a missing record shows up as `uncovered` even when the build happens to come out clean.
 *
 * A key is a string in a small closed grammar, naming what the bridge records:
 *  - `uses:n`: `n` is a used name of the class, in any scope; `uses@Implicit:n` (or another
 *    `UseScope`) in that scope only; `usesFile:n` of some class of the class's source (a
 *    top-level import is charged to one class of its file).
 *  - `ref:T` / `inh:T`: a member-reference / inheritance edge from the class to `T`, internal or
 *    external; `refFile:T` / `inhFile:T` from some class of its source.
 *  - `sees:p`: the class is in package `p` (not a subpackage), or a class of its source records
 *    the reserved used name `p._` (a wildcard import of `p`, or the outer clause of a chained
 *    package clause).
 *
 * A class or target is named as in the program: exactly, with the program package
 * ([[Conformance.Render.pkg]]) prefixed, or by the last segments of its name when that is unique.
 * An unknown key, or a name that matches no class or several, is reported as missing, with a
 * reason, so that a disagreement about names is not mistaken for coverage.
 */
final class KeyCheck(analyses: Seq[Analysis]):
  private val relations = analyses.map(_.relations)
  private val classes: Set[String] = relations.flatMap(_.classes._2s).toSet

  private def resolve(name: String, among: Set[String]): Either[String, String] =
    if among(name) then Right(name)
    else if among(s"${Conformance.Render.pkg}.$name") then Right(s"${Conformance.Render.pkg}.$name")
    else
      among.filter(_.endsWith("." + name)).toList match
        case List(one) => Right(one)
        case Nil       => Left("no class")
        case many      => Left(s"ambiguous: ${many.sorted.mkString(" ")}")

  private def usedNames(c: String): Set[(String, String)] =
    relations.flatMap(_.names.toMultiMap.getOrElse(c, Set.empty)).iterator.flatMap { u =>
      u.scopes.asScala.iterator.map(s => u.name -> s.name)
    }.toSet

  private def sourceClasses(c: String): Set[String] =
    relations.flatMap(r => r.definesClass(c).flatMap(r.classNames)).toSet + c

  private def refs(c: String): Set[String] =
    relations.flatMap(r => r.memberRef.internal.forward(c) ++ r.memberRef.external.forward(c)).toSet

  private def inhs(c: String): Set[String] =
    relations.flatMap(r =>
      r.inheritance.internal.forward(c) ++ r.inheritance.external.forward(c)
    ).toSet

  private def isIn(pkg: String, c: String): Boolean =
    val rest =
      if pkg.isEmpty then c
      else if c.startsWith(pkg + ".") then c.substring(pkg.length + 1) else null
    rest != null && {
      val top = rest.takeWhile(_ != '.')
      top == rest || classes(if pkg.isEmpty then top else s"$pkg.$top")
    }

  private def edge(targets: Set[String], target: String): Option[String] =
    resolve(target, targets ++ classes) match
      case Right(t) => Option.when(!targets(t))("no edge")
      case Left(_) if targets.exists(t => t == target || t.endsWith("." + target)) => None
      case Left(why)                                                               => Some(why)

  /** Why class `c` lacks `key`, or `None` if it has it. */
  def lacks(c: String, key: String): Option[String] =
    key.split(":", 2) match
      case Array("uses", n) => Option.when(!usedNames(c).exists(_._1 == n))("absent")
      case Array(k, n) if k.startsWith("uses@") =>
        val scope = k.stripPrefix("uses@")
        Option.when(!usedNames(c).exists(u => u._1 == n && u._2.equalsIgnoreCase(scope)))("absent")
      case Array("usesFile", n) =>
        Option.when(!sourceClasses(c).exists(s => usedNames(s).exists(_._1 == n)))("absent")
      case Array("ref", t)     => edge(refs(c), t)
      case Array("refFile", t) => edge(sourceClasses(c).flatMap(refs), t)
      case Array("inh", t)     => edge(inhs(c), t)
      case Array("inhFile", t) => edge(sourceClasses(c).flatMap(inhs), t)
      case Array("sees", p)    =>
        val recorded = sourceClasses(c).exists(s => usedNames(s).exists(_._1 == p + "._"))
        Option.when(!isIn(p, c) && !recorded)("not in the package and no `" + p + "._`")
      case _ => Some("unknown key")

  /**
   * The expected keys that are missing, as `Class key (reason)`, for `expected` mapping each
   * observed class to its keys.
   */
  def missing(expected: Map[String, Seq[String]]): Seq[String] =
    expected.toSeq.sortBy(_._1).flatMap { (name, keys) =>
      resolve(name, classes) match
        case Left(why) => keys.map(k => s"$name $k ($why)")
        case Right(c)  => keys.flatMap(k => lacks(c, k).map(why => s"$name $k ($why)"))
    }
end KeyCheck
