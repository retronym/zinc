object Main {
  def main(args: Array[String]): Unit = {
    val expected = args(0).toInt
    val actual = new D().foo
    assert(actual == expected, s"Expected $expected, got $actual")
  }
}
