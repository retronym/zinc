object App {
  def main(args: Array[String]): Unit = {
    val d = D()
    import d.given
    println(summon[Int])
  }
}
