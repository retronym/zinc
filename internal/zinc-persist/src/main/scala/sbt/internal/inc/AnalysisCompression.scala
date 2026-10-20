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

import java.util.zip.Deflater

/**
 * The deflate level the analysis stores compress with: the system property
 * `sbt.analysis.compression`, from 0 (no compression) to 9 (best), or zlib's default.
 *
 * The analysis is written after every compile that changes it, and for a large project
 * compressing it at the default level can take as long as compiling a small change. A lower
 * level trades a larger file for less time.
 */
object AnalysisCompression:
  final val Property = "sbt.analysis.compression"

  def level: Int =
    sys.props.get(Property).map(_.trim) match
      case None | Some("") => Deflater.DEFAULT_COMPRESSION
      case Some(v) =>
        v.toIntOption
          .filter(l => l == Deflater.DEFAULT_COMPRESSION || (l >= 0 && l <= 9))
          .getOrElse(
            throw new IllegalArgumentException(s"$Property must be a deflate level, -1 to 9: $v")
          )
end AnalysisCompression
