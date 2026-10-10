package c

object Foo { val v: Int = 3 }

object Far { def v: Int = Foo.v }
