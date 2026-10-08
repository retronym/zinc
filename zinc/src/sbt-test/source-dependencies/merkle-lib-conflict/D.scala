class D extends P with scala.collection.immutable.Seq[Int] {
  def apply(i: Int): Int = 0
  def length: Int = 0
  def iterator: Iterator[Int] = Iterator.empty
}
