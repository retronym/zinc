package app
object X {
  def main(args: Array[String]): Unit = {
    val v: Any = (new lib.B).m
    assert(v.toString == args(0), v)
  }
}
