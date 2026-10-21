# Merkle PoC vs clean builds on Spark catalyst

The conformance harness (retronym/zinc#25) found no undercompilation in generated programs. This is the same check on real code: across many edits to Spark 4.0.1's `sql/catalyst` (2,527 classes, Scala and Java sources in one module), an incremental build must produce the same classfiles as a clean build of the same sources. Draft PR: retronym/zinc#31.

## Method

- **Edits.** `Hierarchy` reads the stored analysis for descendant and client counts, and `bin/catalyst-edits.py` generates the edits. Targets are the 28 traits and abstract classes with the most descendants (`TreePatternBits`, `TreeNode`, `Expression`, `LogicalPlan`, `Rule`, `UnaryExpression`, `BinaryExpression`, …) and the 10 leaf classes with the most clients. Each target gets four edits: an unused member, an overload taking a fresh class, a body-only change, and `java.io.Serializable` added to its parents. The generator skipped six (no public method to overload, or already serializable), leaving 146 edits. All 146 compile.
- **Check.** After each edit's incremental compile, `IncBench --verify` builds the edited sources from scratch in a mirror and compares every classfile by digest. Each revert is compared with the initial clean build.
- **Batch dependence.** scalac's output depends on its batch, so a digest difference alone doesn't mean staleness. Differences are explained against further clean builds, built only when needed: one that sees the Java classes as classfiles, and one per round that reproduces which Java sources the incremental round had as sources. Each step gets a verdict: `same`, `signature` (only type-variable names in generic signatures differ), `java-context` (explained by those builds), `fresh-mismatch` (written by this compile but explained by none), or `bytecode` (a stale, missing or extra classfile: undercompilation).

## Results with a batch-stable scalac (headline)

Scala `2.13.19-stability-4` is retronym/scala `stability-fixes` at 145161e92e: scala/scala#11289, #11290, #11291 and #11292 merged into 2.13.x. It ran with `javac -parameters` and pipelining off, on all 146 edits for both the PoC and develop. All 146 compile.

- **No undercompilation:** both PoC and develop verify all 292 steps (146 edits, each with its revert). No stale, missing or extra classfiles.
- **One class still differs:** `CatalogV2Util$`. An eta-expanded Java enum `valueOf` names its parameter `x` when the enum is a Java source in the batch and `name` when it's a classfile (fixed by scala/scala#11293). It matches the clean build that reads Java from classfiles.
- **On `2.13.19-stability-5`,** which adds that fix, the edit that exposed it is byte-identical incremental vs clean.

Recompiled classes (median / max) and wall time (median, s) on this compiler:

| target | edit | n | develop | PoC | develop s | PoC s |
|---|---|---|---|---|---|---|
| ancestor | add-member | 28 | 531 / 1372 | 290 / 751 | 6.3 | 3.2 |
| ancestor | overload | 24 | 716 / 1514 | 544 / 1514 | 11.7 | 6.3 |
| ancestor | body | 28 | 8 / 520 | 8 / 531 | 0.4 | 0.5 |
| ancestor | serializable | 28 | 870 / 2305 | 872 / 1988 | 13.9 | 10.9 |
| leaf | add-member | 10 | 16 / 59 | 16 / 59 | 0.5 | 0.6 |
| leaf | body | 10 | 13 / 48 | 13 / 48 | 0.4 | 0.4 |
| leaf | overload | 10 | 120 / 591 | 120 / 591 | 1.8 | 1.6 |
| leaf | serializable | 8 | 33 / 748 | 33 / 760 | 0.6 | 0.5 |

Totals over 146 edits: develop 70,097 classes and 1,113 s; PoC 58,675 and 695 s.

## Earlier results (Scala 2.13.16, pipelining off)

No undercompilation in the PoC or in develop.

- **PoC**, 292 steps: `java-context` or `signature` everywhere except 4 `bytecode` and 1 `fresh-mismatch`.
- **develop**, 292 steps: same, except 4 `bytecode`.
- **The 4 `bytecode`, on each side:** the same two edits (edit and revert). An early version of the harness carried batch-dependent bytes over from the previous edit's revert. Rerun from a clean base, both verify; the harness now rebuilds clean after such reverts.
- **The `fresh-mismatch`, PoC only** (revert of `ImplicitCastInputTypes`-serializable): scalac's erased lub depends on symbol creation order. Reproduced with plain scalac: whether the batch's own old classfiles are on the classpath decides the result. The PoC splits that revert into different rounds than develop, which is why only it hit this.
- **Pipelining on**, PoC, 30-edit sample: all `signature`. With pipelining, Java sources are recompiled on every compile, so even a body-only edit recompiles ~1,700 classes.

## Recompiled classes and wall time, PoC vs develop

Median / max over edits; wall time is the median, in seconds, on a shared machine at load 15–45, so treat it as rough.

| target | edit | n | develop | PoC | develop s | PoC s |
|---|---|---|---|---|---|---|
| ancestor | add-member | 28 | 531 / 1372 | 290 / 751 | 6.9 | 3.5 |
| ancestor | overload | 24 | 716 / 1514 | 544 / 1514 | 10.4 | 5.9 |
| ancestor | body | 28 | 8 / 520 | 8 / 531 | 0.4 | 0.5 |
| ancestor | serializable | 28 | 870 / 2305 | 872 / 1988 | 14.2 | 10.1 |
| leaf | add-member | 10 | 16 / 59 | 16 / 59 | 0.6 | 0.5 |
| leaf | body | 10 | 13 / 48 | 13 / 48 | 0.4 | 0.4 |
| leaf | overload | 10 | 120 / 591 | 120 / 591 | 2.0 | 1.6 |
| leaf | serializable | 8 | 33 / 748 | 33 / 760 | 1.0 | 0.6 |

Totals over all 146 edits: develop 71,334 classes and 1,218 s; PoC 58,695 and 669 s.

## scalac batch dependences found

Each makes an incremental build differ from a clean one without any undercompilation:

| what differs | cause | fix |
|---|---|---|
| static forwarders' type variables, `compose[A]` vs `compose[A$]` | cloned type parameters renamed depending on the batch | scala/scala#11289 |
| Java `static final` constant expressions inlined from a classfile, read as fields from a source | `JavaParsers` folds only single literals | scala/scala#11290 (scala/bug#10410) |
| mixin forwarders' parameter names for Java interfaces | no parameter names in classfiles | compile Java with `javac -parameters` |
| erased lub: `Unevaluable` vs `ImplicitCastInputTypes` | base types of equal depth ordered by symbol id | scala/scala#11291 |
| mixin forwarder `lazyZip[B]`: `B$` vs `B$$$$$` | an existential forwarder info wasn't cloned | scala/scala#11289 (third commit) |
| pickle of a Scala class implementing a Java interface | `Object` typed as `ObjectTpeJava` in Java sources only | scala/scala#11292 |
| Java enum `valueOf` parameter name, `x` vs `name` | `JavaParsers` named it `x`; javac, `name` | scala/scala#11293 |

## Harness changes on this branch

- `IncBench --verify`, with `--only REGEX`, and edits that change several places in one file.
- `Hierarchy` and `bin/catalyst-edits.py` for generating edits.
- Scripted: a `2.13.local` label (this checkout's bridge on a locally built Scala, via `ZINC_SCRIPTED_SCALA213_JARS` and `ZINC_SCRIPTED_SCALA213_VERSION`), and `javac.options` in `incOptions.properties`.
