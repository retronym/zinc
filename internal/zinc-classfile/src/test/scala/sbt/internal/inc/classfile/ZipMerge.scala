package sbt.internal.inc
package classfile

import sbt.internal.inc.UnitSpec

import java.nio.file.Files
import java.nio.file.spi.FileSystemProvider
import scala.collection.JavaConverters._

class ZipMerge extends UnitSpec {
  private def createJar(n: Int) = {
    val out = Files.createTempFile("repro-", ".jar")

    val empty = Files.createTempFile("empty", ".class")
    val name = empty.getFileName
    IndexBasedZipFsOps.createZip(out.toFile, (0 until n).map(i => (empty.toFile, s"$name$i.class")))
    Files.delete(empty)

    out
  }

  lazy val zipFsProvider = FileSystemProvider.installedProviders().asScala.find(
    _.getScheme == "jar"
  ).getOrElse(throw new RuntimeException("No jar filesystem provider"))

  private def createJarJDK(n: Int) = {

    val out = Files.createTempFile("repro-", ".jar")
    Files.delete(out)
    val empty = Files.createTempFile("empty", ".class")
    val name = empty.getFileName
    val env = new java.util.HashMap[String, String]()
    env.put("create", "true")
    val zipfs = zipFsProvider.newFileSystem(out, env)
    val root = zipfs.getRootDirectories.iterator().next()
    (0 until n).foreach(i => Files.write(root.resolve(s"$name$i.class"), Array[Byte]()))
    zipfs.close()
    Files.delete(empty)
    out
  }

  val LargeNum = (1 << 16) + 1

  "ZipMerge" should "largeFileCreation" in {
    val l = createJar(LargeNum)
    val cen = IndexBasedZipFsOps.readCentralDir(l.toFile)
    assert(cen.getHeaders.size() == LargeNum)

  }
  "ZipMerge" should "smallFileMerge" in {
    val l = createJar(1)
    val s = createJar(1)
    IndexBasedZipFsOps.mergeArchives(l, s)
    val cen = IndexBasedZipFsOps.readCentralDir(l.toFile)
    assert(cen.getHeaders.size() == 2)
  }

  "ZipMerge" should "largeFileMerge" in {
    if (true) {
      val l = createJar(LargeNum)
      val s = createJar(1)
      IndexBasedZipFsOps.mergeArchives(l, s)
      val cen = IndexBasedZipFsOps.readCentralDir(l.toFile)
      assert(cen.getHeaders.size() == LargeNum + 1)
    }
  }

  "ZipMerge" should "LargeZip JDK" in {
    val l = createJarJDK(LargeNum + 10)
    val cen = IndexBasedZipFsOps.readCentralDir(l.toFile)
    assert(cen.getHeaders.size() == LargeNum + 10)
  }
}
