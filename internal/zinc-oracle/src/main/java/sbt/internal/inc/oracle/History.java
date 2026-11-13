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

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Replays a project's git history through IncHistory: each commit compiled incrementally from the
 * one before, under one or more incOptions modes, and compared with a clean build of that commit.
 *
 * <pre>
 *   History REPO FROZEN_BUILD OUT [--zinc CHECKOUT] [--classpath-file FILE] [--ref REF] [--steps N]
 *           [--mode NAME[=KEY=VALUE,...]]... [--inc-option KEY=VALUE]... [--scala LABEL]
 *           [--no-profile] [--jvm-opt OPT]... [--sbt-export [--sbt-arg ARG]...] [--no-report]
 * </pre>
 *
 * REPO is a clone of the project with its history. FROZEN_BUILD is an export of some of its modules
 * at one commit, as IncBench's --build reads it (build.json, &lt;module&gt;/src/main/{scala,java},
 * &lt;module&gt;/lib/*.jar, &lt;module&gt;/incOptions.properties), with an export.json naming that commit. The
 * export fixes the modules, their classpaths and their options for the whole replay; only sources
 * follow the history. Where each module's sources live in REPO is inferred by finding the export's
 * files in the tree of the export's commit. Files of the export that are not in that tree, such as
 * generated sources, stay as exported.
 *
 * <p>A step is a first-parent commit that changes a file under those source directories. The last
 * --steps of them up to --ref (default: the export's commit) are replayed, oldest first, starting
 * from the parent of the first. Sources much older than the export stop compiling against its
 * dependencies, so the replay first looks for the oldest step that still compiles and starts no
 * earlier.
 *
 * <p>With --sbt-export the build follows the history too. The project's own sbt re-exports the same
 * modules ({@link Export}, in a clone under OUT) at the start and at every commit that changes
 * `*.sbt` or `project/`, and such a commit is a step whether or not it changes a source: its
 * dependency jars, options, module graph and generated sources replace the build's. A commit whose
 * build does not export keeps the previous export. This runs the project's build code.
 *
 * <p>IncHistory and the bridge are those of the Zinc checkout --zinc, whose sbt is asked for
 * IncHistory's classpath unless --classpath-file gives it. --inc-option applies to every mode and
 * to the clean build, after the defaults, which are sbt's where scripted's differ.
 *
 * <p>OUT gets:
 *
 * <pre>
 *   build/            the build as of the current step, which IncHistory copies per mode
 *   work/             IncHistory's copies, steps.jsonl and diffs/
 *   commits.jsonl     one line per step: the commit, its subject and the files it changed
 *   inchistory.log    everything IncHistory printed
 *   report.md         written by Report, after every step, unless --no-report
 * </pre>
 */
public final class History {
  private History() {}

  static final String USAGE = String.join("\n",
      "History REPO FROZEN_BUILD OUT [--zinc CHECKOUT] [--classpath-file FILE] [--ref REF] [--steps N]",
      "        [--mode NAME[=KEY=VALUE,...]]... [--inc-option KEY=VALUE]... [--scala LABEL]",
      "        [--no-profile] [--jvm-opt OPT]... [--sbt-export [--sbt-arg ARG]...] [--no-report]");

  private static final List<String> DEFAULT_INC_OPTIONS =
      List.of("pipelining=false", "recompileAllFraction=0.5", "recompileOnMacroDef=true");
  static final List<String> DEFAULT_JVM_OPTS = List.of("-Xmx6g", "-Xss4m");

  static void log(String msg) {
    System.err.println("[zinc-oracle] " + msg);
    System.err.flush();
  }

  private static boolean isSource(String path) {
    return path.endsWith(".scala") || path.endsWith(".java");
  }

