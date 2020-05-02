/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc

import java.nio.file.{ Files, Path, Paths }
import java.net.URLClassLoader
import java.util.jar.Manifest

import sbt.util.Logger
import sbt.util.InterfaceUtil._
import sbt.internal.inc.JavaInterfaceUtil.{ EnrichOption, EnrichOptional }
import xsbt.api.Discovery
import xsbti.{ FileConverter, Problem, Severity, VirtualFileRef, VirtualFile }
import xsbti.compile.{
  AnalysisContents,
  AnalysisStore,
  ClasspathOptionsUtil,
  CompileAnalysis,
  CompileOrder,
  DefinesClass,
  IncOptions,
  IncOptionsUtil,
  PerClasspathEntryLookup,
  PreviousResult,
  CompilerCache => XCompilerCache,
  Compilers => XCompilers
}
import sbt.io.IO
import sbt.io.syntax._
import sbt.io.DirectoryFilter
import java.lang.reflect.Method
import java.lang.reflect.Modifier.{ isPublic, isStatic }
import java.util.{ Optional, Properties }

import sbt.internal.inc.classpath.{ ClassLoaderCache, ClasspathUtil, ClasspathFilter }
import sbt.internal.scripted.{ StatementHandler, TestFailed }
import sbt.internal.util.ManagedLogger
import sjsonnew.support.scalajson.unsafe.{ Converter, Parser => JsonParser }

import scala.{ PartialFunction => ?=> }
import scala.collection.mutable
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.Success
import scala.util.control.NonFatal
import xsbti.compile.CompileProgress

final case class Project(
    name: String,
    dependsOn: Option[Vector[String]] = None,
    in: Option[Path] = None,
    scalaVersion: Option[String] = None
)
final case class Build(projects: Seq[Project])

final case class IncState(
    si: xsbti.compile.ScalaInstance,
    cs: XCompilers,
    number: Int,
    compilations: mutable.Map[ProjectStructure, (Future[Analysis], Future[Boolean])]
) {
  def inc: IncState = copy(number = number + 1, compilations = mutable.Map.empty)
}

