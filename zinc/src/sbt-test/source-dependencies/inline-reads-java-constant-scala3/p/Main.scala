package a

object Main {
  def main(args: Array[String]): Unit =
    if L.k != args(0).toInt then sys.error(s"inlined ${L.k}, expected ${args(0)}")
}
