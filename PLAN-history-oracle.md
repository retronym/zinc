# Plan: IncBench as a soundness oracle that replays git history

**Status:** built through step 10 on 2026-11-13; the replays of step 8 and 9 are recorded under "Results". Jason asked for the build-out to proceed without a review stop, so the decisions below were not reviewed first; those marked **(review)** are the ones most worth a second look. Build changes as steps (decision 3) were first refused by the session's permission classifier, since they run the corpus repository's build code; Jason then gave the go-ahead, `--sbt-export` was built, and scala-steward and cats were replayed with it ("Results with the build re-exported").

## Problem

Zinc's soundness bugs are found by users. Undercompilation shows up as a build that needs `clean`; overcompilation shows up as a slow build nobody reports. Every bug found in October came from inputs we chose: generated programs, edit scripts of 8 to 12 hand-picked edits per corpus, and 146 targeted edits to Spark catalyst. Those edits are shaped by what we already suspect.

A project's git history is a long sequence of edits nobody chose with Zinc in mind. Replaying it, compiling each commit incrementally from the previous commit's state and comparing with a clean build of the same commit, turns "is incremental compilation sound and precise" into three numbers per Zinc build:

- **soundness:** on how many steps the incremental result differs from clean, and why;
- **precision:** how much more was recompiled than the change required;
- **performance:** what the incremental step cost against the clean build it replaces.

Run under name hashing and under Merkle on the same history, it is also the comparison the Merkle work has lacked outside hand-picked edits. And the replay is a reusable artefact: anything that can answer "compile this tree incrementally" can be put under the same oracle.

## Where things stand

- **Base.** IncBench's open homes are retronym/zinc#90 (develop) and #71 (1.12.x). This worktree's branch started on the stale local `claude/incbench-develop` (329fa2a4d, without `--profile`/`--serve`); it now sits on #90's head, f9b4b0b71. `claude/incbench-port-develop` is an earlier push of the same four commits; `claude/incbench-harness-1.12.x`, `-defines-class-cache` and `-scala3-profile` are the 1.12.x line, squashed into #71's head 4433cbe68. The oracle is developed here, on #90, and touches only IncBench's own files, so it stays independent of Merkle and ports to #71 as one cherry-pick series.
- **Merkle.** The curated series is rz#86 (precursors) ← rz#24 (name hashes, descendant rules, library ancestors) ← rz#88 (bridge-side hashing), built into `integration/merkle` (develop) and `integration/merkle-1.12.x`. Merkle is an opt-in `incOptions` mode, `hashing=names|merkle` (rz#83), so IncBench selects it per build directory through `incOptions.properties` (`--inc-option hashing=merkle`), and both modes run from one Zinc build. `integration/merkle` already contains #90's IncBench byte for byte, so the side-by-side runs use a second branch, `claude/incbench-history-oracle-merkle` = `integration/merkle` plus cherry-picks of this branch.
- **What ran.** `IncBench --verify --profile` on scala-steward `core` (188 sources, 371 classes), released 2.13 compiler, from the Merkle worktree: all 16 edit and revert steps `same` under `hashing=names` and `hashing=merkle`. Clean build 9 s cold, 3 s warm; incremental steps 0.06 to 1.1 s. A step with its clean reference therefore costs about 5 s on this corpus, so 200 commits are minutes, not hours; exporting the build per commit is what will dominate.
- **What didn't.** `lockstep` asks sbt for a checkout's classpath with `sbt --batch --no-server`, which on the develop line's sbt 2 means "sbtn, and fail without a server". The campaigns ran on 1.12.x. The driver here resolves the classpath itself.
- **Earlier plans.** `PLAN-user-harness.md` already decided that edits should come from git history, that the comparator should be liftable, and that one replay should compare several Zincs; none of it was built. `PLAN-incbench-campaign.md` supplies the in-process driver, the frozen `build.json` export and the lockstep idea. `anomalies/` supplies the real examples of noise used below. No `HARNESS.md` exists under `~/code`; the five-layer harness is recorded here as future work only.

