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

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, OpenOption, Path }

/** JDK 11 and Scala 2.13 conveniences the benchmarks use, for JDK 8 and Scala 2.12. */
private[bench] object Compat {
  def readString(p: Path): String = new String(Files.readAllBytes(p), UTF_8)
  def writeString(p: Path, s: CharSequence, options: OpenOption*): Unit = {
    Files.write(p, s.toString.getBytes(UTF_8), options: _*)
    ()
  }
  def when[A](cond: Boolean)(a: => A): Option[A] = if (cond) Some(a) else None
}
