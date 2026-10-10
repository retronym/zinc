package a

object Client {
  def f(s: S): Int = s match {
    case _: A => 1
    case _: B => 2
  }
}
