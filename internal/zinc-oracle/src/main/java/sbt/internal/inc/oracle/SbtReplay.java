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
import com.eclipsesource.json.WriterConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Replays the last commits of a repository through the project's own sbt, with the sbt-zinc-oracle
 * plugin checking each incremental compile against a clean one.
 *
 * <pre>
 *     SbtReplay BUILD_ROOT OUT [STEPS] [--ref REF] [--sbt COMMAND] [--plugin ORG:NAME:VERSION]
 * </pre>
 *
 * The replay never touches the user's working tree. It makes a full clone of the repository in
 * OUT/checkout, with its own .git directory and its submodules, since some builds do not work in a
 * git worktree (they read .git, or use git plugins that expect a directory there). It adds the plugin to the clone as an untracked file under project/, and runs sbt there. A step is
 * a first-parent commit that changes a Scala or Java source or the build definition below
 * BUILD_ROOT. The child sbt checks out each commit in turn, reloads when the build changed, and
 * runs the plugin's step; this class supervises it. A commit whose build does not load ends the
 * child, and the replay then goes on from the next commit in a new one.
 *
 * OUT gets what the in-process replay writes, so {@link Report} reads it unchanged: replay.json,
 * commits.jsonl, work/steps.jsonl, report.md, and sbt.log with everything sbt printed.
 */
public final class SbtReplay {
  private SbtReplay() {}

  private static final String STATE = "replay-state.json";

  /** The commit the child sbt is to verify next. */
  public record Next(String name, String commit, boolean build, boolean checkedOut, String repository) {}

  private static void log(String message) {
    System.err.println("[incReplay] " + message);
  }

