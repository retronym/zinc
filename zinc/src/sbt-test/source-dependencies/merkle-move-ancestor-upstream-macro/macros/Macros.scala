package macros

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Macros {
  def typeOfX[T]: String = macro typeOfXImpl[T]

  def typeOfXImpl[T: c.WeakTypeTag](c: Context): c.Expr[String] = {
    import c.universe._
    val x = weakTypeOf[T].member(TermName("x"))
    val shown = if (x == NoSymbol) "none" else x.typeSignature.finalResultType.typeSymbol.name.toString
    c.Expr[String](Literal(Constant(shown)))
  }
}
