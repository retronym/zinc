package a
package b

import a.X.given

object Client {
  def main(args: Array[String]): Unit = {
    val n = summon[A].name
    if n != args(0) then sys.error(s"chose $n, expected ${args(0)}")
  }
}
