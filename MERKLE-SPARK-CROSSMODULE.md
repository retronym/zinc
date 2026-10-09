# Merkle name hashes across modules: Spark 4.0.1 sql/catalyst → sql/core

Question: does the Merkle PoC's precision survive the module boundary on a real build? Inside one module, catalyst, adding an unused member to `TreeNode` recompiles 420 classes instead of 1,371. The downstream path (`invalidateClassesExternally` plus `MerkleHashes` composing hashes across analyses) had only been measured on synthetic trees.

## Method

- **Build.** Two IncBench projects: `catalyst` (2,527 classes) and `core` (1,961 classes), `core dependsOn catalyst`. Sources are Spark v4.0.1 `sql/catalyst/src` and `sql/core/src`, Scala 2.13.16 with the in-repo bridge. Dependencies are the published jar closures (`coursier fetch spark-catalyst_2.13:4.0.1` and `spark-sql_2.13:4.0.1`, minus catalyst, sql and scala-library).
- **What sql/core needs beyond its jar closure:**
  - `StateMessage.proto`'s generated classes, which Maven makes with protoc. They're taken from the published spark-sql jar.
  - One source line patched. `TransformWithStateInPandasStateServer.scala` imports `com.google.protobuf.ByteString`, and Maven relocates it to `org.sparkproject.spark_core.protobuf` only after compiling, so the import names the shaded package directly.
  - Nothing else is generated. The setup script is `mk-spark-catalyst-core-bench.sh` in the session notes.
- **Edits**, all to catalyst, so that their effect lands downstream:
  - an unused `def benchX: Int = 1` added to `TreeNode`, `Expression`, `LogicalPlan` and `UnaryExpression`
  - a body-only change to `TreeNode`
  - `java.io.Serializable` added to `TreeNode`'s parents
- **Runs.** `IncBench --reps 1 --inc-option pipelining=false`.
  - A = `claude/merkle-baseline` (sbt/zinc develop plus the harness).
  - B = this branch (the PoC plus the fix below).
  - Each side has its own worktree, Ivy home and copy of the corpus.
- **Correctness.** `--verify` compares the classfile digests of each incremental build with a clean build of the same sources, and of each revert with the original clean build. It keeps the differing files for inspection.
- **Caveat.** The machine was shared with other benchmark sessions (load 5–16 on 18 cores), so wall times are indicative. Class counts and rounds are deterministic, identical across repeated runs.

## Results

Classes recompiled / rounds, per module. "B₀" is the PoC before the `traitDirect` fix below.

| edit (in catalyst) | catalyst A | catalyst B | core A | core B₀ | core B | wall A | wall B |
|---|---|---|---|---|---|---|---|
| `TreeNode`: add unused member | 1,371 / 3 | 420 / 2 | 518 / 2 | 201 / 3 | **44 / 2** | 25.7 s | 8.2 s |
| `Expression`: add unused member | 1,200 / 3 | 388 / 2 | 59 / 1 | 45 / 2 | **28 / 2** | 19.7 s | 6.0 s |
| `LogicalPlan`: add unused member | 389 / 3 | 47 / 2 | 148 / 1 | 143 / 3 | **2 / 1** | 7.9 s | 2.6 s |
| `UnaryExpression`: add unused member | 800 / 3 | 146 / 2 | 21 / 1 | 8 / 1 | **8 / 1** | 7.6 s | 2.6 s |
| `TreeNode`: body only | 10 / 1 | 10 / 1 | 0 | 0 | 0 | 0.75 s | 1.08 s |
| `TreeNode`: add `java.io.Serializable` | 2,304 / 4 | 1,987 / 3 | 1,051 / 2 | 1,051 / 1 | 1,051 / 1 | 39.2 s | 49.6 s |
| clean build, warm JVM | 2,527 | 2,527 | 1,961 | 1,961 | 1,961 | 31.9 s | 32.7 s |

Body-only wall times are medians of 5 edit/revert pairs; the other wall times come from one run per side. The Serializable row is a header change, which both sides handle by recompiling every downstream descendant. B's slower time there is not yet explained; a repeat run is pending.

Stored after a clean build:

| | catalyst A | catalyst B | core A | core B |
|---|---|---|---|---|
| name hashes | 212,389 | 94,046 | 132,675 | 77,870 |
| analysis on disk | 1.87 MB | 1.45 MB | 2.15 MB | 1.96 MB |

### Correctness

No stale output was found on either side. Every incremental-vs-clean classfile difference, on A and on B, comes from scalac emitting different bytecode for an unchanged source depending on what else was in the batch:

- forwarder type parameters named `A$` instead of `A`, and missing `throws` clauses on forwarders to Java methods (scala/scala#11289 fixes both);
- a Java `static final int NUM_BYTES = 4 * 1024` that scalac inlines (`sipush 4096`) when it reads the classfile, but not when it parses the Java source, because the initializer is an expression rather than a literal (a scalac fix is in progress);
- parameter names of forwarders to Java methods (`x$1`), which are intended.

A shows more of these than B only because it recompiles more.

## PoC bug found: `traitDirect` downstream of a trait

In core, B₀ recompiled 143 classes for `LogicalPlan`, against develop's 148. The cause was `traitDirect`: "Descendant InMemoryRelation of LeafNode recompiles (traitDirect: mixes in trait LeafNode)". `LeafNode` is a trait extending the class `LogicalPlan`. Downstream, its composed API moves whenever any ancestor changes, so every class mixing in `LeafNode`, `UnaryNode` and the like recompiled, although a class ancestor's members get no mixin forwarders. Inside catalyst the same edit is attributed to `LogicalPlan` itself, a class, so the rule doesn't fire there.

**Fix** (`96e53a4c8`). A trait's composed API also hashes, per name, the contributions of the trait and its trait ancestors only, plus their `extraHash`es (private fields), under names no client uses. For an upstream trait, `traitDirect` fires only when one of these moves.

**Tests:**
- `merkle-x-trait-class-ancestor` checks the precision. It fails on develop, which recompiles every descendant.
- `merkle-x-trait-move` is the soundness guard. A concrete member moves from a trait ancestor to a class ancestor, and the forwarder must go. Narrowing the rule by looking up the traits' current declarations instead fails this test with a `NoSuchMethodError`; develop passes it.
- The existing `trait-private-val-*transitive-inheritance` tests caught a first version that ignored private fields.

The full scripted suite passes.

## Open

- **Per-compile overhead.** On the body-only edit B takes about 0.3 s longer (1.08 s vs 0.75 s median), with nothing recompiled in core. It's probably `Lookup` composing hashes for every upstream class core depends on, which isn't memoised across classes. In the single-module catalyst bench the two sides are equal.
- **`traitDirect` on core-internal traits.** It still fires a few times (4 classes for `TreeNode`): such a trait's stored API materialises upstream class members, so an upstream class change looks like a trait change on the internal path.
- **`Expression` asymmetry.** The edit recompiles 28 classes in core, its revert 35.
