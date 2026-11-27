package a

object Client {
  def main(args: Array[String]): Unit =
    if L.inl != args(0).toInt then sys.error(s"inlined ${L.inl}, expected ${args(0)}")
}
