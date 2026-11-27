package a

trait L[T] {
  implicit def sl: Show[T] = new Show[T] { def name = "sl" }
}
