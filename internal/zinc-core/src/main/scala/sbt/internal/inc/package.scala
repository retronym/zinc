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

package sbt.internal

import scala.reflect.ClassTag

package object inc {
  private[inc] type BinaryName = BinaryName.Type

  private[inc] implicit final class BinaryNameOps(private val b: BinaryName) extends AnyVal {
    def value: String = b.asInstanceOf[String]
  }

  private[inc] implicit val binaryNameClassTag: ClassTag[BinaryName] =
    ClassTag(classOf[String])
}
