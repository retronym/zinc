package p
object L {
  abstract class A { def a: Int = 2 }
  final case class B(x: Int) extends A
}
