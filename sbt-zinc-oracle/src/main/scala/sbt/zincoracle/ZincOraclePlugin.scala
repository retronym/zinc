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

import sbt._
import sbt.Keys._

/**
 * Checks sbt's incremental compilation against a clean build of the same inputs.
 *
 * `incVerify` compiles, then compiles each module again from scratch into a scratch directory and
 * compares the two outputs. `incReplay N` does that over the last N commits of the repository, in
 * a clone.
 */
object ZincOraclePlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {
    val incVerify = taskKey[Unit](
      "Compiles, compiles again from scratch into a scratch directory, and compares the outputs."
    )
    val incVerifyFailOnFinding = settingKey[Boolean](
      "Whether incVerify fails when incremental compilation differs from the clean build in a way the oracle cannot explain."
    )
    val incOracleCompileAll = taskKey[Unit]("Compiles every module and configuration, without failing.")
    val incOracleVerifyAll = taskKey[Unit]("Runs incVerify in every module and configuration.")
    val incOracleCleanAll = taskKey[Unit]("Cleans every module.")
  }
  import autoImport._

  override def globalSettings: Seq[Setting[_]] = Seq(
    incVerifyFailOnFinding := true,
    commands ++= Steps.commands
  )

  override def projectSettings: Seq[Setting[_]] =
    inConfig(Compile)(Compat.verifySettings) ++ inConfig(Test)(Compat.verifySettings) ++ Compat.bothSettings
}
