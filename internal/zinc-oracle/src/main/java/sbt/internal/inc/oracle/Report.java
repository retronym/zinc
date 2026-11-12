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
import com.eclipsesource.json.JsonValue;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reports on a replay written by incbench-history: soundness, precision and performance of each
 * mode's incremental compiles against the clean build of the same commit.
 *
 * <pre>
 *     Report OUT [OUT2 ...] [--json]
 * </pre>
 *
 * Reads OUT/replay.json, OUT/commits.jsonl and OUT/work/steps.jsonl, writes OUT/report.md and
 * prints its path. A replay that is still running can be reported on: a truncated last line and a
 * step that lacks some of its lines are left out. --json also prints the summary numbers of each
 * OUT to stdout, for comparing two runs; the paths of the reports then go to stderr.
 *
 * <p>The JSON is read into plain maps, lists, strings, {@code Long}, {@code Double} and {@code
 * Boolean}, and numbers keep the integer or floating type they were written with, because the
 * report prints them differently and the summary is compared between runs.
 */
public final class Report {
  private Report() {}

  private static final List<String> FINDINGS = List.of("stale", "missed-error", "spurious-error");
  private static final List<String> VERDICTS =
      List.of("same", "noise", "compiler", "stale", "same-fail", "missed-error", "spurious-error");
  private static final List<String> LEVELS = List.of("raw", "pool", "locals", "typevars", "tasty");
  private static final int CAP = 30;
  private static final Set<String> ZINC_TOTALS = Set.of("compile", "zincCall");
  private static final String NO_ERROR = "(no error recorded)";
  private static final Comparator<String> BY_CODE_POINT = Report::compareCodePoints;
  private static final IntPredicate NONE = i -> false;

  private static final String USAGE = "usage: incbench-history-report [-h] [--json] out [out ...]";

  private static final String HOW_TO_READ =
      """
      ## How to read this

      - A step is a first-parent commit that changes a source of the exported modules. Each mode compiles it incrementally from the previous step's state, and a separate copy is built from scratch; a mode's verdict compares its classfiles with that clean build's.
      - `same`: every classfile is byte-identical. `noise`: every difference disappears under a normalisation level. `compiler`: an unexplained difference, all in classes this step's compile wrote, so the compiler's output depended on what else was in its batch. `stale`: an unexplained difference in a class this step did not write, or a missing or extra classfile: undercompilation.
      - `same-fail`: both builds failed. `missed-error`: the clean build failed and the incremental one did not. `spurious-error`: the reverse. The findings against the incremental compiler are `stale`, `missed-error` and `spurious-error`; after one, the mode is reset to a clean build, and the reset verdict is that build's.
      - Levels, each ignoring more than the one before: `raw` (nothing), `pool` (constant-pool order), `locals` (names of local variables), `typevars` (names of type variables in generic signatures), `tasty` (the TASTy UUID, when the TASTy files differ only in their header or positions). A noise difference carries the lowest level that makes the two sides equal.
      - A difference is fresh when this step's incremental compile wrote the class, and carried when an earlier step already reported it and neither side has changed since. Carried differences are not counted again and do not affect the verdict.
      - Necessary: the sources the commit changed plus the sources whose clean classfiles differ from the previous step's clean build. A sound incremental compile has to produce those classfiles, so compiled / necessary is 1.0 at best. It is a lower bound: the dependencies that have to be rechecked to know that nothing else changed are not in it.
      - Compiled is distinct sources, so a source compiled in two cycles counts once. Cycles is the number of compile rounds over all modules.
      - The initial build `base` is each mode's own clean build of the starting commit; it is checked but not counted.
      """;

  /** Runs with the arguments of the command line and exits with a non-zero status on failure. */
  public static void main(String[] args) {
    int status = run(args, System.out, System.err);
    System.out.flush();
    System.err.flush();
    if (status != 0) System.exit(status);
  }

  /**
   * Writes {@code out/report.md} from the replay in {@code out} and returns its path. The file is
   * written beside the report and moved into place, so a reader never sees half a report.
   */
  public static Path write(Path out) throws IOException {
    Replay rp = new Replay(out, out.toString());
    return writeReport(rp, analyse(rp));
  }

  /** The summary numbers of the replay in {@code out}, as the JSON that {@code --json} prints. */
  public static String summaryJson(Path out) {
    Replay rp = new Replay(out, out.toString());
    return toJson(analyse(rp).toJson());
  }

  /**
   * The command line: one or more output directories and an optional {@code --json}. Returns the
   * exit status, 1 when a directory holds neither {@code replay.json} nor {@code work/steps.jsonl}.
   */
  public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
    List<String> outs = new ArrayList<>();
    boolean json = false;
    boolean optionsDone = false;
    for (String arg : args) {
      if (optionsDone || !arg.startsWith("-") || arg.equals("-")) {
        outs.add(arg);
      } else if (arg.equals("--")) {
        optionsDone = true;
      } else if (arg.equals("-h") || (arg.length() > 2 && "--help".startsWith(arg))) {
        stdout.println(USAGE);
        return 0;
      } else if (arg.length() > 2 && "--json".startsWith(arg)) {
        json = true;
      } else {
        stderr.println(USAGE);
        stderr.println("incbench-history-report: error: unrecognized arguments: " + arg);
        return 2;
      }
    }
    if (outs.isEmpty()) {
      stderr.println(USAGE);
      stderr.println("incbench-history-report: error: the following arguments are required: out");
      return 2;
    }
    List<Object> summaries = new ArrayList<>();
    int status = 0;
    for (String arg : outs) {
      Path out = Path.of(arg);
      if (!Files.exists(out.resolve("work").resolve("steps.jsonl"))
          && !Files.exists(out.resolve("replay.json"))) {
        stderr.println(pathString(arg) + ": neither replay.json nor work/steps.jsonl");
        status = 1;
        continue;
      }
      Replay rp = new Replay(out, arg);
      Summary summary = analyse(rp);
      try {
        writeReport(rp, summary);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      String dir = rp.outName;
      String report =
          dir.equals(".") ? "report.md" : dir.endsWith("/") ? dir + "report.md" : dir + "/report.md";
      (json ? stderr : stdout).println(report);
      summaries.add(summary.toJson());
    }
    if (json) {
      stdout.println(toJson(outs.size() == 1 && !summaries.isEmpty() ? summaries.get(0) : summaries));
    }
    return status;
  }

