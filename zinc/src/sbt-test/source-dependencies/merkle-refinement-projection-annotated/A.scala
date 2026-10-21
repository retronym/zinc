package p

trait F[G[_]]
final case class One[G[_], A](head: A, tail: G[A])

abstract class A {
  @deprecated("", "") def sameFile: F[({ type L[a] = One[List, a] })#L] = null
}
