package show

trait Show[T] { def show: String }
object Show {
  implicit def fallback[T]: Show[T] = new Show[T] { def show = "fallback" }
}
