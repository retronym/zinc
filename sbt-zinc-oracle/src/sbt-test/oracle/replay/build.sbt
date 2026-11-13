import scala.sys.process._

ThisBuild / scalaVersion := "2.13.18"

lazy val a = project
lazy val b = project.dependsOn(a)

lazy val makeHistory = taskKey[Unit]("makes this directory a git repository with a history of edits to A.scala")
lazy val root = (project in file(".")).aggregate(a, b).settings(history, replayed)

def history = makeHistory := {
  val base = (ThisBuild / baseDirectory).value
  def git(args: String*): Unit = {
    val command = Seq("git", "-c", "user.name=test", "-c", "user.email=test@example.invalid") ++ args
    assert(Process(command, base).! == 0, args.mkString(" "))
  }
  IO.write(base / "project" / "build.properties", s"sbt.version=${sbtVersion.value}\n")
  IO.write(base / ".gitignore", "target/\nproject/plugins.sbt\n")
  git("init", "-q", ".")
  git("add", "-A")
  git("commit", "-q", "-m", "base")
  val edits = Seq(
    "a body edit" -> "class A { def f: Int = 2 }\n",
    "an API edit" -> "class A { def f: Long = 2L }\n",
    "does not compile" -> "class A { def f: Int = \"\" }\n",
    "compiles again" -> "class A { def f: Int = 3 }\n",
  )
  for ((subject, content) <- edits) {
    IO.write(base / "a" / "src" / "main" / "scala" / "A.scala", content)
    git("commit", "-q", "-a", "-m", subject)
  }
}

lazy val checkReplay = taskKey[Unit]("the replay's verdicts, one per step")
def replayed = checkReplay := {
  val out = (ThisBuild / baseDirectory).value / "target" / "inc-oracle" / "replay"
  val rows = IO.readLines(out / "work" / "steps.jsonl").filter(_.contains("\"mode\":\"sbt\""))
  val verdicts = rows.map(r => """"verdict":"([^"]*)"""".r.findFirstMatchIn(r).get.group(1))
  assert(verdicts == List("same", "same", "same", "same-fail", "same"), verdicts)
  assert((out / "report.md").isFile)
}