  /** The source files of a commit, all or those under `paths`: path to blob sha. */
  static Map<String, String> tree(Path repo, String commit, List<String> paths) throws IOException {
    List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "-z", commit));
    if (!paths.isEmpty()) {
      args.add("--");
      args.addAll(paths);
    }
    Map<String, String> files = new LinkedHashMap<>();
    for (String entry : Git.run(repo, args.toArray(String[]::new)).split("\0")) {
      if (entry.isEmpty()) continue;
      int tab = entry.indexOf('\t');
      String[] meta = entry.substring(0, tab).strip().split("\\s+");
      String path = entry.substring(tab + 1);
      if (meta[1].equals("blob") && isSource(path)) files.put(path, meta[2]);
    }
    return files;
  }

  private static Map<String, String> tree(Path repo, String commit, String root) throws IOException {
    return tree(repo, commit, root.isEmpty() ? List.of() : List.of(root));
  }

  static String langDir(String path) {
    return path.endsWith(".java") ? "java" : "scala";
  }

  /** A source of the export: its language directory, its path below it, and the file. */
  record Source(String lang, String rel, Path file) {
    String key() {
      return lang + "/" + rel;
    }
  }

  /**
   * The export's sources of a module. They come in the order the Python tool's recursive glob
   * listed them, because that order decides between source directories that explain equally many
   * files: each directory's files as the file system enumerates them, the root's first, then for
   * each directory taken from a stack, those of its subdirectories.
   */
  static List<Source> exportedSources(Path build, String module) throws IOException {
    List<Source> out = new ArrayList<>();
    for (String lang : List.of("scala", "java")) {
      Path root = build.resolve(module).resolve("src").resolve("main").resolve(lang);
      if (!Files.isDirectory(root)) continue;
      addSources(out, lang, root, root);
      Deque<Path> stack = new ArrayDeque<>(List.of(root));
      while (!stack.isEmpty()) {
        for (Path dir : entries(stack.pop())) {
          if (Files.isDirectory(dir)) {
            addSources(out, lang, root, dir);
            stack.push(dir);
          }
        }
      }
    }
    return out;
  }

  private static List<Path> entries(Path dir) throws IOException {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      stream.forEach(out::add);
    }
    return out;
  }

  private static void addSources(List<Source> out, String lang, Path root, Path dir) throws IOException {
    for (Path entry : entries(dir)) {
      if (Files.isRegularFile(entry) && isSource(entry.getFileName().toString())) {
        out.add(new Source(lang, root.relativize(entry).toString().replace(java.io.File.separatorChar, '/'), entry));
      }
    }
  }

  /** Each module's source directories in the repository, and its exported files found nowhere. */
  record Roots(Map<String, List<String>> roots, Map<String, List<String>> frozenOnly) {}

  private static String stripTrailingSlashes(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '/') end--;
    return s.substring(0, end);
  }

  private static String stripLeadingSlashes(String s) {
    int start = 0;
    while (start < s.length() && s.charAt(start) == '/') start++;
    return s.substring(start);
  }

  /**
   * Finds each exported source in the tree of `commit` by name and content. A file that matches
   * under several directories (a copy elsewhere in the repository) votes for each; the directories
   * kept are those that explain the most files, without the ones a better directory already covers.
   */
  static Roots inferRoots(Path repo, String commit, Path build, List<String> modules) throws IOException {
    Map<String, String> files = tree(repo, commit, List.of());
    Map<String, List<String>> byName = new HashMap<>();
    for (String path : files.keySet()) {
      byName.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>()).add(path);
    }
    Map<String, List<String>> roots = new LinkedHashMap<>();
    Map<String, List<String>> frozen = new LinkedHashMap<>();
    for (String m : modules) {
      Map<String, Integer> found = new LinkedHashMap<>();
      List<String> missing = new ArrayList<>();
      Map<Source, String> shas = new LinkedHashMap<>();
      for (Source s : exportedSources(build, m)) {
        String sha = Git.blobSha(Files.readAllBytes(s.file()));
        shas.put(s, sha);
        boolean hit = false;
        for (String p : byName.getOrDefault(s.file().getFileName().toString(), List.of())) {
          if (files.get(p).equals(sha) && (p.equals(s.rel()) || p.endsWith("/" + s.rel()))) {
            hit = true;
            found.merge(stripTrailingSlashes(p.substring(0, p.length() - s.rel().length())), 1, Integer::sum);
          }
        }
        if (!hit) missing.add(s.key());
      }
      List<String> ranked = new ArrayList<>(found.keySet());
      ranked.sort((a, b) -> Integer.compare(found.get(b), found.get(a)));
      List<String> kept = new ArrayList<>();
      Set<Source> covered = new HashSet<>();
      for (String root : ranked) {
        boolean adds = false;
        for (Map.Entry<Source, String> e : shas.entrySet()) {
          String rel = e.getKey().rel();
          if (e.getValue().equals(files.get(root.isEmpty() ? rel : root + "/" + rel)) && covered.add(e.getKey())) adds = true;
        }
        if (adds) kept.add(root);
      }
      roots.put(m, kept);
      frozen.put(m, missing);
    }
    return new Roots(roots, frozen);
  }

  /** The sources a commit gives each module: path in the build to blob sha. */
  static Map<String, String> desired(Path repo, String commit, Map<String, List<String>> roots) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : roots.entrySet()) {
      for (String root : e.getValue()) {
        for (Map.Entry<String, String> f : tree(repo, commit, root).entrySet()) {
          out.put(buildPath(e.getKey(), root, f.getKey()), f.getValue());
        }
      }
    }
    return out;
  }

  /** Where the build keeps a module's source that the repository has at `path`, under `root`. */
  static String buildPath(String module, String root, String path) {
    return module + "/src/main/" + langDir(path) + "/" + stripLeadingSlashes(path.substring(root.length()));
  }

  /**
   * Makes the build's history-tracked sources those of `want`; returns the paths that changed.
   * Removals come first: on a case-insensitive file system, a file renamed only in case would
   * otherwise be written under its new name and then deleted under its old one.
   */
  static List<String> materialise(Path repo, Path build, Map<String, String> want, Map<String, String> have)
      throws IOException {
    List<String> changed = new ArrayList<>();
    for (String path : have.keySet()) {
      if (!want.containsKey(path)) {
        Files.deleteIfExists(build.resolve(path));
        changed.add(path);
      }
    }
    for (Map.Entry<String, String> e : want.entrySet()) {
      if (!e.getValue().equals(have.get(e.getKey()))) {
        Path f = build.resolve(e.getKey());
        Files.createDirectories(f.getParent());
        Files.write(f, Git.blob(repo, e.getValue()));
        changed.add(e.getKey());
      }
    }
    return changed;
  }

  static boolean isBuildFile(String path) {
    return path.startsWith("project/") || (!path.contains("/") && path.endsWith(".sbt"));
  }

  /** The commits to replay, oldest first, and those of them that change the build definition. */
  record Candidates(List<String> steps, Set<String> build) {}

  /**
   * The last `limit` first-parent commits up to `ref` that change a source under `roots` or, with
   * `withBuild`, the build definition.
   */
  static Candidates candidates(Path repo, String ref, Map<String, List<String>> roots, int limit, boolean withBuild)
      throws IOException {
    Set<String> dirs = new TreeSet<>();
    roots.values().forEach(dirs::addAll);
    List<String> args = new ArrayList<>(
        List.of("log", "--first-parent", "-m", "--name-only", "--format=@%H %P", "-n" + limit, ref, "--"));
    for (String d : dirs) args.add(d.isEmpty() ? "." : d);
    if (withBuild) args.addAll(List.of("*.sbt", "project"));
    Set<String> keep = new LinkedHashSet<>();
    Set<String> build = new HashSet<>();
    String current = null;
    for (String line : Git.run(repo, args.toArray(String[]::new)).split("\n")) {
      if (line.startsWith("@")) {
        String[] revs = line.substring(1).strip().split("\\s+");
        current = revs.length > 1 ? revs[0] : null;
      } else if (current != null && withBuild && isBuildFile(line)) {
        keep.add(current);
        build.add(current);
      } else if (current != null && isSource(line)) {
        keep.add(current);
      }
    }
    List<String> steps = new ArrayList<>(keep);
    java.util.Collections.reverse(steps);
    return new Candidates(steps, build);
  }

  /** The project's own sbt, in a clone of the repository, exporting the modules at a commit. */
  private static final class Exporter {
    private final Path checkout;
    private final Path exports;
    private final List<String> modules;
    private final String scala;
    private final boolean noScalaInstance;
    private final List<String> sbtArgs;

    Exporter(Path repo, Path out, List<String> modules, String scala, boolean noScalaInstance, List<String> sbtArgs)
        throws IOException {
      this.checkout = out.resolve("checkout");
      this.exports = out.resolve("exports");
      this.modules = modules;
      this.scala = scala;
      this.noScalaInstance = noScalaInstance;
      this.sbtArgs = sbtArgs;
      Proc.Result r =
          Proc.run(null, List.of("git", "clone", "-q", "--local", "--no-checkout", repo.toString(), checkout.toString()));
      if (r.exit() != 0) throw new Proc.Failure("git clone of " + repo + " failed:\n" + r.err());
      Files.createDirectory(exports);
    }

    /** The export of `commit`, or null if its build does not export; what happened goes to a log beside it. */
    Path at(String commit) throws IOException {
      Git.run(checkout, "checkout", "-q", "-f", "--detach", commit);
      String name = commit.substring(0, 9);
      Path dest = exports.resolve(name);
      long t = System.nanoTime();
      String output;
      boolean ok;
      try {
        output = String.join("\n", Export.export(checkout, dest, modules, scala, sbtArgs, noScalaInstance, 1800)) + "\n";
        ok = true;
      } catch (Proc.Failure e) {
        output = e.getMessage() + "\n";
        ok = false;
      } catch (IOException | RuntimeException e) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        output = trace.toString();
        ok = false;
      }
      Files.writeString(exports.resolve(name + ".log"), output);
      log(String.format(Locale.ROOT, "export %s: %s, %.0fs", name, ok ? "ok" : "FAILED", seconds(t)));
      return ok ? dest : null;
    }
  }

  /** Makes `build` the export, touching only what differs; returns the paths that changed. */
  static List<String> adopt(Path export, Path build) throws IOException {
    List<String> want = Proc.files(export);
    Set<String> have = new TreeSet<>(Proc.files(build));
    List<String> changed = new ArrayList<>();
    Set<String> wanted = new HashSet<>(want);
    for (String path : have) {
      if (!wanted.contains(path)) {
        Files.delete(build.resolve(path));
        changed.add(path);
      }
    }
    for (String path : want) {
      Path src = export.resolve(path);
      Path dest = build.resolve(path);
      if (!have.contains(path) || Files.mismatch(src, dest) != -1) {
        Files.createDirectories(dest.getParent());
        Files.deleteIfExists(dest);
        Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
        if (!path.equals("export.json")) changed.add(path);
      }
    }
    return changed;
  }

  /** Compiles the checkout's 2.13 bridge and IncHistory, and returns IncHistory's classpath. */
  static String zincClasspath(Path checkout) throws IOException {
    Proc.Result r = Proc.run(checkout, List.of(
        "sbt", "--client", "--color=false", "--supershell=false", "--batch",
        "compilerBridge2_13/compile; zincScripted/Test/compile; export zincScripted/Test/fullClasspath"));
    if (r.exit() != 0) {
      throw new Proc.Failure(checkout + ": sbt failed:\n" + Proc.tail(r.out(), 3000) + Proc.tail(r.err(), 2000));
    }
    String cp = null;
    for (String line : r.out().lines().toList()) if (line.startsWith("/") || line.startsWith("List(")) cp = line;
    if (cp == null) throw new Proc.Failure(checkout + ": sbt printed no classpath");
    return cp.startsWith("List(") ? sbt2Classpath(checkout, cp) : cp;
  }

  /**
   * sbt 2 prints a list of entries under `${OUT}`, `${BASE}` and `${CSR_CACHE}`, jars with a
   * content hash appended.
   */
  static String sbt2Classpath(Path checkout, String list) {
    Path home = Path.of(System.getProperty("user.home"));
    Path mac = home.resolve("Library/Caches/Coursier/v1");
    Path linux = home.resolve(".cache/coursier/v1");
    String env = System.getenv("COURSIER_CACHE");
    String cache = env != null && !env.isEmpty() ? env : (Files.exists(mac) ? mac : linux).toString();
    Map<String, String> subst = new LinkedHashMap<>();
    subst.put("${OUT}", checkout.resolve("target/out").toString());
    subst.put("${BASE}", checkout.toString());
    subst.put("${CSR_CACHE}", cache);
    List<String> entries = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String e : list.substring("List(".length(), list.length() - 1).split(", ")) {
      int hash = e.indexOf('>');
      if (hash >= 0) e = e.substring(0, hash);
      for (Map.Entry<String, String> s : subst.entrySet()) e = e.replace(s.getKey(), s.getValue());
      entries.add(e);
      if (!Files.exists(Path.of(e))) missing.add(e);
    }
    if (!missing.isEmpty()) {
      throw new Proc.Failure(checkout + ": classpath entries not found: " + Proc.repr(missing.subList(0, Math.min(5, missing.size()))));
    }
    return String.join(":", entries);
  }

  /**
   * The index of the oldest step whose parent compiles against the export, by bisection: the
   * export's dependencies and generated sources are those of its commit, so older sources stop
   * compiling at some point, and the search takes that point to be the only one.
   */
  private static int firstCompiling(
      Path repo, Path frozen, Path out, Roots inferred, List<String> modules, List<String> steps, String classpath,
      List<String> jvmOpts, List<String> args) throws IOException {
    Path build = out.resolve("probe").resolve("build");
    Map<String, String> have = prepareBuild(frozen, build, modules, inferred.frozenOnly());
    Map<String, String> want = desired(repo, Git.parent(repo, steps.get(0)), inferred.roots());
    materialise(repo, build, want, have);
    have = want;
    List<String> probeArgs = new ArrayList<>(List.of("--build", build.toString(), "--work", out.resolve("probe").resolve("work").toString()));
    probeArgs.addAll(args);
    try (ReplayProcess probe = new ReplayProcess(classpath, jvmOpts, probeArgs, out.resolve("probe.log"))) {
      if (probe.waitFor("ready").get("cleanOk").asBoolean()) return 0;
      int lo = 0;
      int hi = steps.size() - 1;
      want = desired(repo, Git.parent(repo, steps.get(hi)), inferred.roots());
      materialise(repo, build, want, have);
      have = want;
      if (!probe.step("probe-" + hi)) {
        throw new Proc.Failure("the parent of the last step, " + Git.parent(repo, steps.get(hi)).substring(0, 9)
            + ", does not compile against the export");
      }
      while (hi - lo > 1) {
        int mid = (lo + hi) / 2;
        want = desired(repo, Git.parent(repo, steps.get(mid)), inferred.roots());
        materialise(repo, build, want, have);
        have = want;
        boolean ok = probe.step("probe-" + mid);
        log("probe: the parent of step " + (mid + 1) + " of " + steps.size() + " " + (ok ? "compiles" : "does not compile"));
        if (ok) hi = mid;
        else lo = mid;
      }
      return hi;
    } finally {
      Proc.deleteTree(out.resolve("probe"));
    }
  }

  /**
   * Copies the export, and returns its sources that the history tracks, to be replaced. An earlier
   * IncBench run on the export leaves its own options in incOptions.properties and the build's in
   * incOptions.base.properties, which is put back.
   */
  static Map<String, String> prepareBuild(Path frozen, Path build, List<String> modules, Map<String, List<String>> frozenOnly)
      throws IOException {
    Proc.copyTree(frozen, build);
    Map<String, String> have = new LinkedHashMap<>();
    for (String m : modules) {
      Path saved = build.resolve(m).resolve("incOptions.base.properties");
      if (Files.exists(saved)) {
        Files.move(saved, build.resolve(m).resolve("incOptions.properties"), StandardCopyOption.REPLACE_EXISTING);
      }
      Set<String> keep = new HashSet<>(frozenOnly.getOrDefault(m, List.of()));
      for (Source s : exportedSources(build, m)) {
        if (!keep.contains(s.key())) have.put(m + "/src/main/" + s.key(), null);
      }
    }
    return have;
  }

  /**
   * The Zinc checkout to use when none is named: the nearest directory above these classes that
   * has a build.sbt, else the working directory.
   */
  private static Path defaultZinc() {
    try {
      Path p = Path.of(History.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
      for (; p != null; p = p.getParent()) if (Files.exists(p.resolve("build.sbt"))) return p;
    } catch (URISyntaxException | RuntimeException e) {
      return Path.of("").toAbsolutePath();
    }
    return Path.of("").toAbsolutePath();
  }

  /** Writes OUT's report. `quiet` drops what it prints, for the reports written while steps run. */
  private static void report(Path out, boolean quiet) {
    PrintStream nowhere = new PrintStream(OutputStream.nullOutputStream());
    Report.run(new String[] {out.toString()}, quiet ? nowhere : System.out, quiet ? nowhere : System.err);
  }

  private static double seconds(long since) {
    return (System.nanoTime() - since) / 1e9;
  }

  private static JsonObject lists(Map<String, List<String>> map) {
    JsonObject out = new JsonObject();
    map.forEach((k, v) -> out.add(k, Proc.array(v)));
    return out;
  }

  public static void main(String[] argv) throws IOException {
    try {
      run(argv);
    } catch (Proc.Failure e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  private static void run(String[] argv) throws IOException {
    Proc.Args a = Proc.Args.parse(
        argv,
        Set.of("--zinc", "--classpath-file", "--ref", "--steps", "--mode", "--inc-option", "--scala", "--jvm-opt", "--sbt-arg"),
        Set.of("--profile", "--no-profile", "--sbt-export", "--no-report"),
        USAGE);
    if (a.positional.size() != 3) throw new Proc.Failure(USAGE);
    Path repo = Path.of(a.positional.get(0)).toAbsolutePath().normalize();
    Path frozen = Path.of(a.positional.get(1)).toAbsolutePath().normalize();
    Path out = Path.of(a.positional.get(2)).toAbsolutePath().normalize();
    Path zinc = (a.flag("--zinc") ? Path.of(a.last("--zinc", null)) : defaultZinc()).toAbsolutePath().normalize();
    String classpathFile = a.last("--classpath-file", null);
    int maxSteps = Integer.parseInt(a.last("--steps", "200"));
    List<String> modes = a.all("--mode");
    List<String> incOptions = new ArrayList<>(DEFAULT_INC_OPTIONS);
    incOptions.addAll(a.all("--inc-option"));
    String scala = a.last("--scala", "2.13.x");
    boolean profile = !a.flag("--no-profile");
    boolean sbtExport = a.flag("--sbt-export");
    List<String> sbtArgs = a.all("--sbt-arg");
    boolean report = !a.flag("--no-report");

    JsonObject exportJson = Json.parse(Files.readString(frozen.resolve("export.json"))).asObject();
    String baseCommit = exportJson.get("commit").asString();
    List<String> modules = exportJson.get("modules").asObject().names();
    String ref = a.last("--ref", baseCommit);

    Proc.deleteTree(out);
    Files.createDirectories(out);
    Path build = out.resolve("build");
    String classpath = classpathFile != null ? Files.readString(Path.of(classpathFile)).strip() : zincClasspath(zinc);
    List<String> jvmOpts = a.flag("--jvm-opt") ? a.all("--jvm-opt") : DEFAULT_JVM_OPTS;
    List<String> common = new ArrayList<>(List.of("--scala", scala));
    for (String o : incOptions) common.addAll(List.of("--inc-option", o));

    Roots inferred = inferRoots(repo, baseCommit, frozen, modules);
    for (String m : modules) {
      List<String> dirs = inferred.roots().get(m);
      log(m + ": sources from " + (dirs.isEmpty() ? "nowhere" : Proc.repr(dirs)) + "; " + inferred.frozenOnly().get(m).size()
          + " exported files not in the repository");
      if (dirs.isEmpty()) throw new Proc.Failure(m + ": none of the export's sources are in " + repo + " at " + baseCommit);
    }
    Candidates candidates = candidates(repo, ref, inferred.roots(), maxSteps * 20, sbtExport);
    List<String> steps = candidates.steps();
    if (steps.isEmpty()) throw new Proc.Failure("no commit changes the exported modules' sources");

    Exporter exporter = null;
    int available;
    String start;
    Map<String, String> have;
    if (sbtExport) {
      boolean noScalaInstance = false;
      for (String m : modules) {
        noScalaInstance |= Files.readString(frozen.resolve(m).resolve("incOptions.properties")).contains("classpath.scalaInstance");
      }
      exporter = new Exporter(repo, out, modules, scala, noScalaInstance, sbtArgs);
      available = steps.size();
      steps = last(steps, maxSteps);
      Path export = null;
      start = null;
      for (int attempt = 0; attempt < 5 && export == null && !steps.isEmpty(); attempt++) {
        start = Git.parent(repo, steps.get(0));
        export = exporter.at(start);
        if (export == null) steps = steps.subList(1, steps.size());
      }
      if (export == null) throw new Proc.Failure("none of the first five starting commits exports; see exports/*.log");
      int buildChanges = 0;
      for (String s : steps) if (candidates.build().contains(s)) buildChanges++;
      log(steps.size() + " steps, " + buildChanges + " of them build changes, " + start.substring(0, 9) + ".."
          + steps.get(steps.size() - 1).substring(0, 9));
      Files.createDirectory(build);
      adopt(export, build);
      inferred = inferRoots(repo, start, build, modules);
    } else {
      int first = firstCompiling(repo, frozen, out, inferred, modules, steps, classpath, jvmOpts, common);
      available = steps.size() - first;
      steps = last(steps.subList(first, steps.size()), maxSteps);
      start = Git.parent(repo, steps.get(0));
      log(steps.size() + " steps of " + available + " that compile against the export, " + start.substring(0, 9) + ".."
          + steps.get(steps.size() - 1).substring(0, 9));
      have = prepareBuild(frozen, build, modules, inferred.frozenOnly());
      materialise(repo, build, desired(repo, start, inferred.roots()), have);
    }
    have = desired(repo, start, inferred.roots());

    JsonObject replayJson = new JsonObject()
        .add("repo", repo.toString())
        .add("frozenBuild", frozen.toString())
        .add("exportCommit", baseCommit)
        .add("ref", ref)
        .add("start", start)
        .add("steps", steps.size())
        .add("stepsAvailable", available)
        .add("modules", Proc.array(modules))
        .add("roots", lists(inferred.roots()))
        .add("frozenOnly", lists(inferred.frozenOnly()))
        .add("modes", Proc.array(modes))
        .add("sbtExport", sbtExport)
        .add("sbtArgs", Proc.array(sbtArgs))
        .add("incOptions", Proc.array(incOptions))
        .add("scala", scala)
        .add("jvmOpts", Proc.array(jvmOpts))
        .add("classpathFile", classpathFile != null ? Json.value(classpathFile) : Json.NULL)
        .add("zinc", zinc.toString())
        .add("zincCommit", Git.run(zinc, "rev-parse", "HEAD").strip())
        .add("started", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")));
    Files.writeString(out.resolve("replay.json"), Proc.PyJson.indented(replayJson) + "\n");

    List<String> args = new ArrayList<>(List.of("--build", build.toString(), "--work", out.resolve("work").toString()));
    args.addAll(common);
    for (String m : modes) args.addAll(List.of("--mode", m));
    if (profile) args.add("--profile");
    try (ReplayProcess replay = new ReplayProcess(classpath, jvmOpts, args, out.resolve("inchistory.log"));
        Writer commits = Files.newBufferedWriter(out.resolve("commits.jsonl"), StandardCharsets.UTF_8)) {
      replay.waitFor("ready");
      log("base built");
      for (int i = 1; i <= steps.size(); i++) {
        String commit = steps.get(i - 1);
        Boolean exported = null;
        List<String> changed = List.of();
        if (sbtExport && candidates.build().contains(commit)) {
          Path export = exporter.at(commit);
          exported = export != null;
          if (export != null) {
            changed = adopt(export, build);
            inferred = inferRoots(repo, commit, build, modules);
            have = desired(repo, commit, inferred.roots());
          }
        }
        if (exported == null || !exported) {
          Map<String, String> want = desired(repo, commit, inferred.roots());
          changed = materialise(repo, build, want, have);
          have = want;
        }
        if (changed.isEmpty()) continue;
        String name = String.format(Locale.ROOT, "%04d-%s", i, commit.substring(0, 9));
        long t = System.nanoTime();
        replay.step(name);
        String[] subjectAndDate = Git.run(repo, "log", "-1", "--format=%s%x00%cI", commit).strip().split("\0", 2);
        String subject = subjectAndDate[0];
        JsonObject line = new JsonObject()
            .add("step", name)
            .add("commit", commit)
            .add("subject", subject)
            .add("date", subjectAndDate.length > 1 ? subjectAndDate[1] : "")
            .add("changed", Proc.array(changed))
            .add("exported", exported == null ? Json.NULL : Json.value(exported.booleanValue()))
            .add("seconds", Json.value(Math.round(seconds(t) * 10) / 10.0));
        commits.write(Proc.PyJson.line(line) + "\n");
        commits.flush();
        log(String.format(Locale.ROOT, "%s %d files, %.1fs: %s", name, changed.size(), seconds(t), Proc.head(subject, 70)));
        if (report) report(out, true);
      }
    }
    if (report) report(out, false);
  }

  private static List<String> last(List<String> items, int n) {
    return n <= 0 || items.size() <= n ? items : items.subList(items.size() - n, items.size());
  }
}
