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
- [ ] Benchmark on pekko-actor and pekko-stream: normal clean compile plus analysis store write, against clean mode
