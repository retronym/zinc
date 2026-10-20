object Main {
  def params(cls: String): List[String] =
    Class.forName(cls).getDeclaredMethods.toList
      .filter(_.getName == "f")
      .map(_.getParameterTypes.map(_.getName).mkString(","))
      .sorted

  def main(args: Array[String]): Unit = {
    val expected = params("T")
    for (cls <- List("O", "O$"))
      assert(params(cls) == expected, s"$cls.f takes ${params(cls)}, T.f takes $expected")
    println(O.f(new V(1)))
  }
}
