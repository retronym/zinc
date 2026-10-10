package p
object Main {
  def main(args: Array[String]): Unit = assert((new B).m == args(0).toInt, (new B).m)
}
