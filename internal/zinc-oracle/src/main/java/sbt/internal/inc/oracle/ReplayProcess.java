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
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An IncHistory JVM, driven over its standard input. It answers its start with a `{"ready":...}`
 * line and each `step NAME` or `rebase NAME` with a `{"done":...}` line; both say whether the
 * clean build compiled. Everything it prints goes to a log file.
 */
final class ReplayProcess implements AutoCloseable {
  static final String MAIN = "sbt.internal.inc.bench.IncHistory";

  private final Process process;
  private final BufferedReader out;
  private final Writer in;
  private final Writer log;

  ReplayProcess(String classpath, List<String> jvmOpts, List<String> args, Path logfile) throws IOException {
    List<String> command = new ArrayList<>(List.of("java"));
    command.addAll(jvmOpts);
    command.addAll(List.of("-cp", classpath, MAIN));
    command.addAll(args);
    log = Files.newBufferedWriter(logfile, StandardCharsets.UTF_8);
    process = new ProcessBuilder(command).redirectErrorStream(true).start();
    out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
  }

  /** Reads up to the next line that is a JSON object whose first key is `key`, and returns it. */
  JsonObject waitFor(String key) throws IOException {
    String start = "{\"" + key + "\"";
    String line;
    while ((line = out.readLine()) != null) {
      log.write(line);
      log.write('\n');
      log.flush();
      if (line.startsWith(start)) return Json.parse(line).asObject();
    }
    throw new Proc.Failure("IncHistory stopped (exit " + exit() + ") while waiting for " + key);
  }

  /** Compiles the build as it now is, in every mode and clean; true if the clean build compiled. */
  boolean step(String name) throws IOException {
    return send("step " + name);
  }

  /** Makes the build as it now is the state every mode starts its next step from. */
  boolean rebase(String name) throws IOException {
    return send("rebase " + name);
  }

  private boolean send(String command) throws IOException {
    in.write(command + "\n");
    in.flush();
    return waitFor("done").get("cleanOk").asBoolean();
  }

  private int exit() {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /** Asks IncHistory to shut down, and kills it if it has not within a minute. */
  @Override
  public void close() throws IOException {
    Thread rest = new Thread(() -> {
      try {
        String line;
        while ((line = out.readLine()) != null) {
          log.write(line);
          log.write('\n');
        }
      } catch (IOException e) {
        return;
      }
    });
    rest.setDaemon(true);
    try {
      in.write("shutdown\n");
      in.flush();
      rest.start();
      if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly();
      rest.join(5000);
    } catch (IOException e) {
      process.destroyForcibly();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    log.close();
  }
}
