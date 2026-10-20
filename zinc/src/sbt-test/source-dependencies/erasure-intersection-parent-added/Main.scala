package p
object Main {
  def main(args: Array[String]): Unit = {
    val rs = Seq("p.A", "p.O").map(c => c -> Class.forName(c).getMethod("m").getReturnType.getName)
    assert(rs.forall(_._2 == args(0)), rs)
  }
}
