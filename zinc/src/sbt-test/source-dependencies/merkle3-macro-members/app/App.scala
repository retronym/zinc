package p

object App {
  def main(args: Array[String]): Unit = {
    val actual = macros.Macros.typeOfX[B]
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
