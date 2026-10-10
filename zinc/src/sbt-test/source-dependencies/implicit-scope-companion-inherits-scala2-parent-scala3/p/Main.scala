package a

object Main {
  def main(args: Array[String]): Unit =
    val n = summon[Show[C]].name
    if n != args(0) then sys.error(s"found $n, expected ${args(0)}")
}
