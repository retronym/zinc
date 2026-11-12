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

import ZincOraclePlugin.autoImport._

/** The parts of the plugin that sbt 1 and sbt 2 spell differently. */
object Compat {
  def verifySettings: Seq[Setting[_]] = Seq(
    incVerify := {
      val incremental = compile.result.value match {
        case Value(a) => Some(a)
        case Inc(_)   => None
      }
      val out = Verify.run(
        thisProject.value.id,
        configuration.value.name,
        (LocalRootProject / baseDirectory).value,
        target.value / "inc-oracle" / configuration.value.name,
        incremental,
        (compile / compileInputs).value,
        previousCompile.value,
        fileConverter.value,
        streams.value.log
      )
      val verdict = out.get("verdict").asString
      if (incVerifyFailOnFinding.value && Verify.isFinding(verdict))
        throw new MessageOnlyException(
          s"incVerify: ${thisProject.value.id} / ${configuration.value.name} is $verdict"
        )
    }
  )
}