class IncHandler(directory: Path, cacheDir: Path, scriptedLog: ManagedLogger, compileToJar: Boolean)
    extends BridgeProviderSpecification
    with StatementHandler {
  import scala.concurrent.ExecutionContext.Implicits._
  type State = Option[IncState]
  type IncCommand = (ProjectStructure, List[String], IncState) => Future[Unit]

  val incrementalCompiler = new IncrementalCompilerImpl

  def initialState: State = {
    initBuildStructure()
    None
  }

  def finish(state: State): Unit = {
    // Required so that next projects re-read the project structure
    buildStructure.clear()
    ()
  }
  val localBoot = Paths.get(sys.props("user.home")).resolve(".sbt").resolve("boot")
  val javaHome = Paths.get(sys.props("java.home"))
  val localCoursierCache = Vector(
    Paths.get(sys.props("user.home")).resolve(".coursier").resolve("cache"),
    Paths.get(sys.props("user.home")).resolve(".cache").resolve("coursier")
  )
  def rootPaths: Vector[Path] = Vector(directory, localBoot, javaHome) ++ localCoursierCache
  val converter = new MappedFileConverter(rootPaths, true)

  val buildStructure: mutable.Map[String, ProjectStructure] = mutable.Map.empty
  def initBuildStructure(): Unit = {
    val build = initBuild
    build.projects.foreach { p =>
      val in: Path = p.in.getOrElse(directory / p.name)
      val version = p.scalaVersion.getOrElse(scala.util.Properties.versionNumberString)
      val deps = p.dependsOn.toVector.flatten
      val project =
        ProjectStructure(
          p.name,
          deps,
          in,
          converter,
          scriptedLog,
          lookupProject,
          version,
          compileToJar,
          incrementalCompiler
        )
      buildStructure(p.name) = project
    }
  }

  final val RootIdentifier = "root"
  def initBuild: Build = {
    if (Files.exists(directory / "build.json")) {
      import sjsonnew._, BasicJsonProtocol._
      implicit val pathFormat = IsoString.iso[Path](_.toString, Paths.get(_))
      implicit val projectFormat =
        caseClass(Project.apply _, Project.unapply _)("name", "dependsOn", "in", "scalaVersion")
      implicit val buildFormat = caseClass(Build.apply _, Build.unapply _)("projects")
      // Do not parseFromFile as it leaves file open, causing problems on Windows.
      val json = {
        val channel = Files.newByteChannel(directory / "build.json")
        try {
          JsonParser.parseFromChannel(channel).get
        } finally {
          channel.close()
        }
      }
      Converter.fromJsonUnsafe[Build](json)
    } else Build(projects = Vector(Project(name = RootIdentifier).copy(in = Some(directory))))
  }

  def lookupProject(name: String): ProjectStructure = buildStructure(name)

  override def apply(command: String, arguments: List[String], state: State): State = {
    val splitCommands = command.split("/").toList
    // Note that root does not do aggregation as sbt does.
    val (project, commandToRun) = splitCommands match {
      case sub :: cmd :: Nil => buildStructure(sub) -> cmd
      case cmd :: Nil        => buildStructure(RootIdentifier) -> cmd
      case _                 => sys.error(s"The command is either empty or has more than one `/`: $command")
    }
    val runner = (ii: IncState) => commands(commandToRun)(project, arguments, ii)
    Some(onIncState(state, project)(runner))
  }

  def onIncState(i: Option[IncState], p: ProjectStructure)(
      run: IncState => Future[Unit]
  ): IncState = {
    val instance = i.getOrElse(onNewIncState(p))
    try {
      Await.result(run(instance), 60.seconds)
    } catch {
      case NonFatal(e) =>
        instance.compilations.clear()
        throw e
    }
    instance.inc
  }

  private final val noLogger = Logger.Null
  private[this] def onNewIncState(p: ProjectStructure): IncState = {
    val scalaVersion = p.scalaVersion
    val (compilerBridge, si) = IncHandler.getCompilerCacheFor(scalaVersion) match {
      case Some(alreadyInstantiated) =>
        alreadyInstantiated
      case None =>
        val compilerBridge = getCompilerBridge(cacheDir, noLogger, scalaVersion)
        val si = scalaInstance(scalaVersion, cacheDir, noLogger)
        val toCache = (compilerBridge, si)
        IncHandler.putCompilerCache(scalaVersion, toCache)
        toCache
    }
    val analyzingCompiler = scalaCompiler(si, compilerBridge)
    IncState(
      si,
      incrementalCompiler.compilers(si, ClasspathOptionsUtil.boot, None, analyzingCompiler),
      0,
      mutable.Map.empty
    )
  }

  private final val unit = (_: Seq[String]) => ()
  def scalaCompiler(instance: xsbti.compile.ScalaInstance, bridgeJar: Path): AnalyzingCompiler = {
    val bridgeProvider = ZincUtil.constantBridgeProvider(instance, bridgeJar)
    val classpath = ClasspathOptionsUtil.boot
    new AnalyzingCompiler(instance, bridgeProvider, classpath, unit, IncHandler.classLoaderCache)
  }

  lazy val commands: Map[String, IncCommand] = Map(
    noArgs("compile") { case (p, i) => p.compile(i).map(_ => ()) },
    noArgs("clean") { case (p, _)   => p.clean() },
    onArgs("checkIterations") {
      case (p, x :: Nil, i) => p.checkNumberOfCompilerIterations(i, x.toInt)
    },
    onArgs("checkRecompilations") {
      case (p, step :: classNames, i) => p.checkRecompilations(i, step.toInt, classNames)
    },
    onArgs("checkClasses") {
      case (p, src :: products, i) => p.checkClasses(i, dropRightColon(src), products)
    },
    onArgs("checkMainClasses") {
      case (p, src :: products, i) => p.checkMainClasses(i, dropRightColon(src), products)
    },
    onArgs("checkProducts") {
      case (p, src :: products, i) => p.checkProducts(i, dropRightColon(src), products)
    },
    onArgs("checkDependencies") {
      case (p, cls :: dependencies, i) => p.checkDependencies(i, dropRightColon(cls), dependencies)
    },
    noArgs("checkSame") { case (p, i)   => p.checkSame(i) },
    onArgs("run") { case (p, params, i) => p.run(i, params) },
    noArgs("package") { case (p, i)     => p.packageBin(i) },
    onArgs("checkWarnings") {
      case (p, count :: Nil, _) => p.checkMessages(count.toInt, Severity.Warn)
    },
    onArgs("checkWarning") {
      case (p, index :: expected :: Nil, _) => p.checkMessage(index.toInt, expected, Severity.Warn)
    },
    onArgs("checkErrors") {
      case (p, count :: Nil, _) => p.checkMessages(count.toInt, Severity.Error)
    },
    onArgs("checkError") {
      case (p, idx :: expected :: Nil, _) => p.checkMessage(idx.toInt, expected, Severity.Error)
    },
    noArgs("checkNoClassFiles") { case (p, _) => p.checkNoGeneratedClassFiles() }
  )

  private def dropRightColon(s: String) = if (s endsWith ":") s dropRight 1 else s

  private def onArgs(commandName: String)(
      pf: (ProjectStructure, List[String], IncState) ?=> Future[Unit]
  ): (String, IncCommand) =
    commandName ->
      ((p, xs, i) => {
        applyOrElse(pf, (p, xs, i), p.unrecognizedArguments(commandName, xs))
      })

  private def noArgs(commandName: String)(
      pf: (ProjectStructure, IncState) ?=> Future[Unit]
  ): (String, IncCommand) =
    commandName ->
      ((p, xs, i) => {
        applyOrElse(pf, (p, i), p.acceptsNoArguments(commandName, xs))
      })

  private def applyOrElse[A, B](pf: A ?=> B, x: A, fb: => B): B = pf.applyOrElse(x, (_: A) => fb)
}

