# Merkle name hashes: shortest path to a PoC

Context: talk §7–11 (Zinc Incrementality). Today `ExtractAPI.mkStructureWithInherited` materialises every inherited member into each class's `Structure`, so editing an ancestor changes every descendant's name hashes, and Zinc recompiles the whole hierarchy just to refresh them. The Merkle alternative stores decls only and composes a descendant's per-name hash from its ancestors', so an ancestor edit recompiles clients, not the hierarchy.

Ground rules: Scala 2 with the in-repo compiler bridge (scripted's default `2.12.x` label). No compat: this worktree *is* the B side; a sibling worktree on `1.x` is the A side. The `descendantRules` incOption also gives an in-build A/B (`all` = previous behaviour). Java (`ClassToAPI`) and Scala 3 are out of scope.

## Status

DONE (commits on `claude/merkle-hash-poc-path-ec0cd8`):

- Baseline `merkle-*` scripted tests, pinned on today's Zinc. This found a pre-existing undercompilation: making a parent `final` didn't recompile subclasses, because `HashAPI.hashAPI` skips a top-level class's own modifiers. It is spun off as a separate fix for `1.x`.
- Bridge: no materialised members from internal ancestors (decision 4).
- Zinc: `DescendantRule`s + presets + decl stubs in the minimized API (decision 3).
- Zinc: Merkle composition for *cross-project* lookups (`MerkleHashes`, `Lookup`).
- Full `scripted` green (195 pass, 20 pre-existing pending); unit tests green.

Result: §10a Edit 1 recompiles `X Y` instead of `B C X Y`. `transitive-class`, `transitive-memberRef`, `class-based-inheritance` and `local-class-inheritance` now recompile fewer descendants, and each case was checked for soundness (a concrete member added that no descendant declares, uses or must implement).

What implementing it taught us, which changes decisions 1–3:

- **Inside a subproject, no composed hashes are needed.** Zinc records a `memberRef` dependency on the *owner* of every selected member, so clients already carry `(owner, name)` keys, which is the decls+walk design. And `invalidateClassesInternally` already invalidates the `memberRef` clients of every descendant using the ancestor's changed names. That walk *is* decision 2's closure diff. Decision 1 as written was not built.
- **Across subprojects, composition is needed**, and the downstream analysis lookup has no relations. So `MerkleHashes` composes from the stored linearization (`structure.parents`): decision 1, flattened, at lookup time. `macros/macro-type-change-3` is the case that needs it: a macro reflecting over `baseClasses` names no owner. Ancestors contribute their class-side `extraHash`, not `apiHash`, so companion-object members (not inherited) don't leak; this is the sbt/zinc#1796 conflation again. An object's ancestors count only when it has no class side.
- **The rule table shrank to six.** Ablation shows `uses` and `fallbacks` are subsumed, because a descendant is a `memberRef` client of its parent (constructor call, inherited member selections). `default` = `overrides, conflicts, abstract, header, trait, mirror`; `strict` adds `uses` and `fallbacks`. Framing: the rules cover what a descendant's compilation reads *outside* `U(D)`, i.e. its refchecks and forwarder generation.
- **Lean (Flat.lean, FlatRules.lean, Exhaustive.lean in retronym/talks) corrected two things.** `abstract` must count names deferred in *any* ancestor of the descendant, not just the changed class (`merkle-abstract-ancestor`; 672 undercompiles in the exhaustive check otherwise). Flattened composition over a stored linearization is sound (`flat_sound`) only if every transitive descendant of a header-changed class recompiles, so `header` is back in `default`. No scripted test fails without it, and the invalidation logs of `merkle-subproject-header-stale-lin` and `merkle-subproject-header-cascade` show why. A header change moves the class's own class-name hash, and it also invalidates every `memberRef` client of every descendant. Each descendant names its direct parent in its extends clause, so every transitive descendant is recompiled in the same round and its stored linearization refreshed. `header` is kept as defence in depth, for a descendant not recorded as a `memberRef` client of its parent.
- **Macro-expansion edges must also be followed from every descendant** (`merkle-move-ancestor-downstream-macro`; Lean: T2-stale, since a macro client's key covers the whole receiver class). All four internal edge kinds (inheritance, local inheritance, memberRef, macro expansion) now range over the descendant closure.
- **`merkle-trait` doesn't discriminate.** A missing mixin forwarder is invisible at runtime when the JVM resolves the trait's default method. It only shows when the trait overrides a class member, since class methods win resolution (`merkle-trait-override`). In general, behaviour-level checks are a weak oracle for bytecode differences, which argues again for 4b.

- **Cross-module.** The rules now also filter descendants of an *upstream* changed class (`invalidateClassesExternally`), with ancestors found through stored linearizations and `MerkleHashes` composing across subprojects' analyses. `merkle-x-*` repeat the per-rule tests with the ancestor upstream; `none` fails the six negative ones. Two more fixes fell out:
  - a descendant's API materialises an upstream ancestor's members, so `conflicts` must subtract all of the changed class's names;
  - with pipelining, a downstream sees no bytecode hash, so `MerkleHashes` adds the top-level header that `HashAPI` omits.

## Benchmark (IncBench, synthetic)

`zincScripted/Test/runMain sbt.internal.inc.bench.IncBench` generates an inheritance tree spread across modules, applies each edit, and records per module the rounds, classes recompiled, wall time and analysis size. A = `1.x` plus the harness commit (worktree `merkle-baseline`), B = this branch. The tree: 4 modules, depth 6, fan-out 2, 127 classes (m0 3, m1 12, m2 48, m3 64), a client of every class and of every leaf; medians of 3.

| edit | A recompiled | B recompiled | rounds A→B |
|---|---|---|---|
| root: add member | 127 | 1 | 5→1 |
| root: add overload of used member | 318 | 192 (just the clients) | 5→5 |
| mid-level: add member | 15 | 1 | 3→1 |
| root: body only | 1 | 1 | 1→1 |

Wall times from these first runs are void: scripted's `apiDebug` diffed and logged every changed API, which cost more than the compile and grew with API size (the baseline's 17 s for 127 classes was 0.6 s without it). IncBench now times with `apiDebug` off and counts extraction in a separate untimed pass.

With a trait root, adding a member recompiles 3 instead of 127: the root and the two classes that mix it in directly, which hold the forwarders. Analysis size barely moved (246→234 KB) while members inherited from *upstream* modules were still materialised; with that stopped (5622e0248), extracted inherited definitions fall from 19,153 to 6,061 and the minimized analysis from 194 KB to 125 KB.

Surveys (source-level, approximate, in the scratchpad's survey.py): Pekko's cross-module inheritance is mostly traits (346 cross-module descendant edges vs 83 from classes). Spark has deep class hierarchies (`TreeNode` 261 descendants, `Expression` 257), mostly inside `sql/catalyst`; its cross-module reach is dominated by traits (`Logging`, 694 descendants across 23 modules).

TODO:

- Real corpus: drive IncBench from sbt-bloop exports, starting with Spark catalyst/sql.
- Narrow `trait` to descendants that mix the trait in directly (forwarders live only there).
- 4b differential test (incremental vs clean bytes); IncBench can compare against a clean build per edit.
- Lean: see the chip; feed it the findings above.
- Known gaps: `ExternalLookup` fast-track (sbt's own hook) bypasses `MerkleHashes`;  header detection relies on `bytecodeHash` differing when `HashAPI` doesn't see the change.

## Key decisions

**1. Flattened composition over the stored linearization, done in Zinc at diff time.** *(Built only for cross-project lookups; see Status.)* The stub that `APIUtil.minimize` keeps already contains `structure.parents` = the linearized ancestor types as seen from the class (type args included). So for class `C` and name `n`:

    H_C(n) = hash( own_C(n), [ (hash(P as seen from C), own_P(n)) | P ∈ lin(C), P internal, n ∈ decls(P) ] )

where `own_X(n)` is X's decls-only name hash, which is what we persist in `AnalyzedClass.nameHashes`. No recursion and no new persisted fields. Composed hashes are computed on demand (memoised per invalidation run) from the old and new `Analysis` and diffed. Storage becomes Σ|decls|, which is the size win.

Why flattened instead of the recursive §10 formula: no need to persist direct parents, and it reuses what's already stored. The stored `lin(C)` goes stale when an ancestor's extends clause changes, but that's a header change, and header changes keep today's unfiltered transitive inheritance invalidation (decision 3), so `C` is recompiled and its linearization refreshed. Only the ancestor-type term in the composition is per-ancestor; restricting it to ancestors that declare `n` gives the §10a precision (an asSeenFrom edit moves only names declared via that parent; it still moves `g` in Edit 2).

**2. Freshness obligation (Stale.lean).** After round `k`, diff composed hashes over `recompiled ∪ inheritance.internal.reverse*(recompiled)`, not just `recompiled`. The changed names of a descendant feed ordinary `memberRef` invalidation of *its* clients. This replaces the existing "memberRef clients of every inheritor, filtered by the ancestor's changed names" walk in `IncrementalNameHashing`; keep the walk for externals.

**3. Descendants are clients too: name-filter the inheritance edge with a pluggable rule set.** A descendant's own typecheck and codegen read its ancestors (talk §10a, "When a descendant must recompile anyway"). So the inheritance edge stays, but it is filtered. Each rule is a separate predicate that returns a reason string:

```scala
trait DescendantRule:
  def name: String
  /** Why `d` must recompile after `p` changed `changed`, or None. */
  def apply(ctx: HierarchyView, d: String, p: String, changed: Set[String]): Option[String]
```

`HierarchyView` is built once per round, from old and new Analysis: decl stubs per class, `lin(d)`, `U(d)` (`relations.names`), and header diffs. The policy is the disjunction of the enabled rules, chosen through the `descendantRules` incOption (`IncOptions.extra`). Each hit goes to the invalidation log with its rule name ("C: conflict on m via M"), so both scripted tests and perf runs can explain every descendant recompile.

The rules:

| rule | `D` recompiles when | why |
|---|---|---|
| `uses` | `N ∩ U(D) ≠ ∅` | D's body/signatures select inherited members (`this.m`, `super.m`) |
| `overrides` | `N ∩ decls(D) ≠ ∅` | override conformance, bridges |
| `conflicts` | `N ∩ decls(Q) ≠ ∅` for some ancestor `Q` of `D` that is not on `P`'s side of the linearization | inherited-member reconciliation (§10a Edit 3) |
| `abstract` | `D` is concrete and some `n ∈ N` is deferred in `P`, old or new | must implement abstract members |
| `header` | `P`'s parents, type params, self type, modifiers, annotations or `<init>` changed | also keeps stored `lin(D)` fresh (decision 1) |
| `trait` | `P` is a trait | mixin forwarders in `D`'s bytecode for every concrete trait member |
| `mirror` | `D` is a top-level object with no companion class | the mirror class has static forwarders for *all* inherited members |
| `fallbacks` | existing `MemberRefInvalidator` cases (implicit, macro, annotation-defining file) | unchanged |

Presets: `all` (today: every descendant, for A/B inside one build), `default` (every rule above), and `none` (never recompile descendants: deliberately unsound, used only for ablation).

**Default = all rules** *(superseded: ablation reduced the default to five; see Status)*. This is sound for everything I can enumerate, and it still recompiles none of the hierarchy in §10a Edit 1, which is the common case: changing a concrete method's signature in a base class whose descendants neither override it nor use it.

Data needed: `abstract`, `overrides` and `conflicts` need per-class decl *names with modifiers*, and `NameHashing` can't provide them. Change `APIUtil.minimize` to keep decls as stubs (name, `DefinitionType`, modifiers; types dropped), and keep `selfType` for `header`. Compat is ignored, so this is free; it is also a small down payment on the §11 "thin discovery channel".

**Ablation is the test plan for the rules.** Every rule gets a scripted test that passes under `default` and fails (undercompiles, i.e. the incremental result differs from a clean build) with just that rule disabled. A rule with no failing test is either redundant or untested; we find out which. The `none` preset must fail every one of those tests.

**4. Bridge change is one line.** `inherited` = only members whose owner is *not* internal to this subproject, which is the §10 hybrid. Library and upstream-subproject ancestors stay materialised, so library/external invalidation stays as it is. "Internal" = the owner's source is in this run, or its classfile is under the output, which is the same test `ExtractDependencies` already uses. `HashAPI` (the class apiHash) and `NameHashing` then naturally become decls-only for internal ancestry.

## Where Lean helps (and where it doesn't)

The model in `retronym/talks/zinc-incrementality/lean` (`Hier.lean`, `NonLocal.lean`, `Stale.lean`) already settles decision 2 (`stale_unsound`: diffing over `R` alone undercompiles) and the round counts for Edits 1–3. The useful extensions are below; the first two are worth doing before or alongside step 4.

1. **Descendant refchecks as queries, so the rule set falls out of coverage.** Talk §10a already names this as future work: give `unitWalk`/`unitMat` a refchecks prelude in which a class issues `decl n` / `deferred n` queries to its ancestors for its own decls, for names inherited along ≥2 paths, and for abstract members. Those queries become keys like any client's, and T2′ then gives soundness of the filtered inheritance edge for free. The design payoff is a reframing: the rule table *is* the key abstraction of `D`'s refchecks trace, just as `U(d)` is for clients (§12). Then `uses`/`overrides`/`conflicts`/`abstract` stop being a list of heuristics; they become covers of specific query kinds. Missing one becomes a coverage failure the theorem catches.
2. **Flattened composition over stale `lin(C)`.** This is the one genuinely new soundness question in this plan (decision 1). Model `hashDeps` as reading the *stored* linearization and prove, or refute by `native_decide` scenario, that `header`-forces-descendants makes it sound. If it's refuted, fall back to recursive Merkle over direct parents before writing the Zinc code.
3. **Bounded exhaustive check of the presets.** Enumerate all edits in the toy space (a few classes, `{m, g}`, `{int, string, param}`, parent lists) and `decide` that `default` is always clean and `none` is not. Minimal counterexamples from ablating each rule are candidate scripted tests.

Where Lean doesn't help: *completeness of the rule table against real scalac*. The model only knows the observables we put into it. `mirror` (static forwarders) and `trait` (mixin forwarders) were found by thinking about bytecode, not by the model, and specialization and value classes are not modelled either. That is empirical, and the cheaper oracle is differential testing in Zinc itself. Add a Hedgehog generator of small hierarchies plus single edits that compares incremental output against a clean compile (bytes modulo known nondeterminism). This is §19's "weak oracle" fix and outlives the PoC. In short: Lean decides *whether the architecture is sound given a cover*; differential testing decides *whether our cover matches scalac*.

## Steps

1. DONE **Baseline scripted tests (no code change).** Add `source-dependencies/merkle-*` tests encoding the §10a program and its three edits, with `checkRecompilations` asserting *today's* sets (B C X Y / C X Y / C Y). Also add the counterexamples for decision 3: an override in a descendant, a conflict via a mixin, abstract → concrete. Commit on `1.x` too, so A and B share the tests.
2. DONE **Bridge: decls-only for internal ancestors** (decision 4). Run all of `scripted source-dependencies/*`; expect undercompilation failures. That list is the oracle that steps 3–4 have to bring back to green.
3. DONE, differently **Zinc: composed hashes + closure diff** (decisions 1–2) in `IncrementalCommon.detectAPIChanges` / `IncrementalNameHashing`. Add the composed hash to the invalidation log so "why did Y recompile" shows the ancestor.
4. DONE **Zinc: `DescendantRule` + presets + stub decls** (decision 3). Flip the step-1 expectations to the Merkle sets (X Y / X Y Z; Edit 3 still recompiles C). Add one ablation test per rule.
4b. **Differential Hedgehog test** (incremental vs clean on generated hierarchies × edits), run under `default` and `none` as a sanity check that it can find bugs.
5. DONE **Soundness sweep:** full `scripted` on B. Every expectation that changes must be a strict subset of A's and must be explained in the commit. Before trusting the failure list, cross-check the step-2 list against §16's taxonomy.

Correctness milestone = step 5 green, with a diff of recompilation sets vs A.

## Performance deliverable (standalone; useful even if Merkle dies)

The existing `zinc-benchmarks` measure clean compiles (Shapeless) and analysis serialisation. Nothing measures *incremental edit scenarios*. Build that:

- **Driver:** a `main` that takes a project, a list of edits (patch files or tiny AST-free text substitutions), and a zinc classpath; runs clean → edit → incremental → revert, N times. It records, per edit: rounds, classes recompiled, compile wall time, xsbt-api + callback time (async-profiler wall/alloc on the phase), analysis size on disk, and load time. Output is CSV/JSON, so A vs B is a diff of two runs from two worktrees.
- **Edit catalogue:** body-only change; result-type change of a widely inherited concrete method; member added to a base trait; asSeenFrom parent change; leaf change. Pick the targets automatically by ranking classes by inheritor count from an existing Analysis.
- **Corpora:** scala/scala `library` (collections hierarchy: the worst case for Σ|members|), plus one large app-shaped OSS project (e.g. Akka/Pekko or cats-core) on 2.13.
- This also answers the talk's TODOs: the inherited fraction of a real analysis, and xsbt-api phase cost.

## Future work

- Narrower `conflicts` (only ancestors that can actually reach `D`'s linearization alongside `P`'s `n`) and `trait` (only concrete members).
- Recursive Merkle over direct parents (drops reliance on stored linearization; needed if header changes stop forcing recompiles).
- Resolved-member hashing (`h(res_B(n))`) for asSeenFrom precision.
- Bridge-side hashing (§11) and a thin discovery channel; Scala 3; Java parents.
