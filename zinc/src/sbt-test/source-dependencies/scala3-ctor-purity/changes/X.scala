object X {
  inline def ignore(x: Any): Unit = ()
  def main(args: Array[String]): Unit = {
    ignore(new B)
    assert(Counter.n == 1, s"B's constructor was elided, Counter.n == ${Counter.n}")
  }
}
