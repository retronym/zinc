package p

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Mac {
  /** The names of the members of `T` declared in this package, private ones included. */
  def members[T]: String = macro impl[T]
  def impl[T: c.WeakTypeTag](c: Context): c.Tree = {
    import c.universe._
    val ms = weakTypeOf[T].members.toList.filter(_.owner.fullName.startsWith("p."))
    Literal(Constant(ms.map(_.name.decodedName.toString.trim).sorted.mkString(",")))
  }
}
