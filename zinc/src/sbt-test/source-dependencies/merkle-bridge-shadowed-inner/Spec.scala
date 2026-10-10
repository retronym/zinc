class Spec {
  class Duplicator extends { val x = 1 } with Dups {
    final type SpecializeBodyDuplicator = BodyDuplicator
    @deprecated("use SpecializeBodyDuplicator instead", "1.0")
    class BodyDuplicator(i: Int) extends super.BodyDuplicator(i)
    override def newBodyDuplicator(i: Int): SpecializeBodyDuplicator =
      new SpecializeBodyDuplicator(i)
  }
  class SpecializationDuplicator extends Duplicator
}
