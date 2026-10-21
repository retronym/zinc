package a
package b

object Client {
  val use: Any = summon[a.T]
}
