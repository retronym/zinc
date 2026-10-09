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

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths, StandardOpenOption }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import sbt.util.Logger
import xsbt.api.{ APIUtil, HashAPI, NameHashing }
import xsbti.{ ClassHashes, UseScope }
import xsbti.api.{ ClassLike, DefinitionType, NameHash }

/**
 * Checks the bridge's hashes against Zinc's, under `apiCheck`. Hash values differ between the two,
 * so the check is on what they are for: whether a class, or a name in it, changed since the last
 * compile of that class in this JVM. Each disagreement is a warning tagged `[api-check]`, and is
 * appended to the file named by the `xsbt.api.check.report` system property, if set. Within one
 * compile, it also checks that both have the same name-hash keys and macro flag, and that the
 * bridge's thin class is what `APIUtil.minimize` makes of the full one.
 */
object ApiHashCheck:
  final case class Hashes(api: Int, extra: Int, names: Map[(String, UseScope), Int], hasMacro: Boolean)

  def treeHashes(classApi: ClassLike, optimizedSealed: Boolean): ClassHashes =
    val apiHash = HashAPI(classApi)
    val extraHash =
      if classApi.definitionType != DefinitionType.Trait then apiHash
      else HashAPI(_.hashAPI(classApi), includePrivateDefsInTrait = true)
    val nameHashes = new NameHashing(optimizedSealed).nameHashes(classApi)
    ClassHashes(apiHash, extraHash, nameHashes, APIUtil.hasMacro(classApi))

  private def toHashes(h: ClassHashes): Hashes =
    Hashes(
      h.apiHash,
      h.extraHash,
      h.nameHashes.iterator.map(nh => (nh.name, nh.scope) -> nh.hash).toMap,
      h.hasMacro
    )

  private val previous = new ConcurrentHashMap[String, (Hashes, Hashes)]
  val classesChecked = new AtomicLong
  val comparisons = new AtomicLong
  val disagreements = new AtomicLong

  def reset(): Unit =
    previous.clear()
    classesChecked.set(0)
    comparisons.set(0)
    disagreements.set(0)

  def summary: String =
    s"[api-check] ${classesChecked.get} classes checked, ${comparisons.get} compared with a " +
      s"previous version, ${disagreements.get} disagreements"

  def check(
      outputKey: String,
      full: ClassLike,
      thin: ClassLike,
      bridgeHashes: ClassHashes,
      optimizedSealed: Boolean,
      log: Logger
  ): Unit =
    val tree = toHashes(treeHashes(full, optimizedSealed))
    val bridge = toHashes(bridgeHashes)
    val problems = List.newBuilder[String]
    if tree.names.keySet != bridge.names.keySet then
      val onlyTree = (tree.names.keySet -- bridge.names.keySet).map(show).toList.sorted
      val onlyBridge = (bridge.names.keySet -- tree.names.keySet).map(show).toList.sorted
      problems += s"name keys differ: only Zinc's ${onlyTree.mkString(", ")}; only the bridge's ${onlyBridge.mkString(", ")}"
    if tree.hasMacro != bridge.hasMacro then
      problems += s"hasMacro: Zinc ${tree.hasMacro}, bridge ${bridge.hasMacro}"
    thinDifference(APIUtil.minimize(full), thin).foreach(problems += _)
    classesChecked.incrementAndGet()
    val key = s"$outputKey|${full.definitionType}|${full.name}"
    Option(previous.put(key, (tree, bridge))).foreach { case (prevTree, prevBridge) =>
      comparisons.incrementAndGet()
      def agree(what: String, treeChanged: Boolean, bridgeChanged: Boolean): Unit =
        if treeChanged != bridgeChanged then
          problems += s"$what: Zinc says ${changed(treeChanged)}, the bridge ${changed(bridgeChanged)}"
      agree("apiHash", prevTree.api != tree.api, prevBridge.api != bridge.api)
      agree("extraHash", prevTree.extra != tree.extra, prevBridge.extra != bridge.extra)
      val keys = prevTree.names.keySet ++ tree.names.keySet ++ prevBridge.names.keySet ++
        bridge.names.keySet
      keys.toList.sortBy(show).foreach { k =>
        agree(
          s"name ${show(k)}",
          prevTree.names.get(k) != tree.names.get(k),
          prevBridge.names.get(k) != bridge.names.get(k)
        )
      }
    }
    val ps = problems.result()
    if ps.nonEmpty then
      disagreements.addAndGet(ps.size.toLong)
      val lines = ps.map(p => s"[api-check] ${full.name} (${full.definitionType}): $p")
      lines.foreach(log.warn(_))
      Option(System.getProperty("xsbt.api.check.report")).foreach { f =>
        ApiHashCheck.synchronized {
          Files.write(
            Paths.get(f),
            lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }
      }
  end check

  def reportSummary(log: Logger): Unit =
    log.info(summary)
    Option(System.getProperty("xsbt.api.check.report")).foreach { f =>
      Files.write(
        Paths.get(f + ".summary"),
        (summary + "\n").getBytes(StandardCharsets.UTF_8)
      )
    }

  private def changed(b: Boolean) = if b then "changed" else "unchanged"
  private def show(k: (String, UseScope)) = s"${k._1}/${k._2}"

  private def thinDifference(expected: ClassLike, actual: ClassLike): Option[String] =
    def digest(c: ClassLike) = HashAPI(_.hashDefinition(c), includePrivate = true)
    def names(ds: Array[? <: xsbti.api.Definition]) = ds.map(_.name).toList.sorted
    if digest(expected) == digest(actual) &&
      expected.savedAnnotations.toSet == actual.savedAnnotations.toSet &&
      expected.topLevel == actual.topLevel
    then None
    else
      val e = expected.structure
      val a = actual.structure
      def diff(what: String, x: List[String], y: List[String]) =
        if x == y then Nil else List(s"$what: minimize ${x.diff(y)}, bridge ${y.diff(x)}")
      val details =
        diff("declared", names(e.declared), names(a.declared)) ++
          diff("inherited", names(e.inherited), names(a.inherited)) ++
          diff(
            "savedAnnotations",
            expected.savedAnnotations.toList.sorted,
            actual.savedAnnotations.toList.sorted
          )
      Some(s"thin class differs from minimize: ${if details.isEmpty then "in signatures, modifiers or annotations" else details.mkString("; ")}")
end ApiHashCheck
