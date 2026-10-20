package p
object Main {
  def main(args: Array[String]): Unit = {
    val m = B.make.m
    assert(m == 2, m)
  }
}
