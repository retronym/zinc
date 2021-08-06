package sbt.inc.binary

import sbt.internal.inc.{ ConcreteAnalysisContents, FileAnalysisStore }
import xsbti.compile.AnalysisContents

import java.io.File

object Scratch {
  def main(args: Array[String]): Unit = {
    val files = List(
      "/Users/jz/code/scala/target/partest/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/scaladoc/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/replFrontend/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/reflect/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/repl/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/library/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/testkit/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/scalap/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/interactive/zinc/inc_compile.zip",
      "/Users/jz/code/scala/target/compiler/zinc/inc_compile.zip"
    )
    val analysisList =
      files.map(f => migrate(load(f)).getAnalysis)
    println("Loaded all analysis files")
    while (true) {
      Thread.sleep(1000) // run: jcmd $PID GC.heap_dump /tmp/dump.hprof and open with IntelliJ or another tool
    }
  }
  def load(f: String): AnalysisContents = {
    FileAnalysisStore.binary(new File(f)).get().get()
  }
  def migrate(analysis: AnalysisContents): AnalysisContents = {
    val tmp = File.createTempFile("analysis", ".bin")
    try {
      FileAnalysisStore
        .binary(tmp)
        .set(
          ConcreteAnalysisContents(analysis.getAnalysis, analysis.getMiniSetup.withStoreApis(false))
        )
      FileAnalysisStore.binary(tmp).get().get
    } finally {
      tmp.delete()
      ()
    }
  }
}
