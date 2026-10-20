class C(val log: String) {
  var extra: String = ""
  def +(s: String): C = new C(log + s)
}
