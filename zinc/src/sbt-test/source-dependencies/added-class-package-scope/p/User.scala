package c

object Foo { val v: Int = 2 }

object User { def v: Int = Foo.v }