  private static Path writeReport(Replay rp, Summary summary) throws IOException {
    Path report = rp.out.resolve("report.md");
    Path tmp = rp.out.resolve(".report.md." + ProcessHandle.current().pid());
    Files.writeString(tmp, render(rp, summary), StandardCharsets.UTF_8);
    Files.move(tmp, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return report;
  }

  /** The lines of a file that parsed as JSON objects, and how many did not. */
  private record Rows(List<Map<String, Object>> rows, long bad) {}

  private static Rows readJsonl(Path path) {
    List<Map<String, Object>> rows = new ArrayList<>();
    long bad = 0;
    if (!Files.exists(path)) return new Rows(rows, bad);
    String text;
    try {
      text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (String raw : text.split("\r\n|\r|\n")) {
      String line = strip(raw);
      if (line.isEmpty()) continue;
      try {
        JsonValue value = Json.parse(line);
        if (value.isObject()) rows.add(dict(fromJson(value)));
        else bad++;
      } catch (RuntimeException e) {
        bad++;
      }
    }
    return new Rows(rows, bad);
  }

  private static Object fromJson(JsonValue v) {
    if (v.isObject()) {
      Map<String, Object> m = new LinkedHashMap<>();
      for (JsonObject.Member member : v.asObject()) m.put(member.getName(), fromJson(member.getValue()));
      return m;
    } else if (v.isArray()) {
      List<Object> l = new ArrayList<>();
      for (JsonValue x : v.asArray()) l.add(fromJson(x));
      return l;
    } else if (v.isString()) {
      return v.asString();
    } else if (v.isNumber()) {
      String literal = v.toString();
      if (literal.chars().anyMatch(c -> c == '.' || c == 'e' || c == 'E')) return Double.parseDouble(literal);
      try {
        return Long.parseLong(literal);
      } catch (NumberFormatException e) {
        return Double.parseDouble(literal);
      }
    } else if (v.isBoolean()) {
      return v.asBoolean();
    } else {
      return null;
    }
  }

  /** One step of the history: its clean line, the line of each mode, and its commit. */
  private static final class Step {
    final String name;
    final Object index;
    Map<String, Object> clean;
    final Map<String, Map<String, Object>> modes = new LinkedHashMap<>();
    final Map<String, Object> resets = new HashMap<>();
    Map<String, Object> meta;

    Step(String name, Object index) {
      this.name = name;
      this.index = index;
    }

    boolean hasMeta() {
      return meta != null && !meta.isEmpty();
    }

    String number() {
      int dash = name.indexOf('-');
      return dash < 0 ? name : name.substring(0, dash);
    }

    String sha() {
      if (hasMeta() && truthy(meta.get("commit"))) return head(str(meta.get("commit")), 9);
      int dash = name.indexOf('-');
      return dash < 0 ? "" : name.substring(dash + 1);
    }

    String subject() {
      return hasMeta() ? str(meta.getOrDefault("subject", "")) : "";
    }

    Object exported() {
      return hasMeta() ? meta.get("exported") : null;
    }

    String date() {
      return hasMeta() && truthy(meta.get("date")) ? head(str(meta.get("date")), 10) : "";
    }

    boolean cleanOk() {
      return truthy(clean.get("ok")) && clean.get("result") instanceof Map;
    }

    boolean modeOk(String m) {
      return truthy(modes.get(m).get("ok")) && modes.get(m).get("result") instanceof Map;
    }

    Map<String, Object> result(String m) {
      return dict(modes.get(m).get("result"));
    }

    Number compiled(String m) {
      return count(modes.get(m), "compiledCount");
    }

    Number necessary() {
      return count(clean, "necessaryCount");
    }

    Object verdict(String m) {
      return modes.get(m).get("verdict");
    }

    Long filesChanged() {
      if (hasMeta() && meta.get("changed") instanceof List<?> l) return (long) l.size();
      List<Object> all = list(clean.get("changed"));
      long changed = all.stream().filter(c -> str(c).contains("/src/")).count();
      return compare(count(clean, "changedCount"), all.size()) <= 0 ? changed : null;
    }

    /** The sources of the build at this step: what the clean build's first cycles compiled. */
    Number sourceCount() {
      if (!cleanOk()) return null;
      List<Object> runs = list(dict(clean.get("result")).get("zincRuns"));
      if (runs.isEmpty()) return null;
      Number total = 0L;
      for (Object r : runs) {
        List<Object> cycles = list(dict(r).get("cycles"));
        if (cycles.isEmpty()) return null;
        total = add(total, count(dict(cycles.get(0)), "sources"));
      }
      return truthy(total) ? total : null;
    }

    String label() {
      return number() + " `" + sha() + "`";
    }
  }

  /** What a replay directory holds, with the incomplete steps and the initial build set apart. */
  private static final class Replay {
    final Path out;
    final String outName;
    final Map<String, Object> info;
    final long badRows;
    final Path stepsFile;
    final Map<String, String> modeOptions = new LinkedHashMap<>();
    final List<String> modes;
    final List<String> incomplete = new ArrayList<>();
    final Step base;
    final List<Step> steps;

    Replay(Path out, String name) {
      this.out = out;
      this.outName = pathString(name);
      this.info = readInfo(out.resolve("replay.json"));
      Map<Object, Map<String, Object>> commits = new HashMap<>();
      for (Map<String, Object> c : readJsonl(out.resolve("commits.jsonl")).rows()) commits.put(c.get("step"), c);
      this.stepsFile = out.resolve("work").resolve("steps.jsonl");
      Rows read = readJsonl(stepsFile);
      this.badRows = read.bad();

      for (Object spec : list(info.get("modes"))) {
        String s = str(spec);
        int eq = s.indexOf('=');
        modeOptions.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "" : s.substring(eq + 1));
      }
      Set<String> seen = new LinkedHashSet<>();
      for (Map<String, Object> r : read.rows()) {
        Object m = r.get("mode");
        if (truthy(m) && !"clean".equals(m) && r.containsKey("verdict")) seen.add(str(m));
      }
      this.modes = new ArrayList<>(modeOptions.isEmpty() ? seen : modeOptions.keySet());
      for (String m : seen) {
        if (!modes.contains(m)) {
          modes.add(m);
          modeOptions.put(m, "(not in replay.json)");
        }
      }

      Map<String, Step> byName = new LinkedHashMap<>();
      for (Map<String, Object> r : read.rows()) {
        if (r.get("step") == null || r.get("mode") == null) continue;
        String mode = str(r.get("mode"));
        Step s = byName.computeIfAbsent(str(r.get("step")), n -> new Step(n, r.getOrDefault("index", 0L)));
        if (r.containsKey("reset")) s.resets.put(mode, r.get("reset"));
        else if (mode.equals("clean")) s.clean = r;
        else if (r.containsKey("verdict")) s.modes.put(mode, r);
      }
      List<Step> complete = new ArrayList<>();
      for (Step s : byName.values()) {
        s.meta = commits.get(s.name);
        if (s.clean != null && s.modes.keySet().containsAll(modes)) complete.add(s);
        else incomplete.add(s.name);
      }
      this.base =
          complete.stream()
              .filter(s -> s.name.equals("base") || (isNumber(s.index) && compare(num(s.index), 0L) == 0))
              .findFirst()
              .orElse(null);
      this.steps = complete.stream().filter(s -> s != base).toList();
    }

    private static Map<String, Object> readInfo(Path file) {
      try {
        JsonValue v = Json.parse(Files.readString(file));
        return v.isObject() ? dict(fromJson(v)) : new LinkedHashMap<>();
      } catch (IOException | RuntimeException e) {
        return new LinkedHashMap<>();
      }
    }

    Path diffs(Step s, String m) {
      return out.resolve("work").resolve("diffs").resolve(s.name).resolve(m);
    }
  }

  private record Finding(String step, String mode, String verdict, Object reset) {
    Map<String, Object> toJson() {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("step", step);
      j.put("mode", mode);
      j.put("verdict", verdict);
      j.put("reset", reset);
      return j;
    }
  }

  private record Precision(
      long steps,
      Number compiledTotal,
      Number compiledMedian,
      Number necessaryTotal,
      Double ratioOverall,
      Number ratioMedian,
      long stepsWithNecessary,
      Number cyclesTotal,
      Number cyclesMedian,
      Number classesRecompiled,
      Long fullRecompiles,
      long stepsNecessaryNotCompiled) {
    Map<String, Object> toJson() {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("steps", steps);
      j.put("compiledTotal", compiledTotal);
      j.put("compiledMedian", compiledMedian);
      j.put("necessaryTotal", necessaryTotal);
      j.put("ratioOverall", ratioOverall);
      j.put("ratioMedian", ratioMedian);
      j.put("stepsWithNecessary", stepsWithNecessary);
      j.put("cyclesTotal", cyclesTotal);
      j.put("cyclesMedian", cyclesMedian);
      j.put("classesRecompiled", classesRecompiled);
      j.put("fullRecompiles", fullRecompiles);
      j.put("stepsNecessaryNotCompiled", stepsNecessaryNotCompiled);
      return j;
    }
  }

  /** Sources compiled by two modes over the steps where both compiled. */
  private record PrecisionPair(
      String a,
      String b,
      long steps,
      long differ,
      long aFewer,
      Number aFewerBy,
      long bFewer,
      Number bFewerBy,
      Number aTotal,
      Number bTotal) {
    Map<String, Object> toJson() {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("steps", steps);
      j.put("differ", differ);
      j.put(a + "Fewer", aFewer);
      j.put(a + "FewerBy", aFewerBy);
      j.put(b + "Fewer", bFewer);
      j.put(b + "FewerBy", bFewerBy);
      j.put(a + "Total", aTotal);
      j.put(b + "Total", bTotal);
      return j;
    }
  }

  /** Wall time of two modes over the steps where both compiled; differences are b minus a. */
  private record PerformancePair(
      String a, String b, long steps, Number medianMillis, Number sumMillis, long aFaster, long bFaster) {
    Map<String, Object> toJson() {
      String key = b + "Minus" + capitalize(a);
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("steps", steps);
      j.put(key + "MedianMillis", medianMillis);
      j.put(key + "SumMillis", sumMillis);
      j.put(a + "FasterBy10Percent", aFaster);
      j.put(b + "FasterBy10Percent", bFaster);
      return j;
    }
  }

  private record Performance(
      long steps,
      Number incSumMillis,
      Number incMedianMillis,
      Number cleanSumMillis,
      Number cleanMedianMillis,
      Double incOverClean,
      Number incOverCleanMedianPerStep,
      long profiledSteps,
      Number profiledWallMillis,
      Number scalacMillis,
      Number scalacXsbtMillis,
      Number zincOwnMillis,
      Number otherMillis) {
    Map<String, Object> toJson() {
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("steps", steps);
      j.put("incSumMillis", incSumMillis);
      j.put("incMedianMillis", incMedianMillis);
      j.put("cleanSumMillis", cleanSumMillis);
      j.put("cleanMedianMillis", cleanMedianMillis);
      j.put("incOverClean", incOverClean);
      j.put("incOverCleanMedianPerStep", incOverCleanMedianPerStep);
      j.put("profiledSteps", profiledSteps);
      j.put("profiledWallMillis", profiledWallMillis);
      j.put("scalacMillis", scalacMillis);
      j.put("scalacXsbtMillis", scalacXsbtMillis);
      j.put("zincOwnMillis", zincOwnMillis);
      j.put("otherMillis", otherMillis);
      return j;
    }
  }

  /** Every number the report states. */
  private record Summary(
      String out,
      String repo,
      Object zincCommit,
      Map<String, String> modes,
      long complete,
      long incomplete,
      long unparsedLines,
      long cleanFailed,
      Map<String, Object> base,
      Map<String, Map<String, Long>> verdicts,
      List<Finding> findings,
      long stepsWithCompilerVerdict,
      long stepsWithNoiseVerdict,
      Map<String, Map<String, Long>> noiseByLevel,
      long compilerClasses,
      Map<String, Precision> precision,
      Map<String, PrecisionPair> precisionPairs,
      Map<String, PerformancePair> performancePairs,
      Map<String, Performance> performance) {
    Map<String, Object> toJson() {
      Map<String, Object> steps = new LinkedHashMap<>();
      steps.put("complete", complete);
      steps.put("incomplete", incomplete);
      steps.put("unparsedLines", unparsedLines);
      steps.put("cleanFailed", cleanFailed);
      Map<String, Object> j = new LinkedHashMap<>();
      j.put("out", out);
      j.put("repo", repo);
      j.put("zincCommit", zincCommit);
      j.put("modes", modes);
      j.put("steps", steps);
      j.put("base", base);
      j.put("verdicts", verdicts);
      j.put("findings", findings.stream().map(Finding::toJson).toList());
      j.put("stepsWithCompilerVerdict", stepsWithCompilerVerdict);
      j.put("stepsWithNoiseVerdict", stepsWithNoiseVerdict);
      j.put("noiseByLevel", noiseByLevel);
      j.put("compilerClasses", compilerClasses);
      j.put("precision", mapValues(precision, Precision::toJson));
      j.put("precisionPairs", mapValues(precisionPairs, PrecisionPair::toJson));
      j.put("performancePairs", mapValues(performancePairs, PerformancePair::toJson));
      j.put("performance", mapValues(performance, Performance::toJson));
      return j;
    }
  }

  private static Number wall(Map<String, Object> row) {
    return count(dict(row.get("result")), "wallMillis");
  }

  private static Object cycles(Map<String, Object> row) {
    return row.get("result") instanceof Map ? dict(row.get("result")).get("rounds") : null;
  }

  private static Number cyclesOrZero(Map<String, Object> row) {
    Object c = cycles(row);
    return truthy(c) ? num(c) : 0L;
  }

  /** The time in scalac's phases, and the part of it in Zinc's own phases; null without phases. */
  private record ScalacMillis(Number total, Number xsbt) {}

  private static ScalacMillis scalacMillis(Map<String, Object> result) {
    List<Object> phases = list(result.get("scalacPhases"));
    if (phases.isEmpty()) return null;
    Number total = sum(phases.stream().map(p -> count(dict(p), "millis")));
    Number xsbt =
        sum(
            phases.stream()
                .filter(p -> str(dict(p).getOrDefault("phase", "")).startsWith("xsbt-"))
                .map(p -> count(dict(p), "millis")));
    return new ScalacMillis(total, xsbt);
  }

  private static Stream<Map.Entry<String, Object>> zincParts(Map<String, Object> result) {
    return dict(result.get("zinc")).entrySet().stream()
        .filter(e -> !ZINC_TOTALS.contains(e.getKey()) && isNumber(e.getValue()));
  }

  private static Number zincOwnMillis(Map<String, Object> result) {
    if (!truthy(result.get("zinc"))) return null;
    return sum(zincParts(result).map(e -> num(e.getValue())));
  }

  /** The module and the first cycle of a line that reports any API change. */
  private record CycleChanges(Object module, Map<String, Object> cycle) {}

  private static CycleChanges firstCycleChanges(Map<String, Object> row) {
    if (!(row.get("result") instanceof Map)) return null;
    for (Object run : list(dict(row.get("result")).get("zincRuns"))) {
      for (Object c : list(dict(run).get("cycles"))) {
        if (truthy(dict(c).get("changes"))) return new CycleChanges(dict(run).get("module"), dict(c));
      }
    }
    return null;
  }

  private static List<Map<String, Object>> differences(Map<String, Object> row) {
    return list(row.get("differences")).stream().map(Report::dict).toList();
  }

  private static boolean capped(Map<String, Object> row) {
    return compare(count(row, "differenceCount"), list(row.get("differences")).size()) > 0;
  }

  private static Summary analyse(Replay rp) {
    List<String> modes = rp.modes;
    List<Step> steps = rp.steps;

    String repo = pathName(pathString(str(rp.info.getOrDefault("repo", ""))));
    Map<String, String> modeOptions = new LinkedHashMap<>();
    for (String m : modes) modeOptions.put(m, rp.modeOptions.getOrDefault(m, ""));
    Map<String, Object> base = null;
    if (rp.base != null) {
      base = new LinkedHashMap<>();
      for (String m : modes) base.put(m, rp.base.verdict(m));
    }

    Map<String, Map<String, Long>> verdicts = new LinkedHashMap<>();
    for (String m : modes) {
      Map<String, Long> counts = new LinkedHashMap<>();
      VERDICTS.forEach(v -> counts.put(v, 0L));
      verdicts.put(m, counts);
    }
    List<Finding> findings = new ArrayList<>();
    Map<String, Map<String, Long>> noise = new LinkedHashMap<>();
    for (String m : modes) noise.put(m, new TreeMap<>(BY_CODE_POINT));
    Set<Object> compilerClasses = new HashSet<>();
    for (Step s : steps) {
      for (String m : modes) {
        String v = str(s.verdict(m));
        verdicts.get(m).merge(v, 1L, Long::sum);
        if (FINDINGS.contains(v)) findings.add(new Finding(s.name, m, v, s.resets.get(m)));
        for (Map<String, Object> d : differences(s.modes.get(m))) {
          if ("noise".equals(d.get("kind"))) noise.get(m).merge(orElse(d.get("level"), "?"), 1L, Long::sum);
          else if ("compiler".equals(d.get("kind"))) compilerClasses.add(d.get("class"));
        }
      }
    }

    Map<String, Precision> precision = new LinkedHashMap<>();
    for (String m : modes) {
      List<Step> rows = steps.stream().filter(s -> s.cleanOk() && s.modeOk(m)).toList();
      List<Number> compiled = rows.stream().map(s -> s.compiled(m)).toList();
      List<Number> necessary = rows.stream().map(Step::necessary).toList();
      List<Number> ratios = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        if (compare(necessary.get(i), 0L) > 0) ratios.add(divide(compiled.get(i), necessary.get(i)));
      }
      List<Number> counts = rows.stream().map(Step::sourceCount).toList();
      Long full = null;
      if (counts.stream().noneMatch(Objects::isNull)) {
        full = 0L;
        for (int i = 0; i < rows.size(); i++) if (compare(compiled.get(i), counts.get(i)) >= 0) full++;
      }
      List<Number> rounds = rows.stream().map(s -> cyclesOrZero(s.modes.get(m))).toList();
      Number necessaryTotal = sum(necessary);
      precision.put(
          m,
          new Precision(
              rows.size(),
              sum(compiled),
              median(compiled),
              necessaryTotal,
              truthy(necessaryTotal) ? divide(sum(compiled), necessaryTotal) : null,
              median(ratios),
              ratios.size(),
              sum(rounds),
              median(rounds),
              sum(rows.stream().map(s -> count(s.result(m), "recompiled"))),
              full,
              rows.stream().filter(s -> truthy(s.modes.get(m).get("necessaryNotCompiled"))).count()));
    }

    Map<String, PrecisionPair> precisionPairs = new LinkedHashMap<>();
    Map<String, PerformancePair> performancePairs = new LinkedHashMap<>();
    for (int i = 0; i < modes.size(); i++) {
      for (int j = i + 1; j < modes.size(); j++) {
        String a = modes.get(i);
        String b = modes.get(j);
        List<Step> rows = steps.stream().filter(s -> s.cleanOk() && s.modeOk(a) && s.modeOk(b)).toList();
        long differ = 0;
        long aFewer = 0;
        long bFewer = 0;
        List<Number> aFewerBy = new ArrayList<>();
        List<Number> bFewerBy = new ArrayList<>();
        long aFaster = 0;
        long bFaster = 0;
        List<Number> diffs = new ArrayList<>();
        for (Step s : rows) {
          Number x = s.compiled(a);
          Number y = s.compiled(b);
          int c = compare(x, y);
          if (c != 0) differ++;
          if (c < 0) {
            aFewer++;
            aFewerBy.add(subtract(y, x));
          } else if (c > 0) {
            bFewer++;
            bFewerBy.add(subtract(x, y));
          }
          Number wa = wall(s.modes.get(a));
          Number wb = wall(s.modes.get(b));
          diffs.add(subtract(wb, wa));
          if (wa.doubleValue() < 0.9 * wb.doubleValue()) aFaster++;
          if (wb.doubleValue() < 0.9 * wa.doubleValue()) bFaster++;
        }
        precisionPairs.put(
            a + " vs " + b,
            new PrecisionPair(
                a,
                b,
                rows.size(),
                differ,
                aFewer,
                sum(aFewerBy),
                bFewer,
                sum(bFewerBy),
                sum(rows.stream().map(s -> s.compiled(a))),
                sum(rows.stream().map(s -> s.compiled(b)))));
        performancePairs.put(
            a + " vs " + b, new PerformancePair(a, b, rows.size(), median(diffs), sum(diffs), aFaster, bFaster));
      }
    }

    Map<String, Performance> performance = new LinkedHashMap<>();
    for (String m : modes) {
      List<Step> rows = steps.stream().filter(s -> s.cleanOk() && s.modeOk(m)).toList();
      List<Number> inc = rows.stream().map(s -> wall(s.modes.get(m))).toList();
      List<Number> clean = rows.stream().map(s -> wall(s.clean)).toList();
      List<Number> perStep = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        if (compare(clean.get(i), 0L) > 0) perStep.add(divide(inc.get(i), clean.get(i)));
      }
      List<Step> prof =
          rows.stream()
              .filter(s -> scalacMillis(s.result(m)) != null && zincOwnMillis(s.result(m)) != null)
              .toList();
      Number scalac = sum(prof.stream().map(s -> scalacMillis(s.result(m)).total()));
      Number xsbt = sum(prof.stream().map(s -> scalacMillis(s.result(m)).xsbt()));
      Number zincOwn = sum(prof.stream().map(s -> zincOwnMillis(s.result(m))));
      Number profiledWall = sum(prof.stream().map(s -> wall(s.modes.get(m))));
      Number cleanSum = sum(clean);
      performance.put(
          m,
          new Performance(
              rows.size(),
              sum(inc),
              median(inc),
              cleanSum,
              median(clean),
              truthy(cleanSum) ? divide(sum(inc), cleanSum) : null,
              median(perStep),
              prof.size(),
              profiledWall,
              scalac,
              xsbt,
              roundToTenth(zincOwn),
              roundToTenth(subtract(subtract(profiledWall, scalac), zincOwn))));
    }

