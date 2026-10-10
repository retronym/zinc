package p
object X {
  def main(args: Array[String]): Unit = {
    val v: Any = (new C).m
    assert(v.toString == args(0), v)
  }
}
