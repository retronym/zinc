# Merkle hashing as an opt-in mode

## Problem

The Merkle PoC replaced name hashing outright. The IncBench campaign (scala/scala, cats, shapeless, scala-steward, pekko, all with `--verify`) shows it wins on API additions to widely inherited types and has a much cheaper bridge, and is neutral elsewhere; but it also turned up six Merkle-specific bugs. So for a transition period name hashing stays the default and Merkle is opt-in, in the same Zinc and the same bridge.

## Key decisions

1. **One switch, `incOptions.extra("hashing")` = `names` (default) | `merkle`**, read by `Hashing(options)`. Each Merkle-only behaviour asks it, or asks a switch that defers to it (`DescendantRules.fromOptions` is `None` and `LibraryAncestors` is `members` under `names`). `descendantRules`, `libraryAncestors`, `bridgeHashing` and `apiCheck` only mean something under `merkle`.
2. **The bridge is told, not guessed.** `AnalysisCallback5.merkleHashing()` (default `false`). Under `names` the bridge takes inherited members from `nonPrivateMembers` and sends the full tree through `api(source, classApi)`, as 1.12.x does; `apiMode()` is only consulted under `merkle`. A bridge without `AnalysisCallback5` behaves as under `names`. A Scala 3 bridge or an older Merkle bridge in a `merkle` build still materialises inherited members, as rz#48 already allowed.
3. **The mode is part of the compile setup.** Under `merkle`, `MiniSetup.extra` gets `zinc.hashing=merkle`; under `names` nothing is added, so an analysis written by plain 1.12.x stays valid. The existing setup comparison already covers `extra` (`MiniSetupUtil.equivPairs`), and a difference there deletes the products and compiles from scratch (`IncrementalCompilerImpl.prevAnalysis`).
4. **One mode per build, enforced at lookup.** The two modes store different APIs: a `merkle` analysis has decl-only classes that must be composed, a `names` one has materialised classes without the decl stubs the descendant rules read. Mixing them is unsound both ways. Under `merkle`, every analysed class carries a constant marker name hash (`<merkle>`, never used, so it never invalidates), and `Lookup.lookupAnalyzedClass` fails with a clear error when an upstream class was analysed in the other mode. A marker name hash avoids a change to the analysis formats; `PerClasspathEntryLookup` hands over only the `CompileAnalysis`, not its setup.

## Audit of shared code

Every change of rz#48 (769a5c7fe..238ef4921) outside the new Merkle-only files, classified as Merkle-only (guarded by the mode) or general (unconditional, and a candidate for its own upstream PR).