## Key decisions

### 1. Zinc is driven in-process from an exported build, not through the project's sbt

Each replay is one JVM running IncBench's `Runner` (scripted's `IncHandler`) on a frozen export of the project, as the campaigns did. This gives what the three numbers need: both hashing modes from the same Zinc build in the same warm JVM, the invalidation cycles and Zinc's own phase timings per step, and no sbt caches or task graph between us and Zinc.

The cost is that the build must be re-described whenever a commit changes it (decision 3), and that plugins, generators and sbt's own stamping are outside the oracle. Driving the user's sbt (`PLAN-user-harness.md`) remains the right tool for a user's own build; the replay, comparator and report here are written so that driver can reuse them.

### 2. A step is one commit; the unit of replay is "the tree of compiled inputs changed"

The history is the first-parent chain of a branch, oldest first. For each commit the driver materialises the compiled inputs of the exported modules: their sources, dependency jars and options. A commit that changes none of them (docs, tests, CI, a plugin bump) is not a step. "The last 200 commits" therefore means the last 200 commits that change something Zinc compiles.

On the candidate repos most recent commits touch the build, not the sources (scala-steward: of the last 400 first-parent commits 258 touch only `*.sbt` or `project/`, 61 touch main sources), so this definition matters: counting raw commits would replay mostly no-ops.

### 3. Build changes are steps too, with `--sbt-export`; without it the build is frozen at one export **(review)**

A dependency bump or an option change is an edit real users make, and Zinc has to get it right (changed library stamps, changed `MiniSetup`). The intent is to keep the incremental state across them: re-export the build with sbt at every commit that touches `*.sbt` or `project/`, treat a changed export as a step, read source-only commits straight from git, and skip commits whose build does not load. `IncHistory` already supports it: a step may change jars, options and `build.json`, and the copies reload.

**Without `--sbt-export`.** The driver replays against one existing export (the frozen builds of the October campaigns) and follows only the sources: it finds where each module's sources live in the repository by locating the export's files in the tree of the export's commit, and every first-parent commit that changes a file under those directories is a step. Files of the export that are not in the repository (generated sources) stay as exported.

This has a cost that the first runs made plain. Sources older than a dependency change or a generator change do not compile against the export, and then every step is `same-fail`. So the driver first bisects for the oldest step whose parent still compiles and starts no earlier. How far back that reaches depends on the project: 744 steps for scala/scala (no dependencies), all 169 candidate steps for pekko `actor`, 9 for cats (its generated `NTupleMonadInstances` stops matching), 3 for scala-steward (a circe bump).

**With `--sbt-export`.** The project's own sbt re-exports the same modules (`bin/zinc-oracle export`, the campaign's `export-build` moved here) in a clone under the output directory, at the start and at every first-parent commit that changes `*.sbt` or `project/`. Such a commit is a step whether or not it changes a source; the export's jars, options, module graph and generated sources replace the build's, touching only the files that differ, and the source directories are inferred again from the new export. A commit whose build does not export keeps the previous export and is marked in the report. No bisection is needed. A build-changing commit whose export is identical to the previous one (an sbt plugin bump) is not a step. This runs the repository's build code, which is why it is opt-in.

### 4. Commits that do not compile are steps, compared as failures

If the clean build of a commit fails, the incremental build must fail too. The existing verdicts cover it: `same-fail`, `missed-error` (incremental compiled what clean rejects: stale classes hid an error), `spurious-error` (incremental fails where clean compiles). After a failed step the incremental state is whatever Zinc left behind, and the next commit compiles from there, which is exactly the situation of a user who fixes the error and compiles again.

### 5. "Equal to clean" is a ladder of normalisations, and the report says which rung each class needed

