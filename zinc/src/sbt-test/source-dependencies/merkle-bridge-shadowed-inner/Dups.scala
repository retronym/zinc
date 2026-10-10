trait Dups {
  class BodyDuplicator(i: Int)
  def newBodyDuplicator(i: Int): BodyDuplicator = new BodyDuplicator(i)
}
