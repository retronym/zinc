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
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * Compares the classfiles and TASTy files an incremental compile left in a directory with those a
 * clean build of the same inputs wrote to another. Each file that is not the same on both sides is
 * a {@link Difference}, with the normalisation that makes the two equal if there is one: a
 * difference with none is one the incremental compiler has to answer for.
 *
 * <pre>
 * ClassDirDiff INCREMENTAL_DIR CLEAN_DIR [--json]
 * </pre>
 */
public final class ClassDirDiff {
  private ClassDirDiff() {}

  public enum Kind {
    /** Both sides have the file, with different bytes. */
    DIFFERS("differs"),

    /** Only the clean build has the file. */
    MISSING("missing"),

    /** Only the incremental build has the file. */
    EXTRA("extra");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * A file that is not the same in the two directories. {@code path} is relative to both, with
   * {@code /} between its parts. {@code level} is what {@link #explain} says of a file that
   * differs, and empty for one that is missing or extra. {@code pickleOnly} marks an unexplained
   * difference that is confined to the Scala signature (see {@link #onlyPickle}).
   */
  public record Difference(String path, Kind kind, Optional<String> level, boolean pickleOnly) {
    /** Whether no normalisation accounts for the difference. */
    public boolean unexplained() {
      return level.isEmpty();
    }

    public JsonObject json() {
      return Json.object()
          .add("path", path)
          .add("kind", kind.label())
          .add("level", level.map(Json::value).orElse(Json.NULL))
          .add("pickleOnly", pickleOnly);
    }
  }

  /** The level of a difference that is benign in the TASTy files rather than the classfiles. */
  public static final String TASTY = "tasty";

  /** Whether {@code file} is something a compiler writes that this compares. */
  public static boolean isProduct(Path file) {
    String name = file.getFileName().toString();
    return name.endsWith(".class") || name.endsWith(".tasty");
  }

  /**
   * The differences between the products under {@code incremental} and those under {@code clean},
   * in the order of their paths. A directory that doesn't exist has no products.
   */
  public static List<Difference> compare(Path incremental, Path clean) {
    Map<String, Path> inc = products(incremental);
    Map<String, Path> ref = products(clean);
    TreeSet<String> paths = new TreeSet<>(inc.keySet());
    paths.addAll(ref.keySet());
    List<Difference> out = new ArrayList<>();
    for (String path : paths) {
      Path a = inc.get(path);
      Path b = ref.get(path);
      if (a == null) out.add(new Difference(path, Kind.MISSING, Optional.empty(), false));
      else if (b == null) out.add(new Difference(path, Kind.EXTRA, Optional.empty(), false));
      else if (!Arrays.equals(read(a), read(b))) {
        Optional<String> level = explain(a, b);
        out.add(new Difference(path, Kind.DIFFERS, level, level.isEmpty() && onlyPickle(a, b)));
      }
    }
    return out;
  }

  /**
   * The normalisation that makes two products equal, if one does. For a classfile, the label of
   * the lowest {@link ClassfileNorm.Level}; when the classfiles also name different TASTy, the
   * TASTy files beside them must differ only in sections that hold no semantics, which is {@link
   * #TASTY}. A TASTy file is {@link #TASTY} under the same condition. A file that can't be read
   * has no explanation.
   */
  public static Optional<String> explain(Path incremental, Path clean) {
    try {
      if (incremental.getFileName().toString().endsWith(".tasty"))
        return tastyBenign(incremental, clean) ? Optional.of(TASTY) : Optional.empty();
      ClassfileNorm.Digests x = ClassfileNorm.digests(read(incremental));
      ClassfileNorm.Digests y = ClassfileNorm.digests(read(clean));
      Optional<ClassfileNorm.Level> level = ClassfileNorm.level(x, y);
      if (level.isEmpty()) return Optional.empty();
      if (x.tastyUuid().equals(y.tastyUuid())) return level.map(ClassfileNorm.Level::label);
      return tastyBenign(sibling(incremental), sibling(clean))
          ? Optional.of(TASTY)
          : Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Whether two classfiles differ in nothing but their Scala signature: the bytecode is the same,
   * and what differs is what the compiler recorded about types.
   */
  public static boolean onlyPickle(Path incremental, Path clean) {
    try {
      List<String> lines = ClassfileNorm.diff(read(incremental), read(clean), Integer.MAX_VALUE);
      return !lines.isEmpty()
          && lines.stream().allMatch(l -> l.substring(1).trim().startsWith(PICKLE_ANNOTATION));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Prints one line per difference and a summary, as text or with {@code --json} as one JSON
   * object per line. Returns 0 if every difference is explained, 1 if one is not, and 2 if the
   * arguments are wrong.
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    List<String> dirs = new ArrayList<>();
    boolean json = false;
    for (String arg : args) {
      if (arg.equals("--json")) json = true;
      else if (arg.startsWith("--")) {
        err.println("Unknown argument " + arg);
        return 2;
      } else dirs.add(arg);
    }
    if (dirs.size() != 2) {
      err.println("Usage: ClassDirDiff INCREMENTAL_DIR CLEAN_DIR [--json]");
      return 2;
    }
    List<Difference> differences = compare(Paths.get(dirs.get(0)), Paths.get(dirs.get(1)));
    long unexplained = differences.stream().filter(Difference::unexplained).count();
    long missing = differences.stream().filter(d -> d.kind() == Kind.MISSING).count();
    long extra = differences.stream().filter(d -> d.kind() == Kind.EXTRA).count();
    long pickleOnly = differences.stream().filter(Difference::pickleOnly).count();
    if (json) {
      for (Difference d : differences) out.println(d.json());
      JsonValue summary =
          Json.object()
              .add("differences", differences.size())
              .add("explained", differences.size() - unexplained)
              .add("unexplained", unexplained)
              .add("missing", missing)
              .add("extra", extra)
              .add("pickleOnly", pickleOnly);
      out.println(summary);
    } else {
      for (Difference d : differences)
        out.println(
            d.kind().label()
                + "\t"
                + d.level().orElse(d.kind() == Kind.DIFFERS ? "unexplained" : "-")
                + "\t"
                + d.path()
                + (d.pickleOnly() ? "\tpickle only" : ""));
      out.println(
          differences.size()
              + " differences: "
              + (differences.size() - unexplained)
              + " explained, "
              + unexplained
              + " unexplained ("
              + missing
              + " missing, "
              + extra
              + " extra, "
              + pickleOnly
              + " pickle only)");
    }
    return unexplained == 0 ? 0 : 1;
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  private static final String PICKLE_ANNOTATION = "@Lscala/reflect/Scala";

  private static Map<String, Path> products(Path root) {
    Map<String, Path> out = new TreeMap<>();
    if (!Files.exists(root)) return out;
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(f -> Files.isRegularFile(f) && isProduct(f))
          .forEach(f -> out.put(root.relativize(f).toString().replace('\\', '/'), f));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean tastyBenign(Path incremental, Path clean) {
    return Files.exists(incremental)
        && Files.exists(clean)
        && TastyNorm.benign(TastyNorm.read(read(incremental)), TastyNorm.read(read(clean)));
  }

  /** The TASTy file of the top-level class that the class in {@code file} belongs to. */
  private static Path sibling(Path file) {
    String name = file.getFileName().toString();
    int dollar = name.indexOf('$');
    String top = dollar < 0 ? name : name.substring(0, dollar);
    if (top.endsWith(".class")) top = top.substring(0, top.length() - ".class".length());
    return file.resolveSibling(top + ".tasty");
  }
}
