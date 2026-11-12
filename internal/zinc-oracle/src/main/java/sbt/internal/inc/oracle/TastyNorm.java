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

package sbt.internal.inc.oracle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the container of a {@code .tasty} file, far enough to say which of its sections differ
 * from another's. It doesn't read what is in a section, so it needs no Scala 3 compiler.
 *
 * <p>A {@code .tasty} file is a header that ends in a UUID, a table of names, and then named
 * sections. The UUID is a hash of the rest, so it differs whenever anything does, and the class's
 * {@code TASTY} attribute repeats it. Two files that differ only in {@link #BENIGN} sections
 * describe the same program: positions and comments move when a source file is edited above a
 * definition, or compiled in a different batch.
 */
public final class TastyNorm {
  private TastyNorm() {}

  /** A section's name and the digest of its bytes, as 16 hex digits of SHA-256. */
  public record SectionDigest(String name, String digest) {}

  /**
   * A {@code .tasty} file's UUID in hex, and the digest of each section in the order they are in
   * the file. The header without its UUID is the section {@code <header>} and the name table
   * {@code <names>}.
   */
  public record Tasty(String uuid, List<SectionDigest> sections) {
    public Tasty {
      sections = List.copyOf(sections);
    }
  }

  /** Where a section's bytes are: {@code start} inclusive, {@code end} exclusive. */
  public record Section(String name, int start, int end) {}

  public static final String HEADER = "<header>";
  public static final String NAMES = "<names>";

  /** Sections that may differ between two files for the same program. */
  public static final Set<String> BENIGN = Set.of("Positions", "Comments");

  public static Tasty read(byte[] bytes) {
    List<Section> sections = layout(bytes);
    int uuidStart = sections.get(0).end();
    return new Tasty(
        ClassfileNorm.hex(Arrays.copyOfRange(bytes, uuidStart, uuidStart + UUID_LENGTH)),
        sections.stream()
            .map(
                s ->
                    new SectionDigest(
                        s.name(), ClassfileNorm.sha(Arrays.copyOfRange(bytes, s.start(), s.end()))))
            .toList());
  }

  /**
   * The sections of a {@code .tasty} file, {@code <header>} and {@code <names>} first. The UUID is
   * the 16 bytes after {@code <header>}, and in no section.
   *
   * @throws IllegalArgumentException if {@code bytes} are not a TASTy file
   */
  public static List<Section> layout(byte[] bytes) {
    Cursor in = new Cursor(bytes);
    for (int m : MAGIC) if (in.readByte() != m) throw in.fail("bad magic");
    for (int i = 0; i < 3; i++) in.nat();
    in.skip(in.nat());
    Section header = new Section(HEADER, 0, in.pos);
    in.skip(UUID_LENGTH);

    int namesStart = in.pos;
    int namesLength = in.nat();
    int namesEnd = in.pos + namesLength;
    if (namesEnd > bytes.length) throw in.fail("truncated");
    List<String> names = new ArrayList<>();
    while (in.pos < namesEnd) {
      int tag = in.readByte();
      int length = in.nat();
      int start = in.pos;
      in.skip(length);
      names.add(
          tag == UTF8
              ? new String(bytes, start, length, StandardCharsets.UTF_8)
              : "<name tag " + tag + ">");
    }

    List<Section> sections = new ArrayList<>();
    sections.add(header);
    sections.add(new Section(NAMES, namesStart, namesEnd));
    while (in.pos < bytes.length) {
      int ref = in.nat();
      int length = in.nat();
      int start = in.pos;
      in.skip(length);
      String name = ref >= 0 && ref < names.size() ? names.get(ref) : "<name " + ref + ">";
      sections.add(new Section(name, start, in.pos));
    }
    return List.copyOf(sections);
  }

  /** The sections that differ, or that only one of the two has. */
  public static Set<String> differing(Tasty a, Tasty b) {
    Set<SectionDigest> x = new HashSet<>(a.sections());
    Set<SectionDigest> y = new HashSet<>(b.sections());
    Set<String> out = new TreeSet<>();
    for (SectionDigest s : x) if (!y.contains(s)) out.add(s.name());
    for (SectionDigest s : y) if (!x.contains(s)) out.add(s.name());
    return out;
  }

  /** Whether the two differ in {@link #BENIGN} sections at most. Their UUIDs may differ. */
  public static boolean benign(Tasty a, Tasty b) {
    return BENIGN.containsAll(differing(a, b));
  }

  private static final int[] MAGIC = {0x5c, 0xa1, 0xab, 0x1f};
  private static final int UUID_LENGTH = 16;
  private static final int UTF8 = 1;

  private static final class Cursor {
    private final byte[] bytes;
    int pos = 0;

    Cursor(byte[] bytes) {
      this.bytes = bytes;
    }

    IllegalArgumentException fail(String what) {
      return new IllegalArgumentException("not a TASTy file: " + what + " at " + pos);
    }

    int readByte() {
      if (pos >= bytes.length) throw fail("truncated");
      int b = bytes[pos] & 0xff;
      pos += 1;
      return b;
    }

    int nat() {
      int x = 0;
      int b;
      do {
        b = readByte();
        x = (x << 7) | (b & 0x7f);
      } while ((b & 0x80) == 0);
      return x;
    }

    void skip(int length) {
      if (length < 0 || pos + length > bytes.length) throw fail("truncated");
      pos += length;
    }
  }
}
