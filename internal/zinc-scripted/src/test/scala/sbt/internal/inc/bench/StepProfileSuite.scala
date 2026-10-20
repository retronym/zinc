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
package bench

import org.scalatest.funsuite.AnyFunSuite

import StepProfile.Phase

class StepProfileSuite extends AnyFunSuite:

  val scala2 = Seq(
    "info, 1, version, 2, output, /tmp/out",
    "header(main/background),startNs,endNs,runId,phaseId,phaseName,purpose,task-count,threadId,threadName,runNs,idleNs,cpuTimeNs,userTimeNs,allocatedByte,heapSize",
    "header(GC),startNs,endNs,startMs,endMs,name,action,cause,threads",
    "main,100,200,1,1,parser,,0,1,main,3000000,0,2900000,2800000,4000,1000 ",
    "main,200,900,1,4,typer,,0,1,main,70000000,0,69000000,68000000,90000,2000 ",
    "GC,300,400,3, 4,G1 Young Generation,end of minor GC,G1 Evacuation Pause,5",
    "main,900,999,1,13,xsbt-api,,0,1,main,5000000,0,4900000,4800000,7000,2000 ",
  )

  // From Scala 3.3.7's -Yprofile-destination, trimmed.
  val scala3 = Seq(
    "info, 1, version, 2, output, /tmp/out",
    "header(main/background),startNs,endNs,runId,phaseId,phaseName,purpose,task-count,threadId,threadName,runNs,idleNs,cpuTimeNs,userTimeNs,allocatedByte,heapSize",
    "header(GC),startNs,endNs,startMs,endMs,name,action,cause,threads",
    "MAIN,383817912832083,383817943045958,1,1,parser,,0,1,main,30213875,0,29812000,28308000,4842328,23347712 ",
    "MAIN,383817943933500,383818155810291,1,2,typer,,0,1,main,211876791,0,208525000,197005000,35101792,56902144 ",
    "MAIN,383818156189500,383818188970625,1,6,posttyper,,0,1,main,32781125,0,32103000,30562000,4174280,56902144 ",
    "MAIN,383818201490208,383818222298583,1,15,MegaPhase{crossVersionChecks  firstTransform  refchecks},,0,1,main,20808375,0,20431000,19480000,2318960,65290752 ",
    "GC,383818265690458,383818270690458,759, 763,G1 Young Generation,end of minor GC,Metadata GC Threshold,5",
    "MAIN,383818352161416,383818357276958,1,79,MegaPhase{flatten  Collect entry points  collectSuperCalls},,0,1,main,5115542,0,4626000,4391000,592160,31858592 ",
    "MAIN,383818357326583,383818451046708,1,90,genBCode,,0,1,main,93720125,0,90787000,85772000,14905800,48635808",
  )

  test("scalac's phases, in the order they ran") {
    assert(
      StepProfile.parseScalacProfile(scala2) == Seq(
        Phase("parser", 3000000, 4000),
        Phase("typer", 70000000, 90000),
        Phase("xsbt-api", 5000000, 7000),
      )
    )
  }

  test("Scala 3's phases, with its MegaPhase names as Scala 3 gives them") {
    assert(
      StepProfile.parseScalacProfile(scala3) == Seq(
        Phase("parser", 30213875, 4842328),
        Phase("typer", 211876791, 35101792),
        Phase("posttyper", 32781125, 4174280),
        Phase("MegaPhase{crossVersionChecks, firstTransform, refchecks}", 20808375, 2318960),
        Phase("MegaPhase{flatten, Collect entry points, collectSuperCalls}", 5115542, 592160),
        Phase("genBCode", 93720125, 14905800),
      )
    )
  }

  test("each phase sums over the compiler runs that appended to the file") {
    val twice = StepProfile.parseScalacProfile(scala3 ++ scala3.drop(1))
    assert(twice.map(_.name) == StepProfile.parseScalacProfile(scala3).map(_.name))
    assert(twice.find(_.name == "typer").contains(Phase("typer", 2 * 211876791L, 2 * 35101792L)))
  }
end StepProfileSuite
