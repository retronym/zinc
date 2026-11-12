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
- Test and main class discovery finds nothing in this mode, see below.
- Stale analysis files already on disk are left alone. The mode is meant for fresh checkouts.

## Test and main class discovery

sbt discovers tests and main classes from the analysis, not from the classfiles:

- `definedTests` runs `Tests.discover` over `Tests.allDefs(analysis)`, which reads the extracted APIs in `analysis.apis.internal` and matches them against the test frameworks' fingerprints.
- `discoveredMainClasses` calls `Defaults.discoverMainClasses(analysis)`, which reads the `mainClass` reports kept in `analysis.infos`.

In clean mode the analysis is empty, so both come back empty. `run` then finds no main class unless `mainClass` is set explicitly. Worse, `test` runs zero tests and still succeeds, so a CI job that turns this mode on for a project with tests goes green without testing anything. Until discovery stops depending on the analysis, the mode is only safe for compile-only jobs, or for projects whose tests are not run in that build.

There are a few ways out:
- sbt could discover from the classfiles, for example with `ClassfileToAPI`, which Zinc already uses for Java classes it cannot load.
- Zinc could offer a middle mode that keeps the `xsbt-api` phase but drops dependencies, used names and stamping. That gives up roughly half of the saving.
- The clean callback could record only main classes and the definitions that test fingerprints match.

## Steps

- [x] `CleanBuildCallback`, a no-op `AnalysisCallback4` with `enabled() == false`
- [x] `Incremental.compileClean` and the dispatch in `IncrementalCompilerImpl.compileIncrementally`
- [x] Skip classfile analysis in `AnalyzingJavaCompiler` when the callback is disabled
- [x] Tests: mixed Scala and Java project, no analysis files written, empty analysis, outputs present, and the next incremental compile recompiles everything
- [x] Benchmark on pekko-actor and pekko-stream: normal clean compile plus analysis store write, against clean mode

## Results

Measured in the zinc-direct perflab lab (retronym/jvm-perf-lab, `labs/zinc-direct`), which drives Zinc through its API with no sbt. The lab now loads this branch's build, published to a private Ivy home and prepared with `scripts/prepare-zinc-dev.sh`, and has a `mode` parameter (`default` or `clean`). Each op is one from-scratch compile of Pekko 1.7.1 `actor` (214 Scala and 30 Java files) or `stream` (209 Scala and 3 Java files), with Scala 2.13.18, in-process javac, and the analysis written with `FileAnalysisStore` in default mode. The machine is an M5 Pro (testbed `m5-pro`) running a JDK 26 build, with a loadgate of 6 cores. Each fork runs 6 warmup and 6 measured ops.

Timing (campaign `m5-zd-clean-ab`, 3 forks per variant, mean ± 99.9% CI over forks):

| | default | clean | Δ |
|---|---|---|---|
| actor | 2.495 ± 0.35 s/op | 2.063 ± 0.29 s/op | −17.3% ± 10.8% |
| stream | 4.319 ± 0.24 s/op | 3.753 ± 0.21 s/op | −13.1% ± 4.3% |

Where the time goes (campaign `m5-zd-clean-prof`, async-profiler cpu, one fork per variant). This is CPU time per op, with each stack attributed to the outermost frame that names a Zinc subsystem (`scripts/attribute-stacks.py` in the lab):

| ms per op, default − clean | actor | stream |
|---|---|---|
| bridge `xsbt-api` phase | +171 | +205 |
| bridge `xsbt-dependency` phase | +89 | +114 |
| building the analysis after the run (`AnalysisCallback.getCycleResultOnce`) | +124 | +154 |
| of which product stamps (`InitialStamps.product`, hashing every classfile) | 78 | 98 |
| of which source stamps | 11 | 12 |
| of which `completeCycle` | 16 | 16 |
| javac classfile analysis (`JavaAnalyze`, `ClassToAPI`) | +48 | +42 |
| analysis store write | +20 | +22 |
| other Zinc bookkeeping | +16 | +17 |
| scalac's own phases (typer, transform, backend) | +122 | −23 |
| JIT compiler threads | +114 | +61 |
| GC and VM threads | +61 | −43 |

