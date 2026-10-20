package p
object W {
  val s = Mac.members[C]
  def main(args: Array[String]): Unit = assert(s.contains("b: ") == args(0).toBoolean, s)
}
