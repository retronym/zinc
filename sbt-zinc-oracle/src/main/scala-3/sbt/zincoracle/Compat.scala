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

package sbt.zincoracle

import sbt.*
import sbt.Keys.*

import ZincOraclePlugin.autoImport.*

/** The parts of the plugin that sbt 1 and sbt 2 spell differently. */
object Compat:
  private val everywhere = ScopeFilter(inAnyProject, inConfigurations(Compile, Test))

  private def ok(r: Result[?]): Boolean = r match
    case Result.Value(_) => true
    case Result.Inc(_)   => false

  /** Whether everything this module and configuration compiles against compiled. */
  private def upstream: Def.Initialize[Task[Boolean]] = Def.task {
    val others = Def.task(compile.result.value)
      .all(ScopeFilter(inDependencies(ThisProject, includeRoot = false), inConfigurations(Compile, Test)))
      .value
    val own = configuration.value.name == "compile" || ok((Compile / compile).result.value)
    own && others.forall(ok)
  }

  private def check: Def.Initialize[Task[Unit]] = Def.task {
    val incremental = compile.result.value match
      case Result.Value(a) => Some(a)
      case Result.Inc(_)   => None
    val root = (LocalRootProject / baseDirectory).value
    val out = Verify.run(
      thisProject.value.id,
      configuration.value.name,
      root,
      target.value / "inc-oracle" / configuration.value.name,
      incremental,
      (compile / compileInputs).value,
      fileConverter.value,
      streams.value.log
    )
    val verdict = out.get("verdict").asString
    if incVerifyFailOnFinding.value && !Verify.replaying(root) && Verify.isFinding(verdict) then
      throw new MessageOnlyException(
        s"incVerify: ${thisProject.value.id} / ${configuration.value.name} is $verdict"
      )
  }

  private def skip: Def.Initialize[Task[Unit]] = Def.task {
    Verify.skip(thisProject.value.id, configuration.value.name, (LocalRootProject / baseDirectory).value, streams.value.log)
    ()
  }

  def verifySettings: Seq[Setting[?]] = Seq(
    incVerify := Def.uncached(Def.taskDyn(if upstream.value then check else skip).value)
  )

  def bothSettings: Seq[Setting[?]] = Seq(
    incOracleCompileAll := Def.uncached { Def.task(compile.result.value).all(everywhere).value; () },
    incOracleVerifyAll := Def.uncached {
      (Compile / incVerify).all(ScopeFilter(inAnyProject)).value
      (Test / incVerify).all(ScopeFilter(inAnyProject)).value
      ()
    },
    incOracleCleanAll := Def.uncached { clean.all(ScopeFilter(inAnyProject)).value; () },
    incVerify := Def.uncached((Test / incVerify).dependsOn(Compile / incVerify).value)
  )
