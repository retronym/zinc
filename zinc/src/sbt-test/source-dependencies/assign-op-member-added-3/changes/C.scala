class C(val log: String) {
  var extra: String = ""
  def +(s: String): C = new C(log + s)
  def +=(s: String): Unit = extra = "mutated"
}
