package a

package object b {
  implicit val showB: s.Show[T] = new s.Show[T] {}
}
