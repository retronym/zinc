import sbt.*
import sbt.given
import Keys.*
import sbt.librarymanagement.LibraryManagementCodec.given

object ZincBuildUtil:
  @transient
  lazy val genTestResTask = TaskKey[Seq[File]]("gen-test-resources")

  def classesDigest(classesDir: File): String =
    val files = (classesDir ** "*.class").get().sortBy(_.getPath)
    val entries = files.map: f =>
      IO.relativize(classesDir, f).getOrElse(f.getPath) + ":" + Hash.toHex(Hash(f))
    Hash.toHex(Hash(entries.mkString("\n"))).take(16)

  // `timestamp` keys sbt's cache of compiled bridges (ZincComponentManager.stampedVersion).
  // Deriving it from the class files rather than the clock keeps the jar reproducible,
  // which lets downstream compiles hit sbt 2's action cache.
  def generateVersionFile(
      version: String,
      dir: File,
      s: TaskStreams,
      classesDir: File
  ): Seq[File] =
    val content = versionLine(version) + "\ntimestamp=" + classesDigest(classesDir)
    val f = dir / "incrementalcompiler.version.properties"
    if !f.exists || IO.read(f) != content then
      s.log.info("Writing version information to " + f + " :\n" + content)
      IO.write(f, content)
    f :: Nil
  end generateVersionFile
  def versionLine(version: String): String = "version=" + version

  def sampleProjectSettings(ext: String) =
    Seq(
      (Compile / scalaSource) := baseDirectory.value / "src",
      genTestResTask := {
        def resurcesDir = (file("zinc") / "src" / "test" / "resources" / "bin").getAbsoluteFile
        val target = resurcesDir / s"${name.value}.$ext"
        IO.copyFile(fileConverter.value.toPath((Compile / packageBin).value).toFile, target)
        Seq(target)
      }
    ) ++ relaxNon212

  def relaxNon212: Seq[Setting[?]] = Seq(
    scalaVersion := Dependencies.scala212,
    scalacOptions := {
      val old = scalacOptions.value
      scalaBinaryVersion.value match
        case "2.12" => old
        case _      =>
          old filterNot Set(
            "-Xfatal-warnings",
            "-deprecation",
            "-Ywarn-unused",
            "-Ywarn-unused-import"
          )
    }
  )

  import com.typesafe.tools.mima.core.*
  import com.typesafe.tools.mima.core.ProblemFilters.*
  def excludeInternalProblems =
    Seq(
      exclude[DirectMissingMethodProblem]("sbt.internal.*"),
      exclude[IncompatibleSignatureProblem]("sbt.internal.*"),
      exclude[IncompatibleMethTypeProblem]("sbt.internal.*"),
      exclude[ReversedMissingMethodProblem]("sbt.internal.*"),
      exclude[MissingClassProblem]("sbt.internal.*"),
      exclude[IncompatibleResultTypeProblem]("sbt.internal.*"),
      exclude[MissingTypesProblem]("sbt.internal.*"),
      exclude[InheritedNewAbstractMethodProblem](
        "sbt.internal.*"
      ),
      exclude[FinalClassProblem]("sbt.internal.*"),
      exclude[DirectAbstractMethodProblem]("sbt.internal.*"),
    )
end ZincBuildUtil
