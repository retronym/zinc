package app

object App {
  def main(args: Array[String]): Unit = {
    val actual = macros.Macros.typeOfX[lib.D]
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