For `stream`, the direct costs on the compile thread add up to about 556 ms/op, against a 566 ms/op difference in wall time. About half is the two bridge phases, a quarter is building the analysis (two thirds of that is hashing classfiles), and the rest is classfile analysis of javac's output and the store write. In default mode Zinc also makes 7 to 11% more JIT compilations, a significant difference in both projects.

`actor`'s first profile also showed scalac's own phases 122 ms/op slower in default mode, the largest item being the `itable stub` of the typer's self-type dispatch. A second pair of campaigns (`m5-zd-clean-tip-ab`, `m5-zd-clean-tip-prof`) repeated the comparison on the 2.13.x tip (762d80f, built with the release optimiser), which includes scala/scala#11367 ("Avoid megamorphic interface calls to the analyzer"). Its profiles have 2 forks per variant:

| actor, ms per op | 2.13.18 default | 2.13.18 clean | tip default | tip clean |
|---|---|---|---|---|
| compile thread | 2659 | 2123 | 2347 | 1901 |
| Zinc-specific frames | 459 | 5 | 459 | 6 |
| typer and transform | 1705 | 1647 | 1418 | 1432 |
| typer self-type `itable stub` | 74 | 61 | 4 | 4 |

The stub costs about as much in clean mode as in default mode, so it is a scalac cost, not one that Zinc adds, and #11367 removes it. In `stream` the same change cuts the JDK's secondary-supers lookups from 116 to 8 ms/op. The default-mode slowdown of typer and transform is small and inconsistent: +58 ms/op for actor on 2.13.18, about zero on the tip, and within noise for stream. Zinc's own cost is the same on both Scala versions, and the timing shows the same clean-mode saving on the tip (actor −19.2% ± 11.1%, stream −14.6% ± 8.2%).

Product stamps are a cost in incremental builds too: after a compile Zinc opens and hashes every classfile it just wrote, mostly time in `open(2)`, which endpoint security software makes dearer still (see the M4 results below). The callback could hash the bytes as the classfiles are written. That would be worth its own change.

The classfiles are byte-identical between the two modes.

### On the M4

The M5 Pro runs endpoint security software (JumpCloud) that inflates file-open costs, so the comparison was repeated on an M4 laptop without it (testbed `m4`, campaigns `m4-zd-clean-tip-ab` and `m4-zd-clean-tip-prof`, same JDK, Zinc build and variants). Timing, 3 forks per variant, mean ± 99.9% CI over forks:

| | default | clean | Δ |
|---|---|---|---|
| actor, 2.13.18 | 2.532 ± 0.07 s/op | 2.202 ± 0.06 s/op | −13.0% ± 2.8% |
| actor, 2.13.x tip | 2.337 ± 0.06 s/op | 2.025 ± 0.05 s/op | −13.4% ± 2.8% |
| stream, 2.13.18 | 4.545 ± 0.34 s/op | 4.020 ± 0.30 s/op | −11.5% ± 7.9% |
| stream, 2.13.x tip | 4.365 ± 0.32 s/op | 3.866 ± 0.29 s/op | −11.4% ± 7.9% |

On the quiet machine the saving is 11–13%, below the M5 Pro's figures, and the intervals are much tighter. Product stamping costs about half as much on the M4 (38 against 75 ms/op for actor, 52 against 101 for stream, at nearly equal total compile times), so on a quiet machine it is roughly 1–1.5% of a full compile. The bridge phases remain the largest item (`xsbt-api` +184 and +227 ms/op, `xsbt-dependency` +88 and +109 ms/op), followed by building the analysis (+85 and +100 ms/op) and javac classfile analysis (+39 ms/op each). Default mode makes 10–16% more JIT compilations on the M4.

