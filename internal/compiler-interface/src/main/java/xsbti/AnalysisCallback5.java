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

import xsbti.api.ClassLike;

/**
 * Extension to {@link AnalysisCallback4}: the bridge hashes each class's API itself and sends
 * only the part of it that Zinc keeps.
 */
public interface AnalysisCallback5 extends AnalysisCallback4 {
    /** How the bridge reports class APIs in this compilation. */
    enum ApiMode {
        /** The full API through {@link AnalysisCallback#api}, hashed by Zinc. */
        TREE,
        /** The kept API and its hashes through {@link #api(VirtualFileRef, ClassLike, ClassHashes)}. */
        HASHES,
        /** Both, through {@link #apiCheck}, so that Zinc can compare the two hashings. */
        CHECK
    }

    ApiMode apiMode();

    /**
     * Whether the API of a class inheriting from the library class with this binary name should
     * include its members in full. Otherwise they are stubs (name, access, modifiers), and Zinc
     * invalidates by the names a class inherits from the library when the library changes.
     */
    default boolean materialiseLibraryMembers(String binaryClassName) {
        return true;
    }

    /** Whether name hashes include sealed children only in the pattern-matching scope. */
    boolean useOptimizedSealed();

    /**
     * Report a class's API as Zinc stores it: the header, parents, self type, a stub (name,
     * access, modifiers, annotations) of each declared member and of each abstract inherited
     * member, and main methods in full; with the hashes of the whole API.
     */
    void api(VirtualFileRef sourceFile, ClassLike thinClass, ClassHashes hashes);

    /** As {@link #api(VirtualFileRef, ClassLike, ClassHashes)}, with the full API as well. */
    void apiCheck(VirtualFileRef sourceFile, ClassLike fullClass, ClassLike thinClass, ClassHashes hashes);
}
