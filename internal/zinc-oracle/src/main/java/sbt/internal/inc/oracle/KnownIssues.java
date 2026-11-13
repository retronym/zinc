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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Matches a difference from the clean build against the causes already understood, so that a
 * report can set those aside and a reader's time goes to the ones that match nothing. An entry is
 * added when a replay's finding has been explained; each names where it was first seen.
 */
public final class KnownIssues {
  private KnownIssues() {}

  /** A cause already understood: a short id, and what it is in a sentence. */
  public record Issue(String id, String title) {
    public String label() {
      return "`" + id + "`: " + title;
    }
  }

  /**
   * scalac writes a class's specialised forwarders ({@code combineN$mcI$sp}) only if the
   * specialised variants of its parents are compiled in the same run. First seen in cats'
   * {@code SortedSetSemilattice} when {@code SortedSetInstances.scala} is compiled alone.
   */
  public static final Issue SPECIALISATION_BATCH =
      new Issue(
          "scalac-specialisation-batch",
          "specialised members of a recompiled class depend on what scalac compiles with it");

  /**
   * Zinc hashes a member's dealiased type, so changing it from one alias of a type to another
   * invalidates nothing, and a dependent that inferred its own type from it keeps the old name in
   * its signature. First seen in pekko's {@code UdpSender}, {@code immutable.Traversable} to
   * {@code immutable.Iterable}.
   */
  public static final Issue SIGNATURE_ONLY_STALE =
      new Issue(
          "zinc-signature-only-stale",
          "a class that was not recompiled has an out-of-date Scala signature and the same bytecode,"
              + " as when a type it infers changes between aliases of one type");

  /**
   * The importer of a package that no longer exists is not recompiled. Zinc's pending scripted
   * test source-dependencies/import-package.
   */
  public static final Issue IMPORT_REMOVED_PACKAGE =
      new Issue(
          "zinc-import-removed-package",
          "a source that imports a package or object that a change removed was not recompiled"
              + " (Zinc's pending scripted test source-dependencies/import-package)");

  /**
   * The issue a differing class matches, if any. `fresh` is whether the incremental compile wrote
   * the class in the step; `diff` is the textual difference of the two classfiles, which may be
   * empty when none was kept.
   */
  public static Optional<Issue> ofDifference(boolean fresh, boolean pickleOnly, List<String> diff) {
    if (pickleOnly && !fresh) {
      return Optional.of(SIGNATURE_ONLY_STALE);
    }
    if (fresh && diff.stream().anyMatch(l -> l.contains("$mc") && l.contains("$sp"))) {
      return Optional.of(SPECIALISATION_BATCH);
    }
    return Optional.empty();
  }

  /** As {@link #ofDifference(boolean, boolean, List)}, reading the difference from a file if it exists. */
  public static Optional<Issue> ofDifference(boolean fresh, boolean pickleOnly, Path diffFile) {
    List<String> diff = List.of();
    if (diffFile != null && Files.isRegularFile(diffFile)) {
      try {
        diff = Files.readAllLines(diffFile);
      } catch (IOException e) {
        diff = List.of();
      }
    }
    return ofDifference(fresh, pickleOnly, diff);
  }

  /** The issue that a clean build's errors match, when the incremental compile had none. */
  public static Optional<Issue> ofMissedError(String cleanErrors) {
    if (cleanErrors != null && cleanErrors.contains("is not a member of")) {
      return Optional.of(IMPORT_REMOVED_PACKAGE);
    }
    return Optional.empty();
  }
}
