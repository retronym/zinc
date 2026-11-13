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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Freezes modules of an sbt build as a build IncBench can compile.
 *
 * <pre>
 *   Export SBT_PROJECT_DIR OUT_DIR MODULE... [--scala LABEL] [--sbt-arg ARG]...
 *          [--no-scala-instance] [--timeout SECONDS]
 * </pre>
 *
 * Asks sbt for each module's compile settings (`print`), then writes OUT_DIR as scripted's
 * IncHandler reads a build:
 *
 * <pre>
 *   OUT_DIR/build.json                     projects, their dependencies, the Scala label
 *   OUT_DIR/&lt;module&gt;/src/main/scala/...    the module's Scala sources, by path under its source root
 *   OUT_DIR/&lt;module&gt;/src/main/java/...     its Java sources
 *   OUT_DIR/&lt;module&gt;/lib/*.jar             its external dependency jars
 *   OUT_DIR/&lt;module&gt;/plugins/*.jar         compiler plugins the checkout builds itself
 *   OUT_DIR/&lt;module&gt;/incOptions.properties scalac.options and javac.options
 *   OUT_DIR/export.json                    where it came from: commit, settings, file counts
 * </pre>
 *
 * Sources are copied, so the build stays frozen whatever happens to the checkout. Paths into the
 * checkout in the options are rewritten to scripted's `[basedir]`, which IncHandler expands to the
 * module's directory. Modules depend on each other as sbt's internal dependency classpath says, as
 * long as the other module is exported too.
 *
 * <p>`--scala` is the scripted bridge label every module compiles with. `--no-scala-instance`
 * keeps the compiler's own jars off the classpath, for a build that compiles Scala itself.
 */
public final class Export {
  private Export() {}

  static final String USAGE =
      "Export SBT_PROJECT_DIR OUT_DIR MODULE... [--scala LABEL] [--sbt-arg ARG]... [--no-scala-instance] [--timeout SECONDS]";

  private static final List<String> KEYS = List.of(
      "scalacOptions",
      "javacOptions",
      "sources",
      "unmanagedSourceDirectories",
      "managedSourceDirectories",
      "externalDependencyClasspath",
      "internalDependencyClasspath",
      "classDirectory",
      "thisProject");
  private static final String MARK = "@@incbench-export@@";

  /** The sbt commands that print every setting of every module, each after a line that names it. */
  static List<String> commands(List<String> modules) {
    List<String> cmds = new ArrayList<>();
    for (String m : modules) {
      for (String k : KEYS) {
        String setting = k.equals("thisProject") ? m + "/" + k : m + "/Compile/" + k;
        cmds.add("eval println(\"" + MARK + " " + m + " " + k + "\")");
        cmds.add("print " + setting);
      }
    }
    cmds.add("eval println(\"" + MARK + " end end\")");
    return cmds;
  }

