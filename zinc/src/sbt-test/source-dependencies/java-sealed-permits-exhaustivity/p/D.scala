object D {
  def name(s: Shape): String = s match {
    case _: Circle => "circle"
    case _: Square => "square"
  }
}
