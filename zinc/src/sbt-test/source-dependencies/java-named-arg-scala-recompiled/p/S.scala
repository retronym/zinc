object S {
  def main(args: Array[String]): Unit = {
    val s = J.f(height = 2, width = 1)
    assert(s == args(0), s)
  }
}
