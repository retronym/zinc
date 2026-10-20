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

    /**
     * Whether Zinc composes the hashes of inherited members from each ancestor's own (Merkle
     * hashing). If so, the API of a class omits the members it inherits from classes of this
     * subproject or another one Zinc has analysed, and the bridge may hash it itself, as
     * {@link #apiMode} says. Otherwise the API of a class includes every member it inherits, and
     * the bridge sends it in full, through {@link AnalysisCallback#api}.
     */
    default boolean merkleHashing() {
        return false;
    }

    /** How the bridge reports class APIs in this compilation, under {@link #merkleHashing}. */
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

    /**
     * Under {@link #merkleHashing}, report the base classes of a non-local class or object, in
     * linearization order and without the class itself, by binary name (<code>p.O$A</code> for a
     * class <code>A</code> nested in <code>object O</code>), as the compiler resolves them: a
     * parent reached through a path, <code>Inst.B</code> with <code>object Inst extends
     * Outer</code>, is its class, <code>p.Outer$B</code>. Classes with no class file of their own,
     * such as <code>scala.Any</code>, are left out.
     *
     * @param className The class's name as {@link AnalysisCallback#api} reports it.
     * @param module Whether these are the object's base classes rather than the class's.
     */
    default void ancestors(String className, boolean module, String[] binaryNames) {}
}
