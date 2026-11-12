package a

class P extends Q
object P {
  implicit class POps(t: P) { def m: String = "" }
}
