package a

trait A { def name: String }
class B extends A { def name = "B" }
class C extends B { override def name = "C" }

object X {
  given b: B = B()
}
