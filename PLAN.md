# Integration: classfile Java API (sbt/zinc PR 1730) + Java used names (retronym/zinc PR 43)

Branch `claude/integ-1730-java-names`, based on the head of sbt/zinc PR 1730 (`00f8e3ced`, on develop `498dced5d`).

## Why

PR 1730 is the definition side of Java incremental compilation: it builds a Java class's API from its classfile, so analysis never loads the class. PR 43 is the use side: it records the names a Java class looks up and edges for its imports, which a classfile cannot show. Together they make it possible to filter Java dependents by name hash, as Scala ones already are. Until now Zinc invalidated every member-ref dependent of a changed Java class, because Java classes had no used names.

## Contents

### 1. PR 1730 as submitted — DONE

### 2. Improvements to PR 1730, standalone — DONE

- Precursor, a bug that predates both PRs: reflection recorded no inheritance edge to a parameterized supertype (`W extends V<String>`). A change to `V` reached a dependent of `W` only if the dependent's bytecode named `W` (and so `V`, via sbt/zinc#148). `java-constant-import-generic-super` covers a client linked to `W` only by an inlined constant in an annotation value (sbt/zinc#145); a new `V.Foo` makes `Foo` ambiguous under `import static a.W.*`. The test fails on PR 1730's reflection path without the fix. Classfile mode already records the edge from the erased names.
- Every inheritance edge under `classfileApiOnly`. The PR recorded only direct supertypes, and no local inheritance for anonymous/local classes; reflection records every ancestor. `ClassfileToAPISpecification` "record the same inheritance dependencies…" fails on the PR as submitted with exactly those missing edges.
- One memoised parent resolver. `ClassfileToAPI` now uses `JavaAnalyze`'s resolver instead of a second one that re-parsed parents per source and fell back to sbt's own classpath.
- Member classes inherited from a parameterized supertype are listed, because Java inherits them. The PR copied reflection's walk, which drops them (`Enum.EnumDesc`, `Outer<String>.Inner.Member`).
- `sealed`/`permits` is modelled as the sealed type's children, on both paths. Pre-existing in reflection mode: `java-sealed-permits-added` fails on develop's reflection path.
- JFR test that classfileJavaApi loads none of the compiled classes (`IncrementalCompilerSpec`). The reflection run checks that the probe sees loads.

### 3. PR 43 (on PR 34) — DONE

Merged; `JavaUsedNamesSpec` ported to Hedgehog.

### 4. From the combination — DONE

- PR 43's static-import edge relies on the precursor fix: `java-on-demand-import-ambiguity-generic-super` is the same ambiguity reached through the import edge alone.
- Local and anonymous classes' used names (keyed `D$1` by javac) land on the enclosing class in both modes. Checked; no mismatch.
- Experiment, `-Dzinc.experimental.javaNameHashing=true`: name hashing for Java dependents.
  - Use side: qualified selections, method references, and constant-pool member names, including implicit calls and constructors named `pkg;C;init;` as `ClassToAPI` names them.
  - Filtering: Java classes that have used names are filtered; forked-javac classes, which have none, stay unfiltered.
  - Result: filtering works, i.e. an unrelated added method no longer recompiles a Java client. The full `source-dependencies` suite passes in both API modes with it on. Probes for class-level changes, annotation-default removal and overload changes via an argument's supertype all invalidate correctly; they are kept as scripted tests.

## Verification

- Unit: zincApiInfo, zincClassfile, zincCompileCore, zincCore and zinc tests.
- Scripted `source-dependencies/*` in four runs: reflection; classfile with strict; experiment with reflection; experiment with classfile. To switch modes in one sbt server, use `eval System.setProperty("zinc.scripted.classfileJavaApi","true")`. Clear it afterwards, because a failed command leaves it set.

## Future work

- Promote the experiment to an `IncOptions` field. Before that, add adversarial cases: constants reached only through `import static`, annotation processors that read CLASS-retention annotations, and lambdas over project functional interfaces.
- A real javac `-Xplugin` that writes the tree facts (constant deps, import edges, used names) to a sidecar file, so forked javac gets them too. Today only the in-process `LocalJavaCompiler` runs the listeners.
- Reflection's `ClassToAPI` still drops member classes inherited from a parameterized supertype. Fixing it changes every enum's hash (one-time recompile). Decide whether reflection mode stays at all once classfile mode is the default.
- Reflection records no inheritance edges for private classes (`!Modifier.isPrivate`); the classfile path's behaviour for private nested classes is untested.
- Differential test over a large corpus (java.base, Guava) comparing the two API paths and their dependency relations.
- JFR class-load check over the whole scripted suite in classfile mode, not just one unit test.
