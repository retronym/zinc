import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Macros {
  def fieldsOf(x: Any): String = macro MacrosImpl.fieldsOfImpl
  def fieldsDeep[T]: String = macro MacrosImpl.fieldsDeepImpl[T]
}

object MacrosImpl {
  private def fields(c: Context)(tpe: c.Type): List[c.universe.MethodSymbol] = {
    import c.universe._
    tpe.decls.sorted.collect { case m: MethodSymbol if m.isGetter && m.isPublic => m }
  }

  def fieldsOfImpl(c: Context)(x: c.Expr[Any]): c.Expr[String] = {
    import c.universe._
    val names = fields(c)(x.actualType).map(_.name.decodedName.toString.trim)
    c.Expr[String](Literal(Constant(names.mkString(","))))
  }

  def fieldsDeepImpl[T: c.WeakTypeTag](c: Context): c.Expr[String] = {
    import c.universe._
    val s = fields(c)(weakTypeOf[T]).map { f =>
      val inner = fields(c)(f.typeSignature.finalResultType).map(_.name.decodedName.toString.trim)
      f.name.decodedName.toString.trim + inner.mkString("(", ",", ")")
    }
    c.Expr[String](Literal(Constant(s.mkString(","))))
  }
}
