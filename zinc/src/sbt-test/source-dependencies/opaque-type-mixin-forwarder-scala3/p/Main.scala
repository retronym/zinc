package a

object Main {
  def main(args: Array[String]): Unit =
    val forwarders = classOf[K].getDeclaredMethods.filter(_.getName == "h").map(_.getParameterTypes.head.getName)
    if !forwarders.sameElements(args) then
      sys.error(s"K's forwarder takes ${forwarders.mkString(", ")}, expected ${args.mkString(", ")}")
}
