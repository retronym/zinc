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

import java.nio.file.Files
import sbt.internal.inc.ZincBuildInfo.*

class BridgeSelectionReproSpec extends UnitSpec with BridgeProviderTestkit:
  "scripted label 2.13.y" should "select scala2-sbt-bridge, not the bridge built from source" in {
    info(s"scalaVersion213=$scalaVersion213 scalaVersion213Bin=$scalaVersion213Bin")
    switchScalaVersion(Some("2.13.y")) should not be switchScalaVersion(Some("2.13.x"))
  }

  it should "resolve to the prebuilt scala2-sbt-bridge jar" in {
    val tmp = Files.createTempDirectory("bridge-repro")
    val provider = getZincProvider(tmp, sbt.util.Logger.Null)
    val instance = provider.fetchScalaInstance(switchScalaVersion(Some("2.13.y")), null)
    val jar = provider.fetchCompiledBridge(instance, null)
    info(s"bridge jar: $jar")
    jar.getName should startWith("scala2-sbt-bridge")
  }
end BridgeSelectionReproSpec
