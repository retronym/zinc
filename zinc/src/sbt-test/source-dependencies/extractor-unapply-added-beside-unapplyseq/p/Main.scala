object Main {
  def main(args: Array[String]): Unit = {
    val r = "x" match { case Ex(a, b) => a + b }
    assert(r == args(0), r)
  }
}
