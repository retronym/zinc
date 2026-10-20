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

import scala.collection.mutable
import scala.util.Random

/**
 * The order in which [[Conformance]] runs its cases, chosen for time to first bug rather than
 * for enumeration order.
 *
 * A case is a layout, a base program and one edit. Its factors are the base's cfg fields, the
 * layout, the field the edit changes and that field's new value. Interaction bugs usually need
 * two or three factors (`final` and a layout; an upstream abstract member and an override; a
 * class turned trait and a client in another subproject), so `covering` runs first a greedy
 * (AETG-style) set of cases that covers every pair of factor values that occurs, then every
 * triple. The rest follows in digit-reversed mixed-radix order of the base's cfg (the
 * categorical van der Corput sequence), so that any prefix is spread over every field; there a
 * base runs with all its remaining edits, to reuse its build.
 */
object ConformanceOrder:
  final case class Item(layout: String, base: Conformance.Base, edit: Conformance.EditCase):
    def group: (String, String) = (layout, base.id)

  /** The field an edit changes, and its new value. */
  def editKind(it: Item): (String, String) =
    it.base.factors
      .zip(it.edit.factors)
      .collectFirst { case ((k, v), (_, w)) if v != w => (k, w) }
      .getOrElse(("?", "?"))

  def factorValues(it: Item): Seq[String] =
    val (field, value) = editKind(it)
    it.base.factors.map(_._2) ++ Seq(it.layout, field, s"$field=$value")

  /** The cases in enumeration order: layouts, then bases, then edits, as the dump lists them. */
  def enumeration(bases: Seq[Conformance.Base], layouts: Seq[String]): IndexedSeq[Item] =
    (for l <- layouts; b <- bases; e <- b.edits yield Item(l, b, e)).toIndexedSeq

  /** Indices into `items` that cover every `t`-way combination of factor values, greedily. */
  def covering(items: IndexedSeq[Item], t: Int, seed: Long, from: Seq[Int]): Seq[Int] =
    val ids = mutable.HashMap.empty[(Int, String), Int]
    val coded = items.map { it =>
      factorValues(it).zipWithIndex.map((v, f) => ids.getOrElseUpdate((f, v), ids.size)).toArray
    }
    val width = math.max(ids.size, 1).toLong
    val tuples = coded.headOption.fold(Seq.empty[Seq[Int]])(c => c.indices.combinations(t).toSeq)
    def combos(i: Int): Iterator[Long] =
      val c = coded(i)
      tuples.iterator.map(_.foldLeft(0L)((acc, f) => acc * width + c(f)))
    val uncovered = mutable.HashSet.empty[Long]
    items.indices.foreach(i => combos(i).foreach(uncovered += _))
    val chosen = mutable.ArrayBuffer.empty[Int]
    val taken = mutable.HashSet.empty[Int]
    def take(i: Int): Unit =
      chosen += i
      taken += i
      combos(i).foreach(uncovered -= _)
    from.foreach(take)
    chosen.clear()
    val rnd = new Random(seed)
    val candidates = 200
    while uncovered.nonEmpty do
      var best = -1
      var bestGain = 0
      var k = 0
      while k < candidates do
        val i = rnd.nextInt(items.size)
        if !taken(i) then
          val gain = combos(i).count(uncovered)
          if gain > bestGain then
            best = i
            bestGain = gain
        k += 1
      if best < 0 then
        val target = uncovered.head
        best = items.indices.find(i => !taken(i) && combos(i).contains(target)).get
      take(best)
    chosen.toSeq
  end covering

  /** Bases sorted by their cfg digits reversed, so the first field varies fastest. */
  def reversedBases(bases: Seq[Conformance.Base]): Seq[Conformance.Base] =
    val domains = bases.headOption.fold(Seq.empty[mutable.LinkedHashMap[String, Int]])(b =>
      b.factors.map(_ => mutable.LinkedHashMap.empty[String, Int])
    )
    for b <- bases; ((_, v), d) <- b.factors.zip(domains) do d.getOrElseUpdate(v, d.size)
    def digits(b: Conformance.Base) = b.factors.zip(domains).map((f, d) => d(f._2)).reverse
    bases.sortWith { (a, b) =>
      val c = digits(a).zip(digits(b)).find(_ != _)
      c.exists((x, y) => x < y)
    }

  /**
   * The cases of one shard, in order. `covering`: the 2-way then 3-way covering prefix, dealt
   * to shards round-robin within each stratum of layout and edited field, then the remaining
   * (layout, base) groups in digit-reversed order, dealt round-robin within each layout.
   * `reversed`: only the latter. `enum`: enumeration order, dealt by base.
   */
  def order(
      bases: Seq[Conformance.Base],
      layouts: Seq[String],
      mode: String,
      seed: Long,
      shard: (Int, Int),
  ): Seq[Item] =
    val (me, n) = shard
    val items = enumeration(bases, layouts)
    mode match
      case "enum" =>
        val baseShard = bases.zipWithIndex.map((b, i) => b.id -> i % n).toMap
        items.filter(it => baseShard(it.base.id) == me)
      case "covering" | "reversed" =>
        val prefix =
          if mode == "reversed" then Seq.empty[Int]
          else
            val two = covering(items, 2, seed, Nil)
            val three = covering(items, 3, seed + 1, two)
            Console.err.println(
              s"covering prefix: ${two.size} cases for 2-way, ${three.size} more for 3-way, of ${items.size}"
            )
            two ++ three
        val inPrefix = prefix.toSet
        val strata = mutable.HashMap.empty[(String, String), Int]
        val mine = prefix.filter { i =>
          val it = items(i)
          val key = (it.layout, editKind(it)._1)
          val c = strata.getOrElse(key, key.hashCode & 0x7fffffff)
          strata(key) = c + 1
          c % n == me
        }.map(items)
        val byGroup = items.indices.filterNot(inPrefix).groupBy(i => items(i).group)
        val perLayout = mutable.HashMap.empty[String, Int]
        val rest =
          for
            b <- reversedBases(bases)
            l <- layouts
            is <- byGroup.get((l, b.id)).toSeq
            c = perLayout.getOrElse(l, 0)
            _ = perLayout(l) = c + 1
            if c % n == me
            i <- is
          yield items(i)
        mine ++ rest
      case other => sys.error(s"Unknown order $other")
    end match
  end order
end ConformanceOrder