    return new Summary(
        rp.outName,
        repo.isEmpty() ? null : repo,
        rp.info.get("zincCommit"),
        modeOptions,
        steps.size(),
        rp.incomplete.size(),
        rp.badRows,
        steps.stream().filter(s -> !s.cleanOk()).count(),
        base,
        verdicts,
        findings,
        steps.stream().filter(s -> modes.stream().anyMatch(m -> "compiler".equals(s.verdict(m)))).count(),
        steps.stream().filter(s -> modes.stream().anyMatch(m -> "noise".equals(s.verdict(m)))).count(),
        noise,
        compilerClasses.size(),
        precision,
        precisionPairs,
        performancePairs,
        performance);
  }

  private static String bottomLine(Replay rp, Summary summary) {
    List<String> modes = rp.modes;
    long n = rp.steps.size();
    if (n == 0) return "No step is complete yet, so there is nothing to report.";
    List<String> parts = new ArrayList<>();
    parts.add(plural(n, "step") + " replayed");
    long failed = summary.cleanFailed();
    if (failed == n) {
      return plural(n, "step")
          + " replayed, and the clean build failed at every one of them, so no incremental "
          + "build was compared with a clean build and this run says nothing about soundness, precision or "
          + "performance. The errors are listed under Soundness; when the initial build fails too, the "
          + "export's classpath or options probably do not fit this part of the history.";
    }
    if (failed != 0) {
      parts.add("the clean build failed at " + failed + " of them, which leaves " + (n - failed) + " compared");
    }
    if (summary.findings().isEmpty()) {
      parts.add(
          "no findings under "
              + (modes.size() == 2 ? "either mode" : modes.size() > 1 ? "any mode" : "`" + modes.get(0) + "`"));
    } else {
      parts.add(
          "findings against the incremental compiler: "
              + modes.stream()
                  .map(m -> summary.findings().stream().filter(f -> f.mode().equals(m)).count() + " under `" + m + "`")
                  .collect(Collectors.joining(", ")));
    }
    parts.add(
        plural(summary.stepsWithCompilerVerdict(), "step")
            + " with compiler nondeterminism and "
            + summary.stepsWithNoiseVerdict()
            + " with only noise");
    for (PrecisionPair p : summary.precisionPairs().values()) {
      Number ta = p.aTotal();
      Number tb = p.bTotal();
      if (truthy(ta) && truthy(tb) && compare(ta, tb) != 0) {
        boolean bLower = compare(tb, ta) < 0;
        Number lo = bLower ? tb : ta;
        Number hi = bLower ? ta : tb;
        parts.add(
            "`"
                + (bLower ? p.b() : p.a())
                + "` compiled "
                + fixed(100.0 * subtract(hi, lo).doubleValue() / hi.doubleValue(), 1)
                + "% fewer sources than `"
                + (bLower ? p.a() : p.b())
                + "` over the history ("
                + commas(lo)
                + " against "
                + commas(hi)
                + ")");
      } else if (compare(ta, tb) == 0) {
        parts.add(
            "`" + p.a() + "` and `" + p.b() + "` compiled the same number of sources over the history (" + commas(ta) + ")");
      }
    }
    for (String m : modes) {
      Precision p = summary.precision().get(m);
      Performance q = summary.performance().get(m);
      if (p.ratioOverall() != null && q.incOverClean() != null) {
        parts.add(
            "`"
                + m
                + "` compiled "
                + fixed(p.ratioOverall(), 1)
                + " times the necessary sources and took "
                + fixed(100 * q.incOverClean(), 0)
                + "% of the clean build time in total");
      }
    }
    if (rp.base != null && modes.stream().anyMatch(m -> !"same".equals(rp.base.verdict(m)))) {
      parts.add("the initial build's verdict is not `same` (see Soundness)");
    }
    String joined = String.join("; ", parts);
    int first = joined.offsetByCodePoints(0, 1);
    return joined.substring(0, first).toUpperCase(Locale.ROOT) + joined.substring(first) + ".";
  }

  private static List<String> header(Replay rp, Summary summary) {
    Map<String, Object> info = rp.info;
    List<Step> steps = rp.steps;
    List<String> lines = new ArrayList<>();
    lines.add("# Incremental compile replay: " + (summary.repo() != null ? summary.repo() : pathName(rp.outName)));
    lines.add("");
    String start = truthy(info.get("start")) ? head(str(info.get("start")), 9) : "";
    Step last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
    List<String> dated = steps.stream().map(Step::date).filter(d -> !d.isEmpty()).toList();
    String range = !start.isEmpty() && last != null ? "`" + start + "`..`" + last.sha() + "`" : "unknown";
    boolean sbtExport = truthy(info.get("sbtExport"));
    lines.add(
        "- Repository: `"
            + str(summary.repo())
            + "`, first-parent commits "
            + range
            + (dated.isEmpty() ? "" : ", committed " + dated.get(0) + " to " + dated.get(dated.size() - 1))
            + (truthy(info.get("exportCommit")) && !sbtExport
                ? "; the export (modules, classpaths, options) is of `" + head(str(info.get("exportCommit")), 9) + "`"
                : "")
            + ".");
    if (sbtExport) {
      List<Step> exported = steps.stream().filter(s -> truthy(s.exported())).toList();
      List<Step> failed = steps.stream().filter(s -> Boolean.FALSE.equals(s.exported())).toList();
      lines.add(
          "- Build: re-exported by the project's sbt at the start and at each commit that changes `*.sbt` or `project/`, "
              + "so dependencies, options and generated sources follow the history: "
              + plural(exported.size(), "step")
              + " took a new export"
              + (failed.isEmpty()
                  ? ""
                  : ", and "
                      + plural(failed.size(), "step")
                      + " ("
                      + take(failed, 5).stream().map(s -> code(s.name)).collect(Collectors.joining(", "))
                      + ") did not export and kept the previous one")
              + ". Those steps are marked `[build]` under Steps.");
    }
    String line = "- Steps: " + steps.size() + " complete";
    if (!rp.incomplete.isEmpty()) {
      line +=
          ", "
              + rp.incomplete.size()
              + " in progress and left out ("
              + take(rp.incomplete, 3).stream().map(Report::code).collect(Collectors.joining(", "))
              + ")";
    }
    line += "; the number planned is not recorded in `replay.json`.";
    if (rp.badRows != 0) {
      line +=
          " "
              + plural(rp.badRows, "line")
              + " of `steps.jsonl` did not parse (a line still being written) and "
              + (rp.badRows == 1 ? "was" : "were")
              + " skipped.";
    }
    lines.add(line);
    List<String> missing = steps.stream().filter(s -> !s.hasMeta()).map(s -> s.name).toList();
    if (!missing.isEmpty()) {
      boolean one = missing.size() == 1;
      lines.add(
          "- "
              + plural(missing.size(), "step")
              + " ("
              + take(missing, 3).stream().map(Report::code).collect(Collectors.joining(", "))
              + ") "
              + (one ? "is" : "are")
              + " not in `commits.jsonl` yet, so "
              + (one ? "its" : "their")
              + " subject, date and files changed are blank.");
    }
    String modules = list(info.get("modules")).stream().map(Report::code).collect(Collectors.joining(", "));
    lines.add(
        "- Modules: "
            + (modules.isEmpty() ? "unknown" : modules)
            + "; Scala "
            + str(info.getOrDefault("scala", "unknown"))
            + ".");
    lines.add(
        "- Modes: "
            + rp.modes.stream()
                .map(m -> "`" + m + "` (" + orElse(rp.modeOptions.get(m), "no options of its own") + ")")
                .collect(Collectors.joining("; "))
            + (truthy(info.get("incOptions"))
                ? ". Options of every mode and of the clean build: "
                    + list(info.get("incOptions")).stream().map(Report::code).collect(Collectors.joining(", "))
                    + "."
                : "."));
    lines.add(
        "- Zinc: `"
            + head(orElse(info.get("zincCommit"), "unknown"), 9)
            + "`"
            + (truthy(info.get("zinc")) ? " in `" + str(info.get("zinc")) + "`" : "")
            + ".");
    String when = "- Started " + str(info.getOrDefault("started", "unknown"));
    if (Files.exists(rp.stepsFile)) {
      try {
        when +=
            "; `steps.jsonl` last written "
                + DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx", Locale.ROOT)
                    .format(Files.getLastModifiedTime(rp.stepsFile).toInstant().atZone(ZoneId.systemDefault()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    Number total = sum(steps.stream().filter(Step::hasMeta).map(s -> count(s.meta, "seconds")));
    if (truthy(total)) when += "; the steps took " + fixed(total.doubleValue() / 60, 1) + " minutes";
    lines.add(when + ".");
    lines.add("");
    lines.add(bottomLine(rp, summary));
    lines.add("");
    return lines;
  }

  /** Lines for the differences of the given kinds at a step, with their evidence, and their number. */
  private record DiffList(List<String> lines, int count) {}

  private static DiffList diffList(Replay rp, Step s, String m, Set<String> kinds, int cap) {
    List<Map<String, Object>> diffs =
        differences(s.modes.get(m)).stream().filter(d -> kinds.contains(d.get("kind"))).toList();
    Path root = rp.diffs(s, m);
    List<String> lines = new ArrayList<>();
    for (Map<String, Object> d : take(diffs, cap)) {
      String key = str(d.getOrDefault("class", ""));
      String line =
          "  - `" + key + "`: " + str(d.get("kind")) + (truthy(d.get("level")) ? " at level " + str(d.get("level")) : "");
      if (truthy(d.get("pickleOnly"))) line += ", only in the Scala signature (bytecode equal)";
      line += truthy(d.get("fresh")) ? ", written by this step" : ", not written by this step";
      if (Files.exists(root.resolve(key + ".diff"))) {
        line += "; diff `work/diffs/" + s.name + "/" + m + "/" + key + ".diff`";
      } else {
        List<String> sides =
            Stream.of("inc", "clean").filter(side -> Files.exists(root.resolve(side).resolve(key))).toList();
        if (!sides.isEmpty()) line += "; classfile kept for " + String.join(" and ", sides) + " only";
      }
      lines.add(line);
    }
    if (diffs.size() > cap) lines.add("  - and " + commas(diffs.size() - cap) + " more.");
    return new DiffList(lines, diffs.size());
  }

  private static String evidencePath(Replay rp, Step s, Collection<String> modes, String key) {
    for (String m : modes) {
      if (Files.exists(rp.diffs(s, m).resolve(key + ".diff"))) {
        return "`work/diffs/" + s.name + "/" + m + "/" + key + ".diff`";
      }
    }
    return "-";
  }

  private static List<Object> errors(Map<String, Object> row) {
    return list(row.get("errors"));
  }

  private static List<Object> cleanErrors(Step s) {
    List<Object> errs = errors(s.clean);
    return errs.isEmpty() ? List.of(NO_ERROR) : errs;
  }

  /** A class with unexplained or explained differences, over the steps and modes that show it. */
  private static final class ClassGroup {
    final Map<String, Step> steps = new LinkedHashMap<>();
    final Set<String> modes = new LinkedHashSet<>();
    final Set<String> levels = new TreeSet<>(BY_CODE_POINT);
    int count;
  }

  private static List<String> soundness(Replay rp, Summary summary) {
    List<String> modes = rp.modes;
    List<Step> steps = rp.steps;
    Map<String, Map<String, Long>> verdicts = summary.verdicts();
    List<String> lines = new ArrayList<>(List.of("## Soundness", ""));
    List<String> used =
        VERDICTS.stream()
            .filter(v -> modes.stream().anyMatch(m -> verdicts.get(m).getOrDefault(v, 0L) != 0))
            .collect(Collectors.toCollection(ArrayList::new));
    Set<String> extra = new TreeSet<>(BY_CODE_POINT);
    for (String m : modes) verdicts.get(m).keySet().stream().filter(v -> !VERDICTS.contains(v)).forEach(extra::add);
    used.addAll(extra);
    lines.add(
        "Steps by verdict, over "
            + plural(steps.size(), "step")
            + ". Findings against the incremental compiler are `stale`, `missed-error` and `spurious-error`.");
    lines.add("");
    List<String> heads = new ArrayList<>(List.of("Mode"));
    heads.addAll(used);
    heads.add("findings");
    List<List<Object>> verdictRows = new ArrayList<>();
    for (String m : modes) {
      List<Object> row = new ArrayList<>(List.of(code(m)));
      used.forEach(v -> row.add(verdicts.get(m).getOrDefault(v, 0L)));
      row.add(FINDINGS.stream().mapToLong(v -> verdicts.get(m).getOrDefault(v, 0L)).sum());
      verdictRows.add(row);
    }
    lines.addAll(table(heads, verdictRows, range(1, used.size() + 2)));
    lines.add("");

    if (rp.base != null) {
      Step base = rp.base;
      List<String> bad = modes.stream().filter(m -> !"same".equals(base.verdict(m))).toList();
      if (bad.isEmpty()) {
        lines.add(
            "The initial build (`base`) is `same` under every mode: each mode's own clean build equals the reference.");
      } else {
        lines.add(
            "The initial build (`base`) is not `same`: "
                + bad.stream().map(m -> "`" + m + "` is `" + str(base.verdict(m)) + "`").collect(Collectors.joining(", "))
                + ".");
        List<Object> errs = errors(base.clean);
        if (errs.isEmpty()) {
          errs = bad.stream().map(m -> errors(base.modes.get(m))).filter(e -> !e.isEmpty()).findFirst().orElse(List.of());
        }
        if (!errs.isEmpty()) {
          lines.add(
              "The first of its "
                  + errs.size()
                  + (errs.size() >= 20 ? " or more" : "")
                  + " compile errors: "
                  + take(distinct(errs), 3).stream().map(e -> code(trunc(e, 120))).collect(Collectors.joining("; "))
                  + ".");
        }
        for (String m : bad) {
          DiffList dl = diffList(rp, base, m, Set.of("stale", "missing", "extra", "compiler", "noise"), 8);
          if (dl.count() != 0) {
            lines.add("- `" + m + "`: " + plural(dl.count(), "difference"));
            lines.addAll(dl.lines());
          }
        }
      }
      lines.add("");
    } else {
      lines.addAll(List.of("The initial build (`base`) is not in `steps.jsonl`.", ""));
    }

    lines.addAll(List.of("### Findings", ""));
    Map<String, List<String>> found = new LinkedHashMap<>();
    Map<String, Step> foundSteps = new HashMap<>();
    for (Step s : steps) {
      for (String m : modes) {
        if (FINDINGS.contains(str(s.verdict(m)))) {
          found.computeIfAbsent(s.name, k -> new ArrayList<>()).add(m);
          foundSteps.put(s.name, s);
        }
      }
    }
    if (found.isEmpty()) {
      lines.add(
          "None: no step is `stale`, `missed-error` or `spurious-error` under "
              + (modes.size() > 1 ? "any mode" : "the mode")
              + ".");
      lines.add("");
    }
    for (Map.Entry<String, List<String>> e : found.entrySet()) {
      Step s = foundSteps.get(e.getKey());
      List<String> ms = e.getValue();
      lines.add(
          "#### "
              + s.name
              + ": "
              + ms.stream().map(m -> str(s.verdict(m)) + " under " + m).collect(Collectors.joining(", ")));
      lines.add("");
      Long files = s.filesChanged();
      lines.add(
          "Commit `"
              + s.sha()
              + "`"
              + (s.date().isEmpty() ? "" : ", " + s.date())
              + (s.subject().isEmpty() ? "" : ": " + esc(s.subject()))
              + ". "
              + plural(files == null ? 0 : files, "file")
              + " changed, "
              + str(s.necessary())
              + " necessary.");
      lines.add("");
      for (String m : ms) {
        Map<String, Object> row = s.modes.get(m);
        String v = str(row.get("verdict"));
        lines.add(
            "- `"
                + m
                + "`: `"
                + v
                + "`; compiled "
                + plural(s.compiled(m), "source")
                + (cycles(row) != null ? " in " + plural(num(cycles(row)), "cycle") : "")
                + "; verdict after the reset to a clean build: "
                + (s.resets.containsKey(m) ? code(s.resets.get(m)) : "not recorded")
                + ".");
        if (v.equals("stale")) {
          DiffList dl = diffList(rp, s, m, Set.of("stale", "missing", "extra"), 15);
          lines.add(
              "  - "
                  + plural(dl.count(), "class", "classes")
                  + " stale, missing or extra"
                  + (capped(row)
                      ? " (of " + str(row.get("differenceCount")) + " differences; the list in `steps.jsonl` is capped at 200)"
                      : "")
                  + ":");
          dl.lines().forEach(x -> lines.add("  " + x));
          Map<String, Long> other = new TreeMap<>(BY_CODE_POINT);
          for (Map<String, Object> d : differences(row)) {
            if ("compiler".equals(d.get("kind")) || "noise".equals(d.get("kind"))) {
              other.merge(str(d.get("kind")), 1L, Long::sum);
            }
          }
          if (!other.isEmpty()) {
            lines.add(
                "  - other differences at this step: "
                    + other.entrySet().stream().map(o -> o.getValue() + " " + o.getKey()).collect(Collectors.joining(", "))
                    + ".");
          }
        } else {
          boolean spurious = v.equals("spurious-error");
          List<Object> errs = errors(spurious ? row : s.clean);
          lines.add(
              "  - the "
                  + (spurious ? "incremental" : "clean")
                  + " build failed and the other did not; its errors"
                  + (errs.size() >= 20 ? " (first 20)" : "")
                  + ":");
          take(errs, 10).forEach(err -> lines.add("    - `" + trunc(err, 200) + "`"));
          if (errs.size() > 10) lines.add("    - and " + (errs.size() - 10) + " more.");
        }
        List<Object> notCompiled = list(row.get("necessaryNotCompiled"));
        if (!notCompiled.isEmpty()) {
          lines.add(
              "  - necessary but not compiled ("
                  + notCompiled.size()
                  + (notCompiled.size() >= 200 ? " or more" : "")
                  + "): "
                  + take(notCompiled, 8).stream().map(Report::code).collect(Collectors.joining(", "))
                  + (notCompiled.size() > 8 ? " and " + (notCompiled.size() - 8) + " more" : "")
                  + ".");
        } else {
          lines.add("  - necessary but not compiled: none.");
        }
        Path root = rp.diffs(s, m);
        if (Files.isDirectory(root)) {
          List<String> logs = new ArrayList<>();
          try (var found2 = Files.newDirectoryStream(root, "invalidation-*.log")) {
            found2.forEach(p -> logs.add(p.getFileName().toString()));
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
          logs.sort(BY_CODE_POINT);
          lines.add(
              "  - evidence: `work/diffs/"
                  + s.name
                  + "/"
                  + m
                  + "/` (`inc/` and `clean/` classfiles, `.class.diff` files"
                  + (logs.isEmpty() ? "" : ", " + logs.stream().map(Report::code).collect(Collectors.joining(", ")))
                  + ").");
        } else {
          lines.add("  - evidence: none under `work/diffs/" + s.name + "/" + m + "/`.");
        }
      }
      lines.add("");
    }

    lines.addAll(List.of("### Compiler nondeterminism", ""));
    Map<String, ClassGroup> groups = new LinkedHashMap<>();
    Map<String, ClassGroup> noisy = new LinkedHashMap<>();
    Map<String, Long> levels = new HashMap<>();
    Map<String, Map<String, Long>> perMode = new HashMap<>();
    modes.forEach(m -> perMode.put(m, new HashMap<>()));
    long cappedLines = 0;
    for (Step s : steps) {
      for (String m : modes) {
        Map<String, Object> row = s.modes.get(m);
        if (capped(row)) cappedLines++;
        for (Map<String, Object> d : differences(row)) {
          if ("compiler".equals(d.get("kind"))) {
            ClassGroup g = groups.computeIfAbsent(str(d.get("class")), k -> new ClassGroup());
            g.steps.put(s.name, s);
            g.modes.add(m);
          } else if ("noise".equals(d.get("kind"))) {
            String level = orElse(d.get("level"), "?");
            levels.merge(level, 1L, Long::sum);
            perMode.get(m).merge(level, 1L, Long::sum);
            ClassGroup c = noisy.computeIfAbsent(str(d.get("class")), k -> new ClassGroup());
            c.count++;
            c.steps.put(s.name, s);
            c.modes.add(m);
            c.levels.add(level);
          }
        }
      }
    }
    if (groups.isEmpty()) {
      lines.add(
          "None: no class that a step wrote differs from the clean build beyond what the normalisation levels explain.");
      lines.add("");
    } else {
      lines.add(
          plural(groups.size(), "class", "classes")
              + " that an incremental compile wrote and that "
              + (groups.size() == 1 ? "differs" : "differ")
              + " from the clean build's with no normalisation level to explain it, over "
              + plural(summary.stepsWithCompilerVerdict(), "step")
              + " with the verdict `compiler`. A class is listed once, however many steps and modes show it.");
      lines.add("");
      List<Map.Entry<String, ClassGroup>> sorted = new ArrayList<>(groups.entrySet());
      sorted.sort(
          Comparator.comparingInt((Map.Entry<String, ClassGroup> e) -> -e.getValue().steps.size())
              .thenComparing(Map.Entry::getKey, BY_CODE_POINT));
      List<List<Object>> rows = new ArrayList<>();
      for (Map.Entry<String, ClassGroup> e : sorted) {
        ClassGroup g = e.getValue();
        Step first = g.steps.values().iterator().next();
        rows.add(
            List.of(
                code(e.getKey()),
                g.steps.size(),
                String.join(", ", g.modes),
                first.label(),
                evidencePath(rp, first, g.modes, e.getKey())));
      }
      lines.addAll(table(List.of("Class", "Steps", "Modes", "First step", "Evidence"), rows, i -> i == 1, CAP, "classes"));
      lines.add("");
    }

    lines.addAll(List.of("### Noise", ""));
    if (levels.isEmpty()) {
      lines.addAll(List.of("None: no difference was explained by a normalisation level.", ""));
    } else {
      lines.add(
          "Differences that a normalisation level explains, counted once per class, step and mode ("
              + commas(levels.values().stream().mapToLong(Long::longValue).sum())
              + " in all, over "
              + commas(noisy.size())
              + " classes). A class is counted at the lowest level at which the two sides are equal.");
      lines.add("");
      List<String> order = LEVELS.stream().filter(levels::containsKey).collect(Collectors.toCollection(ArrayList::new));
      levels.keySet().stream().filter(l -> !LEVELS.contains(l)).sorted(BY_CODE_POINT).forEach(order::add);
      List<String> heads2 = new ArrayList<>(List.of("Level"));
      modes.forEach(m -> heads2.add(code(m)));
      heads2.add("Total");
      List<List<Object>> rows = new ArrayList<>();
      for (String level : order) {
        List<Object> row = new ArrayList<>(List.of(code(level)));
        modes.forEach(m -> row.add(perMode.get(m).getOrDefault(level, 0L)));
        row.add(levels.get(level));
        rows.add(row);
      }
      lines.addAll(table(heads2, rows, range(1, modes.size() + 2)));
      lines.add("");
      if (cappedLines != 0) {
        lines.add(
            plural(cappedLines, "step-mode line")
                + " had more differences than the 200 that `steps.jsonl` lists, so these counts are a lower bound.");
        lines.add("");
      }
      List<Map.Entry<String, ClassGroup>> sorted = new ArrayList<>(noisy.entrySet());
      sorted.sort(
          Comparator.comparingInt((Map.Entry<String, ClassGroup> e) -> -e.getValue().steps.size())
              .thenComparingInt(e -> -e.getValue().count)
              .thenComparing(Map.Entry::getKey, BY_CODE_POINT));
      lines.addAll(List.of("The most frequent classes:", ""));
      List<List<Object>> classRows = new ArrayList<>();
      for (Map.Entry<String, ClassGroup> e : sorted) {
        ClassGroup c = e.getValue();
        classRows.add(List.of(code(e.getKey()), String.join(", ", c.levels), c.steps.size(), String.join(", ", c.modes)));
      }
      lines.addAll(table(List.of("Class", "Level", "Steps", "Modes"), classRows, i -> i == 2, 15, "classes"));
      lines.add("");
    }

    lines.addAll(List.of("### Steps where the clean build failed", ""));
    List<Step> failed = steps.stream().filter(s -> !s.cleanOk()).toList();
    if (failed.isEmpty()) {
      lines.addAll(List.of("None.", ""));
    } else {
      lines.add(
          plural(failed.size(), "step")
              + " of "
              + steps.size()
              + ". A step whose incremental builds fail too is `same-fail` and is not compared further. Grouped by the first error line of the clean build, shown with the next distinct ones (file name and message; warnings that the build's options make fatal are among them):");
      lines.add("");
      Map<Object, List<Step>> byError = new LinkedHashMap<>();
      for (Step s : failed) byError.computeIfAbsent(cleanErrors(s).get(0), k -> new ArrayList<>()).add(s);
      List<List<Object>> rows = new ArrayList<>();
      for (List<Step> ss : byError.values()) {
        String firstErrors =
            take(distinct(cleanErrors(ss.get(0))), 3).stream().map(e -> code(trunc(e, 90))).collect(Collectors.joining("; "));
        rows.add(List.of(firstErrors, ss.size(), ss.get(0).label(), ss.get(ss.size() - 1).label()));
      }
      lines.addAll(table(List.of("First errors", "Steps", "First", "Last"), rows, i -> i == 1, CAP, "distinct errors"));
      lines.add("");
      long odd =
          failed.stream()
              .filter(
                  s ->
                      modes.stream()
                          .anyMatch(m -> "same-fail".equals(s.verdict(m)) && compare(s.compiled(m), 0L) > 0))
              .map(s -> s.name)
              .distinct()
              .count();
      if (odd != 0) {
        lines.add(
            "In "
                + odd
                + " of these steps a failing incremental compile still recorded compiled sources (`compiledCount` above zero); they are not counted under Precision.");
        lines.add("");
      }
    }
    return lines;
  }

  private static List<Object> row(String label, List<String> modes, Function<String, Object> cell) {
    List<Object> row = new ArrayList<>(List.of(label));
    modes.forEach(m -> row.add(cell.apply(m)));
    return row;
  }

  private static List<String> modeHeads(String first, List<String> modes) {
    List<String> heads = new ArrayList<>(List.of(first));
    modes.forEach(m -> heads.add(code(m)));
    return heads;
  }

  private static Number worst(Step s, List<String> modes) {
    Number best = null;
    for (String m : modes) {
      if (s.modeOk(m) && (best == null || compare(s.compiled(m), best) > 0)) best = s.compiled(m);
    }
    return best == null ? 0L : best;
  }

  private static String worstMode(Step s, List<String> modes) {
    String best = null;
    for (String m : modes) {
      if (s.modeOk(m) && (best == null || compare(s.compiled(m), s.compiled(best)) > 0)) best = m;
    }
    return best;
  }

  private static List<String> precision(Replay rp, Summary summary) {
    List<String> modes = rp.modes;
    List<Step> steps = rp.steps;
    List<String> lines = new ArrayList<>(List.of("## Precision", ""));
    List<Step> eligible = steps.stream().filter(Step::cleanOk).toList();
    if (eligible.isEmpty()) {
      lines.addAll(List.of("No step has a clean build that compiled, so there is no necessary set to compare with.", ""));
      return lines;
    }
    lines.add(
        "Over the history steps where both the mode and the clean build compiled. \"Compiled\" is the number of distinct sources the incremental compile compiled; \"necessary\" is the number of sources that changed or whose clean classfiles changed since the previous step, a lower bound for any incremental compiler.");
    lines.add("");
    Map<String, Precision> p = summary.precision();
    List<List<Object>> rows =
        List.of(
            row("Steps", modes, m -> p.get(m).steps()),
            row("Sources compiled, total", modes, m -> num(p.get(m).compiledTotal(), 0)),
            row("Sources compiled, median per step", modes, m -> num(p.get(m).compiledMedian(), 1)),
            row("Necessary, total", modes, m -> num(p.get(m).necessaryTotal(), 0)),
            row("Compiled / necessary, overall", modes, m -> num(p.get(m).ratioOverall(), 2)),
            row("Compiled / necessary, median per step", modes, m -> num(p.get(m).ratioMedian(), 2)),
            row("Classes recompiled, total over cycles", modes, m -> num(p.get(m).classesRecompiled(), 0)),
            row("Cycles, total", modes, m -> num(p.get(m).cyclesTotal(), 0)),
            row("Cycles, median per step", modes, m -> num(p.get(m).cyclesMedian(), 1)),
            row(
                "Steps that compiled every source",
                modes,
                m -> p.get(m).fullRecompiles() == null ? "unknown" : p.get(m).fullRecompiles()),
            row("Steps with a necessary source not compiled", modes, m -> p.get(m).stepsNecessaryNotCompiled()));
    lines.addAll(table(modeHeads("", modes), rows, range(1, modes.size() + 1)));
    lines.add("");
    List<Number> counts = eligible.stream().map(Step::sourceCount).toList();
    if (counts.stream().noneMatch(Objects::isNull)) {
      Number min = counts.stream().min(Report::compare).orElseThrow();
      Number max = counts.stream().max(Report::compare).orElseThrow();
      lines.add(
          "The build's source count is taken per step from the clean build's first cycle of each module ("
              + (compare(min, max) == 0
                  ? commas(min) + " sources throughout"
                  : commas(min) + " to " + commas(max) + " sources over the history")
              + "); a step compiled every source when its distinct count reaches it.");
    } else {
      lines.add(
          "The build's source count cannot be derived: the clean lines carry no `zincRuns` (the replay ran without `--profile`), so full recompiles are not counted.");
    }
    long zero = eligible.stream().filter(s -> compare(s.necessary(), 0L) == 0).count();
    lines.add(
        "The median ratio is over the steps with a non-empty necessary set ("
            + (eligible.size() - zero)
            + " of "
            + eligible.size()
            + ").");
    List<String> notCompiled = new ArrayList<>();
    for (Step s : eligible) {
      for (String m : modes) {
        if (s.modeOk(m) && truthy(s.modes.get(m).get("necessaryNotCompiled"))) {
          notCompiled.add(
              s.label()
                  + " under `"
                  + m
                  + "` ("
                  + list(s.modes.get(m).get("necessaryNotCompiled")).size()
                  + ", "
                  + str(s.verdict(m))
                  + ")");
        }
      }
    }
    if (!notCompiled.isEmpty()) {
      lines.add(
          "A necessary source that was not compiled, at a step whose verdict is not a finding, means its classfiles were equal to the clean build's anyway or differed only as noise: "
              + String.join(", ", take(notCompiled, 10))
              + (notCompiled.size() > 10 ? " and " + (notCompiled.size() - 10) + " more" : "")
              + ".");
    }
    lines.add("");

    for (PrecisionPair pair : summary.precisionPairs().values()) {
      String a = pair.a();
      String b = pair.b();
      lines.addAll(List.of("### " + a + " against " + b, ""));
      lines.add(
          "Of "
              + plural(pair.steps(), "step")
              + " where both compiled, they compiled different numbers of sources in "
              + pair.differ()
              + ": `"
              + a
              + "` fewer in "
              + pair.aFewer()
              + " (by "
              + commas(pair.aFewerBy())
              + " sources in total), `"
              + b
              + "` fewer in "
              + pair.bFewer()
              + " (by "
              + commas(pair.bFewerBy())
              + "). Totals: `"
              + a
              + "` "
              + commas(pair.aTotal())
              + ", `"
              + b
              + "` "
              + commas(pair.bTotal())
              + (truthy(pair.aTotal())
                  ? " ("
                      + signed(
                          100.0 * subtract(pair.bTotal(), pair.aTotal()).doubleValue() / pair.aTotal().doubleValue(), 1)
                      + "% relative to `"
                      + a
                      + "`)."
                  : "."));
      lines.add("");
      List<Step> differ =
          eligible.stream()
              .filter(s -> s.modeOk(a) && s.modeOk(b) && compare(s.compiled(a), s.compiled(b)) != 0)
              .collect(Collectors.toCollection(ArrayList::new));
      differ.sort(Comparator.comparing((Step s) -> negate(abs(subtract(s.compiled(a), s.compiled(b)))), Report::compare));
      if (!differ.isEmpty()) {
        lines.addAll(List.of("The largest differences:", ""));
        List<List<Object>> pairRows = new ArrayList<>();
        for (Step s : differ) {
          Number delta = subtract(s.compiled(b), s.compiled(a));
          pairRows.add(
              List.of(
                  s.label(),
                  trunc(s.subject(), 50),
                  s.necessary(),
                  s.compiled(a),
                  str(cycles(s.modes.get(a))),
                  s.compiled(b),
                  str(cycles(s.modes.get(b))),
                  (compare(delta, 0L) >= 0 ? "+" : "") + str(delta)));
        }
        lines.addAll(
            table(
                List.of("Step", "Subject", "Necessary", a + " sources", a + " cycles", b + " sources", b + " cycles", b + " - " + a),
                pairRows,
                range(2, 8),
                15,
                "steps"));
        lines.add("");
      }
    }

    lines.addAll(List.of("### Steps that compiled the most beyond the necessary", ""));
    List<Step> candidates =
        eligible.stream()
            .filter(s -> worstMode(s, modes) != null && compare(worst(s, modes), s.necessary()) > 0)
            .toList();
    if (candidates.isEmpty()) {
      lines.addAll(List.of("No step compiled more than the necessary set.", ""));
      return lines;
    }
    List<Step> byRatio =
        take(
            candidates.stream()
                .filter(s -> compare(s.necessary(), 0L) > 0)
                .sorted(Comparator.comparingDouble(s -> divide(negate(worst(s, modes)), s.necessary())))
                .toList(),
            10);
    List<Step> byExcess =
        take(
            candidates.stream()
                .sorted(Comparator.comparing((Step s) -> negate(subtract(worst(s, modes), s.necessary())), Report::compare))
                .toList(),
            10);

    Function<List<Step>, List<List<Object>>> rowsOf =
        ss -> {
          List<List<Object>> out = new ArrayList<>();
          for (Step s : ss) {
            Number n = s.necessary();
            Number sources = s.sourceCount();
            List<Object> cells =
                new ArrayList<>(
                    List.of(
                        s.label(),
                        trunc(s.subject(), 45),
                        sources == null ? "-" : sources,
                        n,
                        subtract(worst(s, modes), n)));
            for (String m : modes) {
              Number c = s.modeOk(m) ? s.compiled(m) : null;
              cells.add(c == null ? "-" : c);
              cells.add(c == null || !truthy(n) ? "-" : fixed(divide(c, n), 1));
            }
            out.add(cells);
          }
          return out;
        };

    List<String> heads = new ArrayList<>(List.of("Step", "Subject", "Sources", "Necessary", "Excess"));
    modes.forEach(m -> heads.addAll(List.of(m, m + " ratio")));
    IntPredicate right = range(2, 5 + 2 * modes.size());
    lines.add(
        "By the ratio of compiled to necessary. The mode that compiled more decides the rank and the excess; Sources is the size of the build; steps with an empty necessary set are left out:");
    lines.add("");
    List<String> ratioTable = table(heads, rowsOf.apply(byRatio), right);
    lines.addAll(ratioTable.isEmpty() ? List.of("None.", "") : ratioTable);
    lines.add("");
    Function<List<Step>, Set<String>> names = ss -> ss.stream().map(s -> s.name).collect(Collectors.toSet());
    if (names.apply(byExcess).equals(names.apply(byRatio))) {
      lines.add(
          "The steps with the most sources compiled beyond the necessary (the Excess column) are the same ones.");
      lines.add("");
    } else {
      lines.addAll(List.of("By the number of sources compiled beyond the necessary:", ""));
      lines.addAll(table(heads, rowsOf.apply(byExcess), right));
      lines.add("");
    }

    Map<String, Step> union = new LinkedHashMap<>();
    Stream.concat(byRatio.stream(), byExcess.stream()).forEach(s -> union.put(s.name, s));
    List<String> notes = new ArrayList<>();
    for (Step s : union.values()) {
      String m = worstMode(s, modes);
      CycleChanges fc = firstCycleChanges(s.modes.get(m));
      if (fc == null) continue;
      Map<String, Object> c = fc.cycle();
      List<Object> changes = list(c.get("changes"));
      List<String> items = new ArrayList<>();
      for (Object change : take(changes, 3)) {
        Map<String, Object> x = dict(change);
        List<Object> changed = list(x.get("names"));
        Number more =
            subtract(
                truthy(x.get("namesTotal")) ? num(x.get("namesTotal")) : (long) changed.size(),
                (long) Math.min(changed.size(), 4));
        String desc =
            take(changed, 4).stream().map(Report::str).collect(Collectors.joining(", "))
                + (compare(more, 0L) > 0 ? " and " + str(more) + " more" : "");
        String reason = str(x.getOrDefault("reason", "")).replaceAll("\\.+$", "");
        items.add("`" + str(x.get("class")) + "` (" + reason + (desc.isEmpty() ? "" : ": `" + desc + "`") + ")");
      }
      Number total = c.containsKey("apiChanges") ? num(c.get("apiChanges")) : (long) changes.size();
      notes.add(
          "- "
              + s.label()
              + " under `"
              + m
              + "`: cycle "
              + str(c.get("cycle"))
              + " of `"
              + str(fc.module())
              + "` compiled "
              + plural(count(c, "sources"), "source")
              + " and reported "
              + plural(total, "API change")
              + ": "
              + String.join("; ", items)
              + (compare(total, 3L) > 0 ? "; and " + str(subtract(total, 3L)) + " more" : "")
              + ".");
    }
    if (!notes.isEmpty()) {
      lines.add(
          "What the first cycle with API changes reported at those steps, under the mode that compiled more (the changes that started the invalidation):");
      lines.add("");
      lines.addAll(notes);
      lines.add("");
    } else {
      lines.addAll(List.of("The lines carry no `zincRuns`, so the API changes behind these steps are not known.", ""));
    }
    return lines;
  }

  private static List<String> performance(Replay rp, Summary summary) {
    List<String> modes = rp.modes;
    List<Step> steps = rp.steps;
    List<String> lines = new ArrayList<>(List.of("## Performance", ""));
    lines.add(
        "These timings come from a machine that was running other work. Each step ran once. The modes ran one after the other in the same JVM, in an order that rotates from step to step, and the clean build ran after them. Read differences of a few percent as noise.");
    lines.add("");
    Map<String, Performance> q = summary.performance();
    if (modes.stream().noneMatch(m -> q.get(m).steps() != 0)) {
      lines.addAll(
          List.of("No step has both an incremental and a clean build that compiled, so there are no timings to compare.", ""));
      return lines;
    }
    lines.add(
        "Wall time of the incremental compile (`wallMillis`) over the history steps where both the mode and the clean build compiled, against the clean build of the same steps.");
    lines.add("");
    List<List<Object>> rows =
        List.of(
            row("Steps", modes, m -> q.get(m).steps()),
            row("Incremental, sum (s)", modes, m -> secs(q.get(m).incSumMillis())),
            row("Incremental, median (ms)", modes, m -> num(q.get(m).incMedianMillis(), 0)),
            row("Clean, sum (s)", modes, m -> secs(q.get(m).cleanSumMillis())),
            row("Clean, median (ms)", modes, m -> num(q.get(m).cleanMedianMillis(), 0)),
            row(
                "Incremental / clean, of the sums",
                modes,
                m -> pct(q.get(m).incSumMillis(), q.get(m).cleanSumMillis(), 1)),
            row(
                "Incremental / clean, median per step",
                modes,
                m ->
                    q.get(m).incOverCleanMedianPerStep() == null
                        ? "-"
                        : fixed(100 * q.get(m).incOverCleanMedianPerStep().doubleValue(), 1) + "%"));
    lines.addAll(table(modeHeads("", modes), rows, range(1, modes.size() + 1)));
    lines.add("");

    lines.addAll(List.of("### Where the incremental time went", ""));
    if (modes.stream().noneMatch(m -> q.get(m).profiledSteps() != 0)) {
      lines.addAll(
          List.of("The lines carry no `scalacPhases` or `zinc` timings (the replay ran without `--profile`).", ""));
    } else {
      Function<Function<Performance, Number>, Function<String, Object>> share =
          part -> m -> secs(part.apply(q.get(m))) + " (" + pct(part.apply(q.get(m)), q.get(m).profiledWallMillis(), 0) + ")";
      List<List<Object>> profiled =
          List.of(
              row("Profiled steps", modes, m -> q.get(m).profiledSteps()),
              row("Wall, sum (s)", modes, m -> secs(q.get(m).profiledWallMillis())),
              row("scalac phases (s)", modes, share.apply(Performance::scalacMillis)),
              row("of which Zinc's phases in scalac, `xsbt-*` (s)", modes, share.apply(Performance::scalacXsbtMillis)),
              row("Zinc's own (s)", modes, share.apply(Performance::zincOwnMillis)),
              row("Unattributed (s)", modes, share.apply(Performance::otherMillis)));
      lines.addAll(table(modeHeads("", modes), profiled, range(1, modes.size() + 1)));
      lines.add("");
      lines.add(
          "How this is computed: scalac is the sum of `scalacPhases` millis; Zinc's own is the sum of every entry of `zinc` except `compile` (which is the scalac run, already counted through its phases) and `zincCall` (the total of the others), so it includes `analysisWrite`, which falls outside `zincCall`; unattributed is wall time less both, that is the part of `compile` outside any phase and the harness around the Zinc call. Percentages are of the wall time.");
      lines.add("");
      Map<String, Map<String, Double>> parts = new LinkedHashMap<>();
      for (String m : modes) {
        for (Step s : steps) {
          if (s.cleanOk() && s.modeOk(m)) {
            zincParts(s.result(m))
                .forEach(
                    e ->
                        parts
                            .computeIfAbsent(e.getKey(), k -> new HashMap<>())
                            .merge(m, num(e.getValue()).doubleValue(), (acc, v) -> acc + v));
          }
        }
      }
      if (!parts.isEmpty()) {
        List<String> keys = new ArrayList<>(parts.keySet());
        keys.sort(Comparator.comparingDouble(k -> -parts.get(k).values().stream().mapToDouble(v -> v).max().orElse(0)));
        lines.addAll(List.of("Zinc's own time by part, summed over the steps (s):", ""));
        List<List<Object>> partRows = new ArrayList<>();
        for (String k : keys) {
          partRows.add(row(code(k), modes, m -> fixed(parts.get(k).getOrDefault(m, 0.0) / 1000, 2)));
        }
        lines.addAll(table(modeHeads("Part", modes), partRows, range(1, modes.size() + 1)));
        lines.add("");
      }
    }

    for (PerformancePair p : summary.performancePairs().values()) {
      if (p.steps() == 0) continue;
      String a = p.a();
      String b = p.b();
      lines.addAll(List.of("### " + a + " against " + b + ", step by step", ""));
      Number sa = q.get(a).incSumMillis();
      lines.add(
          "Over "
              + plural(p.steps(), "step")
              + " where both compiled, the difference `"
              + b
              + "` minus `"
              + a
              + "` in wall time has median "
              + num(p.medianMillis(), 0)
              + " ms and sum "
              + num(p.sumMillis(), 0)
              + " ms"
              + (truthy(sa) && q.get(a).steps() == p.steps()
                  ? " (" + pct(p.sumMillis(), sa, 1) + " of `" + a + "`'s total)"
                  : "")
              + ". `"
              + a
              + "` was faster by more than 10% in "
              + p.aFaster()
              + " steps and `"
              + b
              + "` in "
              + p.bFaster()
              + " (one is faster by more than 10% when its time is under 0.9 of the other's).");
      lines.add("");
    }
    return lines;
  }

  private static List<String> stepsTable(Replay rp) {
    List<String> modes = rp.modes;
    List<String> lines = new ArrayList<>(List.of("## Steps", ""));
    if (rp.steps.isEmpty()) {
      lines.addAll(List.of("No complete step.", ""));
      return lines;
    }
    lines.add(
        "One row per history step. Per mode: verdict, distinct sources compiled, cycles, wall time in ms. A dash is a build that failed.");
    lines.add("");
    List<String> heads = new ArrayList<>(List.of("Step", "SHA", "Date", "Subject", "Files", "Nec."));
    modes.forEach(m -> heads.addAll(List.of(m, "src", "cyc", "ms")));
    heads.add("clean ms");
    List<List<Object>> rows = new ArrayList<>();
    for (Step s : rp.steps) {
      Long files = s.filesChanged();
      String mark = truthy(s.exported()) ? "[build] " : Boolean.FALSE.equals(s.exported()) ? "[build failed] " : "";
      List<Object> r =
          new ArrayList<>(
              List.of(
                  s.number(),
                  code(s.sha()),
                  s.date(),
                  trunc(mark + s.subject(), 44),
                  files == null ? "-" : files,
                  s.cleanOk() ? s.necessary() : "-"));
      for (String m : modes) {
        Map<String, Object> row = s.modes.get(m);
        boolean ok = s.modeOk(m);
        r.add(str(row.get("verdict")));
        r.add(s.compiled(m));
        r.add(ok ? str(cycles(row)) : "-");
        r.add(ok ? num(wall(row), 0) : "-");
      }
      r.add(s.cleanOk() ? num(wall(s.clean), 0) : "-");
      rows.add(r);
    }
    int last = 6 + 4 * modes.size();
    IntPredicate right = i -> i == 4 || i == 5 || i == last || (i >= 7 && i < last && (i - 6) % 4 != 0);
    lines.addAll(table(heads, rows, right));
    lines.add("");
    return lines;
  }

  private static String render(Replay rp, Summary summary) {
    List<String> lines = header(rp, summary);
    if (!rp.steps.isEmpty() || rp.base != null) lines.addAll(soundness(rp, summary));
    if (!rp.steps.isEmpty()) {
      lines.addAll(precision(rp, summary));
      lines.addAll(performance(rp, summary));
      lines.addAll(stepsTable(rp));
    }
    lines.addAll(HOW_TO_READ.lines().toList());
    return String.join("\n", lines).replaceAll("\n+$", "") + "\n";
  }

  private static IntPredicate range(int from, int until) {
    return i -> i >= from && i < until;
  }

  private static List<String> table(List<String> headers, List<List<Object>> rows, IntPredicate right) {
    return table(headers, rows, right, Integer.MAX_VALUE, "rows");
  }

  /** A Markdown table; {@code right} picks the right-aligned columns. Empty without rows. */
  private static List<String> table(
      List<String> headers, List<List<Object>> rows, IntPredicate right, int cap, String what) {
    List<String> out = new ArrayList<>();
    if (rows.isEmpty()) return out;
    List<List<Object>> shown = take(rows, cap);
    out.add("| " + String.join(" | ", headers) + " |");
    StringBuilder rule = new StringBuilder("|");
    for (int i = 0; i < headers.size(); i++) rule.append((right == null ? NONE : right).test(i) ? "---:" : "---").append('|');
    out.add(rule.toString());
    for (List<Object> r : shown) {
      out.add("| " + r.stream().map(Report::esc).collect(Collectors.joining(" | ")) + " |");
    }
    if (rows.size() > shown.size()) {
      out.add("");
      out.add("and " + commas(rows.size() - shown.size()) + " more " + what + ".");
    }
    return out;
  }

  private static String esc(Object s) {
    return str(s).replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  private static String trunc(Object value, int n) {
    String s = str(value);
    if (s.codePointCount(0, s.length()) <= n) return s;
    return stripTrailing(head(s, n - 1)) + "…";
  }

  private static String code(Object s) {
    return "`" + str(s).replace("`", "'") + "`";
  }

  private static String num(Number x, int digits) {
    if (x == null) return "-";
    if (digits != 0) return fixed(x.doubleValue(), digits, true, false);
    return commas(x instanceof Double d ? (long) Math.rint(d) : x.longValue());
  }

  private static String pct(Number a, Number b, int digits) {
    return truthy(b) ? fixed(100.0 * a.doubleValue() / b.doubleValue(), digits) + "%" : "-";
  }

  private static String secs(Number millis) {
    return millis == null ? "-" : fixed(millis.doubleValue() / 1000.0, 1, true, false);
  }

  private static String plural(Number n, String word) {
    return plural(n, word, word + "s");
  }

  private static String plural(Number n, String word, String many) {
    return commas(n) + " " + (compare(n, 1L) == 0 ? word : many);
  }

  private static String commas(Number n) {
    if (n instanceof Double d) {
      String repr = repr(d);
      int point = repr.indexOf('.');
      if (point < 0 || repr.contains("e")) return repr;
      boolean negative = repr.startsWith("-");
      return (negative ? "-" : "") + group(repr.substring(negative ? 1 : 0, point)) + repr.substring(point);
    }
    long value = n.longValue();
    return (value < 0 ? "-" : "") + group(Long.toString(value).substring(value < 0 ? 1 : 0));
  }

  private static String group(String digits) {
    StringBuilder sb = new StringBuilder(digits);
    for (int i = digits.length() - 3; i > 0; i -= 3) sb.insert(i, ',');
    return sb.toString();
  }

  private static String fixed(double x, int digits) {
    return fixed(x, digits, false, false);
  }

  private static String signed(double x, int digits) {
    return fixed(x, digits, false, true);
  }

  /**
   * A number with a fixed count of decimals, rounded from its exact binary value with ties to
   * even, as C's {@code printf} does. {@code String.format} rounds the shortest decimal half up
   * instead, which gives a different last digit for some values.
   */
  private static String fixed(double x, int digits, boolean grouped, boolean plus) {
    if (Double.isNaN(x)) return "nan";
    boolean negative = x < 0 || (x == 0 && 1 / x < 0);
    String sign = negative ? "-" : plus ? "+" : "";
    if (Double.isInfinite(x)) return sign + "inf";
    String s = new BigDecimal(x).abs().setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    if (grouped) {
      int point = s.indexOf('.');
      s = point < 0 ? group(s) : group(s.substring(0, point)) + s.substring(point);
    }
    return sign + s;
  }

  /** Rounds a floating number to one decimal from its exact value, ties to even; integers are kept. */
  private static Number roundToTenth(Number x) {
    if (!(x instanceof Double d) || d.isNaN() || d.isInfinite()) return x;
    return Math.copySign(new BigDecimal(d).setScale(1, RoundingMode.HALF_EVEN).doubleValue(), d);
  }

  /** The shortest decimal that reads back as the same double, laid out as Python's {@code repr}. */
  private static String repr(double x) {
    if (Double.isNaN(x)) return "nan";
    if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
    String sign = x < 0 || (x == 0 && 1 / x < 0) ? "-" : "";
    if (x == 0) return sign + "0.0";
    BigDecimal exact = new BigDecimal(Math.abs(x));
    BigDecimal shortest = exact;
    for (int precision = 1; precision <= 17; precision++) {
      BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
      if (candidate.doubleValue() == Math.abs(x)) {
        shortest = candidate;
        break;
      }
    }
    shortest = shortest.stripTrailingZeros();
    String digits = shortest.unscaledValue().toString();
    int point = digits.length() - shortest.scale();
    if (point > 16 || point < -3) {
      int exponent = point - 1;
      return sign
          + digits.charAt(0)
          + (digits.length() > 1 ? "." + digits.substring(1) : "")
          + "e"
          + (exponent < 0 ? "-" : "+")
          + (Math.abs(exponent) < 10 ? "0" : "")
          + Math.abs(exponent);
    }
    if (point <= 0) return sign + "0." + "0".repeat(-point) + digits;
    if (point >= digits.length()) return sign + digits + "0".repeat(point - digits.length()) + ".0";
    return sign + digits.substring(0, point) + "." + digits.substring(point);
  }

  /** A value as Python's {@code str} prints it, which is how the report shows cells and counts. */
  private static String str(Object o) {
    if (o == null) return "None";
    if (o instanceof String s) return s;
    if (o instanceof Boolean b) return b ? "True" : "False";
    if (o instanceof Double d) return repr(d);
    if (o instanceof List<?> l) return l.stream().map(Report::quoted).collect(Collectors.joining(", ", "[", "]"));
    if (o instanceof Map<?, ?> m) {
      return m.entrySet().stream()
          .map(e -> quoted(e.getKey()) + ": " + quoted(e.getValue()))
          .collect(Collectors.joining(", ", "{", "}"));
    }
    return o.toString();
  }

  /** A value inside a list or map, where Python quotes strings and escapes what does not print. */
  private static String quoted(Object o) {
    if (!(o instanceof String s)) return str(o);
    char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
    StringBuilder sb = new StringBuilder().append(quote);
    s.codePoints()
        .forEach(
            c -> {
              switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                  if (c == quote) sb.append('\\').appendCodePoint(c);
                  else if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", c));
                  else sb.appendCodePoint(c);
                }
              }
            });
    return sb.append(quote).toString();
  }

  private static String orElse(Object value, String fallback) {
    return truthy(value) ? str(value) : fallback;
  }

  /** Whether a JSON value counts as true where the report tests it: not null, false, zero or empty. */
  private static boolean truthy(Object o) {
    if (o == null) return false;
    if (o instanceof Boolean b) return b;
    if (o instanceof Long l) return l != 0;
    if (o instanceof Number n) return n.doubleValue() != 0;
    if (o instanceof String s) return !s.isEmpty();
    if (o instanceof Collection<?> c) return !c.isEmpty();
    if (o instanceof Map<?, ?> m) return !m.isEmpty();
    return true;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> dict(Object o) {
    return o == null ? Map.of() : (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object o) {
    return o == null ? List.of() : (List<Object>) o;
  }

  private static <K, V, W> Map<K, W> mapValues(Map<K, V> map, Function<V, W> f) {
    Map<K, W> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, f.apply(v)));
    return out;
  }

  private static <T> List<T> take(List<T> xs, int n) {
    return xs.size() <= n ? xs : xs.subList(0, n);
  }

  private static List<Object> distinct(List<Object> xs) {
    return new ArrayList<>(new LinkedHashSet<>(xs));
  }

  private static boolean isNumber(Object o) {
    return o instanceof Long || o instanceof Double || o instanceof Boolean;
  }

  private static Number num(Object o) {
    if (o instanceof Boolean b) return b ? 1L : 0L;
    if (o instanceof Integer i) return i.longValue();
    return (Number) o;
  }

  private static Number count(Map<String, Object> map, String key) {
    return map.containsKey(key) ? num(map.get(key)) : 0L;
  }

  private static boolean integral(Number... xs) {
    return Stream.of(xs).noneMatch(x -> x instanceof Double);
  }

  private static Number add(Number a, Number b) {
    return integral(a, b) ? (Number) Math.addExact(a.longValue(), b.longValue()) : (Number) (a.doubleValue() + b.doubleValue());
  }

  private static Number subtract(Number a, Number b) {
    return integral(a, b)
        ? (Number) Math.subtractExact(a.longValue(), b.longValue())
        : (Number) (a.doubleValue() - b.doubleValue());
  }

  private static Number negate(Number a) {
    return integral(a) ? (Number) (-a.longValue()) : (Number) (-a.doubleValue());
  }

  private static Number abs(Number a) {
    return integral(a) ? (Number) Math.abs(a.longValue()) : (Number) Math.abs(a.doubleValue());
  }

  private static double divide(Number a, Number b) {
    return a.doubleValue() / b.doubleValue();
  }

  private static int compare(Number a, Number b) {
    if (integral(a, b)) return Long.compare(a.longValue(), b.longValue());
    double x = a.doubleValue();
    double y = b.doubleValue();
    return x < y ? -1 : x > y ? 1 : 0;
  }

  private static Number sum(Stream<Number> xs) {
    return sum(xs.toList());
  }

  /**
   * The sum as Python's {@code sum} computes it: exact while the numbers are integers, and from
   * the first floating one a compensated (Neumaier) sum, whose low-order part is added at the end.
   * The summary rounds these sums and prints them in full, so a plain running sum would differ in
   * the last digits.
   */
  private static Number sum(Collection<? extends Number> xs) {
    long whole = 0;
    boolean floating = false;
    double high = 0;
    double low = 0;
    for (Number n : xs) {
      if (!floating && !(n instanceof Double)) {
        whole = Math.addExact(whole, n.longValue());
      } else if (!floating) {
        high = whole + n.doubleValue();
        floating = true;
      } else {
        double x = n.doubleValue();
        double t = high + x;
        low += Math.abs(high) >= Math.abs(x) ? (high - t) + x : (x - t) + high;
        high = t;
      }
    }
    if (!floating) return whole;
    return low != 0 && Double.isFinite(low) ? high + low : high;
  }

  /** The middle value, or the mean of the two middle ones, which is then a floating number. */
  private static Number median(List<? extends Number> xs) {
    if (xs.isEmpty()) return null;
    List<Number> sorted = new ArrayList<>(xs);
    sorted.sort(Report::compare);
    int n = sorted.size();
    if (n % 2 == 1) return sorted.get(n / 2);
    return add(sorted.get(n / 2 - 1), sorted.get(n / 2)).doubleValue() / 2;
  }

  private static String capitalize(String s) {
    if (s.isEmpty()) return s;
    int first = s.offsetByCodePoints(0, 1);
    return s.substring(0, first).toUpperCase(Locale.ROOT) + s.substring(first).toLowerCase(Locale.ROOT);
  }

  private static String head(String s, int n) {
    return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, Math.max(n, 0)));
  }

  private static boolean isSpace(int c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
  }

  private static String stripTrailing(String s) {
    int end = s.length();
    while (end > 0 && isSpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
    return s.substring(0, end);
  }

  private static String strip(String s) {
    String t = stripTrailing(s);
    int start = 0;
    while (start < t.length() && isSpace(t.codePointAt(start))) start += Character.charCount(t.codePointAt(start));
    return t.substring(start);
  }

  private static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i);
      int y = b.codePointAt(j);
      if (x != y) return Integer.compare(x, y);
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /**
   * A path as the summary and the printed report path name it: without {@code .} components,
   * repeated or trailing slashes, and {@code .} when nothing is left.
   */
  private static String pathString(String path) {
    String root = path.startsWith("/") ? (path.startsWith("//") && !path.startsWith("///") ? "//" : "/") : "";
    String rest =
        Stream.of(path.split("/")).filter(p -> !p.isEmpty() && !p.equals(".")).collect(Collectors.joining("/"));
    return root.isEmpty() && rest.isEmpty() ? "." : root + rest;
  }

  private static String pathName(String normalized) {
    if (normalized.equals(".")) return "";
    return normalized.substring(normalized.lastIndexOf('/') + 1);
  }

  private static String toJson(Object value) {
    StringBuilder sb = new StringBuilder();
    writeJson(value, "", sb);
    return sb.toString();
  }

  /** JSON indented by two spaces with ASCII-only strings, the layout the Python report printed. */
  private static void writeJson(Object value, String indent, StringBuilder sb) {
    String inner = indent + "  ";
    if (value instanceof Map<?, ?> m) {
      if (m.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        sb.append(inner);
        writeJsonString(str(e.getKey()), sb);
        sb.append(": ");
        writeJson(e.getValue(), inner, sb);
        sb.append(++i < m.size() ? ",\n" : "\n");
      }
      sb.append(indent).append('}');
    } else if (value instanceof List<?> l) {
      if (l.isEmpty()) {
        sb.append("[]");
        return;
      }
      sb.append("[\n");
      for (int i = 0; i < l.size(); i++) {
        sb.append(inner);
        writeJson(l.get(i), inner, sb);
        sb.append(i + 1 < l.size() ? ",\n" : "\n");
      }
      sb.append(indent).append(']');
    } else if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeJsonString(s, sb);
    } else if (value instanceof Boolean b) {
      sb.append(b ? "true" : "false");
    } else if (value instanceof Double d) {
      sb.append(d.isNaN() ? "NaN" : d.isInfinite() ? (d > 0 ? "Infinity" : "-Infinity") : repr(d));
    } else {
      sb.append(value);
    }
  }

  private static void writeJsonString(String s, StringBuilder sb) {
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
