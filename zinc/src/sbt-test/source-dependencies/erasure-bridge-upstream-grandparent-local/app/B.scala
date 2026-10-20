package p
object B {
  def make: M[Int] = new A { override def m: Int = 2 }
}