  private static String git(Path repo, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      String out;
      try (InputStream in = p.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (p.waitFor() != 0) {
        throw new IllegalStateException("git " + String.join(" ", args) + " failed in " + repo);
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Makes the clone's working tree that of a commit, submodules included. */
  public static void checkout(Path clone, String commit) {
    git(clone, "checkout", "-q", "-f", "--detach", commit);
    if (Files.exists(clone.resolve(".gitmodules"))) {
      git(clone, "submodule", "update", "--init", "--recursive", "-q");
    }
  }

  private static JsonObject load(Path out) {
    try {
      return Json.parse(Files.readString(out.resolve(STATE))).asObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void save(Path out, JsonObject state) {
    try {
      Path tmp = out.resolve(STATE + ".tmp");
      Files.writeString(tmp, state.toString(WriterConfig.PRETTY_PRINT) + "\n");
      Files.move(tmp, out.resolve(STATE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** For the child sbt: the next commit, or null when the replay is over. */
  public static Next next(Path out) {
    JsonObject state = load(out);
    JsonArray commits = state.get("commits").asArray();
    int i = state.get("next").asInt();
    if (i >= commits.size()) {
      return null;
    }
    JsonObject c = commits.get(i).asObject();
    boolean checkedOut = state.get("checkedOut").asBoolean();
    state.set("checkedOut", false);
    save(out, state);
    return new Next(
        c.get("name").asString(),
        c.get("commit").asString(),
        c.get("build").asBoolean(),
        checkedOut,
        state.get("repository").asString());
  }

  /** For the child sbt: the step it was given is in steps.jsonl. */
  public static void stepDone(Path out, String name, boolean finding) {
    if (!Files.exists(out.resolve(STATE))) {
      return;
    }
    JsonObject state = load(out);
    state.set("next", state.get("next").asInt() + 1);
    save(out, state);
  }

  private static boolean isSource(String path) {
    return path.endsWith(".scala") || path.endsWith(".java");
  }

  private static boolean isBuild(String path, String prefix) {
    String rel = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    return rel.startsWith("project/") || (rel.indexOf('/') < 0 && rel.endsWith(".sbt"));
  }

  private record Candidate(String commit, boolean build) {}

  /** The last first-parent commits up to ref that change a source or the build, oldest first. */
  private static List<Candidate> candidates(Path repo, String ref, String prefix, int limit) {
    List<String> args =
        new ArrayList<>(
            List.of("log", "--first-parent", "-m", "--name-only", "--format=@%H %P", "-n" + limit * 20, ref));
    if (!prefix.isEmpty()) {
      args.add("--");
      args.add(prefix);
    }
    List<Candidate> found = new ArrayList<>();
    String current = null;
    boolean source = false;
    boolean build = false;
    for (String line : (git(repo, args.toArray(String[]::new)) + "\n@").split("\n")) {
      if (line.startsWith("@")) {
        if (current != null && (source || build)) {
          found.add(new Candidate(current, build));
        }
        String[] parts = line.substring(1).trim().split(" ");
        current = parts.length > 1 ? parts[0] : null;
        source = false;
        build = false;
      } else if (current != null && !line.isEmpty()) {
        source |= isSource(line);
        build |= isBuild(line, prefix);
      }
    }
    List<Candidate> last = new ArrayList<>(found.subList(0, Math.min(limit, found.size())));
    Collections.reverse(last);
    return last;
  }

  private static String pluginLine(String override) throws IOException {
    String[] parts;
    if (override != null) {
      parts = override.split(":");
    } else {
      Properties p = new Properties();
      try (InputStream in = SbtReplay.class.getResourceAsStream("/sbt-zinc-oracle.properties")) {
        if (in == null) {
          throw new IllegalStateException("not running from the sbt-zinc-oracle plugin; pass --plugin ORG:NAME:VERSION");
        }
        p.load(in);
      }
      parts = new String[] {p.getProperty("organization"), p.getProperty("name"), p.getProperty("version")};
    }
    return "addSbtPlugin(\"%s\" %% \"%s\" %% \"%s\")\n".formatted(parts[0], parts[1], parts[2]);
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (Files.exists(root)) {
      try (Stream<Path> s = Files.walk(root)) {
        for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  private static JsonArray strings(List<String> values) {
    JsonArray a = new JsonArray();
    values.forEach(a::add);
    return a;
  }

  /** Runs a replay; returns the process exit code. */
  public static int run(String[] args) {
    try {
      return replay(args);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 1;
    }
  }

  private static int replay(String[] args) throws IOException, InterruptedException {
    List<String> positional = new ArrayList<>();
    String ref = "HEAD";
    String plugin = null;
    List<String> sbt = List.of("sbt");
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--ref" -> ref = args[++i];
        case "--plugin" -> plugin = args[++i];
        case "--sbt" -> sbt = List.of(args[++i].split(" "));
        default -> positional.add(args[i]);
      }
    }
    if (positional.size() < 2 || positional.size() > 3) {
      System.err.println("usage: SbtReplay BUILD_ROOT OUT [STEPS] [--ref REF] [--sbt COMMAND] [--plugin ORG:NAME:VERSION]");
      return 2;
    }
    Path buildRoot = Path.of(positional.get(0)).toRealPath();
    Path out = Path.of(positional.get(1)).toAbsolutePath().normalize();
    int steps = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 50;

    Path repo = Path.of(git(buildRoot, "rev-parse", "--show-toplevel").trim()).toRealPath();
    String rel = repo.relativize(buildRoot).toString().replace(File.separatorChar, '/');
    String prefix = rel.isEmpty() ? "" : rel + "/";
    String tip = git(repo, "rev-parse", ref).trim();

    List<Candidate> found = candidates(repo, tip, prefix, steps);
    if (found.isEmpty()) {
      log("no commit up to " + ref + " changes a source or the build");
      return 1;
    }
    String start = git(repo, "rev-parse", found.get(0).commit() + "^1").trim();

    deleteRecursively(out);
    Files.createDirectories(out.resolve("work"));
    Path checkout = out.resolve("checkout");
    git(repo, "clone", "-q", "--local", "--no-checkout", repo.toString(), checkout.toString());
    checkout(checkout, start);
    Path build = rel.isEmpty() ? checkout : checkout.resolve(rel);
    Files.createDirectories(build.resolve("project"));
    Files.writeString(build.resolve("project/zz-inc-oracle.sbt"), pluginLine(plugin));

    JsonArray commits = new JsonArray();
    commits.add(new JsonObject().add("name", "base").add("commit", start).add("build", false));
    StringBuilder meta = new StringBuilder();
    String previous = start;
    int n = 0;
    for (Candidate c : found) {
      String name = "%04d-%s".formatted(++n, c.commit().substring(0, 9));
      commits.add(new JsonObject().add("name", name).add("commit", c.commit()).add("build", c.build()));
      String[] info = git(repo, "log", "-1", "--format=%s%x00%cI", c.commit()).trim().split("\0");
      List<String> changed =
          git(repo, "diff", "--name-only", previous, c.commit()).lines()
              .filter(p -> isSource(p) || isBuild(p, prefix))
              .toList();
      JsonObject row =
          new JsonObject()
              .add("step", name)
              .add("commit", c.commit())
              .add("subject", info[0])
              .add("date", info.length > 1 ? info[1] : "")
              .add("changed", strings(changed));
      row.add("exported", c.build() ? Json.TRUE : Json.NULL);
      meta.append(row).append('\n');
      previous = c.commit();
    }
    Files.writeString(out.resolve("commits.jsonl"), meta.toString());
    save(
        out,
        new JsonObject()
            .add("repository", checkout.toString())
            .add("commits", commits)
            .add("next", 0)
            .add("checkedOut", true));
    Files.writeString(
        out.resolve("replay.json"),
        new JsonObject()
                .add("repo", repo.toString())
                .add("ref", tip)
                .add("start", start)
                .add("steps", found.size())
                .add("modules", new JsonArray())
                .add("modes", strings(List.of("sbt")))
                .add("incOptions", new JsonArray())
                .add("scala", "the build's")
                .add("driver", "sbt")
                .add("sbt", String.join(" ", sbt))
                .add("started", OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")))
                .toString(WriterConfig.PRETTY_PRINT)
            + "\n");
    log(found.size() + " steps, " + start.substring(0, 9) + ".." + found.get(found.size() - 1).commit().substring(0, 9)
        + ", in " + out);

    List<String> command = new ArrayList<>(sbt);
    command.add("-Dinc.oracle.home=" + out.resolve("work"));
    command.add("incReplayRun");
    File sbtLog = out.resolve("sbt.log").toFile();
    while (true) {
      Process child =
          new ProcessBuilder(command)
              .directory(build.toFile())
              .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(sbtLog))
              .start();
      int exit = child.waitFor();
      JsonObject state = load(out);
      int next = state.get("next").asInt();
      if (next >= commits.size()) {
        break;
      }
      JsonValue failed = commits.get(next);
      log(failed.asObject().get("name").asString() + ": sbt stopped (exit " + exit
          + "), so the build does not load or the step crashed; see sbt.log. Going on from the next commit.");
      Files.writeString(
          out.resolve("failed.jsonl"),
          failed.toString() + "\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      next++;
      state.set("next", next);
      if (next >= commits.size()) {
        save(out, state);
        break;
      }
      checkout(checkout, commits.get(next).asObject().get("commit").asString());
      state.set("checkedOut", true);
      save(out, state);
    }
    try {
      Report.write(out);
    } catch (RuntimeException | IOException e) {
      log("no report: " + e);
    }
    log("report: " + out.resolve("report.md"));
    return 0;
  }

  public static void main(String[] args) {
    System.exit(run(args));
  }
}
