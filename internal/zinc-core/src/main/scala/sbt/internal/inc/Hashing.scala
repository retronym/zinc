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

import xsbti.UseScope
import xsbti.api.{ AnalyzedClass, NameHash }
import xsbti.compile.IncOptions

/**
 * How a class's API is hashed and how a change to it reaches the classes that inherit it, chosen
 * by `incOptions.extra("hashing")`.
 *
 *  - `names` (the default): name hashing. The bridge copies every inherited member into each
 *    class's API, so a change to an ancestor changes the API of every descendant, and every
 *    descendant recompiles to refresh it.
 *  - `merkle`: a class's API holds its own declarations; the hashes of what it inherits are
 *    composed from its ancestors' (see [[MerkleHashes]]), and only the descendants whose own
 *    compilation reads a change recompile (see [[DescendantRules]]). Library ancestors are
 *    handled coarsely (see [[LibraryAncestors]]), and a bridge that can hash APIs itself does.
 *
 * The mode is recorded in the compile setup, so changing it recompiles the subproject. Every
 * subproject of a build must use the same mode: an analysis made in one mode can't be read by a
 * compile in the other, and looking up a class from it fails (see [[checkUpstream]]).
 */
sealed abstract class Hashing(val name: String) extends Product with Serializable

object Hashing:
  case object Names extends Hashing("names")
  case object Merkle extends Hashing("merkle")

  val Key = "hashing"

  /** The key of the mode in the compile setup's `extra`, present for `merkle` only. */
  val SetupKey = "zinc.hashing"

  def apply(options: IncOptions): Hashing =
    Option(options.extra().get(Key)).map(_.trim).getOrElse("") match
      case "" | Names.name => Names
      case Merkle.name     => Merkle
      case other           =>
        throw new IllegalArgumentException(s"Unknown incOptions $Key: $other (names or merkle)")

  def isMerkle(options: IncOptions): Boolean = apply(options) == Merkle

  /** The setup entries that record `options`' mode. */
  def setupExtra(options: IncOptions): List[(String, String)] =
    if isMerkle(options) then List(SetupKey -> Merkle.name) else Nil

  /**
   * A name hash that every class analysed under `merkle` carries, so that a compile can tell
   * which mode an upstream analysis was made in. No class uses the name, and its hash is constant.
   */
  private[inc] val MarkerName = "<merkle>"

  private[inc] val Marker: NameHash = NameHash.of(MarkerName, UseScope.Default, 0)

  private[inc] def isMarker(nh: NameHash): Boolean = nh.name == MarkerName

  private[inc] def modeOf(c: AnalyzedClass): Hashing =
    if c.nameHashes.exists(isMarker) then Merkle else Names

  /** Fails when `upstream`, from another subproject's analysis, was analysed in another mode. */
  private[inc] def checkUpstream(mode: Hashing, upstream: AnalyzedClass): Unit =
    val theirs = modeOf(upstream)
    if theirs != mode then
      throw new IllegalStateException(
        s"Class ${upstream.name} comes from a subproject compiled with incOptions $Key=${theirs.name}, " +
          s"but this one uses $Key=${mode.name}. Every subproject of a build must use the same mode."
      )
end Hashing
