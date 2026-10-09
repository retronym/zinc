package a
package b

object Main {
  def main(args: Array[String]): Unit = assert(Foo.v == args(0).toInt, Foo.v)
}
