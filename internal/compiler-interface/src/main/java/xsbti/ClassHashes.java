/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package xsbti;

import xsbti.api.NameHash;

/**
 * The hashes of one class's API, computed by the compiler bridge from compiler symbols and types,
 * in place of Zinc hashing a full <code>xsbti.api.ClassLike</code>.
 *
 * Only equality between two versions of a class is meaningful; values may change between Zinc
 * versions, which costs one full recompile.
 */
public final class ClassHashes {
    private final int apiHash;
    private final int extraHash;
    private final NameHash[] nameHashes;
    private final boolean hasMacro;

    /**
     * @param apiHash The hash of the class's non-private API.
     * @param extraHash For a trait, the API hash including the private members that its
     *                  implementors depend on (fields, objects, super accessors), before Zinc folds
     *                  in its parents'. For other classes, equal to <code>apiHash</code>.
     * @param nameHashes One hash per (simple name, scope) of the class's non-private members.
     * @param hasMacro Whether the class declares a macro.
     */
    public ClassHashes(int apiHash, int extraHash, NameHash[] nameHashes, boolean hasMacro) {
        this.apiHash = apiHash;
        this.extraHash = extraHash;
        this.nameHashes = nameHashes;
        this.hasMacro = hasMacro;
    }

    public int apiHash() { return apiHash; }
    public int extraHash() { return extraHash; }
    public NameHash[] nameHashes() { return nameHashes; }
    public boolean hasMacro() { return hasMacro; }
}