| Change | Where | Class | Under `names` |
|---|---|---|---|
| Inherited members: ancestors' declarations, without those of analysed ancestors, without override checks; library and platform members as stubs; only static annotations make an inherited member visible to discovery (#61) | `ExtractAPI.mkStructureWithInherited`, `ancestorDecls` | Merkle | `nonPrivateMembers`, as 1.12.x |
| Bridge-side hashing (`ClassHashes`, thin class, `HASHES`/`CHECK`), hashing class and companion (#56), extracted classes keyed by name (244ae6d), tparam naming (238ef49) | `API.scala`, `ExtractAPI` "Direct hashing", `AnalysisCallback5`, `ApiHashCheck` | Merkle | always `TREE` |
| Stored API keeps decl stubs, abstract inherited stubs and the self type | `APIUtil.minimizeWithStubs` (was `minimize`) | Merkle | `APIUtil.minimize`, as 1.12.x |
| Descendant rules filter which descendants recompile | `IncrementalNameHashing.filterDescendants`, `DescendantRules` | Merkle | every descendant |
| Local-inheritance and macro-expansion edges followed from every descendant, internal and external | `IncrementalNameHashing` | Merkle | from the changed class, as 1.12.x |
| Header change detected beyond `SameAPI`, and it reaches every member-ref client of every descendant | `IncrementalNameHashing.detectAPIChange`, invalidation | Merkle | off |
| Composition of upstream classes' hashes, and its per-analysis cache | `Lookup`, `MerkleHashes`, `MAnalysis.composedCache` | Merkle | the stored API |
| Coarse library ancestors: stubs, jar stamps folded into inherited names, descendants of a changed jar recompile | `LibraryAncestors`, `AnalysisCallback.libraryFingerprint`, `IncrementalCommon.libraryDescendants` | Merkle | `members` |
| A trait's extraHash covers its own members only (f837911) | `AnalysisCallback.companionsWithHash` | Merkle | `ExtraHashes` folds in trait parents' (sbt/zinc#662), restored |
| A local subclass's local-inheritance edge to its own enclosing trait is kept (33c5336) | `AnalysisCallback.internalClassDependency` | Merkle | dropped, as 1.12.x |
| `isSubprojectClass` | `AnalysisCallback4` | Merkle (read only by Merkle paths) | unused |
| An added top-level class can shadow a name: classes using its simple name that see its package are invalidated; the bridge records a package wildcard import or chained package clause as the used name `a.b._` | `IncrementalCommon.invalidateByAddedClasses`, `PackageScope`, `ExtractUsedNames` | general (#41, develop's #32/#34/#35) | on |
| Names and implicits added to a package object reach the classes that see the package; class/package-object clashes | `invalidateByPackageScope`, `PackageScope` | general (#41) | on |
| A name added to a class reaches same-source siblings that use it through a top-level import charged to another class | `MemberRefInvalidator.siblingsUsingAddedNames` | general (#41) | on |
| `ancestorChanges`: the before and after of each changed class, recorded in `detectAPIChange` | `IncrementalNameHashing` | general (feeds the three rows above) | on |
| Early output written only once a cycle is known to be the last | `AnalysisCallback.getCycleResultOnce` | general (#41's adaptation to 1.12.x) | on |
| With pipelining, Java compiled apart records dependencies on the subproject's own classes as internal; external change detection skips them | `AnalysisCallback.Builder.build(previous)`, `IncrementalCommon` | general (#55) | on |
| Upstream APIs processed by a cycle are recorded even for classes that did not recompile, as the no-change path already did | `Incremental.refreshExternalAPIs` | general | on |
| The erased signature of each member is part of its API | `ExtractAPI.withErasedSignature` | general (develop: `claude/erased-signature-witness`) | on |
| Traits and classes hash apart | `HashAPI` `TraitHash` | general (develop: `claude/kind-in-class-hash`) | on |
| Per-run `ExtractAPI` with shared caches; allocation-free `macroExpandee`; `LocalToNonLocalClass` cache; per-callback memo of upstream lookups | bridge, `AnalysisCallback.externalAPIs` | general (performance) | on |
| `PhaseTimings`, `InvalidationLog` sections | zinc-core | general (instrumentation) | on |

The two `TraitHash` and erased-signature changes move hash values, so the first incremental compile after upgrading from plain 1.12.x sees API changes in the recompiled classes.

## Tests

- Scripted: `IncHandler` reads `-Dzinc.scripted.hashing=names|merkle` for every project whose `incOptions.properties` doesn't set `hashing`. All `merkle-*` tests set `hashing = merkle` in each project.
- Four pre-existing tests that rz#48 had rewritten to Merkle's expectations (`class-based-inheritance`, `local-class-inheritance`, `transitive-class`, `transitive-memberRef`) are back to 1.12.x's, with `merkle-*` copies keeping rz#48's. `module-inheritance-extra-hash-213-bin` is pending again, as on 1.12.x; `merkle-module-inheritance-extra-hash-213-bin` is rz#48's version. `implicit-scope-grandparent-companion` passes only under Merkle (composition folds in ancestors' implicits): it is pending, with `merkle-implicit-scope-grandparent-companion` the passing copy.
- Bridge unit tests: `TestCallback(merkle)` implements `AnalysisCallback5`; `ExtractAPISpecification` is 1.12.x's again and `MerkleExtractAPISpecification` holds rz#48's Merkle versions.

## Results

`scripted source-dependencies/*` (pending tests not run):

| Zinc | pass | fail |
|---|---|---|
| `integration/develop-1.12.x` | 148 | 0 |
| `integration/merkle-1.12.x` | 257 | 0 |
| rz#48 (238ef49) | 253 | 2: `merkle-refinement-projection-annotated(-213)`, which need rz#80 |
| this, `names` | 256 | the same 2, and `implicit-scope-grandparent-companion` (now pending) |
| this, `merkle` everywhere | 254 | the same 2, and the four 1.12.x tests above, whose `merkle-*` copies pass |

IncBench on cats (frozen build, `cats.tsv` edits, `--verify`, the campaign's inc options), four cells in one lockstep run: `integration/develop-1.12.x`, `integration/merkle-1.12.x`, and this branch merged into `integration/merkle-1.12.x` once under `names` and once with `hashing=merkle`. On all 22 steps each mode matches its branch exactly, in sources recompiled per module, cycles, and the verify verdict and diff. For example `functor-add-member`: 705 (names) and 630 (merkle) sources; `eq-add-member`: 523 and 515.

## Follow-ups

- Split the general rows out as upstream PRs (the #41 group, #55, `refreshExternalAPIs`, and the two hash fixes that already have develop branches).
- Bridge hashing is Merkle-only here. Under `names` it would have to hash materialised members; not worth it if Merkle becomes the default.
- On the develop line (rz#24) the same switch, plus the Scala 3 bridge's `merkleHashing()`.
