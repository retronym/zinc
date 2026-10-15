import scala.annotation.nowarn

trait A {
  @nowarn("cat=deprecation") def m: Int = 1
}
