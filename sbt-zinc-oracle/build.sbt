ThisBuild / organization := "org.scala-sbt"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val oracleSources = settingKey[File]("The Java sources of Zinc's zinc-oracle module")

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-zinc-oracle",
    scalaVersion := "2.12.21",
    crossScalaVersions := Seq("2.12.21", "3.8.4"),
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.9.9"
        case _      => "2.0.10"
      }
    },
    oracleSources := baseDirectory.value.getParentFile / "internal" / "zinc-oracle" / "src" / "main" / "java",
    Compile / unmanagedSourceDirectories += oracleSources.value,
    Compile / javacOptions ++= Seq("--release", "17"),
    compileOrder := CompileOrder.JavaThenScala,
    Compile / doc / sources := Nil,
    libraryDependencies ++= Seq(
      "org.ow2.asm" % "asm" % "9.9.1",
      "org.ow2.asm" % "asm-util" % "9.9.1",
      "com.eclipsesource.minimal-json" % "minimal-json" % "0.9.5",
    ),
    Compile / resourceGenerators += Def.task {
      val f = (Compile / resourceManaged).value / "sbt-zinc-oracle.properties"
      IO.write(f, s"organization=${organization.value}\nname=${name.value}\nversion=${version.value}\n")
      Seq(f)
    }.taskValue,
    scriptedLaunchOpts ++= Seq("-Xmx1g", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
  )
