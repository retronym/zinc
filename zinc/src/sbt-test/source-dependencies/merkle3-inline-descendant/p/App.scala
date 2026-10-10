object App {
  def main(args: Array[String]): Unit = {
    val actual = s"${D().n},${D().m}"
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