Raw byte equality is the goal and stays the headline. But scalac's output depends on the batch in ways that do not change behaviour, and an oracle that cries wolf on each of them is not read. So classfiles are compared at successive levels, each justified by a difference we have actually seen, and a class is reported with the lowest level at which it matches:

| Level | Ignores | Real example |
|---|---|---|
| `raw` | nothing | |
| `pool` | constant-pool order and indices | cats `cats/package$$anon$1`: `<A>` against `<A$>` in one signature shifts every later pool index, which defeated the existing signature check (anomalies/cats §5) |
| `locals` | names in `LocalVariableTable`/`LocalVariableTypeTable` (slots and code kept) | shapeless `LinearAlgebraExamples$`: `x0$3` against `x0$13` from macro fresh-name counters that are not per compilation unit (anomalies/shapeless §4) |
| `typevars` | names of type variables in generic signatures | `A` against `A$` on forwarders, scala/scala#11289 and #11319; every `signature` verdict of the pekko campaign |
| `tasty` | the TASTy UUID in the classfile's `TASTY` attribute, when the `.tasty` files differ only in their header UUID or `Positions`/`Comments` sections | pekko under Scala 3, `RepointableActorRef`: not recompiled, bytecode identical, TASTy differing in stale annotation positions (bug hunt finding 14) |

What is deliberately not normalised: instruction order, local slots (shapeless `IsHCons1Macros` swaps `aload 6`/`aload 7`; that is a compiler nondeterminism worth seeing), synthetic member and anonymous class numbering, pickles and `ScalaSignature`.

A class that matches only above `raw` is still listed, as noise with its level, never folded into `same`. That keeps Jason's earlier ruling (a type-variable-name difference is a mismatch, attributed to scalac) while making the report readable.

Two facts decide what a remaining difference means, and both are recorded per class:

- **stale or fresh:** did this incremental compile write the class? A differing class that was *not* recompiled is the signature of undercompilation. A differing class that *was* recompiled, from the same source, is the compiler depending on its batch.
- **Java context:** in a module with Java sources, scalac sees a Java class as source in a clean build and often as a classfile incrementally. IncBench's `java-context` check (a clean build that sees Java as classfiles) is not carried over yet: such a difference is in a class the step wrote, so it shows as `compiler`, not as a finding. pekko's 169 steps showed none.

The verdict of a step is then: `same`; `noise` (every difference explained by a level or by Java context); `compiler` (unexplained, all fresh); `stale` (an unexplained difference in a class not recompiled, or a missing or extra classfile); or the failure verdicts of decision 4. Only `stale`, `missed-error` and `spurious-error` are soundness findings against Zinc.

The comparison uses ASM (test scope) rather than more hand-written classfile parsing; `Digest.normalize` in IncBench is superseded by the `typevars` level.

### 6. One clean reference serves every mode, and gives the precision baseline for free

The hashing mode changes what Zinc records and invalidates, not what the compiler emits, so each step needs one clean build however many modes run. Comparing the clean builds of consecutive commits also yields the set of sources whose classfiles actually changed (after normalisation), plus the edited sources themselves: the **necessary set**, a lower bound on what any incremental compiler must recompile for that step. Precision is reported against it: sources compiled (distinct, and total over cycles) over the necessary set.

### 7. Modes run side by side in one process; two Zinc checkouts are a later driver concern

Each mode (`names`, `merkle`, or any other `incOptions` variation) has its own copy of the build with its own outputs and analysis, and at each step the modes compile one after another in rotating order in the same warm JVM, followed by the clean reference. This is the in-situ bakeoff's inner loop. Comparing two different Zinc builds needs one JVM per checkout in lockstep, which `--serve` already supports; the replay's step protocol is kept compatible with it, and that orchestration is future work.

### 8. A divergence is isolated before it is reported

