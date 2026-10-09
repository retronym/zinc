import show.Show

package object pkg {
  val unrelated = 0
  implicit val fooShow: Show[Foo] = new Show[Foo] { def show = "pkg" }
}
