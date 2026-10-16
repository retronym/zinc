package macros

import scala.quoted.*

object Macros {
  inline def typeOfX[T]: String = ${ typeOfXImpl[T] }

  def typeOfXImpl[T: Type](using Quotes): Expr[String] = {
    import quotes.reflect.*
    val x = TypeRepr.of[T].typeSymbol.methodMember("x")
    val shown = x.headOption.map(s => TypeRepr.of[T].memberType(s).widen match {
      case MethodType(_, _, res) => res.typeSymbol.name
      case other => other.typeSymbol.name
    }).getOrElse("none")
    Expr(shown)
  }
}
