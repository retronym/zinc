object Ex {
  def unapplySeq(s: String): Option[Seq[String]] = Some(Seq("seq", s))
}
