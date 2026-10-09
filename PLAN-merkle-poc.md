# Merkle name hashes: shortest path to a PoC

Context: talk §7–11 (Zinc Incrementality). Today `ExtractAPI.mkStructureWithInherited` materialises every inherited member into each class's `Structure`, so editing an ancestor changes every descendant's name hashes, and Zinc recompiles the whole hierarchy just to refresh them. The Merkle alternative stores decls only and composes a descendant's per-name hash from its ancestors', so an ancestor edit recompiles clients, not the hierarchy.

Ground rules: Scala 2 with the in-repo compiler bridge (scripted's default `2.12.x` label). No compat: this worktree *is* the B side, based on sbt/zinc `develop`; the A side is `claude/merkle-baseline` (`develop` plus the IncBench commits only, worktree `merkle-baseline`). The `descendantRules` incOption also gives an in-build A/B (`all` = previous behaviour). Java (`ClassToAPI`) and Scala 3 are out of scope.

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

`zincScripted/Test/runMain sbt.internal.inc.bench.IncBench` generates an inheritance tree spread across modules, applies each edit, and records per module the rounds, classes recompiled, wall time and stored size; `bin/incbench-report.py` renders an HTML comparison (published: https://claude.ai/artifact/7i7oZAGogR2efBfJWMH3ko). A = sbt/zinc `develop` plus the harness commits (`claude/merkle-baseline`), B = this branch. Timed with `apiDebug` off; extraction counted in a separate untimed pass. The tree: 4 modules, depth 8, fan-out 2, 511 classes with 30 methods each, a client of every class and of every leaf (1,279 compiled in all); medians of 3.

| edit | A recompiled | B recompiled | rounds A→B | wall A→B |
|---|---|---|---|---|
| root: add member | 511 | 1 | 5→1 | 22.1 s→0.60 s |
| root: add overload of used member | 1,278 | 768 (just the clients) | 5→5 | 23.5 s→2.1 s |
| mid-level: add member | 31 | 1 | 3→1 | 2.2 s→0.47 s |
| root: body only | 1 | 1 | 1→1 | 0.49 s→0.46 s |
| warm clean build (reference) | 1,279 | 1,279 | 4→4 | 18.0 s→17.1 s |

On `develop`, adding a member nobody uses to the root takes longer than a clean build. With a trait root, B recompiles 7 classes in 2 rounds (0.80 s), A the same 511 in 5.

Stored after a clean build: inherited definitions extracted 132,901→24,301 (−82%; the rest are `Any`/`AnyRef` members), name hashes 148,233→39,633 (−73%), analysis on disk 1.22 MB→0.73 MB (−40%, including the new decl stubs).

The first runs' wall times (with `apiDebug` on) are void: it diffed and logged every changed API, which cost more than the compile and grew with API size.

Surveys (source-level, approximate, in the scratchpad's survey.py): Pekko's cross-module inheritance is mostly traits (346 cross-module descendant edges vs 83 from classes). Spark has deep class hierarchies (`TreeNode` 261 descendants, `Expression` 257), mostly inside `sql/catalyst`; its cross-module reach is dominated by traits (`Logging`, 694 descendants across 23 modules).

- **Decision (2026-10-09): macros that observe more than the public API are outside Zinc's contract**, possibly to be revisited. A macro may observe any type, not only its type arguments, so the macro-expansion dependency on type arguments is a heuristic for the common derivation shape, not a cover. The conformance harness's `macro-observes-private-member` (a macro listing a class's private members) is kept as a pending test on `claude/merkle-baseline-bugs`. Options considered: a private-member hash per class, triggering macro clients only; invalidating macro clients on any bytecode change of an observed class; recording what the macro reads (sbt/zinc#1478).

## Benchmark (IncBench, Spark 4.0.1 `sql/catalyst`)

2,527 classes in one module, dependencies from published jars (`--build`/`--edits` mode), pipelining off, one repetition. Report: https://claude.ai/artifact/5zosHgbDvNpZ2xkX7psq16.

| edit | A: recompiled, rounds, wall | B: recompiled, rounds, wall |
|---|---|---|
| `TreeNode`: body only | 10, 1, 0.78 s | 10, 1, 0.74 s |
| `TreeNode`: add an unused member | 1,371, 3, 14.4 s | 420, 2, 4.5 s |
| `Expression`: add an unused member | 1,200, 3, 10.7 s | 388, 2, 3.4 s |
| `TreeNode`: add `java.io.Serializable` to its parents | 2,304, 4, 29.0 s | 1,987, 3, 22.7 s |
| `Expression`: add `java.io.Serializable` to its parents | 1,897, 3, 18.1 s | 1,897, 2, 10.4 s |
| clean build, warm | 2,527, 1, 11.4 s | 2,527, 1, 11.1 s |

Stored: name hashes 212,389 → 90,711 before the library stubs, 94,046 after; analysis 1.88 MB → 1.43 MB (1.45 MB after).

The corpus found three overcompilations the scripted suite had not, each recompiling most of catalyst: header comparison by `equals` on types with lazy parts (now by `HashAPI`), a `mirror` rule that fired for every case class's companion (now only objects that themselves extend the changed class), and a trait's `extraHash` folding in its *class* parents' (now trait parents only; this predates the PoC). With pipelining on, every Java source's API also looked fully changed on each compile, so catalyst runs use `pipelining=false`.

**Extraction cost** (scalac `-Yprofile-enabled` via `--scalac-option`, catalyst warm clean builds, mean of 2): `xsbt-api` 0.85 s and 1,093 MB allocated on develop, 0.75 s and 704 MB on the PoC (−12% time, −36% allocation). For scale, typer allocates 1,250 MB and all phases take 10.1 s; Zinc's work outside the compiler is about 2 s on both sides.

## Holes found by review (2026-10-09)

- DONE **Test discovery.** sbt's annotated-test discovery reads inherited methods' annotations (`savedAnnotations`); a JUnit `@Test` in a base class was lost. The bridge keeps annotated inherited members; the `annotated` rule refreshes descendants (`merkle-discovery`, `merkle-discovery-added`).
- DONE **Library ancestors seen only through materialised members.** A library class has no stored API, and an overridden library member was not in the descendant's either, so `abstract` and `conflicts` were blind to it (`merkle-lib-abstract`, `merkle-lib-abstract-other-path`, `merkle-lib-conflict`). The bridge stubs overridden library declarations and platform members (`Any`, `AnyRef`, scala-library, scala-reflect); the minimized API keeps abstract inherited stubs. This adds name hashes rather than removing them: minimization already drops inherited definitions, and the stored record of inherited names is the name hashes.
- DONE **Tests ran on full APIs.** Scripted defaults to `apiDebug`; Zinc stores minimized APIs. The `merkle-*` tests now set `apiDebug = false`, and so does the conformance harness.
- DONE **Header edits.** Measured above: not worse than develop, but expensive on both sides, because the class-name hash moves for every client naming the class.
- **Library members are still materialised for third-party jars.** Only they let Java and macro clients, which record no owner, see a library ancestor's change through a descendant. The consistent fix is a per-class ABI hash for library classes, composed like an analysed ancestor's.
- **Rule completeness against scalac** is tested empirically only: specialization, optimizer inlining across classes (`-opt:inline`), value classes over universal traits, and Scala 3 (`export` forwarders, `inline`, trait parameters) are unprobed.
- **Invalidation cost** of the rules per descendant is unmeasured, as is bridge extraction time.

TODO:

- Measure Zinc's invalidation time on incremental edits (per-step phase profiles).
- Name-filter the header path by the names whose as-seen-from rendering changed.
- Per-class ABI hashing of library classes.
- Cross-module real corpus (catalyst + sql/core).
- Known gaps: `ExternalLookup` fast-track (sbt's own hook) bypasses `MerkleHashes`; header detection relies on `bytecodeHash` differing when `HashAPI` doesn't see the change; the bridge cache in `~/.ivy2/local` is shared between worktrees, so runs take turns.

## Phase 2 (design, for review): hash in the bridge

Goal: one performance package, Merkle plus in-bridge hashing, measured as A = develop, B = Merkle, C = Merkle + bridge hashing, on catalyst and the cross-module Spark build.

**Problem.** For every compiled class, `xsbt-api` builds a full `xsbti.api` tree: every declared member with its types, plus stubs and some inherited members now. Zinc then hashes it (`HashAPI`, `NameHashing`, `ExtraHashes`) and minimizes it, discarding most of the tree. On catalyst that tree is the phase's 704 MB of allocation, about as much as typer's 1,250 MB. Zinc's own work outside the compiler is about 2 s of a 12 s clean build, part of which is that hashing. The tree is a general reflection of the type system, built so it can be hashed and thrown away (talk §11).

**Key decisions.**

1. **The bridge sends what Zinc keeps, plus the hashes.** Per class: the thin `ClassLike` that `APIUtil.minimize` produces today (header, parents, declaration stubs, abstract inherited stubs, mains, annotated members) and a `ClassHashes` value (apiHash, extraHash, name hashes by `UseScope`, hasMacro, isAnnotationDefinition). The descendant rules, test and main discovery, and `MerkleHashes` read only the thin part, so they keep working unchanged.
2. **A new callback, with a fallback.** `AnalysisCallback5.api(source, thinClass, hashes)`. Zinc keeps computing hashes from the tree for older bridges and for Java (`ClassToAPI`). Under `apiDebug` the bridge sends the full tree as today, so `APIDiff` still explains changes.
3. **The hash function lives in the bridge, over compiler types.** It's a type hasher that mirrors `ExtractAPI.makeType` case by case: refinement unrolling, existential renaming, refinement-relative names, value-class erasure. It feeds a hasher instead of allocating `xsbti.api.Type`, and is memoised per (owner, type) like `typeCache`. A parent's per-name hashes are computed once per run and reused by every subclass, which is also the memoised Merkle composition of §10.
4. **Equivalence is checked, not assumed.** In a transition mode the bridge computes both: the tree, hashed the old way, and the direct hash. Wherever the two disagree on *equality*, i.e. whether two versions of a class hash the same, that's a bug. Hash *values* may differ from today's, which costs one full recompile on upgrade. The conformance harness and the scripted suite run in this mode.
5. **The name-hash contract gets written down,** because Zinc can no longer change it unilaterally: the `UseScope` partition, sealed children (`useOptimizedSealed`), private-member rules, and trait breakers for extraHash. That spec doubles as documentation of §12's key space.

**Phasing,** each step measured A/B/C:

- **H1:** keep the tree, move only the hashing into the bridge (over `xsbti.api`, a port of `HashAPI`/`NameHashing` into compiler-interface or the bridge), and send the thin class plus hashes. This proves the callback, the fallback and equivalence. It saves Zinc's hashing time and the minimize step, but not bridge allocation.
- **H2:** hash directly from compiler types, and stop building the tree except for the thin parts. This is the allocation win, and the bulk of the work.
- **H3:** memoise per symbol across classes in a run, so ancestors' per-name hashes are shared. This one is Merkle-specific.

**Costs and risks.**
- Hashing code then exists in two bridges plus Zinc's Java path.
- A hashing bug needs a bridge release.
- Hash stability between typing from source and unpickling must be preserved. The existing `ExtractAPI` special cases exist for exactly this, and the type hasher must mirror them.
- Tools reading `Analysis.apis` see the same thin classes as today's minimized ones.

**H2 status (2026-10-09).** Built on `claude/merkle-bridge-hashing`:

- `AnalysisCallback5` (`apiMode`, `useOptimizedSealed`, `api(source, thinClass, ClassHashes)`, `apiCheck(source, full, thin, hashes)`) and `ClassHashes` (apiHash, extraHash before Zinc folds in trait parents, name hashes, hasMacro). `isAnnotationDefinition` stays in Zinc: the thin class keeps the parents it reads.
- Zinc picks the mode: `TREE` under `apiDebug` or `bridgeHashing=false` (incOption), `CHECK` under `apiCheck=true` (incOption or `-Dxsbt.api.check=true`), else `HASHES`. Java (`ClassToAPI`) and older bridges keep the tree path.
- The bridge builds the thin class directly (no full structure), and hashes from symbols and types (`ExtractAPI`, "Direct hashing").
- `ApiHashCheck`: under `CHECK`, per class, Zinc hashes the tree too and reports (`[api-check]` warnings, and the `apiCheckReport` file) where the two disagree on whether the class's apiHash, extraHash or any name hash changed since the previous compile of that class in the JVM; and, within one compile, any difference in name-hash keys, `hasMacro`, or the thin class vs `APIUtil.minimize(full)`.

**H2 results (catalyst, 2026-10-09; times unreliable, machine loaded).**

- Equivalence, check mode, 0 disagreements everywhere: smoke scripted (76 tests; 541 classes, 231 cross-version comparisons); catalyst member edits (10,200 classes, 6,390 comparisons); catalyst header edits (23,673 classes, 19,797 comparisons). The checker catches a broken hasher: dropping member signatures gives 6 disagreements and two undercompiling tests.
- Recompiled sets are identical to the PoC's on every catalyst edit (10 / 420 / 388; header 1,987 and 1,897).
- `xsbt-api` allocation, warm clean build, mean of 2: 737 MB on the tree path (PoC), 512 MB with H2 (-31%). The first H2 version allocated 800 MB; the cuts were not building the erased-signature string, caching method names (one regex per name), stubs, modifiers and printed annotation arguments, two-level memo maps without tuple keys, and saved annotations read from symbols. Of what remains, about two thirds is the bridge's own work (mostly selecting inherited members and erasing for the witness), a sixth `ExtractUsedNames`, a tenth `registerGeneratedClasses`.
- Per-run caches (2026-10-10): one `ExtractAPI` per run instead of per unit. Hashes keyed by a symbol alone, conversions and hashes of plain types (no alias, refinement, raw type, annotation or binder, so independent of the owner), and refinement-owned entries are shared by all units; anything hashed inside an existential stays per unit, as the tree path's `typeCache` does. Library base classes' declarations are cached per run for `overriddenLibraryDecls`. `xsbt-api` allocation, warm clean build: 512–524 MB before, 376–381 MB after (a third run gave 440 on a loaded machine; run-to-run noise is ±30 MB, as on the untouched `xsbt-dependency`). Check mode still 0 disagreements (smoke scripted 76 tests; catalyst member edits 10,200 classes, 6,402 comparisons), recompiled sets unchanged (10 / 420 / 388).
- Floor (measured, not committed): skipping inherited-member selection entirely (`nonPrivateMembers`, overridden library decls, stubs) gives 380–384 MB, so that selection now costs nothing measurable. What remains is mostly `ExtractUsedNames` (JFR: `processMacroExpansion` allocates a `BooleanRef` and a closure per tree node; `resolveNonLocal` a closure per lookup), then the per-class name-hash groups, `DefH`s and the thin class.
- Zinc's `HashAPI`/`NameHashing`/`minimize` no longer run on the H2 path (0.39 s on catalyst in the PoC measurement).
- Thin stubs drop the erased-signature witness, and so does `APIUtil.minimize`: it only feeds the hash.

**Name-hash contract (H2).** What the bridge hashes, so that Zinc and the bridge agree on it. Values are not stable across Zinc versions; only equality between two versions of a class is.

- *apiHash* covers the class's type parameters, self type, sealed descendants (including itself), whether it is a trait, its linearized parents as seen from it (plus a value class's underlying type), and every non-private declared and inherited member. Inherited members are those `ExtractAPI` materialises: from library ancestors in full, platform and overridden-library members as typeless stubs. Non-private means not `private` or `private[this]`; `private[pkg]` counts. A top-level class's own name, access, modifiers and annotations are not in it.
- A *member* hashes its API name, static annotations (plus the erased-signature witness for vals, vars and defs), modifiers, access, and its signature as seen from the class: type parameters, value parameter lists (names, types, repeated/by-name, defaults, implicitness), result type; a type member's bounds or alias; a nested class's type parameters only.
- *Types* hash as `makeType` would build them: aliases dealiased, references to refinement classes unrolled once (recursive references dropped), existential variables renamed by nesting position, type parameters named relative to the outermost refinement (sbt/sbt#1079), raw Java types as existentials, constant types with their value, non-static annotations dropped.
- *extraHash*: for a trait, apiHash plus its private fields, objects and super accessors (trait breakers); otherwise apiHash. Zinc folds in trait parents' extraHashes.
- *Name hashes* are keyed by simple name (after the last `.`) and `UseScope`. Entries: the class itself under its simple name (its header and parents, without members); every non-private member; and the non-private members of every refinement reachable from those, recursively. Implicit members go to `Implicit`, others to `Default`. With `useOptimizedSealed`, sealed descendants are hashed only into a `PatMatTarget` entry for a sealed class; otherwise into its `Default` entry. Each entry is salted with the class's name and namespace.
- *hasMacro*: the class or any declared member (private too) is a macro, or `@inline` under the optimizer.

**Out of scope for the PoC:** the Scala 3 bridge, which has its own ExtractAPI; `scala2-sbt-bridge` in scala/scala; Java.

**Measurement:**
- `xsbt-api` time and allocation, from scalac's `-Yprofile-enabled`;
- Zinc time outside the compiler;
- analysis size, and load and save time;
- IncBench edit timings on catalyst, the cross-module build and the synthetic tree.

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
