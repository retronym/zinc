object App {
  def main(args: Array[String]): Unit = {
    val s = Macros.fieldsDeep[Box]
    assert(s == args(0), s)
  }
}
