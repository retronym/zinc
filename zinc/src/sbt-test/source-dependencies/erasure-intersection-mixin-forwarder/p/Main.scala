package a

object Main {
  def main(args: Array[String]): Unit =
    val ps = classOf[K].getDeclaredMethods.filter(_.getName == "m").map(_.getParameterTypes.head.getName).sorted
    if !ps.sameElements(args.sorted) then
      sys.error(s"K's forwarder takes ${ps.mkString(", ")}, expected ${args.mkString(", ")}")
}
