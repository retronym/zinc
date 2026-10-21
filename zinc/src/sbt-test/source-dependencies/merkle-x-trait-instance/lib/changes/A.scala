package p
class Outer { trait T { def m: String = "s" } }
object Inst extends Outer
