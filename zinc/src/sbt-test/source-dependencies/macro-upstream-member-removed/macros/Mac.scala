package p

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Mac {
  /** The members of `T` declared in this package, with their types as seen from `T`. */
  def members[T]: String = macro impl[T]
  def impl[T: c.WeakTypeTag](c: Context): c.Tree = {
    import c.universe._
    val t = weakTypeOf[T]
    val ms = t.members.toList.filter(_.owner.fullName.startsWith("p."))
      .map(s => s.name.decodedName.toString + ": " + s.typeSignatureIn(t).toString).sorted
    Literal(Constant(ms.mkString(", ")))
  }
}
