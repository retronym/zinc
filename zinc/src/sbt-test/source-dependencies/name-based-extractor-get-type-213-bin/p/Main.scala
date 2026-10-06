object Main {
  def main(args: Array[String]): Unit = {
    val s = 5 match { case Ex(x) => (x + 1).toString }
    assert(s == args(0), s)
  }
}
