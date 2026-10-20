object App {
  def kind(x: Int): String = "Int"
  def kind(x: String): String = "String"
  def main(args: Array[String]): Unit = {
    val actual = kind(D().t)
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
