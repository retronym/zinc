# Plan: IncBench as a soundness oracle that replays git history

**Status:** plan written 2026-11-12 after running what exists (see "Where things stand"). Jason asked for the build-out to proceed without a review stop; decisions marked **(review)** are the ones most worth a second look.

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

### 3. Build changes are steps too, re-exported from sbt only when a build file changed **(review)**

A dependency bump or an option change is an edit real users make, and Zinc has to get it right (changed library stamps, changed `MiniSetup`). So the replay keeps the incremental state across them rather than restarting.

- The build is exported with sbt (`print` of sources, classpaths, options and source directories per module, as `export-build` does) at the first commit and again at every commit that touches `*.sbt` or `project/`. If the export is unchanged, the commit is not a build step.
- Between exports, a commit's sources are read straight from git under the module's exported source directories, so no sbt runs for a source-only commit.
- Managed (generated) sources are captured at each export. A generator whose inputs live outside `project/` would be missed between exports; the driver re-exports at the last commit and reports any drift rather than trusting this silently.
- A commit whose build does not load or resolve is skipped, and its changes fold into the next step that exports.

The exporter moves into this repo beside `bin/incbench-report.py`, since the oracle cannot depend on a script in the talks repo.

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
- **Java context:** in a module with Java sources, scalac sees a Java class as source in a clean build and often as a classfile incrementally. IncBench's existing `java-context` check (a clean build that sees Java as classfiles) is kept and run only for steps that otherwise diverge.

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

### 10. The first two repos: scala-steward and cats

- **scala-steward** `core`: one module, application-style code on cats-effect, circe and http4s, with a dense history of small real edits and constant dependency bumps (which exercise decision 3). It compiles in seconds, so it is the tracer bullet.
- **cats** `kernel`, `core`, `free`: several modules, generated sources, type classes and implicits throughout, and the one corpus where Merkle is known to recompile both fewer and more than name hashing.

Both are Scala 2.13 by default, and both are corpora with October results to compare against. pekko (`actor`, `actor-typed`: Java mixed with Scala, deep hierarchies, and a Scala 3 build for the `tasty` level) is the third, after the first two run clean.

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
- TODO 4. Comparator: the normalisation ladder over ASM, with tests built from classfiles that differ in exactly one respect.
- TODO 5. `IncHistory`: replay session with modes, one clean reference, verdicts, necessary set, evidence, reset after a finding; tested on a small synthetic history.
- TODO 6. Exporter and history driver: commits, exports at build changes, source sync from git, failing and unloadable commits.
- TODO 7. Report generator: soundness, precision, performance.
- TODO 8. Tracer bullet: scala-steward, 200 steps, both modes; fix what it shows.
- TODO 9. cats, 200 steps, both modes.
- TODO 10. Minimisation of findings by changed file.
- TODO 11. Write-up of the two replays, and a port note for 1.12.x.

## Future work

- **pekko**, Java-mixed and under Scala 3, with the per-round Java context check (`byRound`) carried over from `--verify`.
- **In-situ bakeoff of two Zinc implementations** on a user's build: one JVM per Zinc checkout in lockstep over the same replay, or the sbt-driven driver of `PLAN-user-harness.md` reusing the comparator and report.
- **Bug-report generator** for a user who just hit under- or overcompilation: take the working tree's last commits (or a stash) as a two-step history, replay, minimise, and write a report directory whose summary contains no code.
- **Hedgehog differential test:** a generated class hierarchy plus an edit, incremental against clean, with the same comparator as the property's oracle; shrinking takes the place of minimisation.
- **Five-layer incremental test harness with oracles (`HARNESS.md`).** No such file exists yet. The layers this work suggests: generated programs against a model (conformance), scripted tests pinning recompiled sets, edit scripts on real builds (`--verify`), history replay (this plan), and users' builds (the sbt driver). Each layer's oracle and what it cannot see should be written down in one place.
- Hunk-level minimisation; a CI mode (replay the commits of a PR); tracking the numbers per Zinc release; Mill and Bleep as drivers.
