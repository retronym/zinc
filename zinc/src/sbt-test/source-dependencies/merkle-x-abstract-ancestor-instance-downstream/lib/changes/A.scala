package p
class Outer {
  abstract class A { def m: Int }
  abstract class B extends A
}
object Inst extends Outer
