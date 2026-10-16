object Main {
  def main(args: Array[String]): Unit = {
    assert(X.f(new C).toString == args(0), X.f(new C))
    assert(Y.f(new C).toString == args(1), Y.f(new C))
  }
}
