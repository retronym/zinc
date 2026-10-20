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

import xsbt.api.Discovery
import xsbti.api.{ Parameterized, Projection, Type }

/**
 * A class's ancestors by binary name, for a bridge that does not report them through
 * `AnalysisCallback5.ancestors` (Scala 3's, and Java classes read from their class files),
 * recovered from the parents in its API, which list its linearization as types.
 *
 * A rendered parent does not say which class it is. A class nested in an object is `p.O.A`, or
 * `p.O$.A` from Scala 3, where its binary name is `p.O$A`; so each is tried as each binary name it
 * may stand for, and the first that a class is known by is kept. A member class reached through
 * an object, `Inst.B` with `object Inst extends Outer`, is rendered under the object's name, which
 * no class has; its class is taken from the class's upstream parents in the inheritance relation,
 * with their own ancestors, when one has the unresolved parent's simple name. Scala 3 also records
 * a wildcard `export` as an inheritance edge, and the simple name keeps it out of the ancestors.
 *
 * Everything else reads ancestors in the form the bridge reports, so this is the only place
 * names are guessed, and it goes away for a bridge that reports them.
 */
private[inc] object LegacyAncestors {

  /**
   * @param parents A side's parents, as its API lists them.
   * @param isClass Whether a class with this binary name exists: this subproject's, another's,
   *                or a library's.
   * @param upstreamParents The class's parents in other subprojects, by the inheritance relation,
   *                        each with its own ancestors.
   */
  def apply(
      parents: Array[Type],
      isClass: String => Boolean,
      upstreamParents: => Seq[(String, Array[String])]
  ): Array[String] =
    if (parents.isEmpty) NoAncestors
    else {
      val rendered = parents.iterator.flatMap(typeName).toVector.distinct
      val resolved = rendered.map(n => binaryNameCandidates(n).find(isClass).toRight(n))
      val found = resolved.collect { case Right(b) => b }
      val unresolved = resolved.collect { case Left(n) => simpleName(n) }.toSet
      val reached =
        if (unresolved.isEmpty) Nil
        else
          upstreamParents.sortBy(_._1).flatMap {
            case (b, theirs) =>
              if (unresolved(simpleName(b))) b +: theirs.toSeq else Nil
          }
      (found ++ reached).filterNot(Platform).distinct.toArray
    }

  private[this] val NoAncestors = new Array[String](0)

  /** Ancestors the Scala 2 bridge leaves out: no class file of their own, or every class's. */
  private[this] val Platform =
    Set("java.lang.Object", "scala.Any", "scala.AnyRef", "scala.Matchable")

  /** The class a parent type names, dropping type arguments. */
  private def typeName(t: Type): Option[String] = t match {
    case p: Parameterized => typeName(p.baseType)
    case p: Projection =>
      p.prefix match {
        case q: Projection => typeName(q).map(_ + "." + p.id)
        case _             => Discovery.simpleName(p)
      }
    case t => Discovery.simpleName(t)
  }

  /**
   * The binary names a rendered class name may stand for: `a.b.C.D` is `a.b.C.D` if `C` is a
   * package, or `a.b.C$D` if it is a class or object, and so on outwards. Scala 3's `a.O$.C` is
   * first taken as Scala 2's `a.O.C`.
   */
  private[inc] def binaryNameCandidates(name: String): List[String] = {
    val c = name.replace("$.", ".")
    Iterator
      .iterate(c)(n =>
        n.lastIndexOf('.') match {
          case -1 => n
          case i  => n.substring(0, i) + "$" + n.substring(i + 1)
        }
      )
      .take(c.count(_ == '.') + 1)
      .toList
  }

  private def simpleName(name: String): String =
    name.stripSuffix("$").split('.').last.split('$').last
}
