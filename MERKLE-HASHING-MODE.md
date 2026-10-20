# Merkle hashing as an opt-in mode

## Problem

The Merkle PoC (rz#24, on `develop`) replaced name hashing outright. The IncBench campaign (scala/scala, cats, shapeless, scala-steward, pekko, all with `--verify`) shows it wins on API additions to widely inherited types and has a much cheaper bridge, and is neutral elsewhere; but it also turned up six Merkle-specific bugs. So for a transition period name hashing stays the default and Merkle is opt-in, in the same Zinc and the same bridge.

## Key decisions

1. **One switch, `incOptions.extra("hashing")` = `names` (default) | `merkle`**, read by `Hashing(options)`. Each Merkle-only behaviour asks it, or asks a switch that defers to it (`DescendantRules.fromOptions` is `None` and `LibraryAncestors` is `members` under `names`). `descendantRules`, `libraryAncestors`, `bridgeHashing` and `apiCheck` only mean something under `merkle`.
2. **The bridge is told, not guessed.** `AnalysisCallback5.merkleHashing()` (default `false`). Under `names` the bridge takes every inherited member from `nonPrivateMembers` and sends the full tree through `api(source, classApi)`, as `develop` does; `apiMode()` is only consulted under `merkle`. A bridge without `AnalysisCallback5` behaves as under `names`. The Scala 3 compiler's own extraction does not ask yet: the Merkle one (retronym/scala3 `integration/scala3-merkle`) leaves out members inherited from analysed classes whatever the mode, which is unsound under `names`, so its `ExtractAPI` must consult `merkleHashing()` too. A released Scala 3 materialises every inherited member, which a `merkle` build tolerates, as rz#24 already did.
3. **The mode is part of the compile setup.** Under `merkle`, `MiniSetup.extra` gets `zinc.hashing=merkle`; under `names` nothing is added, so an analysis written by plain `develop` stays valid. The existing setup comparison already covers `extra` (`MiniSetupUtil.equivPairs`), and a difference there deletes the products and compiles from scratch (`IncrementalCompilerImpl.prevAnalysis`).
4. **One mode per build, enforced at lookup.** The two modes store different APIs: a `merkle` analysis has decl-only classes that must be composed, a `names` one has materialised classes without the decl stubs the descendant rules read. Mixing them is unsound both ways. Under `merkle`, every analysed class carries a constant marker name hash (`<merkle>`, never used, so it never invalidates), and `Lookup.lookupAnalyzedClass` fails with a clear error when an upstream class was analysed in the other mode. A marker name hash avoids a change to the analysis formats; `PerClasspathEntryLookup` hands over only the `CompileAnalysis`, not its setup.

## Audit of shared code

Every change of rz#24 (e65e35a8d..6df63a4cb) outside the new Merkle-only files, classified as Merkle-only (guarded by the mode) or general (unconditional, and a candidate for its own upstream PR).

| Change | Where | Class | Under `names` |
|---|---|---|---|
| Inherited members: without those of analysed ancestors; library and platform members as stubs, plus overridden library declarations; only static annotations make an inherited member visible to discovery (#61) | `ExtractAPI.mkStructureWithInherited` | Merkle | all of `nonPrivateMembers`, as `develop` |
| Bridge-side hashing (`ClassHashes`, thin class, `HASHES`/`CHECK`), hashing class and companion (#56), tparam naming (6df63a4) | `API.scala`, `ExtractAPI` "Direct hashing", `AnalysisCallback5`, `ApiHashCheck` | Merkle | always `TREE` |
| Stored API keeps decl stubs, abstract inherited stubs and the self type | `APIUtil.minimizeWithStubs` (was `minimize`) | Merkle | `APIUtil.minimize`, as `develop` |
| Descendant rules filter which descendants recompile | `IncrementalNameHashing.filterDescendants`, `DescendantRules` | Merkle | every descendant |
| Local-inheritance and macro-expansion edges followed from every descendant, internal and external | `IncrementalNameHashing` | Merkle | from the changed class, as `develop` |
| Header change detected beyond `SameAPI`, and it reaches every member-ref client of every descendant | `IncrementalNameHashing.detectAPIChange`, invalidation | Merkle | off |
| Composition of upstream classes' hashes, and its per-analysis cache | `Lookup`, `MerkleHashes`, `MAnalysis.composedCache` | Merkle | the stored API |
| Coarse library ancestors: stubs, jar stamps folded into inherited names, descendants of a changed jar recompile | `LibraryAncestors`, `AnalysisCallback.libraryFingerprint`, `IncrementalCommon.libraryDescendants` | Merkle | `members` |
| A trait's extraHash covers its own members only (2efaab4) | `AnalysisCallback.companionsWithHash` | Merkle | `ExtraHashes` folds in trait parents' (sbt/zinc#662), restored |
| A local subclass's local-inheritance edge to its own enclosing trait is kept (39a9dd8) | `AnalysisCallback.internalClassDependency` | Merkle | dropped, as `develop` |
| `isSubprojectClass` | `AnalysisCallback4` | Merkle (read only by Merkle paths) | unused |
| An added top-level class can shadow a name: classes using its simple name that see its package are invalidated; the bridge records a package wildcard import or chained package clause as the used name `a.b._` | `IncrementalCommon.invalidateByAddedClasses`, `PackageScope`, `ExtractUsedNames` | general (rz#32/#34/#35/#47) | on |
| Names and implicits added to a package object reach the classes that see the package; class/package-object clashes | `invalidateByPackageScope`, `PackageScope` | general (rz#47) | on |
| A name added to a class reaches same-source siblings that use it through a top-level import charged to another class | `MemberRefInvalidator.siblingsUsingAddedNames` | general (rz#47) | on |
| `ancestorChanges`: the before and after of each changed class, recorded in `detectAPIChange` | `IncrementalNameHashing` | general (feeds the three rows above) | on |
| Early output written only once a cycle is known to be the last | `AnalysisCallback` | general (fae4ce7) | on |
| With pipelining, Java compiled apart records dependencies on the subproject's own classes as internal; external change detection skips them | `AnalysisCallback.Builder.build(previous)`, `IncrementalCommon` | general (#55) | on |
| Upstream APIs processed by a cycle are recorded even for classes that did not recompile, as the no-change path already did | `Incremental.refreshExternalAPIs` | general | on |
| The erased signature of each member is part of its API | `ExtractAPI.withErasedSignature` | general (rz#28) | on |
| Traits and classes hash apart | `HashAPI` `TraitHash` | general (rz#27) | on |
| Per-run `ExtractAPI` with shared caches; allocation-free `macroExpandee`; `LocalToNonLocalClass` cache; per-callback memo of upstream lookups | bridge, `AnalysisCallback.externalAPIs` | general (performance) | on |
| `PhaseTimings`, `InvalidationLog` sections | zinc-core | general (instrumentation) | on |

The two `TraitHash` and erased-signature changes move hash values, so the first incremental compile after upgrading from plain `develop` sees API changes in the recompiled classes.

## Tests

- Scripted: `IncHandler` reads `-Dzinc.scripted.hashing=names|merkle` for every project whose `incOptions.properties` doesn't set `hashing`. All `merkle-*` tests set `hashing = merkle` in each project.
- Four pre-existing tests that rz#24 had rewritten to Merkle's expectations (`class-based-inheritance`, `local-class-inheritance`, `transitive-class`, `transitive-memberRef`) are back to `develop`'s, with `merkle-*` copies keeping rz#24's. `module-inheritance-extra-hash-213-bin` is pending again, as on `develop`; `merkle-module-inheritance-extra-hash-213-bin` is rz#24's version. The `merkle3-*` tests set `hashing = merkle` too. `implicit-scope-grandparent-companion` passes only under Merkle (composition folds in ancestors' implicits): it is pending, with `merkle-implicit-scope-grandparent-companion` the passing copy.
- Bridge unit tests: `TestCallback(merkle)` implements `AnalysisCallback5`; `ExtractAPISpecification` is `develop`'s again and `MerkleExtractAPISpecification` holds rz#24's Merkle versions.

## Results

`scripted source-dependencies/*`, failures that are not pending tests (published Scala 3, no `zinc.scala3ForBridge`):

| Zinc | pass | fail |
|---|---|---|
| rz#24 (6df63a4) | 263 | 2: `merkle-refinement-projection-annotated(-213)`, which need the alias-tparam fix |
| this, `names` | 267 | the same 2 |
| this, `merkle` everywhere | 265 | the same 2, and the four `develop` tests above, whose `merkle-*` copies pass |

IncBench on cats (the develop-line IncBench has no `--verify`; recompilation is deterministic, so each cell ran on its own): `integration/develop`, `integration/merkle`, and this branch merged into `integration/merkle` once under `names` and once with `hashing=merkle`. On all 58 steps each mode matches its branch exactly in sources recompiled per module and cycles: `functor-add-member` 705 vs 630, `eq-add-member` 523 vs 515.

Merging into `integration/merkle` conflicts with sz#1846 in `Incremental.scala`: its `InheritedImplicitScopes` replaces the `ExtraHashes` fold that this branch restores for `names`. The bench build kept sz#1846's side and only the mode guards.

## Follow-ups

- The Scala 3 compiler's `ExtractAPI` (retronym/scala3 `integration/scala3-merkle`) must consult `merkleHashing()`.
- Split the general rows out as upstream PRs where they are not already separate PRs.
