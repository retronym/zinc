package p
object L {
  abstract class A { def a: Int = 1 }
  final case class B(x: Int) extends A
}
