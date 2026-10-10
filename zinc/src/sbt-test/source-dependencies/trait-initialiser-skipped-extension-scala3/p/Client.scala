package a

object Client extends P {
  extension (s: String) def twice: String = s + s
  def main(args: Array[String]): Unit =
    if args.contains("expect-init") && !Flag.set then sys.error("P's initialiser did not run")
}
