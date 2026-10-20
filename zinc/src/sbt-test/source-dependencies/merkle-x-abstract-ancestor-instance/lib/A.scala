package p
class Outer {
  abstract class A { def m: Int }
  abstract class B extends A { def m: Int = 1 }
}
object Inst extends Outer
