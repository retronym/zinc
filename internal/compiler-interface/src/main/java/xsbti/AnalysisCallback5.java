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
 * Extension to {@link AnalysisCallback4}: the bridge extracts APIs for Merkle hashing.
 */
public interface AnalysisCallback5 extends AnalysisCallback4 {
    /**
     * Whether Zinc composes the hashes of inherited members from each ancestor's own (Merkle
     * hashing). If so, the API of a class omits the members it inherits from classes of this
     * subproject or another one Zinc has analysed. Otherwise the API of a class includes every
     * member it inherits.
     */
    default boolean merkleHashing() {
        return false;
    }

    /**
     * Whether the API of a class inheriting from the library class with this binary name should
     * include its members in full. Otherwise they are stubs (name, access, modifiers), and Zinc
     * invalidates by the names a class inherits from the library when the library changes.
     */
    default boolean materialiseLibraryMembers(String binaryClassName) {
        return true;
    }
}
