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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** The git command line, run in a repository. */
final class Git {
  private Git() {}

  /** What `git -C repo args...` prints; a failing command is a {@link Proc.Failure}. */
  static String run(Path repo, String... args) throws IOException {
    return new String(bytes(repo, args), StandardCharsets.UTF_8);
  }

  /** The content of a blob. */
  static byte[] blob(Path repo, String sha) throws IOException {
    return bytes(repo, "cat-file", "blob", sha);
  }

  /** The first parent of a commit. */
  static String parent(Path repo, String commit) throws IOException {
    return run(repo, "rev-parse", commit + "^1").strip();
  }

  /** The name git gives a blob with this content, so that a file can be looked for in a tree. */
  static String blobSha(byte[] data) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(sha1.digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] bytes(Path repo, String... args) throws IOException {
    List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    Proc.Result r = Proc.run(null, command);
    if (r.exit() != 0) {
      throw new Proc.Failure(String.join(" ", command) + " failed (exit " + r.exit() + "):\n" + r.err());
    }
    return r.stdout();
  }
}