  /**
   * The values sbt printed, by `module key`: the lines between one marker and the next, without
   * sbt's own `[info]` lines and without the `* ` that `print` puts before each item of a list.
   */
  static Map<String, List<String>> parse(String stdout) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    String key = null;
    List<String> buf = new ArrayList<>();
    for (String line : stdout.lines().toList()) {
      if (line.startsWith(MARK)) {
        if (key != null) values.put(key, buf);
        String[] parts = line.strip().split("\\s+");
        if (parts.length != 3) throw new Proc.Failure("unexpected marker line from sbt: " + line);
        key = parts[1] + " " + parts[2];
        buf = new ArrayList<>();
      } else if (key != null && !line.startsWith("[")) {
        buf.add(line.startsWith("* ") ? line.substring(2) : line);
      }
    }
    return values;
  }

  private static Map<String, List<String>> sbtPrint(Path projectDir, List<String> modules, List<String> sbtArgs, int timeout)
      throws IOException {
    List<String> command = new ArrayList<>(List.of("sbt", "--batch", "--no-server", "--color=false"));
    command.addAll(sbtArgs);
    command.addAll(commands(modules));
    Proc.Result r = Proc.run(projectDir, command, Duration.ofSeconds(timeout));
    if (r == null) throw new Proc.Failure("sbt did not finish in " + timeout + "s");
    if (r.exit() != 0) {
      throw new Proc.Failure("sbt failed:\n" + Proc.tail(r.out(), 4000) + "\n" + Proc.tail(r.err(), 2000));
    }
    return parse(r.out());
  }

  /** `print` shows a classpath entry as `Attributed(<path>)`. */
  static String attributedPath(String entry) {
    return entry.startsWith("Attributed(") ? entry.substring("Attributed(".length(), entry.length() - 1) : entry;
  }

  private static List<String> get(Map<String, List<String>> values, String module, String key) {
    List<String> out = new ArrayList<>();
    for (String v : values.getOrDefault(module + " " + key, List.of())) if (!v.isBlank()) out.add(v);
    return out;
  }

  /**
   * An option as the frozen build takes it. A compiler plugin built by the checkout is frozen with
   * the module, like its jars; a path under a source directory becomes one under `[basedir]`; any
   * other mention of the checkout is refused.
   */
  private static String rewrite(String opt, String module, Path src, Path base, List<Path> roots) throws IOException {
    if (opt.startsWith("-Xplugin:") && opt.contains(src.toString())) {
      Path plugins = base.resolve("plugins");
      Files.createDirectories(plugins);
      List<String> kept = new ArrayList<>();
      for (String p : opt.substring("-Xplugin:".length()).split(",")) {
        Path jar = Path.of(p);
        Proc.copyFile(jar, plugins.resolve(jar.getFileName().toString()));
        kept.add("[basedir]/plugins/" + jar.getFileName());
      }
      return "-Xplugin:" + String.join(",", kept);
    }
    for (Path r : roots) {
      if (opt.startsWith(r.toString())) return "[basedir]/src/main/scala" + opt.substring(r.toString().length());
    }
    if (opt.contains(src.toString())) {
      throw new Proc.Failure(module + ": option " + opt + " names the checkout and isn't a source directory");
    }
    return opt;
  }

  private static int parts(Path p) {
    return p.getNameCount() + (p.getRoot() != null ? 1 : 0);
  }

  /**
   * Exports `modules` of the build in `projectDir` to `outDir`, replacing it, and returns one line
   * per module saying what was exported. A build that cannot be exported is a {@link Proc.Failure}.
   */
  static List<String> export(
      Path projectDir, Path outDir, List<String> modules, String scala, List<String> sbtArgs, boolean noScalaInstance, int timeout)
      throws IOException {
    Path src = projectDir.toAbsolutePath().normalize();
    Path out = outDir.toAbsolutePath().normalize();
    return write(src, out, modules, scala, noScalaInstance, sbtPrint(src, modules, sbtArgs, timeout));
  }

  /** Writes the frozen build from the values sbt printed. */
  static List<String> write(
      Path src, Path out, List<String> modules, String scala, boolean noScalaInstance, Map<String, List<String>> values)
      throws IOException {
    Proc.deleteTree(out);
    Files.createDirectories(out);
    Map<String, String> classDirs = new LinkedHashMap<>();
    for (String m : modules) {
      List<String> dir = get(values, m, "classDirectory");
      if (dir.isEmpty()) throw new Proc.Failure(m + ": sbt printed no classDirectory");
      classDirs.put(m, dir.get(0));
    }
    JsonArray projects = new JsonArray();
    JsonObject summary = new JsonObject();
    List<String> report = new ArrayList<>();
    for (String m : modules) {
      Path base = out.resolve(m);
      List<Path> roots = new ArrayList<>();
      for (String d : get(values, m, "unmanagedSourceDirectories")) roots.add(Path.of(d));
      for (String d : get(values, m, "managedSourceDirectories")) roots.add(Path.of(d));
      roots.sort(Comparator.comparingInt((Path p) -> -parts(p)));
      int scalaCount = 0;
      int javaCount = 0;
      for (String source : get(values, m, "sources")) {
        Path s = Path.of(source);
        Path root = roots.stream().filter(s::startsWith).findFirst().orElse(null);
        if (root == null) throw new Proc.Failure(m + ": " + s + " is under none of its source directories");
        boolean java = s.getFileName().toString().endsWith(".java");
        Path dest = base.resolve("src").resolve("main").resolve(java ? "java" : "scala").resolve(root.relativize(s).toString());
        Files.createDirectories(dest.getParent());
        Proc.copyFile(s, dest);
        if (java) javaCount++;
        else scalaCount++;
      }
      Path lib = base.resolve("lib");
      Files.createDirectories(lib);
      List<String> jars = new ArrayList<>();
      for (String e : get(values, m, "externalDependencyClasspath")) {
        Path j = Path.of(attributedPath(e));
        if (!Files.isRegularFile(j)) throw new Proc.Failure(m + ": dependency " + j + " is not a jar");
        Proc.copyFile(j, lib.resolve(j.getFileName().toString()));
        jars.add(j.getFileName().toString());
      }
      Set<String> internal = new java.util.HashSet<>();
      for (String e : get(values, m, "internalDependencyClasspath")) internal.add(attributedPath(e));
      List<String> deps = new ArrayList<>();
      for (String d : modules) if (!d.equals(m) && internal.contains(classDirs.get(d))) deps.add(d);

      List<String> scalac = new ArrayList<>();
      for (String o : get(values, m, "scalacOptions")) {
        String rewritten = rewrite(o, m, src, base, roots);
        scalac.add(o.startsWith("-Wconf:") ? rewritten.replace(" ", "\\s") : rewritten);
      }
      if (scala.startsWith("3") && !scalac.contains("-sourceroot")) {
        scalac.add("-sourceroot");
        scalac.add("[basedir]");
      }
      List<String> javac = get(values, m, "javacOptions");
      for (List<String> opts : List.of(scalac, javac)) {
        for (String o : opts) {
          if (o.contains(" ")) {
            throw new Proc.Failure(m + ": option '" + o + "' has a space, which incOptions.properties can't keep");
          }
        }
      }
      StringBuilder props = new StringBuilder("scalac.options = " + property(scalac) + "\n");
      if (!javac.isEmpty()) props.append("javac.options = ").append(property(javac)).append('\n');
      if (noScalaInstance) props.append("classpath.scalaInstance = false\n");
      Files.writeString(base.resolve("incOptions.properties"), props);

      projects.add(new JsonObject().add("name", m).add("dependsOn", Proc.array(deps)).add("scalaVersion", scala));
      summary.add(m, new JsonObject()
          .add("scala", scalaCount)
          .add("java", javaCount)
          .add("jars", Proc.array(jars))
          .add("dependsOn", Proc.array(deps))
          .add("scalacOptions", Proc.array(scalac))
          .add("javacOptions", Proc.array(javac)));
      report.add(m + ": " + scalaCount + " Scala, " + javaCount + " Java, " + jars.size() + " jars, depends on "
          + (deps.isEmpty() ? "nothing" : Proc.repr(deps)));
    }

    Files.writeString(out.resolve("build.json"), Proc.PyJson.indented(new JsonObject().add("projects", projects)) + "\n");
    String commit = Proc.run(src, List.of("git", "rev-parse", "HEAD")).out().strip();
    JsonObject export = new JsonObject().add("from", src.toString()).add("commit", commit).add("modules", summary);
    Files.writeString(out.resolve("export.json"), Proc.PyJson.indented(export) + "\n");
    return report;
  }

  /**
   * Options as one value of incOptions.properties: IncHandler splits it on spaces, and a
   * properties file reads a backslash as an escape.
   */
  private static String property(List<String> opts) {
    return String.join(" ", opts).replace("\\", "\\\\");
  }

  public static void main(String[] argv) throws IOException {
    try {
      Proc.Args a = Proc.Args.parse(argv, Set.of("--scala", "--sbt-arg", "--timeout"), Set.of("--no-scala-instance"), USAGE);
      if (a.positional.size() < 3) throw new Proc.Failure(USAGE);
      List<String> modules = a.positional.subList(2, a.positional.size());
      List<String> report = export(
          Path.of(a.positional.get(0)),
          Path.of(a.positional.get(1)),
          modules,
          a.last("--scala", "2.13.x"),
          a.all("--sbt-arg"),
          a.flag("--no-scala-instance"),
          Integer.parseInt(a.last("--timeout", "1800")));
      report.forEach(System.out::println);
    } catch (Proc.Failure e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }
}
