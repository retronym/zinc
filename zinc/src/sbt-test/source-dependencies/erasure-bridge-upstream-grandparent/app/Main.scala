package p
object Main {
  def main(args: Array[String]): Unit = {
    val m = (new B: M[Int]).m
    assert(m == 2, m)
  }
}
