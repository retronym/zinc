object App {
  def main(args: Array[String]): Unit = {
    val d = D()
    val t: d.T = d.mk
    val actual = d.show(t)
    assert(actual == args(0), s"Expected ${args(0)}, obtained $actual")
  }
}
