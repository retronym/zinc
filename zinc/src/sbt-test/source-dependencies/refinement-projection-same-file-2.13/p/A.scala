package p

trait F[G[_]]
final case class One[G[_], A](head: A, tail: G[A])
object Aliases { type MyList[X] = List[X] }

abstract class A {
  def sameFile: F[({ type L[a] = One[List, a] })#L] = null
  def userAlias: F[({ type L[a] = One[Aliases.MyList, a] })#L] = null
  def onlyAlias: F[({ type L[a] = One[List, Int] })#L] = null
}
