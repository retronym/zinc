object Ex {
  def unapplySeq(s: String): Option[Seq[String]] = Some(Seq("seq", s))
  def unapply(s: String): Option[(String, String)] = Some(("fixed", s))
}
