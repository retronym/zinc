object C {
  def x(o: Outer): Int = new o.Sub().hashCode
}
