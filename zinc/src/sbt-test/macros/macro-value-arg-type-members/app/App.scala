object App {
  def main(args: Array[String]): Unit = {
    val s = Macros.fieldsOf(new A)
    assert(s == args(0), s)
  }
}
