package p
object X {
  def main(args: Array[String]): Unit = {
    val v = (new B).m
    assert(v == args(0).toInt, v)
  }
}
