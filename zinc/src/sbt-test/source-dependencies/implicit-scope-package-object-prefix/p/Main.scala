import show.Show

object Main {
  def main(args: Array[String]): Unit = {
    val s = implicitly[Show[pkg.Foo]].show
    assert(s == args(0), s)
  }
}
