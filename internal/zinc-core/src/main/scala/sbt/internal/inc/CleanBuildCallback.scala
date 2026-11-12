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

package sbt.internal.inc

import java.io.File
import java.nio.file.Path
import java.util as ju
import java.util.{ EnumSet, Optional }
import xsbti.{
  AnalysisCallback4,
  Action,
  DiagnosticCode,
  DiagnosticRelatedInformation,
  FileConverter,
  Position,
  Severity,
  T2,
  UseScope,
  VirtualFile,
  VirtualFileRef
}
import xsbti.api.{ ClassLike, DependencyContext }
import xsbti.compile.analysis.ReadSourceInfos

/**
 * The callback of a clean build, see [[Incremental.compileClean]]. It reports itself disabled, so
 * compilers skip the phases that extract APIs and dependencies, and it ignores everything else.
 */
private[sbt] final class CleanBuildCallback(converter: FileConverter, pickleJava: Boolean)
    extends AnalysisCallback4:
  override def enabled(): Boolean = false

  override def startSource(source: File): Unit = ()
  override def startSource(source: VirtualFile): Unit = ()
  override def classDependency(on: String, from: String, context: DependencyContext): Unit = ()
  override def classDependency(
      on: xsbti.ClassRef,
      from: xsbti.ClassRef,
      context: DependencyContext
  ): Unit = ()
  override def binaryDependency(
      onBinaryEntry: File,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: File,
      context: DependencyContext
  ): Unit = ()
  override def binaryDependency(
      onBinaryEntry: Path,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = ()
  override def binaryDependency(
      onBinaryEntry: Path,
      onBinaryClassName: String,
      fromClass: xsbti.ClassRef,
      fromSourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = ()
  override def generatedNonLocalClass(
      source: File,
      classFile: File,
      binaryClassName: String,
      srcClassName: String
  ): Unit = ()
  override def generatedNonLocalClass(
      source: VirtualFileRef,
      classFile: Path,
      binaryClassName: String,
      srcClassName: String
  ): Unit = ()
  override def generatedLocalClass(source: File, classFile: File): Unit = ()
  override def generatedLocalClass(source: VirtualFileRef, classFile: Path): Unit = ()
  override def api(sourceFile: File, classApi: ClassLike): Unit = ()
  override def api(sourceFile: VirtualFileRef, classApi: ClassLike): Unit = ()
  override def mainClass(sourceFile: File, className: String): Unit = ()
  override def mainClass(sourceFile: VirtualFileRef, className: String): Unit = ()
  override def usedName(className: String, name: String, useScopes: EnumSet[UseScope]): Unit =
    ()
  override def usedName(
      className: String,
      name: String,
      qualifierKinds: EnumSet[xsbti.NameKind],
      useScopes: EnumSet[UseScope]
  ): Unit = ()
  override def problem(
      what: String,
      pos: Position,
      msg: String,
      severity: Severity,
      reported: Boolean
  ): Unit = ()
  override def problem2(
      what: String,
      pos: Position,
      msg: String,
      severity: Severity,
      reported: Boolean,
      rendered: Optional[String],
      diagnosticCode: Optional[DiagnosticCode],
      diagnosticRelatedInformation: ju.List[DiagnosticRelatedInformation],
      actions: ju.List[Action]
  ): Unit = ()
  override def dependencyPhaseCompleted(): Unit = ()
  override def apiPhaseCompleted(): Unit = ()
  override def classesInOutputJar(): ju.Set[String] = ju.Collections.emptySet()
  override def isPickleJava(): Boolean = pickleJava
  override def getPickleJarPair(): Optional[T2[Path, Path]] = Optional.empty()
  override def toVirtualFile(path: Path): VirtualFile = converter.toVirtualFile(path)
  override def getSourceInfos(): ReadSourceInfos = SourceInfos.empty
end CleanBuildCallback
