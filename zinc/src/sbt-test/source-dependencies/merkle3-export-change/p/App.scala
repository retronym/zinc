object App {
  def main(args: Array[String]): Unit = {
    val actual = W(D()).m.toString
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
