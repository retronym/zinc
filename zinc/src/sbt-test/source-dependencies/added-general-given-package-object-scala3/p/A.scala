package a

trait A { def name: String }
class B extends A { def name = "B" }

object X {
  given b: B = B()
}