- **A stale class persists**, so a divergence would be re-reported at every later step. After a step whose verdict is a finding, that mode's state is reset to a clean build, so each finding is attributed to the commit that introduced it and later steps are judged independently.
- **Evidence is kept at the step:** the commit pair, the changed files, both versions of every differing classfile, the textual (ASM) diff, the invalidation cycles and the per-module invalidation log.
- **Minimisation** is over the commit's changed files: restore the pre-step state, apply a subset, and ask the same oracle (delta debugging). It needs snapshot and restore of a mode's state, and runs only on findings. First version: file granularity; hunks later.

### 9. The report answers three questions per mode, from one `steps.jsonl`

- **Soundness:** steps by verdict; each finding with its evidence; noise by level and by class, so recurring compiler nondeterminism is visible once rather than per step.
- **Precision:** per step, sources and classes recompiled, cycles, the necessary set, and the ratio; the distribution over the history; steps where the modes differ, in both directions; full recompiles.
- **Performance:** incremental wall time per mode against the clean build of the same commit, scalac against Zinc's own time, and paired differences between modes on the same step. Timings from a busy machine are labelled as such; the soundness and precision columns do not depend on a quiet machine.

Markdown, generated from the JSON lines, so two runs can be diffed.

### 10. The repos: scala/scala and pekko, with cats as a short third

The plan was scala-steward (application code, dense history) and cats (several modules, implicits, generated sources). With the build frozen at one export (decision 3) they replay 3 and 9 steps. The repositories that replay deep are those whose compiled inputs are only their sources:

- **scala/scala** `library`, `reflect`, `compiler` at 2.13.x: no external dependencies, 1,072 sources, deep hierarchies, macros, Java mixed in, and the build we know best. 200 steps of the 744 available.
- **pekko** `actor`, `actor-typed`: Java mixed with Scala, deep hierarchies, where Merkle was known to recompile fewer sources. All 169 steps.
- **cats** `kernel`, `core`, `free`: the 9 steps that compile, as a sample of implicit-heavy code.

## Prior art

