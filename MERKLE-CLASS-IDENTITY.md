# Merkle: ancestors by class identity, not by rendered name

Design note, for review before code. Applies to the Merkle series (`claude/merkle-bridge-hash`, rz#24 + rz#88) and its 1.12.x line.

## Problem

Seven fixes in two days had the same cause. Merkle code compared one class's name in two encodings, both typed `String`:

| Commit | Compared | Effect |
|---|---|---|
| `165a9dbce` | ancestor's binary name `a.b.C$D` vs this run's classes, known only by source name | clean build took the output directory for a library; inherited hashes differed between clean and incremental builds (scala/scala: 1/6/16/246 sources for a one-member edit) |
| `bf0a34777` | `Discovery.simpleName` on `O.L` / `L[Int]` returns nothing | library ancestors nested in an object or taking type arguments missed; stale override compiled |
| `2dfbcbd51` | stored linearization `p.O.A` vs upstream APIs keyed `p.O$A`; `p.Inst.B` names no class | `abstract` rule never fired; downstream class no longer implementing `m` not recompiled |
| `8f5c1c4c1` | Scala 3 renders `p.O$.A` | all of the above, again, under Scala 3 |
| `768771b18` | regression from `8f5c1c4c1`: candidates lost the stored `p.L$.A` form | `165a9dbce` again; pekko 869 sources / 6 cycles instead of 156 / 3 |
| `fed760376` | `p.Inst.B` in cross-subproject composition | downstream class not recompiled |
| `fdafc0a4a` | change's `p.Q$A` vs stored `p.Q.A` / `p.Q$.A` in the `mirror` rule | stale static forwarder, `NoSuchMethodError` from Java |

Each fix added a heuristic in `LibraryAncestors`: `canonical` (`replace("$.", ".")`), `binaryNameCandidates` (try every `.`→`$` split, including ones that make a package a class), `sourceName` (replace every `$`, wrong for a class whose source name has one), plus fallbacks to the external inheritance relation where the rendered name is a path, not a class. One fix regressed an earlier one. The guessing does not converge, because it reconstructs information the bridge had and threw away.

There are two distinct mistakes:

1. **A rendered type where a class was meant.** The "stored linearization" (`MerkleHashes.linearization`) is `Structure.parents`, which the bridge fills with `info.baseClasses.tail.map(info.baseType)`, rendered as `xsbti.api.Type`. A `baseType` keeps its prefix, so a member class reached through an object comes out as `p.Inst.B`, a path. `parents` also carries a value class's underlying type (for name hashing's `value-class-underlying`), so today `linearization` counts `scala.Int` as an ancestor of `class Meter(val x: Int) extends AnyVal`.
2. **Two encodings of one identity in one type.** Even with the right class in hand, Merkle code mixes source names (keys of `apis.internal`, rendered linearization), binary names (`inheritance.external`, `productClassName`, jar entries, the change's class name for an upstream class) and Scala 3's third form. Every bug above is a `Map.get`, `Set.contains` or `==` across encodings.

## Proposal

**The bridge records each class's ancestors as binary names, from symbols.** For a class symbol `s`, `s.info.baseClasses.tail` mapped through the same binary-name function `binaryDependency` uses (`flatname` in Scala 2's bridge, its counterpart in Scala 3's). No rendering, no prefix: `Inst.B` gives `p.Outer$B`, `O.A` gives `p.O$A` on both compilers. `Any`/`AnyRef`/`AnyVal` have no classfile and map to `java.lang.Object` or are dropped. A value class's underlying type stays in `parents` for name hashing and is not an ancestor.

Java classes analysed by `ClassToAPI` get the same list from `Class.getSuperclass`/`getInterfaces` transitively, with `getName`, which is already the binary name.

**Zinc's Merkle code keys ancestors, changes and upstream lookups by binary name.** Where it needs an internal `AnalyzedClass` (keyed by source name, public API, unchanged), it goes through `productClassName`, which the early analysis already keeps under pipelining. In the current compile, `generatedNonLocalClass` gives the same mapping. Library lookups use the binary name to find the classfile. Changed classes from a jar diff are binary names already, so `sourceName` on jar entries goes away.

**Distinct types inside the Merkle code.** A `BinaryClassName` type (opaque or value class) for ancestors, change names and upstream keys. The point is lookups more than `==`: a `Map[BinaryClassName, AnalyzedClass]` or `Set[BinaryClassName]` won't accept a source-name `String`, so the class of bug above stops compiling. Conversions exist only at the boundary (`productClassName`, callback).

**Delete** `canonical`, `binaryNameCandidates`, `sourceName`, `MerkleHashes.typeName`'s projection walking, and the external-inheritance fallbacks in `HierarchyView`, `MerkleHashes.compose` and the `mirror` rule, if the scripted tests below stay green without them. Keep one backstop: a class found in this compile's own output is never a library (`768771b18`), since it's cheap and catches anything this note missed.

## Where it goes

`Structure.parents` can't change meaning: it feeds the API and name hashes, and name hashing must be untouched. So the ancestor list is a new field. Under bridge hashing it rides on `ClassHashes` (bridge → Zinc) and lands in a new `AnalyzedClass` field persisted by the text and consistent formats. Without bridge hashing, Zinc can't recover it from the tree for the reasons above, so either the bridge sends it on a separate callback in that mode too, or Merkle requires bridge hashing. I'd do the former: one more callback method on `AnalysisCallback5`, which is new in this series and not on develop.

The format change is additive, and Merkle already refuses an upstream analysis made in the other mode (the marker name hash), so there is no migration: an analysis without the field is a name-hashing one. The new field adds one `String[]` per class. Most of it is platform (`Object`, `Product`, `Serializable`, `Equals`); measure on scala/scala before deciding whether to drop the always-stubbed platform classes from the list.

Scala 3 needs the matching change in its `sbt-bridge` (on `integration/scala3-merkle`). Until it lands, a Scala 3 analysis has no ancestor list and Merkle can fall back to the current code or refuse; I'd refuse, behind the same mode check.

## Open questions

- **Order.** `baseClasses` order is the linearization, which `trait`'s "only for a trait its superclass's linearization lacks" and composition depend on. Storing it ordered keeps that. Java's order (superclass chain, then interfaces breadth-first) only matters for composition, where Java members don't conflict.
- **Pipelining.** `productClassName` in the early analysis must hold every nested class before its classfile exists. `768771b18` suggests it does for Scala 2 (the clean-build bug was about the *previous* analysis lacking the name, not this one). This needs a check with Scala 3 and Java.
- **The `exports` rule** matches by simple name (`stored.contains(simple(p))`) because a parent nested in a class is stored as a projection with only its id. That only over-compiles, but it goes away too once Scala 3's export inheritance edge can be compared by binary name.
- **"Recursive Merkle over direct parents"** (PLAN, future work) would also stop relying on the stored linearization. This proposal is independent and smaller: it fixes how ancestors are named, not which ones are composed.

## Lean

None needed for this. The Lean model already treats classes as atoms, which is why it couldn't see these bugs: they live in the refinement from atoms to strings, and the fix is to make the implementation keep the atom. The property worth stating in the model, if it isn't already, is that composed hashes depend only on sources and classpath, not on whether the previous build was clean or incremental. `165a9dbce` and `768771b18` broke exactly that, and today only the conformance harness and `IncBench --verify` check it.

## Tests that pin the behaviour

These must pass unchanged, on Scala 2 and (where marked) Scala 3:

- Nested in an object: `merkle-x-abstract-nested`, `merkle-x-abstract-ancestor-nested`, `merkle-x-trait-nested`, `merkle-x-member-type-nested`, `merkle-x-mirror-nested`, `merkle-mirror-nested`, `merkle-libjar-override-nested`.
- Reached through an object (`Inst.B`): `merkle-x-abstract-ancestor-instance`, `merkle-x-abstract-ancestor-instance-downstream`, `merkle-x-trait-instance`, `merkle-x-mirror-instance`.
- Type arguments: `merkle-libjar-override-parameterized`.
- Clean vs incremental stability: `merkle-nested-ancestor-stamp`, `merkle3-nested-ancestor-stable` (Scala 3), plus `LibraryAncestorsSpec`, which pins the candidates today and would be rewritten.
- Forwarders: `merkle-mirror`, `merkle-x-mirror`, `merkle-mirror-companion-added`.

New tests: a value class whose underlying type's library changes (it shouldn't count as an ancestor), a Java class nested in a Java class as a Scala ancestor, and a source-level class name containing `$`. Then the pekko and scala/scala IncBench replays from `165a9dbce` and `768771b18`, with `--verify`.
