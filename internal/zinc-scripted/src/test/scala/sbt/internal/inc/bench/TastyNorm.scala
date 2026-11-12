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

import java.security.MessageDigest

/**
 * Reads the container of a `.tasty` file, far enough to say which of its sections differ from
 * another's. It doesn't read what is in a section, so it needs no Scala 3 compiler.
 *
 * A `.tasty` file is a header that ends in a UUID, a table of names, and then named sections.
 * The UUID is a hash of the rest, so it differs whenever anything does, and the class's `TASTY`
 * attribute repeats it. Two files that differ only in [[Benign]] sections describe the same
 * program: positions and comments move when a source file is edited above a definition, or
 * compiled in a different batch.
 */
object TastyNorm:

  /**
   * A `.tasty` file's UUID in hex, and the digest of each section in the order they are in the
   * file. The header without its UUID is the section `<header>` and the name table `<names>`.
   */
  final case class Tasty(uuid: String, sections: Seq[(String, String)])

  /** Where a section's bytes are: `start` inclusive, `end` exclusive. */
  final case class Section(name: String, start: Int, end: Int)

  final val Header = "<header>"
  final val Names = "<names>"

  /** Sections that may differ between two files for the same program. */
  val Benign: Set[String] = Set("Positions", "Comments")

  def read(bytes: Array[Byte]): Tasty =
    val sections = layout(bytes)
    val uuidStart = sections.head.end
    Tasty(
      hex(bytes.slice(uuidStart, uuidStart + UuidLength)),
      sections.map(s => s.name -> sha(bytes.slice(s.start, s.end)))
    )

  /**
   * The sections of a `.tasty` file, `<header>` and `<names>` first. The UUID is the 16 bytes
   * after `<header>`, and in no section.
   */
  def layout(bytes: Array[Byte]): Seq[Section] =
    var pos = 0
    def fail(what: String) = throw IllegalArgumentException(s"not a TASTy file: $what at $pos")
    def byte(): Int =
      if pos >= bytes.length then fail("truncated")
      val b = bytes(pos) & 0xff
      pos += 1
      b
    def nat(): Int =
      var x = 0
      var b = 0
      while
        b = byte()
        x = (x << 7) | (b & 0x7f)
        (b & 0x80) == 0
      do ()
      x
    def skip(length: Int): Unit =
      if length < 0 || pos + length > bytes.length then fail("truncated")
      pos += length

    for m <- Magic do if byte() != m then fail("bad magic")
    for _ <- 0 until 3 do nat()
    skip(nat())
    val header = Section(Header, 0, pos)
    skip(UuidLength)

    val namesStart = pos
    val namesEnd = locally:
      val length = nat()
      pos + length
    if namesEnd > bytes.length then fail("truncated")
    val names = Vector.newBuilder[String]
    while pos < namesEnd do
      val tag = byte()
      val length = nat()
      val start = pos
      skip(length)
      names += (if tag == Utf8 then String(bytes, start, length, "UTF-8") else s"<name tag $tag>")
    val nameTable = names.result()

    val sections = Vector.newBuilder[Section]
    while pos < bytes.length do
      val ref = nat()
      val length = nat()
      val start = pos
      skip(length)
      sections += Section(nameTable.applyOrElse(ref, i => s"<name $i>"), start, pos)
    header +: Section(Names, namesStart, namesEnd) +: sections.result()
  end layout

  /** The sections that differ, or that only one of the two has. */
  def differing(a: Tasty, b: Tasty): Set[String] =
    val (x, y) = (a.sections.toSet, b.sections.toSet)
    ((x -- y) ++ (y -- x)).map(_._1)

  /** Whether the two differ in [[Benign]] sections at most. Their UUIDs may differ. */
  def benign(a: Tasty, b: Tasty): Boolean = differing(a, b).subsetOf(Benign)

  private final val Magic = Seq(0x5c, 0xa1, 0xab, 0x1f)
  private final val UuidLength = 16
  private final val Utf8 = 1

  private def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  private def sha(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(16)
end TastyNorm
