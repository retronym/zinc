ThisBuild / scalaVersion := "2.13.18"

lazy val a = project
lazy val b = project.dependsOn(a)
lazy val root = (project in file(".")).aggregate(a, b)

val check = inputKey[Unit]("check PROJECT CONFIG VERDICT [compiled COUNT]: what the last incVerify said")
check := {
  val args = complete.DefaultParsers.spaceDelimited("<arg>").parsed
  val file = (ThisBuild / baseDirectory).value / "target" / "inc-oracle" / "results" / "verify" / s"${args(0)}-${args(1)}.json"
  val json = IO.read(file)
  assert(json.contains(s""""verdict":"${args(2)}""""), json)
  if (args.length > 3) {
    val compiled = """"compiled":\[([^\]]*)\]""".r.findFirstMatchIn(json).get.group(1)
    val count = if (compiled.isEmpty) 0 else compiled.split(",").length
    assert(count == args(4).toInt, json)
  }
}
