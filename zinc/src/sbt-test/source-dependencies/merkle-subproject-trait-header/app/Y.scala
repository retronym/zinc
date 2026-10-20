package app
object Y {
  def main(args: Array[String]): Unit = {
    val v: Int = (new lib.C).g
    assert(v.toString == args(0), v)
  }
}
