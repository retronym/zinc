import java.lang.reflect.{ InvocationTargetException, Method, Modifier }

object Client {
  def sig(m: Method): String =
    m.getParameterTypes.map(_.getName).mkString(s"${m.getName}(", ",", s")${m.getReturnType.getName}")

  def sigs(c: Class[?], static: Boolean): List[String] =
    c.getDeclaredMethods.toList
      .filter(m => m.getName == "f" && Modifier.isStatic(m.getModifiers) == static)
      .map(sig)
      .sorted

  def main(args: Array[String]): Unit = {
    val owner = O
    val inT = sigs(classOf[T], static = false)
    val forwarders = sigs(O.getClass, static = false)
    val staticForwarders = sigs(Class.forName("O"), static = true)
    val stale = O.getClass.getDeclaredMethods.toList.filter(m => m.getName == "f" && !inT.contains(sig(m)))
    val calls = stale.map { m =>
      try { m.invoke(owner, (new V(1): Any).asInstanceOf[AnyRef]); s"${sig(m)} returned" }
      catch { case e: InvocationTargetException => s"${sig(m)} threw ${e.getCause}" }
    }
    assert(
      forwarders == inT && staticForwarders == inT,
      s"T declares $inT; the forwarders $forwarders, static forwarders $staticForwarders; calling the stale ones: $calls"
    )
  }
}
