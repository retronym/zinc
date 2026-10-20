package a.b.c

object Single { def v: Int = a.Foo.v + Foo.v }

object Foo { val v: Int = 4 }
