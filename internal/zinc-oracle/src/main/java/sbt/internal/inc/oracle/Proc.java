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

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What the history tools share that is not about git or IncHistory: running a command to its end,
 * copying and deleting directory trees, reading a command line, and writing JSON the way the
 * Python tools these replace did, so that their files stay comparable.
 */
final class Proc {
  private Proc() {}

  /** An error the user can act on: `main` prints its message and exits with status 1. */
  static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Failure(String message) {
      super(message);
    }
  }

  /** A finished command: its exit status and everything it wrote. */
  record Result(int exit, byte[] stdout, byte[] stderr) {
    String out() {
      return new String(stdout, StandardCharsets.UTF_8);
    }

    String err() {
      return new String(stderr, StandardCharsets.UTF_8);
    }
  }

  static Result run(Path cwd, List<String> command) throws IOException {
    return run(cwd, command, null);
  }

  /**
   * Runs `command` in `cwd` with no input and waits for it. With a `timeout`, a command that is
   * still running when it ends is killed and null is returned.
   */
  static Result run(Path cwd, List<String> command, Duration timeout) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(devNull()));
    if (cwd != null) builder.directory(cwd.toFile());
    Process process = builder.start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread outReader = drain(process.getInputStream(), out);
    Thread errReader = drain(process.getErrorStream(), err);
    try {
      if (timeout == null) {
        process.waitFor();
      } else if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return null;
      }
      outReader.join();
      errReader.join();
      return new Result(process.exitValue(), out.toByteArray(), err.toByteArray());
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new Failure("interrupted while running " + command.get(0));
    }
  }

  private static java.io.File devNull() {
    return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
  }

  private static Thread drain(InputStream from, ByteArrayOutputStream to) {
    Thread thread = new Thread(() -> {
      try (from) {
        from.transferTo(to);
      } catch (IOException e) {
        to.writeBytes(("\n" + e).getBytes(StandardCharsets.UTF_8));
      }
    });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** The last `n` characters of `s`. */
  static String tail(String s, int n) {
    return s.length() <= n ? s : s.substring(s.length() - n);
  }

  /** The first `n` code points of `s`. */
  static String head(String s, int n) {
    return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
  }

  /** A list of strings as Python prints it, which the tools' log lines and messages used. */
  static String repr(List<String> items) {
    StringBuilder sb = new StringBuilder("[");
    for (String item : items) {
      if (sb.length() > 1) sb.append(", ");
      sb.append('\'').append(item).append('\'');
    }
    return sb.append(']').toString();
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(root)) {
      paths = walk.sorted(Comparator.reverseOrder()).toList();
    }
    for (Path p : paths) Files.deleteIfExists(p);
  }

  /** Copies a directory tree, with file times, leaving out anything named `target`. */
  static void copyTree(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    Files.walkFileTree(from, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (!dir.equals(from) && dir.getFileName().toString().equals("target")) return FileVisitResult.SKIP_SUBTREE;
        Files.createDirectory(to.resolve(from.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!file.getFileName().toString().equals("target")) copyFile(file, to.resolve(from.relativize(file).toString()));
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Copies a file with its times, replacing `to`. */
  static void copyFile(Path from, Path to) throws IOException {
    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  /** The regular files under `root`, as `/`-separated paths below it, sorted. */
  static List<String> files(Path root) throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .map(f -> root.relativize(f).toString().replace(java.io.File.separatorChar, '/'))
          .sorted()
          .toList();
    }
  }

  static List<String> strings(JsonValue array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) for (JsonValue v : array.asArray()) out.add(v.asString());
    return out;
  }

  static JsonArray array(Iterable<String> items) {
    JsonArray out = new JsonArray();
    for (String item : items) out.add(item);
    return out;
  }

  /**
   * A command line: positional arguments, options that take a value (`--name VALUE` or
   * `--name=VALUE`, repeatable) and flags.
   */
  static final class Args {
    final List<String> positional = new ArrayList<>();
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final Set<String> valued;
    private final Set<String> flags;

    private Args(Set<String> valued, Set<String> flags) {
      this.valued = valued;
      this.flags = flags;
    }

    /** Reads `argv`; `--help` prints `usage` and exits. */
    static Args parse(String[] argv, Set<String> valued, Set<String> flags, String usage) {
      Args args = new Args(valued, flags);
      for (int i = 0; i < argv.length; i++) {
        String arg = argv[i];
        if (arg.equals("--help") || arg.equals("-h")) {
          System.out.println(usage);
          System.exit(0);
        } else if (arg.startsWith("--")) {
          int eq = arg.indexOf('=');
          String name = eq < 0 ? arg : arg.substring(0, eq);
          if (flags.contains(name) && eq < 0) {
            args.values.computeIfAbsent(name, k -> new ArrayList<>()).add("");
          } else if (valued.contains(name)) {
            if (eq < 0 && i + 1 == argv.length) throw new Failure(name + " needs a value\n" + usage);
            args.values.computeIfAbsent(name, k -> new ArrayList<>()).add(eq < 0 ? argv[++i] : arg.substring(eq + 1));
          } else {
            throw new Failure("unknown option " + name + "\n" + usage);
          }
        } else {
          args.positional.add(arg);
        }
      }
      return args;
    }

    boolean flag(String name) {
      return values.containsKey(name);
    }

    List<String> all(String name) {
      return values.getOrDefault(name, List.of());
    }

    String last(String name, String otherwise) {
      List<String> given = all(name);
      return given.isEmpty() ? otherwise : given.get(given.size() - 1);
    }
  }

  /**
   * JSON text as Python's `json.dumps` writes it: `, ` and `: ` between items on one line, or
   * two-space indentation, and every character outside ASCII escaped.
   */
  static final class PyJson {
    private PyJson() {}

    static String line(JsonValue value) {
      StringBuilder sb = new StringBuilder();
      write(sb, value, -1);
      return sb.toString();
    }

    static String indented(JsonValue value) {
      StringBuilder sb = new StringBuilder();
      write(sb, value, 0);
      return sb.toString();
    }

    private static void write(StringBuilder sb, JsonValue value, int depth) {
      if (value.isString()) {
        string(sb, value.asString());
      } else if (value.isArray()) {
        JsonArray array = value.asArray();
        if (array.isEmpty()) {
          sb.append("[]");
          return;
        }
        sb.append('[');
        boolean first = true;
        for (JsonValue item : array) {
          separate(sb, first, depth);
          first = false;
          write(sb, item, depth < 0 ? depth : depth + 1);
        }
        close(sb, depth);
        sb.append(']');
      } else if (value.isObject()) {
        JsonObject object = value.asObject();
        if (object.isEmpty()) {
          sb.append("{}");
          return;
        }
        sb.append('{');
        boolean first = true;
        for (JsonObject.Member member : object) {
          separate(sb, first, depth);
          first = false;
          string(sb, member.getName());
          sb.append(": ");
          write(sb, member.getValue(), depth < 0 ? depth : depth + 1);
        }
        close(sb, depth);
        sb.append('}');
      } else {
        sb.append(value.toString());
      }
    }

    private static void separate(StringBuilder sb, boolean first, int depth) {
      if (depth < 0) {
        if (!first) sb.append(", ");
      } else {
        if (!first) sb.append(',');
        sb.append('\n').append("  ".repeat(depth + 1));
      }
    }

    private static void close(StringBuilder sb, int depth) {
      if (depth >= 0) sb.append('\n').append("  ".repeat(depth));
    }

    private static void string(StringBuilder sb, String s) {
      sb.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          case '\b' -> sb.append("\\b");
          case '\f' -> sb.append("\\f");
          default -> {
            if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
          }
        }
      }
      sb.append('"');
    }
  }
}
