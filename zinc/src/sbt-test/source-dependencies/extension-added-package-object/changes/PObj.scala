package a

package object b {
  implicit class BOps(t: a.T) { def m: String = "" }
}
