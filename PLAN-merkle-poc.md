# Merkle name hashes: shortest path to a PoC

Context: talk §7–11 (Zinc Incrementality). Today `ExtractAPI.mkStructureWithInherited` materialises every inherited member into each class's `Structure`, so editing an ancestor changes every descendant's name hashes, and Zinc recompiles the whole hierarchy just to refresh them. The Merkle alternative stores decls only and composes a descendant's per-name hash from its ancestors', so an ancestor edit recompiles clients, not the hierarchy.

Ground rules: Scala 2.13 + `scala2-sbt-bridge` only, in this repo. No flags, no compat: this worktree *is* the B side; a sibling worktree on `1.x` is the A side. Java (`ClassToAPI`) and Scala 3 are out of scope.

## Key decisions

**1. Flattened composition over the stored linearization, done in Zinc at diff time.** The stub that `APIUtil.minimize` keeps already contains `structure.parents` = the linearized ancestor types as seen from the class (type args included). So for class `C` and name `n`:

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

`HierarchyView` is built once per round, from old and new Analysis: decl stubs per class, `lin(d)`, `U(d)` (`relations.names`), and header diffs. The policy is the disjunction of the enabled rules, chosen through `IncOptions.extra("zinc.descendantRules")`. Each hit goes to the invalidation log with its rule name ("C: conflict on m via M"), so both scripted tests and perf runs can explain every descendant recompile.

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

**Default = all rules.** This is sound for everything I can enumerate, and it still recompiles none of the hierarchy in §10a Edit 1, which is the common case: changing a concrete method's signature in a base class whose descendants neither override it nor use it.

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

1. **Baseline scripted tests (no code change).** Add `source-dependencies/merkle-*` tests encoding the §10a program and its three edits, with `checkRecompilations` asserting *today's* sets (B C X Y / C X Y / C Y). Also add the counterexamples for decision 3: an override in a descendant, a conflict via a mixin, abstract → concrete. Commit on `1.x` too, so A and B share the tests.
2. **Bridge: decls-only for internal ancestors** (decision 4). Run all of `scripted source-dependencies/*`; expect undercompilation failures. That list is the oracle that steps 3–4 have to bring back to green.
3. **Zinc: composed hashes + closure diff** (decisions 1–2) in `IncrementalCommon.detectAPIChanges` / `IncrementalNameHashing`. Add the composed hash to the invalidation log so "why did Y recompile" shows the ancestor.
4. **Zinc: `DescendantRule` + presets + stub decls** (decision 3). Flip the step-1 expectations to the Merkle sets (X Y / X Y Z; Edit 3 still recompiles C). Add one ablation test per rule.
4b. **Differential Hedgehog test** (incremental vs clean on generated hierarchies × edits), run under `default` and `none` as a sanity check that it can find bugs.
5. **Soundness sweep:** full `scripted` on B. Every expectation that changes must be a strict subset of A's and must be explained in the commit. Before trusting the failure list, cross-check the step-2 list against §16's taxonomy.

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
