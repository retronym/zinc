package a

trait Show[T] { def name: String }

object Show {
  implicit def default[T]: Show[T] = new Show[T] { def name = "default" }
}
