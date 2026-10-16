package p
object Main {
  def main(args: Array[String]): Unit = {
    val r = classOf[A].getMethod("m").getReturnType.getName
    assert(r == args(0), r)
  }
}