case class ProjectStructure(
    name: String,
    dependsOn: Vector[String],
    baseDirectory: Path,
    converter: FileConverter,
    scriptedLog: ManagedLogger,
    lookupProject: String => ProjectStructure,
    scalaVersion: String,
    compileToJar: Boolean,
    incrementalCompiler: IncrementalCompilerImpl
) extends BridgeProviderSpecification {
  import scala.concurrent.ExecutionContext.Implicits._
  val maxErrors = 100
  val targetDir = baseDirectory / "target"
  // val targetDir = Paths.get("/tmp/pipelining") / name / "target"
  val classesDir = targetDir / "classes"
  val outputJar = if (compileToJar) Some(classesDir / "output.jar") else None
  val output = outputJar.getOrElse(classesDir)
  val earlyOutput = targetDir / "early" / "output.jar"
  if (!earlyOutput.toFile.getParentFile.exists()) {
    earlyOutput.toFile.getParentFile.mkdirs()
  }
  val generatedClassFiles = classesDir.toFile ** "*.class"
  val scalaSourceDirectory = baseDirectory / "src" / "main" / "scala"
  val javaSourceDirectory = baseDirectory / "src" / "main" / "java"
  def scalaSources: List[Path] =
    ((scalaSourceDirectory.toFile ** "*.scala").get.toList ++
      (baseDirectory.toFile * "*.scala").get.toList)
      .map(_.toPath)
  def javaSources: List[Path] =
    ((javaSourceDirectory.toFile ** "*.java").get.toList ++
      (baseDirectory.toFile * "*.java").get.toList)
      .map(_.toPath)
  val stamper = Stamps.timeWrapLibraryStamps(converter)
  val cacheFile = baseDirectory / "target" / "inc_compile.zip"
  val fileStore = FileAnalysisStore.binary(cacheFile.toFile)
  val cachedStore = AnalysisStore.cached(fileStore)
  val earlyCacheFile = baseDirectory / "target" / "early" / "inc_compile.zip"
  val earlyAnalysisStore = FileAnalysisStore.binary(earlyCacheFile.toFile)
  // val earlyCachedStore = AnalysisStore.cached(fileStore)

  // We specify the class file manager explicitly even though it's noew possible
  // to specify it in the incremental option property file (this is the default for sbt)
  val (incOptions, scalacOptions) = {
    val properties = loadIncProperties(baseDirectory / "incOptions.properties")
    val (incOptions0, sco) = loadIncOptions(properties)
    val storeApis = Option(properties.getProperty("incOptions.storeApis"))
      .map(_.toBoolean)
      .getOrElse(incOptions0.storeApis())
    val transactional: Optional[xsbti.compile.ClassFileManagerType] =
      Optional.of(
        xsbti.compile.TransactionalManagerType
          .of((targetDir / "classes.bak").toFile, sbt.util.Logger.Null)
      )
    val incO =
      incOptions0
        .withClassfileManagerType(transactional)
        .withStoreApis(storeApis)
    (incO, sco)
  }

  def prev(useCachedAnalysis: Boolean = true) = {
    val store = if (useCachedAnalysis) cachedStore else fileStore
    store.get.toOption match {
      case Some(contents) =>
        PreviousResult.of(Optional.of(contents.getAnalysis), Optional.of(contents.getMiniSetup))
      case _ => incrementalCompiler.emptyPreviousResult
    }
  }
  def earlyPreviousResult: PreviousResult = {
    val store = earlyAnalysisStore
    store.get.toOption match {
      case Some(contents) =>
        PreviousResult.of(Optional.of(contents.getAnalysis), Optional.of(contents.getMiniSetup))
      case _ => incrementalCompiler.emptyPreviousResult
    }
  }
  def unmanagedJars: List[Path] =
    ((baseDirectory / "lib").toFile ** "*.jar").get.toList.map(_.toPath)
  def dependsOnRef: Vector[ProjectStructure] = dependsOn map { lookupProject(_) }
  def internalClasspath: Vector[Path] = dependsOnRef flatMap { proj =>
    Vector(proj.classesDir) ++ proj.outputJar
  }

  def checkSame(i: IncState): Future[Unit] =
    cachedStore.get.toOption match {
      case Some(contents) =>
        val prevAnalysis = contents.getAnalysis.asInstanceOf[Analysis]
        compile(i) map { analysis =>
          analysis.apis.internal foreach {
            case (k, api) =>
              assert(api.apiHash == prevAnalysis.apis.internalAPI(k).apiHash)
          }
        }
      case _ => Future { () }
    }

  def clean(): Future[Unit] = Future { IO.delete(classesDir.toFile) }

  def checkNumberOfCompilerIterations(i: IncState, expected: Int): Future[Unit] =
    compile(i) map { analysis =>
      assert(
        (analysis.compilations.allCompilations.size: Int) == expected,
        "analysis.compilations.allCompilations.size = %d (expected %d)"
          .format(analysis.compilations.allCompilations.size, expected)
      )
      ()
    }

  def checkRecompilations(i: IncState, step: Int, expected: List[String]): Future[Unit] =
    compile(i) map { analysis =>
      val allCompilations = analysis.compilations.allCompilations
      val recompiledClasses: Seq[Set[String]] = allCompilations map { c =>
        val recompiledClasses = analysis.apis.internal.collect {
          case (className, api) if api.compilationTimestamp() == c.getStartTime => className
        }
        recompiledClasses.toSet
      }
      def recompiledClassesInIteration(iteration: Int, classNames: Set[String]) = {
        assert(
          recompiledClasses(iteration) == classNames,
          s"""${recompiledClasses(iteration)} != $classNames
           |allCompilations = ${allCompilations.mkString("\n  ")}""".stripMargin
        )
      }

      assert(step < allCompilations.size.toInt)
      recompiledClassesInIteration(step, expected.toSet)
      ()
    }

  def checkClasses(i: IncState, src: String, expected: List[String]): Future[Unit] =
    compile(i) map { analysis =>
      def classes(src: String): Set[String] =
        analysis.relations.classNames(converter.toVirtualFile(baseDirectory / src))
      def assertClasses(expected: Set[String], actual: Set[String]) =
        assert(
          expected == actual,
          s"Expected $expected classes, got $actual \n\n" + analysis.relations
        )
      assertClasses(expected.toSet, classes(src))
      ()
    }

  def checkMainClasses(i: IncState, src: String, expected: List[String]): Future[Unit] =
    compile(i) map { analysis =>
      def mainClasses(src: String): Set[String] =
        analysis.infos.get(converter.toVirtualFile(baseDirectory / src)).getMainClasses.toSet
      def assertClasses(expected: Set[String], actual: Set[String]) =
        assert(
          expected == actual,
          s"Expected $expected classes, got $actual\n\n" + analysis.infos.allInfos
        )

      assertClasses(expected.toSet, mainClasses(src))
      ()
    }

  def checkProducts(i: IncState, src: String, expected: List[String]): Future[Unit] =
    compile(i) map { analysis =>
      // def isWindows: Boolean = sys.props("os.name").toLowerCase.startsWith("win")
      // def relativeClassDir(f: File): File = f.relativeTo(classesDir) getOrElse f
      // def normalizePath(path: String): String = {
      //   if (isWindows) path.replace('\\', '/') else path
      // }
      def products(srcFile: String): Set[String] = {
        val productFiles = analysis.relations.products(converter.toVirtualFile(baseDirectory / src))
        productFiles.map { file: VirtualFileRef =>
          // if (JarUtils.isClassInJar(file)) {
          //   JarUtils.ClassInJar.fromPath(output.toPath).toClassFilePath
          // } else {
          //   normalizePath(relativeClassDir(file).getPath)
          // }
          file.id
        }
      }
      def assertClasses(expected: Set[String], actual: Set[String]) =
        assert(expected == actual, s"Expected $expected products, got $actual")

      assertClasses(expected.toSet, products(src))
      ()
    }

  def checkNoGeneratedClassFiles(): Future[Unit] =
    Future {
      val allPlainClassFiles = generatedClassFiles.get.toList.map(_.toString)
      val allClassesInJar: List[String] = outputJar.toList
        .filter(Files.exists(_))
        .flatMap(p => JarUtils.listClassFiles(p.toFile))
      if (allPlainClassFiles.nonEmpty || allClassesInJar.nonEmpty)
        sys.error(
          s"""classes exists: allPlainClassFiles = ${allPlainClassFiles
               .mkString("\n * ", "\n * ", "")}
                   |
                   |allClassesInJar = $allClassesInJar""".stripMargin
        )
      else ()
    }

  def checkDependencies(i: IncState, className: String, expected: List[String]): Future[Unit] =
    compile(i) map { analysis =>
      def classDeps(cls: String): Set[String] = analysis.relations.internalClassDep.forward(cls)
      def assertDependencies(expected: Set[String], actual: Set[String]) =
        assert(expected == actual, s"Expected $expected dependencies, got $actual")

      assertDependencies(expected.toSet, classDeps(className))
      ()
    }

  def run(i: IncState, params: Seq[String]): Future[Unit] =
    compile(i) map { analysis =>
      discoverMainClasses(Some(analysis.apis)) match {
        case Seq(mainClassName) =>
          val classpath: Array[Path] =
            ((i.si.allJars.map(_.toPath) :+ classesDir) ++ outputJar).map(_.toAbsolutePath)
          val loader = ClasspathUtil.makeLoader(classpath, i.si, baseDirectory)
          try {
            val main = getMainMethod(mainClassName, loader)
            invokeMain(loader, main, params)
          } finally {
            loader match {
              case f: ClasspathFilter => f.close()
            }
          }
        case Seq() =>
          throw new TestFailed(s"Did not find any main class")
        case s =>
          throw new TestFailed(s"Found more than one main class: $s")
      }
    }

  def compile(i: IncState): Future[Analysis] = startCompilation(i)._1
  def earlyArtifact(i: IncState): Future[Boolean] = startCompilation(i)._2

  def startCompilation(i: IncState): (Future[Analysis], Future[Boolean]) = synchronized {
    def traditionalLookupAnalysis: VirtualFile => Option[CompileAnalysis] = {
      val f0: PartialFunction[VirtualFile, Option[CompileAnalysis]] = {
        case x if converter.toPath(x).toAbsolutePath == classesDir.toAbsolutePath =>
          prev().analysis.toOption
      }
      val f1 = dependsOnRef.foldLeft(f0) { (acc, dep) =>
        acc orElse {
          case x if converter.toPath(x).toAbsolutePath == dep.classesDir.toAbsolutePath =>
            dep.prev().analysis.toOption
        }
      }
      f1 orElse { case _ => None }
    }
    def pipelinedLookupAnalysis: VirtualFile => Option[CompileAnalysis] = {
      val f0: PartialFunction[VirtualFile, Option[CompileAnalysis]] = {
        case x if converter.toPath(x).toAbsolutePath == classesDir.toAbsolutePath =>
          prev().analysis.toOption
      }
      val f1 = dependsOnRef.foldLeft(f0) { (acc, dep) =>
        acc orElse {
          case x
              if (converter.toPath(x).toAbsolutePath == dep.classesDir.toAbsolutePath)
                || (converter.toPath(x).toAbsolutePath == dep.earlyOutput.toAbsolutePath) =>
            dep.earlyPreviousResult.analysis().toOption
        }
      }
      f1 orElse { case _ => None }
    }

    i.compilations.get(this) match {
      case Some(x) => x
      case _ =>
        val notifyEarlyOutput: Promise[Boolean] = Promise[Boolean]()
        if (incOptions.pipelining) {
          // future of early outputs
          val earlyDeps: Future[Seq[Path]] = Future.sequence(dependsOnRef map { dep =>
            dep.earlyArtifact(i) map { success =>
              if (success) dep.earlyOutput
              else dep.output
            }
          })
          val futureAnalysis = earlyDeps.map(
            internapCp => doCompile(i, notifyEarlyOutput, internapCp, pipelinedLookupAnalysis)
          )
          // wait for the full compilation from the dependencies
          // during pipelining, downstream compilation may complete before the upstream
          // to avoid deletion of directories etc, we need to wait for the upstream to finish
          val f = for {
            _ <- Future.sequence(dependsOnRef map { _.compile(i) })
            a <- futureAnalysis
          } yield a
          i.compilations(this) = (f, notifyEarlyOutput.future)
          (f, notifyEarlyOutput.future)
        } else {
          val fullDeps = Future.sequence(dependsOnRef map { dep =>
            dep.compile(i)
          })
          val f =
            fullDeps.map(
              _ => doCompile(i, notifyEarlyOutput, internalClasspath, traditionalLookupAnalysis)
            )
          i.compilations(this) = (f, notifyEarlyOutput.future)
          (f, notifyEarlyOutput.future)
        }
    }
  }

  class PerClasspathEntryLookupImpl(
      am: VirtualFile => Option[CompileAnalysis],
      definesClassLookup: VirtualFile => DefinesClass
  ) extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: VirtualFile): Optional[CompileAnalysis] =
      am(classpathEntry).toOptional
    override def definesClass(classpathEntry: VirtualFile): DefinesClass =
      definesClassLookup(classpathEntry)
  }

  // For traditional compilation, internalCp would be the target directory or output JAR,
  // whereas for pipelining, you'd get an early JAR.
  private def doCompile(
      i: IncState,
      notifyEarlyOutput: Promise[Boolean],
      internalCp: Seq[Path],
      lookupAnalysis: VirtualFile => Option[CompileAnalysis]
  ): Analysis = {
    import i._
    val sources = scalaSources ++ javaSources
    val vs = sources.toList.map(converter.toVirtualFile)
    val entryLookup = new PerClasspathEntryLookupImpl(lookupAnalysis, Locate.definesClass)
    val reporter = new ManagedLoggedReporter(maxErrors, scriptedLog)
    val extra = Array(t2(("key", "value")))
    val previousResult = prev()
    val progress = new CompileProgress {
      override def startUnit(phase: String, unitPath: String): Unit = {
        // scriptedLog.debug(s"[zinc] start $phase $unitPath")
      }
      override def advance(current: Int, total: Int, prevPhase: String, nextPhase: String) = true
      override def earlyOutputComplete(success: Boolean) = {
        if (success) {
          scriptedLog.info(s"[progress] early output is done for $name!")
        } else {
          scriptedLog.info(s"[progress] early output can't be made for $name because macros!")
        }
        notifyEarlyOutput.complete(Success(success))
      }
    }
    val setup = incrementalCompiler.setup(
      entryLookup,
      skip = false,
      cacheFile,
      cache = XCompilerCache.fresh,
      incOptions,
      reporter,
      Some(progress),
      earlyAnalysisStore = Some(earlyAnalysisStore),
      extra = extra
    )

    val classpath: Seq[Path] =
      (i.si.allJars.toList.map(_.toPath) ++ (unmanagedJars :+ output) ++ internalCp)
    val cp = classpath.map(converter.toVirtualFile)
    val in = incrementalCompiler.inputs(
      cp.toArray,
      vs.toArray,
      output,
      Some(earlyOutput),
      scalacOptions,
      javacOptions = Array(),
      maxErrors,
      sourcePositionMappers = Array(),
      CompileOrder.Mixed,
      cs,
      setup,
      previousResult,
      temporaryClassesDirectory = Optional.empty(),
      converter,
      stamper
    )
    val result = incrementalCompiler.compile(in, scriptedLog)
    val analysis = result.analysis match { case a: Analysis => a }
    cachedStore.set(AnalysisContents.create(analysis, result.setup))
    scriptedLog.info(s"""$name: compilation done: ${sources.toList.mkString(", ")}""")
    analysis
  }

  def packageBin(i: IncState): Future[Unit] =
    compile(i) map { _ =>
      val targetJar = targetDir / s"$name.jar"
      outputJar match {
        case Some(currentJar) =>
          IO.copy(Seq(currentJar.toFile -> targetJar.toFile))
          ()
        case None =>
          val manifest = new Manifest
          val sources =
            (classesDir.toFile ** -DirectoryFilter).get flatMap { x =>
              IO.relativize(classesDir.toFile, x) match {
                case Some(path) => List((x, path))
                case _          => Nil
              }
            }
          IO.jar(sources, targetJar.toFile, manifest, Some(0L))
      }
    }

  def unrecognizedArguments(commandName: String, args: List[String]): Future[Unit] =
    scriptError("Unrecognized arguments for '" + commandName + "': '" + spaced(args) + "'.")

  def acceptsNoArguments(commandName: String, args: List[String]): Future[Unit] =
    scriptError(
      "Command '" + commandName + "' does not accept arguments (found '" + spaced(args) + "')."
    )

  def spaced[T](l: Seq[T]): String = l.mkString(" ")

  def scriptError(message: String): Future[Unit] = Future {
    sys.error("Test script error: " + message)
  }

  def discoverMainClasses(apisOpt: Option[APIs]): Seq[String] =
    apisOpt match {
      case Some(apis) =>
        def companionsApis(c: xsbti.api.Companions): Seq[xsbti.api.ClassLike] =
          Seq(c.classApi, c.objectApi)
        val allDefs = apis.internal.values.flatMap(x => companionsApis(x.api)).toSeq
        Discovery
          .applications(allDefs)
          .collect({ case (definition, discovered) if discovered.hasMain => definition.name })
          .sorted
      case None => Nil
    }

  // Taken from Run.scala in sbt/sbt
  def getMainMethod(mainClassName: String, loader: ClassLoader) = {
    val mainClass = Class.forName(mainClassName, true, loader)
    val method = mainClass.getMethod("main", classOf[Array[String]])
    // jvm allows the actual main class to be non-public and to run a method in the non-public class,
    //  we need to make it accessible
    method.setAccessible(true)
    val modifiers = method.getModifiers
    if (!isPublic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not public")
    if (!isStatic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not static")
    method
  }

  def invokeMain(loader: ClassLoader, main: Method, options: Seq[String]): Unit = {
    val currentThread = Thread.currentThread
    val oldLoader = Thread.currentThread.getContextClassLoader
    currentThread.setContextClassLoader(loader)
    try {
      main.invoke(null, options.toArray[String]); ()
    } finally {
      currentThread.setContextClassLoader(oldLoader)
    }
    ()
  }

  def loadIncProperties(src: Path): Properties = {
    if (Files.exists(src)) {
      val properties = new Properties()
      val stream = Files.newInputStream(src)
      try {
        properties.load(stream)
      } finally {
        stream.close()
      }
      properties
    } else new Properties()
  }

  def loadIncOptions(properties: Properties): (IncOptions, Array[String]) = {
    import scala.collection.JavaConverters._
    val map = new java.util.HashMap[String, String]
    properties.asScala foreach { case (k: String, v: String) => map.put(k, v) }

    val incOptions = {
      val opts = IncOptionsUtil.fromStringMap(map, scriptedLog)
      if (opts.recompileAllFraction() != IncOptions.defaultRecompileAllFraction()) opts
      else opts.withRecompileAllFraction(1.0)
    }
    val scalacOptions: List[String] =
      Option(map.get("scalac.options")).toList
        .flatMap(_.toString.split(" +").toList)
    (incOptions, scalacOptions.toArray)
  }

  def getProblems(): Seq[Problem] =
    cachedStore.get.toOption match {
      case Some(analysisContents) =>
        val analysis = analysisContents.getAnalysis.asInstanceOf[Analysis]
        val allInfos = analysis.infos.allInfos.values.toSeq
        allInfos flatMap (i => i.getReportedProblems ++ i.getUnreportedProblems)
      case _ =>
        Nil
    }

  def checkMessages(expected: Int, severity: Severity): Future[Unit] =
    Future {
      val messages = getProblems() filter (_.severity == severity)
      assert(
        messages.toList.length == expected,
        s"""Expected $expected messages with severity $severity but ${messages.length} found:
                                           |${messages mkString "\n"}""".stripMargin
      )
      ()
    }

  def checkMessage(index: Int, expected: String, severity: Severity): Future[Unit] =
    Future {
      val problems = getProblems() filter (_.severity == severity)
      problems lift index match {
        case Some(problem) =>
          assert(
            problem.message contains expected,
            s"""'${problem.message}' doesn't contain '$expected'."""
          )
        case None =>
          throw new TestFailed(
            s"Problem not found: $index (there are ${problems.length} problem with severity $severity)."
          )
      }
      ()
    }
}

object IncHandler {
  type Cached = (Path, xsbti.compile.ScalaInstance)
  private[this] final val scriptedCompilerCache = new mutable.WeakHashMap[String, Cached]()
  def getCompilerCacheFor(scalaVersion: String): Option[Cached] =
    synchronized(scriptedCompilerCache.get(scalaVersion))
  def putCompilerCache(scalaVersion: String, cached: Cached): Option[Cached] =
    synchronized(scriptedCompilerCache.put(scalaVersion, cached))

  private[internal] final val classLoaderCache = Some(
    new ClassLoaderCache(new URLClassLoader(Array()))
  )
}
