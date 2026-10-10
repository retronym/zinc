package app
object Y {
  implicit class R(n: lib.N) { def x: Int = 1 }
  def main(args: Array[String]): Unit = {
    val v = lib.Mk.n.x
    assert(v.toString == args(0), v)
  }
}
