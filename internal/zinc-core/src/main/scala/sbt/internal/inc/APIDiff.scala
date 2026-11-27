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

import xsbt.api.DefaultShowAPI

import xsbti.api.Companions

/**
 * A class which computes diffs (unified diffs) between two textual representations of an API.
 *
 */
private[inc] class APIDiff:

  /**
   * Generates an unified diff between textual representations of `api1` and `api2`.
   */
  def generateApiDiff(
      fileName: String,
      api1: Companions,
      api2: Companions,
      contextSize: Int
  ): String =
    val api1Str = DefaultShowAPI(api1.classApi) + "\n" + DefaultShowAPI(api1.objectApi)
    val api2Str = DefaultShowAPI(api2.classApi) + "\n" + DefaultShowAPI(api2.objectApi)
    APIDiff.DiffUtil.mkColoredCodeDiff(api2Str, api1Str, printDiffDel = true)
end APIDiff

private[inc] object APIDiff:

  /**
   * Derived from Dotty's DiffUtil, rewritten for speed with the same alignment and output:
   * [[https://github.com/lampepfl/dotty/blob/0.1.2-RC1/compiler/src/dotty/tools/dotc/util/DiffUtil.scala]]
   *
   * Copyright (c) 2014, EPFL
   *
   * All rights reserved.
   * Redistribution and use in source and binary forms, with or without modification, are
   * permitted provided that the following conditions are met:
   *
   * 1. Redistributions of source code must retain the above copyright notice, this list of
   * conditions and the following disclaimer.
   * 2. Redistributions in binary form must reproduce the above copyright notice, this list
   * of conditions and the following disclaimer in the documentation and/or other materials
   * provided with the distribution.
   * 3. Neither the name of the copyright holder nor the names of its contributors may be used
   * to endorse or promote products derived from this software without specific prior written
   * permission.
   *
   * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
   * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
   * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
   * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
   * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
   * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
   * OF THE POSSIBILITY OF SUCH DAMAGE.
   */
  private[inc] object DiffUtil:

    private final val ANSI_DEFAULT = "\u001B[0m"
    private final val ANSI_RED = "\u001B[31m"
    private final val ANSI_GREEN = "\u001B[32m"

    private final val DELETION_COLOR = ANSI_RED
    private final val ADDITION_COLOR = ANSI_GREEN

    private def isWordChar(c: Char): Boolean =
      Character.isAlphabetic(c.toInt) || Character.isDigit(c.toInt)

    private def isSingleCharToken(c: Char): Boolean =
      Character.isMirrored(c.toInt) || Character.isWhitespace(c.toInt)

    private def splitTokens(str: String): Array[String] =
      val tokens = Array.newBuilder[String]
      val n = str.length
      var start = 0
      while start < n do
        val c = str.charAt(start)
        var end = start + 1
        if isWordChar(c) then while end < n && isWordChar(str.charAt(end)) do end += 1
        else if !isSingleCharToken(c) then
          while end < n && !isWordChar(str.charAt(end)) && !isSingleCharToken(str.charAt(end)) do
            end += 1
        tokens += str.substring(start, end)
        start = end
      tokens.result()

    def mkColoredCodeDiff(code: String, lastCode: String, printDiffDel: Boolean): String =
      mkColoredCodeDiff(code, lastCode, printDiffDel, InitialBand)

    private[inc] def mkColoredCodeDiff(
        code: String,
        lastCode: String,
        printDiffDel: Boolean,
        initialBand: Int
    ): String =
      if code.nonEmpty && code == lastCode then code
      else
        val tokens = splitTokens(code)
        val lastTokens = splitTokens(lastCode)

        hirschberg(lastTokens, tokens, initialBand, new Render(printDiffDel)).result

    /** Appends the coloured text of each patch, as Dotty's `Patch` cases were rendered. */
    private final class Render(printDiffDel: Boolean):
      private val sb = new java.lang.StringBuilder

      private def appendAll(tokens: Array[String], from: Int, until: Int): Unit =
        var i = from
        while i < until do
          sb.append(tokens(i))
          i += 1

      def unmodified(str: String): Unit = sb.append(str)

      def inserted(tokens: Array[String], from: Int, until: Int): Unit =
        sb.append(ADDITION_COLOR)
        appendAll(tokens, from, until)
        sb.append(ANSI_DEFAULT)

      def deleted(tokens: Array[String], from: Int, until: Int): Unit =
        if printDiffDel then
          sb.append(DELETION_COLOR)
          appendAll(tokens, from, until)
          sb.append(ANSI_DEFAULT)

      def modified(original: String, str: String): Unit =
        if printDiffDel then sb.append(DELETION_COLOR).append(original)
        sb.append(ADDITION_COLOR).append(str).append(ANSI_DEFAULT)

      def result: String = sb.toString
    end Render

    private final val InitialBand = 16

    /**
     * Hirschberg's linear-space alignment of `a` against `b`.
     *
     * Tokens are interned to `Int`s so the inner loops compare primitives, and the score rows are
     * allocated once and reused at every level of the recursion. The split point and the base
     * case break ties exactly as Dotty's original did, so the alignment is unchanged.
     *
     * The split point is first sought with scores restricted to a band around the diagonal, which
     * costs time proportional to the band rather than to `b`. Bounds on the scores of alignments
     * that leave the band show whether the banded scores already determine the first maximum
     * exactly; when they do not, the band is widened, up to the whole matrix.
     */
    private def hirschberg(
        a: Array[String],
        b: Array[String],
        initialBand: Int,
        out: Render
    ): Render =
      val ids = new java.util.HashMap[String, Integer]
      def intern(tokens: Array[String]): Array[Int] =
        val out = new Array[Int](tokens.length)
        var i = 0
        while i < tokens.length do
          val id = ids.putIfAbsent(tokens(i), ids.size)
          out(i) = if id == null then ids.size - 1 else id.intValue
          i += 1
        out
      def reverse(xs: Array[Int]): Array[Int] =
        val out = new Array[Int](xs.length)
        var i = 0
        while i < xs.length do
          out(i) = xs(xs.length - 1 - i)
          i += 1
        out
      val x = intern(a)
      val y = intern(b)
      val xr = reverse(x)
      val yr = reverse(y)
      val n = x.length
      val m = y.length
      val forward = new Array[Int](m + 1)
      val backward = new Array[Int](m + 1)

      def split(xlo: Int, xmid: Int, xhi: Int, ylo: Int, yhi: Int): Int =
        val ylen = yhi - ylo
        val p1 = xmid - xlo
        val p2 = xhi - xmid
        val full = math.max(math.max(p1, p2), ylen)
        var band = math.max(1, math.min(initialBand, full))
        var ymid = -1
        while ymid < 0 do
          nwScore(x, xlo, xmid, y, ylo, yhi, band, forward)
          nwScore(xr, n - xhi, n - xmid, yr, m - yhi, m - ylo, band, backward)
          val lower = lowerBound(ylen)
          ymid = if lower == Int.MinValue then -1 else exactSplit(p1, p2, ylen, band, lower)
          band = if band * 8 >= full then full else band * 4
        ymid

      def lowerBound(ylen: Int): Int =
        var lower = Int.MinValue
        var j = 0
        while j <= ylen do
          val f = forward(j)
          val b = backward(ylen - j)
          if f != Unreachable && b != Unreachable && f + b > lower then lower = f + b
          j += 1
        lower

      def exactSplit(p1: Int, p2: Int, ylen: Int, band: Int, lower: Int): Int =
        var best = Int.MinValue
        var ymid = -1
        var j = 0
        while j <= ylen do
          val f = forward(j)
          val b = backward(ylen - j)
          val fu = upperBound(p1, j, f, band)
          val bu = upperBound(p2, ylen - j, b, band)
          if fu + bu >= lower then
            if fu != f || bu != b then return -1
            if f + b > best then
              best = f + b
              ymid = j
          j += 1
        ymid
      end exactSplit

      def build(xlo: Int, xhi: Int, ylo: Int, yhi: Int): Unit =
        if xlo == xhi then out.inserted(b, ylo, yhi)
        else if ylo == yhi then out.deleted(a, xlo, xhi)
        else if xhi - xlo == 1 || yhi - ylo == 1 then
          needlemanWunsch(a, b, x, y, xlo, xhi, ylo, yhi, out)
        else
          val xmid = xlo + (xhi - xlo) / 2
          val ymid = split(xlo, xmid, xhi, ylo, yhi)
          build(xlo, xmid, ylo, ylo + ymid)
          build(xmid, xhi, ylo + ymid, yhi)

      build(0, n, 0, m)
      out
    end hirschberg

    private final val Gap = -2
    private final val Match = 2
    private final val Mismatch = -1
    private final val Unreachable = Int.MinValue / 4

    /**
     * An upper bound on the best score aligning `p` tokens with `k` tokens, given the best score
     * `banded` over the alignments that stay within `band` of the diagonal (or `Unreachable`).
     *
     * An alignment with `g` gaps has `(p + k - g) / 2` pairs and scores at most `p + k - 3 * g`. It
     * has at least `|p - k|` gaps, and at least `2 * band + 2 - |p - k|` if it leaves the band.
     */
    private def upperBound(p: Int, k: Int, banded: Int, band: Int): Int =
      val dev = math.abs(p - k)
      if band >= p && band >= k then banded
      else if dev > band then p + k - 3 * dev
      else math.max(banded, p + k - 3 * (2 * band + 2 - dev))

    /**
     * Sets `row(j)`, for `j` in `0 to yhi - ylo`, to the best score aligning `x(xlo until xhi)`
     * with `y(ylo until ylo + j)` over the alignments that stay within `band` of the diagonal, or
     * to `Unreachable` outside the band.
     */
    private def nwScore(
        x: Array[Int],
        xlo: Int,
        xhi: Int,
        y: Array[Int],
        ylo: Int,
        yhi: Int,
        band: Int,
        row: Array[Int]
    ): Unit =
      val ylen = yhi - ylo
      val p = xhi - xlo
      row(0) = 0
      var j = 1
      while j <= ylen do
        row(j) = if j <= band then row(j - 1) + Gap else Unreachable
        j += 1
      var i = 1
      while i <= p do
        val xi = x(xlo + i - 1)
        val lo = math.max(0, i - band)
        val hi = math.min(ylen, i + band)
        var diag = 0
        var left = Unreachable
        if lo > ylen then
          if lo - 1 == ylen then row(ylen) = Unreachable
          j = hi + 1
        else if lo == 0 then
          diag = row(0)
          left = row(0) + Gap
          row(0) = left
          j = 1
        else
          diag = row(lo - 1)
          row(lo - 1) = Unreachable
          j = lo
        while j <= hi do
          val up = row(j)
          val sub = diag + (if xi == y(ylo + j - 1) then Match else Mismatch)
          val score = math.max(math.max(sub, up + Gap), left + Gap)
          row(j) = score
          left = score
          diag = up
          j += 1
        i += 1
      end while
    end nwScore

    private final val Pair: Byte = 0
    private final val Delete: Byte = 1
    private final val Insert: Byte = 2

    private def needlemanWunsch(
        a: Array[String],
        b: Array[String],
        x: Array[Int],
        y: Array[Int],
        xlo: Int,
        xhi: Int,
        ylo: Int,
        yhi: Int,
        out: Render
    ): Unit =
      val n = xhi - xlo
      val m = yhi - ylo
      val w = m + 1
      val d = 1
      def similarity(i: Int, j: Int) = if x(xlo + i - 1) == y(ylo + j - 1) then 2 else -1
      val score = new Array[Int]((n + 1) * w)
      var j = 0
      while j <= m do
        score(j) = d * j
        j += 1
      var i = 1
      while i <= n do
        score(i * w) = d * i
        j = 1
        while j <= m do
          val mtch = score((i - 1) * w + j - 1) + similarity(i, j)
          val delete = score((i - 1) * w + j) + d
          val insert = score(i * w + j - 1) + d
          score(i * w + j) = math.max(math.max(mtch, insert), delete)
          j += 1
        i += 1

      val ops = new Array[Byte](n + m)
      var k = 0
      i = n
      j = m
      while i > 0 || j > 0 do
        if i > 0 && j > 0 && score(i * w + j) == score((i - 1) * w + j - 1) + similarity(i, j) then
          ops(k) = Pair
          i = i - 1
          j = j - 1
        else if i > 0 && score(i * w + j) == score((i - 1) * w + j) + d then
          ops(k) = Delete
          i = i - 1
        else
          ops(k) = Insert
          j = j - 1
        k += 1
      while k > 0 do
        k -= 1
        ops(k) match
          case Pair =>
            if x(xlo + i) == y(ylo + j) then out.unmodified(a(xlo + i))
            else out.modified(a(xlo + i), b(ylo + j))
            i += 1
            j += 1
          case Delete =>
            out.deleted(a, xlo + i, xlo + i + 1)
            i += 1
          case _ =>
            out.inserted(b, ylo + j, ylo + j + 1)
            j += 1
    end needlemanWunsch
  end DiffUtil
end APIDiff
