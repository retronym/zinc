# Clean build mode

## Problem

A CI job that builds from a fresh checkout pays for incremental compilation, but it never uses it. Zinc extracts and hashes APIs, records class dependencies and used names, stamps sources, products and libraries, analyses javac's classfiles, writes the early analysis store, and hands back an `Analysis` for sbt to persist. The next build is a fresh checkout again, so none of that is read.

## Decision

Give `IncOptions.enabled = false` a defined meaning: a non-incremental clean build.

- Zinc ignores the previous analysis and setup and compiles every source in one cycle. It skips invalidation, stamping and lookups.
- The callback reports `enabled() == false`, so the Scala 2 bridge and the Scala 3 compiler drop the API extraction and dependency phases. They already check this flag.
- Javac output is not read back: there is no `JavaAnalyze` and no constant-dependency collection.
- The early analysis store is neither read nor written. With early output configured, `afterEarlyOutput(false)` is signalled so pipelined dependants fall back to the full classes.
- The result is `Analysis.empty`. If a caller persists it anyway, the next incremental build sees no products and recompiles everything, which is safe.

Why reuse `enabled` rather than add an option: it is existing public API ("Determines whether incremental compilation is enabled"), the bridges already honour it, and today `enabled = false` produces an analysis without APIs or dependencies that later incremental builds would silently trust. Defining it as a clean build fixes that hazard.

## Caveats

- Output directories are not cleaned, just as for a compile with no previous analysis. Stale classes from deleted sources are only removed if the caller cleans the output. A jar output is rewritten from scratch.
- sbt's test and main-class discovery reads APIs from the analysis, so it finds nothing in this mode. That suits compile-only CI jobs, or needs discovery to come from somewhere else.
- Stale analysis files already on disk are left alone. The mode is meant for fresh checkouts.

## Steps

- [x] `CleanBuildCallback`, a no-op `AnalysisCallback4` with `enabled() == false`
- [x] `Incremental.compileClean` and the dispatch in `IncrementalCompilerImpl.compileIncrementally`
- [x] Skip classfile analysis in `AnalyzingJavaCompiler` when the callback is disabled
- [x] Tests: mixed Scala and Java project, no analysis files written, empty analysis, outputs present, and the next incremental compile recompiles everything
- [x] Benchmark on pekko-actor and pekko-stream: normal clean compile plus analysis store write, against clean mode

## Results

`sbt.inc.CleanBuildBench` (zinc test scope) compiles a source tree from scratch in both modes. The default mode includes writing the analysis through `MixedAnalyzingCompiler.staticCachedStore`, as sbt 2 does. The corpus is pekko 1.7.1 `actor` (214 Scala and 30 Java files) and `stream` (209 Scala and 3 Java files, against the published pekko-actor jar), compiled with Scala 2.13.16 on an Apple silicon Mac.

| | default | clean | saving |
|---|---|---|---|
| actor, cold JVM (median of 3) | 9063 ms | 7832 ms | 14% |
| stream, cold JVM (median of 3) | 11357 ms | 9841 ms | 13% |
| actor, warm (median of last 4 of 12) | 2385 ms | 1890 ms | 21% |
| stream, warm (median of last 4 of 12) | 3850 ms | 3340 ms | 13% |

Where the warm saving goes, from `-Yprofile-enabled` phase times:

- In scalac, `xsbt-api` (about 170 ms for actor, 210 ms for stream) and `xsbt-dependency` (60 to 80 ms) disappear. That is roughly half of the saving.
- The rest is Zinc's side: building the analysis in the callback, stamping, and reading javac's classfiles. Writing the analysis (450 KB) costs only about 20 ms warm and 70 ms cold.

The classfiles are byte-identical between the two modes (1892 classes for actor).
