/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.internal.inc.caching

import java.io.FileNotFoundException
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import xsbti.compile.FileHash
import sbt.internal.inc.{EmptyStamp, Stamper}
import sbt.internal.io.Milli

object ClasspathCache {
  // For more safety, store both the time and size
  private type JarMetadata = (FileTime, Long)
  private[this] val cacheMetadataJar = new ConcurrentHashMap[Path, (JarMetadata, FileHash)]()
  private[this] final val emptyStampCode = EmptyStamp.hashCode()
  private def emptyFileHash(file: Path) = FileHash.of(file, emptyStampCode)
  private def genFileHash(file: Path, metadata: JarMetadata): FileHash = {
    val newHash = FileHash.of(file, Stamper.forFarmHashP(file).getValueId())
    cacheMetadataJar.put(file, (metadata, newHash))
    newHash
  }
  def hashClasspath(classpath: Seq[Path]): Array[FileHash] = {
    // #433: Cache jars with their metadata to avoid recomputing hashes transitively in other projects
    def fromCacheOrHash(file: Path): FileHash = {
      if (!Files.exists(file)) emptyFileHash(file)
      else {
        // `readAttributes` needs to be guarded by `file.exists()`, otherwise it fails
        val attrs = Files.readAttributes(file, classOf[BasicFileAttributes])
        if (attrs.isDirectory) emptyFileHash(file)
        else {
          val currentLastModified: FileTime = getModifiedTimeOrZeroEfficient(file, attrs)
          val currentSize = attrs.size()
          cacheMetadataJar.get(file) match {
            case (metadata, hashHit)
                if metadata._2 == currentSize && metadata._1 == currentLastModified =>
              hashHit
            case null => genFileHash(file, (currentLastModified, currentSize))
          }
        }
      }
    }
    classpath.toParArray.map(fromCacheOrHash).toArray
  }

  private def getModifiedTimeOrZeroEfficient(file: Path, attrs: BasicFileAttributes): FileTime = {
    Milli.milli match {
      case sbt.io.JavaMillis =>
        // Avoid another call to readAttributes in JavaMillis.
        attrs.lastModifiedTime()
      case m =>
        try {
          FileTime.fromMillis(m.getModifiedTime(file.toString)) // Note java.nio.Path internally caches .toString
        } catch {
          case _: FileNotFoundException =>
            FileTime.fromMillis(0L)
        }
    }
  }
}
