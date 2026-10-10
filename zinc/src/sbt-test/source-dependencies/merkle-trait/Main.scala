object Main {
  def main(args: Array[String]): Unit = assert(X.f(new B).toString == args(0), X.f(new B))
}