- `IncBench --verify` (rz#31, #71, #90) and its verdicts; the conformance harness (rz#25) for generated programs.
- `PLAN-user-harness.md`: history as the source of edits, a liftable comparator, several toolchains per replay.
- `export-build`, `lockstep`, `lockstep-summary` in the talks repo's `integration/`.
- Reproducible-builds tooling (`diffoscope`, `strip-nondeterminism`): compare after stripping known-meaningless variation, and report what was stripped.
- Delta debugging (Zeller's `ddmin`) for minimising an edit.
- Bazel's and Buck's "incremental equals clean" correctness checks over real change lists, and rustc's incremental-compilation verification (`-Z incremental-verify-ich`), which recomputes and compares instead of trusting the cache.

## Steps

- DONE 1. Survey: branches, PRs, Merkle selection; rebased onto #90's head.
- DONE 2. Ran the existing `--verify` on scala-steward under both modes.
- DONE 3. This plan.
- DONE 4. Comparator: `ClassfileNorm` (the ladder, over ASM's textual form, with `ScalaInlineInfo` decoded so no pool index survives) and `TastyNorm`, with tests on classfiles that differ in exactly one respect.
- DONE 5. `IncHistory`: replay session with modes, one clean reference, verdicts, necessary set, evidence, carried differences, reset after a finding. `IncHistorySuite` runs it on a two-module build, including a known Zinc undercompilation (the pending scripted test `import-package`), which it reports as `missed-error`.
- DONE 6. History driver `bin/zinc-oracle history`, against a frozen export, with the bisection for the oldest step that compiles.
- DONE 7. Report generator `bin/zinc-oracle report`: soundness, precision, performance, per step.
- DONE 8. Tracer bullet. It was cats, not scala-steward, and it found two harness bugs: old commits not compiling against the export, and files renamed only in case being lost on a case-insensitive file system.
- DONE 9. Replays of scala/scala, pekko and cats under `hashing=names` and `hashing=merkle`; see Results.
- DONE 10. `bin/zinc-oracle minimise`: delta debugging over a step's changed files, each trial from a clean build of the parent (`rebase`).
- DONE 10a. `--sbt-export`: `bin/zinc-oracle export` and build changes as steps (decision 3); scala-steward and cats replayed with it.
- TODO 11. Port to 1.12.x (rz#71). The Python tools carry over as they are. The Scala sources do not cherry-pick: the 1.12.x line's `zinc-scripted` is Scala 2 (braces, no `enum`, no `given`), so `ClassfileNorm`, `TastyNorm`, `IncHistory` and their suites need a syntax port, and `Runner` there needs the same three additions (`compileEvery`, `analysis`, `path`). Not attempted.
- TODO 12. The `java-context` check for Java-mixed modules, and the `tasty` level on a real Scala 3 replay (pekko's Scala 3 export exists; the level is tested only on scala3-library's own files).

## Results

Replays of 2026-11-13, each under `hashing=names` and `hashing=merkle` in one JVM, from `claude/incbench-history-oracle-merkle` (`integration/merkle` 9e7c3e276 plus this branch), released Scala 2.13 compiler, `pipelining=false`, `recompileAllFraction=0.5`, `recompileOnMacroDef=true`. So `names` here is name hashing on the integration line's Zinc, with its fixes, not on a release. Outputs are in `~/code/.integration/history-oracle/run2-{scala,pekko,cats}` (`report.md`, `work/steps.jsonl`, `work/diffs/`). The machine was running other work, each step ran once, and the timings are indicative only.

| | scala/scala | pekko | cats |
|---|---:|---:|---:|
| Steps (first-parent commits that change sources) | 200 | 168 | 9 |
| Sources in the build | 1,072 | 322 | 353 |
| Findings (`stale`, `missed-error`, `spurious-error`), either mode | 0 | 1 | 0 |
| Steps with a compiler difference (`compiler`) | 1 | 0 | 1 |
| Steps with noise only (all `typevars`) | 53 | 35 (names), 36 (merkle) | 7 |
| Necessary sources, total | 1,924 | 787 | 108 |
| Sources compiled, names / merkle | 2,905 / 2,777 | 1,132 / 1,138 | 374 / 363 |
| Compiled over necessary, names / merkle | 1.51 / 1.44 | 1.44 / 1.45 | 3.5 / 3.4 |
| Steps where merkle compiled fewer / names fewer | 30 / 3 | 3 / 3 | |
| Incremental wall, names / merkle (s) | 256 / 190 | 50.4 / 50.7 | 29.8 / 26.4 |
| Clean wall (s) | 3,304 | 559 | 67 |
| Incremental over clean, names / merkle | 7.8% / 5.8% | 9.0% / 9.1% | 45% / 39% |

**Soundness.**

- One finding, in both modes, and it reproduces on develop's Zinc without Merkle: pekko 3a601fa75 changes `Udp.SimpleSender(options: immutable.Traversable[SocketOption])` to `immutable.Iterable[...]`. `Traversable` is a deprecated alias of `Iterable`, so the API hash of `options` does not change and `UdpSender`, whose `private val options = simpleSender.options` infers its type from it, is not recompiled. Its bytecode equals the clean build's; its pickle still names the alias. The minimiser reduces the commit's 5 files to `Udp.scala` in 5 trials. This is the dealiasing Zinc does on purpose, with a visible consequence only for a later compile that reads the stale pickle (a deprecation warning that a clean build would not give). Worth a decision on whether the oracle should treat pickle-only differences in private members as their own verdict; they are now marked `pickleOnly`.
- No step in 377 produced stale bytecode, a missed error or a spurious error, under either mode.
- Compiler differences, both in classes the step recompiled: cats `SortedSetSemilattice` loses its specialised `combineN$mc*$sp` forwarders when `SortedSetInstances.scala` is compiled without the rest of kernel (the batch dependence of anomalies/cats §4, now with a one-file reproduction from the minimiser); scala/scala `StringBuilder` at 701ca74c2 differs only in its pickle.
- All noise is `typevars` (`A` against `A$`), from the released compiler. The `pool`, `locals` and `tasty` levels did not occur on their own.
- The oracle itself is checked against a known Zinc bug: `IncHistorySuite` replays the pending scripted test `import-package` and gets `missed-error`.

**Precision.** Both modes compile about 1.5 times the necessary sources on scala/scala and pekko, with a median of exactly the necessary set. Merkle compiles fewer sources than name hashing on scala/scala in 30 of 200 steps (133 sources, 4.4%) and takes fewer cycles (313 against 343); on pekko the two are level (6 steps differ, 3 each way; the largest, +9 for Merkle, is 2214215b7, which adds an annotation to public sealed traits). cats recompiles 3.5 times the necessary set under both, the macro-definition cascade of anomalies/cats §6.

**Performance.** Incremental compilation costs 6 to 9% of the clean builds it replaces on scala/scala and pekko. On scala/scala Merkle's total is 26% below name hashing's (190 s against 256 s); most of that is inside scalac's `xsbt-*` phases (8 s against 50 s), with Zinc's own time equal (31 s). On pekko the modes are equal. `analysisWrite` and `prune` are the largest parts of Zinc's own time under both. These need a quiet machine and repetitions before they are quoted.

**What the replay cost.** scala/scala: 80 minutes for 200 steps, of which 55 are the clean reference builds. pekko: 16 minutes. The bisection for the first compiling commit took 6 to 9 clean builds.

### Results with the build re-exported

Same setup, with `--sbt-export`, over the last 200 first-parent commits that change the modules' sources or the build definition. Outputs: `~/code/.integration/history-oracle/run3-{steward,cats}`.

| | scala-steward | cats |
|---|---:|---:|
| Commits considered | 200 | 200 |
| Steps (the build or a source differs from the previous step) | 200 | 67 |
| Span of history | 2025-09-15 to 2026-10-17 | 2023-09-16 to 2026-09-22 |
| Exports run / failed | 171 / 0 | 152 / 0 |
| Steps that took a new export | 170 | 18 |
| Steps that change a dependency jar / an option | 37 / 4 | 8 / 9 |
| Findings, either mode | 0 | 0 |
| Steps with a compiler difference | 0 | 5 |
| Steps with noise only | 0 | 50 |
| Necessary sources, total | 455 | 704 |
| Sources compiled, names / merkle | 1,744 / 1,744 | 5,381 / 5,392 |
| Compiled over necessary, names / merkle | 3.83 / 3.83 | 7.64 / 7.66 |
| Incremental wall, names / merkle (s) | 56.3 / 56.5 | 251.5 / 233.1 |
| Clean wall (s) | 546 | 451 |
| Incremental over clean, names / merkle | 10.3% / 10.3% | 56% / 52% |

- **Soundness.** No finding in either repository under either mode, across 45 dependency changes and 13 option changes as well as the source edits. Every scala-steward step is byte-equal to clean.
- **Compiler differences.** cats shows the batch dependence of the first replay at five steps and in nine classes, all specialised instance classes in `kernel` and `core` (`SortedSetSemilattice`, `SortedMap*Monoid`, `SeqOrder`, `OptionCommutativeMonoid`), in both modes alike. The `typevars` noise is carried from step to step, which is why 50 steps show it.
- **Precision.** The two modes are level on both. scala-steward's median step compiles exactly the necessary set; the total of 3.8 times comes from dependency bumps, 4 of which compile every source. On cats the median step compiles 13 times the necessary set in 3 cycles, the macro-definition cascade again, and 8 steps compile everything.
- **Performance.** On cats Merkle spends 11.8 s in scalac's `xsbt-*` phases against 27.8 s, the same direction as scala/scala. Same caveat on the timings.
- **What it cost.** An export takes 7 to 13 s once sbt's caches are warm: 323 exports took 55 minutes in all. In scala-steward, 133 of the 170 exported steps change nothing but the generated `BuildInfo.scala`.
- **Not covered.** The compiler is the one the Zinc checkout's `2.13.x` label gives, whatever `scalaVersion` the commit sets, so its full-cross-versioned compiler plugins are loaded into a possibly different 2.13 patch release. That worked for every export here. The scala-steward replay ran from a classpath without ASM by mistake; it never needed the comparator because no class differed.

## Deployment to users

**Problem.** Everything above is a Zinc developer's tool: test sources in `zinc-scripted`, Python drivers, a frozen export, a compiled Zinc checkout. A user who suspects under- or overcompilation in their own build, or who wants to check a Zinc release against their history, can run none of it. The in-process harness also never exercises sbt's side of incremental compilation (pipelining, sbt 2's action cache and jar outputs, the `MiniSetup` sbt computes), which is where the next bugs are likely.

**Decisions.**

1. **The comparators and tools are a Java module, `zinc-oracle`** (`internal/zinc-oracle`, package `sbt.internal.inc.oracle`, Java 17, depends on ASM and minimal-json only). Java, so that one artifact serves sbt 1 (Scala 2.12), sbt 2 (Scala 3), the 1.12.x line and other build tools, with no cross-building and no syntax port. It holds `ClassfileNorm`, `TastyNorm`, `ClassDirDiff` (two output directories in, differences with their levels out; also a CLI, which is the whole integration for Mill, Bloop, scala-cli or Maven), `Report`, and the history driver, exporter and minimiser, which replace the Python scripts. `IncHistory` stays in `zinc-scripted`, in Scala, because it drives scripted's `IncHandler`. **(review: Java 17 excludes sbt 1 users on JDK 8 and 11.)**
2. **sbt users get a plugin, `sbt-zinc-oracle`**, that drives their own sbt instead of an export, so the build's own `scalaVersion`, compiler plugins, options and generated sources are used and the export's caveats go away.
   - `incVerify`: after `compile`, compile the same inputs again into a scratch directory with no previous analysis, and compare. Verdicts as in decision 5 above, with `missed-error` and `spurious-error` from the two compiles' outcomes. Modules are verified in dependency order, and a module's clean compile reads its upstream modules' incremental output, so a stale upstream class is reported once, upstream.
   - `incReplay N`: clone the repository into a scratch directory, add the plugin to the clone, and in a child sbt check out each of the last N commits, `reload` when the build changed, and `incVerify`. It writes the same `steps.jsonl`, so `Report` works unchanged.
   - On a finding, a report directory whose summary names classes, verdicts and versions but contains no source, with the evidence beside it for the user to decide about.
3. **The plugin is its own small sbt build**, in `sbt-zinc-oracle/`, cross-built for sbt 1 and sbt 2, compiling the module's Java sources into its own jar. A plugin project inside Zinc's sbt 2 build would have to build for sbt 1 from there, and would make Zinc's build depend on sbt. **(review)**
4. **One mode per session.** A user's sbt has one Zinc, so comparing two hashing modes or two Zincs is two replays (two `incOptions`, or two sbt launchers), not one warm JVM. Timing comparisons get noisier; soundness and precision do not. The in-process harness stays as the tool for Zinc's own CI.

**Steps.**

- DONE D1. Module skeleton in the build.
- DONE D2. Comparators ported to Java (`ClassfileNorm`, `TastyNorm`, and `ClassDirDiff` with a CLI); `zinc-scripted` uses them. Checked against the Scala originals on 7,324 classes and 941 TASTy files before those were deleted.
- DONE D3. `Report` in Java, byte-identical to the Python on the six existing replays and on edge cases derived from them.
- DONE D4. `History`, `Export`, `Minimise` in Java, agreeing with the Python on a cats replay and a minimisation; the Python scripts are removed and `bin/zinc-oracle` launches the tools.
- DONE D5. Plugin: `incVerify`, with scripted tests on sbt 1.9.9 and sbt 2.0.10.
- DONE D6. Plugin: `incReplay` and the report directory, with a scripted test that replays a five-commit history.
- DONE D7. Run on scala-steward through its own sbt 1, and on a small sbt 2 build.
- DONE D7a. `KnownIssues`: a table of causes already understood, matched against a difference's evidence. The report's compiler-difference table has a "Known issue" column and each finding says which issue it matches or that it matches none; the plugin's finding summary says the same. Three entries, each from a replay or test here: `scalac-specialisation-batch` (all nine cats classes match), `zinc-signature-only-stale` (pekko's `UdpSender`), `zinc-import-removed-package` (the `import-package` test).
- TODO D7b. Confirm `compiler` directly instead of inferring it: run scalac alone on the sources of the cycle that wrote the class, against the same classpath, and compare. Today a recompiled class that differs is attributed to the compiler because Zinc cannot have left it stale.
- TODO D8. Publishing: coordinates, a release, and whether `zinc-oracle` is published from Zinc's build as well as inside the plugin's jar.
- Future: the minimiser through sbt; phase timings (they need rz#90's hooks in the user's Zinc); two Zincs in lockstep; the necessary set and compiled sources as paths relative to the build, which sbt 2 gives and sbt 1 does not.

**What running it showed.**

- **It finds a real bug through real sbt.** The scripted test `import-package` (Zinc's own pending test) passes because sbt 1.9.9 and sbt 2.0.10 both leave the importer of a deleted package uncompiled, and `incVerify` reports `missed-error` and fails the build.
- **scala-steward through its own sbt 1.13:** 12 commits, every step `same`, the report rendering from the plugin's `steps.jsonl`. A step costs 10 to 20 s once caches are warm (the incremental compile, a clean compile of every module and configuration, and a `reload` when the build changed). The first run took 26 minutes because older commits download their own sbt and dependencies.
- **A full clone, not a worktree.** The replay clones the repository with its own `.git` directory and its submodules, since some builds do not work in a git worktree.
- **A commit that does not compile** is `same-fail` for the module that fails, and the modules that depend on it are not checked: their clean compile would read the failed module's older output and could succeed where sbt rightly did not try.
- **sbt 2 differences.** A task gets no analysis from before the compile and the timestamps in the one after are normalised, so which sources were compiled is read from the modification times of their outputs. A second replay of the same history is served from sbt 2's disk cache and compiles nothing, which is the cache working but makes precision numbers meaningless on a repeat; a replay should have its own cache directory. Not done.
- **Not covered.** A build whose root is not the repository's root is handled but untested. Java 17 is required. The child sbt is the `sbt` on the `PATH`.

## Future work

- **pekko under Scala 3**, with the per-round Java context check (`byRound`) carried over from `--verify`.
- **In-situ bakeoff of two Zinc implementations** on a user's build: one JVM per Zinc checkout in lockstep over the same replay, or the sbt-driven driver of `PLAN-user-harness.md` reusing the comparator and report.
- **Bug-report generator** for a user who just hit under- or overcompilation: take the working tree's last commits (or a stash) as a two-step history, replay, minimise, and write a report directory whose summary contains no code.
- **Hedgehog differential test:** a generated class hierarchy plus an edit, incremental against clean, with the same comparator as the property's oracle; shrinking takes the place of minimisation.
- **Five-layer incremental test harness with oracles (`HARNESS.md`).** No such file exists yet. The layers this work suggests: generated programs against a model (conformance), scripted tests pinning recompiled sets, edit scripts on real builds (`--verify`), history replay (this plan), and users' builds (the sbt driver). Each layer's oracle and what it cannot see should be written down in one place.
- Hunk-level minimisation; a CI mode (replay the commits of a PR); tracking the numbers per Zinc release; Mill and Bleep as drivers.
