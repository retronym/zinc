package a

object L {
  inline def inl: Int = scala.compiletime.constValue[D.N]
}
