object Main {
  def main(args: Array[String]): Unit = {
    var c = new C("")
    c += "x"
    val s = c.log + c.extra
    assert(s == args(0), s)
  }
}
