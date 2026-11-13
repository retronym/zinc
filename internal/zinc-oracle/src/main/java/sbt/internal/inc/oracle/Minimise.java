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
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Minimises a step of a history replay: finds a smallest set of the commit's changed files that
 * still gives the step's verdict when compiled incrementally from a clean build of its parent.
 *
 * <pre>
 *   Minimise OUT STEP [--mode NAME] [--verdict VERDICT]... [--zinc CHECKOUT]
 * </pre>
 *
 * OUT is a finished (or running) replay of {@link History} and STEP one of its steps, such as
 * 0042-ab12cd34e. The verdict to keep is the one the replay recorded for --mode at that step
 * (default: the first mode with a finding, else the first that is not `same`), unless --verdict
 * names others to accept. --zinc is a Zinc checkout to run instead of the replay's.
 *
 * <p>The replay judged the step from the state its history had left. Here every trial starts from a
 * clean build of the parent commit, so the first trial applies the whole commit: if the verdict
 * does not come back, the finding depends on the earlier history and nothing is minimised. A
 * trial reproduces when it gives the verdict and, if the replay recorded differing classes that no
 * normalisation explains, at least one of those classes differs again. Then delta debugging
 * (Zeller's ddmin) over the changed files: a trial puts a subset of them at the commit's version,
 * the rest at the parent's, and asks the same oracle. A subset that does not compile is a trial
 * that does not reproduce.
 *
 * <p>Writes OUT/minimised/STEP/: `result.json`, `report.md` with the minimal files and their diffs,
 * and `work/`, IncHistory's copies and evidence for the trials.
 */
public final class Minimise {
  private Minimise() {}

  static final String USAGE = "Minimise OUT STEP [--mode NAME] [--verdict VERDICT]... [--zinc CHECKOUT]";

  private static final Set<String> FINDINGS = Set.of("stale", "missed-error", "spurious-error");

  /** Whether a subset of the items still shows what is being minimised. */
  interface Test {
    boolean test(List<String> subset) throws IOException;
  }

  /** A 1-minimal sublist of `items` for which `test` holds; `test` must hold for `items`. */
  static List<String> ddmin(List<String> items, Test test) throws IOException {
    int n = 2;
    while (items.size() >= 2) {
      int size = Math.max(1, items.size() / n);
      List<List<String>> chunks = new ArrayList<>();
      for (int i = 0; i < items.size(); i += size) chunks.add(items.subList(i, Math.min(i + size, items.size())));
      boolean reduced = false;
      for (List<String> chunk : chunks) {
        if (test.test(chunk)) {
          items = chunk;
          n = 2;
          reduced = true;
          break;
        }
      }
      if (!reduced) {
        for (List<String> chunk : chunks) {
          List<String> rest = new ArrayList<>(items);
          rest.removeAll(chunk);
          if (!rest.isEmpty() && test.test(rest)) {
            items = rest;
            n = Math.max(n - 1, 2);
            reduced = true;
            break;
          }
        }
      }
      if (!reduced) {
        if (n >= items.size()) break;
        n = Math.min(items.size(), n * 2);
      }
    }
    return items;
  }

  /** The trials of one step: a build that is moved between trees of sources, and IncHistory on it. */
  private static final class Trials implements Test {
    final Path repo;
    final Path build;
    final Path steps;
    final String mode;
    final Map<String, String> before;
    final Map<String, String> after;
    final int changed;
    final Set<String> wantVerdicts;
    final Set<String> wantClasses;
    final JsonArray trials = new JsonArray();
    Map<String, String> have;
    ReplayProcess session;

    Trials(Path repo, Path build, Path steps, String mode, Map<String, String> before, Map<String, String> after, int changed,
        Set<String> wantVerdicts, Set<String> wantClasses, Map<String, String> have) {
      this.repo = repo;
      this.build = build;
      this.steps = steps;
      this.mode = mode;
      this.before = before;
      this.after = after;
      this.changed = changed;
      this.wantVerdicts = wantVerdicts;
      this.wantClasses = wantClasses;
      this.have = have;
    }

    void put(Map<String, String> tree) throws IOException {
      History.materialise(repo, build, tree, have);
      have = tree;
    }

    private JsonObject verdictOf(String name) throws IOException {
      JsonObject last = null;
      for (String line : Files.readAllLines(steps)) {
        if (line.isBlank()) continue;
        JsonObject d = Json.parse(line).asObject();
        if (name.equals(d.getString("step", null)) && mode.equals(d.getString("mode", null)) && d.get("verdict") != null) last = d;
      }
      if (last == null) throw new Proc.Failure(name + ": IncHistory recorded no verdict for mode " + mode);
      return last;
    }

    @Override
    public boolean test(List<String> subset) throws IOException {
      int n = trials.size() + 1;
      if (n > 1) {
        put(before);
        session.rebase(String.format(Locale.ROOT, "trial-%03d-base", n));
      }
      Map<String, String> tree = new LinkedHashMap<>(before);
      for (String p : subset) {
        if (after.containsKey(p)) tree.put(p, after.get(p));
        else tree.remove(p);
      }
      put(tree);
      String name = String.format(Locale.ROOT, "trial-%03d", n);
      session.step(name);
      JsonObject d = verdictOf(name);
      List<String> classes = classes(d, false);
      String verdict = d.get("verdict").asString();
      boolean ok = wantVerdicts.contains(verdict) && (wantClasses.isEmpty() || classes.stream().anyMatch(wantClasses::contains));
      trials.add(new JsonObject()
          .add("trial", name)
          .add("files", Proc.array(subset))
          .add("verdict", verdict)
          .add("reproduces", ok)
          .add("differences", Proc.array(classes.subList(0, Math.min(20, classes.size())))));
      History.log(name + ": " + subset.size() + " of " + changed + " files -> " + verdict);
      return ok;
    }
  }

  /** The classes a step's record says differ, all of them or those no normalisation explains. */
  private static List<String> classes(JsonObject record, boolean unexplainedOnly) {
    List<String> out = new ArrayList<>();
    JsonValue differences = record.get("differences");
    if (differences != null) {
      for (JsonValue x : differences.asArray()) {
        JsonObject difference = x.asObject();
        if (!unexplainedOnly || !"noise".equals(difference.get("kind").asString())) out.add(difference.get("class").asString());
      }
    }
    return out;
  }

  private static Map<String, List<String>> lists(JsonObject object) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (JsonObject.Member m : object) out.put(m.getName(), Proc.strings(m.getValue()));
    return out;
  }

  private static String quoted(List<String> items) {
    return items.stream().map(s -> "`" + s + "`").collect(Collectors.joining(", "));
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
    Proc.Args a = Proc.Args.parse(argv, Set.of("--mode", "--verdict", "--zinc"), Set.of(), USAGE);
    if (a.positional.size() != 2) throw new Proc.Failure(USAGE);
    Path out = Path.of(a.positional.get(0)).toAbsolutePath().normalize();
    String step = a.positional.get(1);
    JsonObject replay = Json.parse(Files.readString(out.resolve("replay.json"))).asObject();
    JsonObject commit = null;
    for (String line : Files.readAllLines(out.resolve("commits.jsonl"))) {
      if (line.isBlank()) continue;
      JsonObject c = Json.parse(line).asObject();
      if (commit == null && step.equals(c.get("step").asString())) commit = c;
    }
    if (commit == null) throw new Proc.Failure(step + ": not a step of " + out.resolve("commits.jsonl"));
    Map<String, JsonObject> recorded = new LinkedHashMap<>();
    for (String line : Files.readAllLines(out.resolve("work").resolve("steps.jsonl"))) {
      JsonObject d;
      try {
        d = Json.parse(line).asObject();
      } catch (ParseException | UnsupportedOperationException e) {
        continue;
      }
      if (step.equals(d.getString("step", null)) && d.get("verdict") != null) recorded.put(d.get("mode").asString(), d);
    }
    String mode = a.last("--mode", null);
    if (mode == null) {
      for (Map.Entry<String, JsonObject> e : recorded.entrySet()) {
        if (mode == null && FINDINGS.contains(e.getValue().get("verdict").asString())) mode = e.getKey();
      }
    }
    if (mode == null) {
      for (Map.Entry<String, JsonObject> e : recorded.entrySet()) {
        if (mode == null && !e.getValue().get("verdict").asString().equals("same")) mode = e.getKey();
      }
    }
    if (mode == null) throw new Proc.Failure(step + ": every mode is the same as clean; nothing to minimise");
    if (!recorded.containsKey(mode)) throw new Proc.Failure(step + ": the replay recorded no verdict for mode " + mode);
    Set<String> wantVerdicts =
        new TreeSet<>(a.flag("--verdict") ? a.all("--verdict") : List.of(recorded.get(mode).get("verdict").asString()));
    Set<String> wantClasses = new HashSet<>(classes(recorded.get(mode), true));
    String spec = null;
    for (String m : Proc.strings(replay.get("modes"))) {
      if (spec == null && m.split("=", 2)[0].equals(mode)) spec = m;
    }
    if (spec == null) throw new Proc.Failure(mode + ": not a mode of the replay");

    Path repo = Path.of(replay.get("repo").asString());
    Path frozen = Path.of(replay.get("frozenBuild").asString());
    Map<String, List<String>> roots = lists(replay.get("roots").asObject());
    Map<String, List<String>> frozenOnly = lists(replay.get("frozenOnly").asObject());
    List<String> modules = Proc.strings(replay.get("modules"));
    String child = commit.get("commit").asString();
    String parent = Git.parent(repo, child);
    Map<String, String> before = History.desired(repo, parent, roots);
    Map<String, String> after = History.desired(repo, child, roots);
    Set<String> paths = new TreeSet<>(before.keySet());
    paths.addAll(after.keySet());
    List<String> changed = new ArrayList<>();
    for (String p : paths) if (!Objects.equals(before.get(p), after.get(p))) changed.add(p);

    Path dest = out.resolve("minimised").resolve(step);
    Proc.deleteTree(dest);
    Files.createDirectories(dest);
    Path build = dest.resolve("build");
    Map<String, String> have = History.prepareBuild(frozen, build, modules, frozenOnly);
    Trials trials = new Trials(repo, build, dest.resolve("work").resolve("steps.jsonl"), mode, before, after, changed.size(),
        wantVerdicts, wantClasses, have);
    trials.put(before);

    String classpath;
    JsonValue classpathFile = replay.get("classpathFile");
    if (a.flag("--zinc")) {
      classpath = History.zincClasspath(Path.of(a.last("--zinc", null)).toAbsolutePath().normalize());
    } else if (classpathFile != null && classpathFile.isString() && !classpathFile.asString().isEmpty()) {
      classpath = Files.readString(Path.of(classpathFile.asString())).strip();
    } else {
      classpath = History.zincClasspath(Path.of(replay.get("zinc").asString()));
    }
    List<String> args = new ArrayList<>(List.of(
        "--build", build.toString(), "--work", dest.resolve("work").toString(), "--scala", replay.get("scala").asString(),
        "--mode", spec));
    for (String o : Proc.strings(replay.get("incOptions"))) args.addAll(List.of("--inc-option", o));
    List<String> jvmOpts = Proc.strings(replay.get("jvmOpts"));
    if (jvmOpts.isEmpty()) jvmOpts = History.DEFAULT_JVM_OPTS;

    boolean fromClean;
    List<String> minimal;
    try (ReplayProcess session = new ReplayProcess(classpath, jvmOpts, args, dest.resolve("inchistory.log"))) {
      trials.session = session;
      session.waitFor("ready");
      fromClean = trials.test(changed);
      minimal = fromClean && changed.size() > 1 ? ddmin(changed, trials) : changed;
    }

    JsonObject result = new JsonObject()
        .add("step", step)
        .add("commit", child)
        .add("parent", parent)
        .add("mode", mode)
        .add("verdicts", Proc.array(wantVerdicts))
        .add("changed", Proc.array(changed))
        .add("reproducesFromClean", fromClean)
        .add("minimal", fromClean ? Proc.array(minimal) : Json.NULL)
        .add("trials", trials.trials);
    Files.writeString(dest.resolve("result.json"), Proc.PyJson.indented(result) + "\n");

    List<String> lines = new ArrayList<>(List.of(
        "# Minimised: " + step,
        "",
        "Commit " + child.substring(0, 9) + " (" + commit.get("subject").asString() + "), parent " + parent.substring(0, 9)
            + ", mode `" + mode + "`, verdict " + quoted(new ArrayList<>(wantVerdicts)) + " in the replay.",
        ""));
    if (!fromClean) {
      lines.add("Compiled incrementally from a clean build of the parent, the whole commit (" + changed.size() + " files) gives `"
          + trials.trials.get(0).asObject().get("verdict").asString() + "`. The replay's verdict depends on the state the earlier"
          + " history left, so there is nothing to minimise from a clean start.");
      lines.add("");
    } else {
      JsonObject last = null;
      List<String> sortedMinimal = minimal.stream().sorted().toList();
      for (JsonValue t : trials.trials) {
        JsonObject trial = t.asObject();
        if (trial.get("reproduces").asBoolean() && Proc.strings(trial.get("files")).stream().sorted().toList().equals(sortedMinimal)) {
          last = trial;
        }
      }
      String lastName = last.get("trial").asString();
      lines.add(minimal.size() + " of the commit's " + changed.size() + " changed files are enough, from a clean build of the parent ("
          + trials.trials.size() + " trials):");
      lines.add("");
      for (String p : minimal) lines.add("- `" + p + "`");
      lines.add("");
      lines.add("Differing classes in the reproducing trial `" + lastName + "`: " + quoted(Proc.strings(last.get("differences"))) + ".");
      lines.add("");
      lines.add("Evidence: `work/diffs/" + lastName + "/" + mode + "/`.");
      lines.add("");
      lines.add("## The minimal edit");
      lines.add("");
      Map<String, String> toRepo = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> e : roots.entrySet()) {
        for (String root : e.getValue()) {
          List<String> under = root.isEmpty() ? List.of() : List.of(root);
          for (String path : History.tree(repo, child, under).keySet()) toRepo.put(History.buildPath(e.getKey(), root, path), path);
          for (String path : History.tree(repo, parent, under).keySet()) {
            toRepo.putIfAbsent(History.buildPath(e.getKey(), root, path), path);
          }
        }
      }
      List<String> diffArgs = new ArrayList<>(List.of("diff", parent, child, "--"));
      for (String p : minimal) if (toRepo.containsKey(p)) diffArgs.add(toRepo.get(p));
      String diff = Git.run(repo, diffArgs.toArray(String[]::new)).stripTrailing();
      lines.add("```diff");
      lines.add(Proc.head(diff, 20000));
      lines.add("```");
      lines.add("");
    }
    Files.writeString(dest.resolve("report.md"), String.join("\n", lines));
    System.out.println(dest.resolve("report.md"));
  }
}
