package sbt.inc.binary

object Scratch {
  def timed[T](f: => T) = {
    val start = System.nanoTime;
    try f
    finally {
      println(((System.nanoTime - start) / 1000 / 1000) + " ms")
    }
  }
  def main(args: Array[String]): Unit = {
    for (i <- 1 to 32) {
      println(
        timed(
          sbt.internal.inc.FileAnalysisStore
            .binary(new java.io.File("/Users/jz/code/scala/target/compiler/zinc/inc_compile.zip"))
            .get
        )
      )
    }
    timed(
      for (i <- 1 to 32) {
        println(
          timed(
            sbt.internal.inc.FileAnalysisStore
              .binary(new java.io.File("/Users/jz/code/scala/target/compiler/zinc/inc_compile.zip"))
              .get
          )
        )
      }
    )
  }
}
