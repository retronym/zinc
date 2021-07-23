package sbt.inc.binary

import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.AnalysisContents

import java.io.File
import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit

object UsedNameTest {
  def main(args: Array[String]): Unit = {
    val inFile = new File("/Users/jz/code/scala/target/library/zinc/inc_compile.zip")
    val store = FileAnalysisStore.binary(
      inFile
    )
    val contents = store.get().get()
    val analysis = contents.getAnalysis.asInstanceOf[sbt.internal.inc.Analysis]
    val outFile = new File("/tmp/inc_compile.zip")
    val outStore = FileAnalysisStore.binary(outFile)
    outStore.set(
      AnalysisContents.create(
        analysis.copy(relations = analysis.relations.withoutUsedNames),
        contents.getMiniSetup
      )
    )
    printSize(inFile.toPath)
    printSize(outFile.toPath)
    def timedLoad(path: Path): Unit = {
      val now = System.nanoTime()
      FileAnalysisStore.binary(path.toFile).get.get().getAnalysis
      val end = System.nanoTime()
      println(path + " loaded in " + TimeUnit.NANOSECONDS.toMillis((end - now)) + "ms")
    }
    (1 to 32).foreach(_ => timedLoad(inFile.toPath))
    (1 to 32).foreach(_ => timedLoad(outFile.toPath))
  }

  private def printSize(path: Path): Unit = {
    println(path + " size = " + +Files.size(path))
  }
}
