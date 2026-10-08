package p
object W {
  val s = Mac.members[A]
  def main(args: Array[String]): Unit = assert(s.split(",").contains("a") == args(0).toBoolean, s)
}
