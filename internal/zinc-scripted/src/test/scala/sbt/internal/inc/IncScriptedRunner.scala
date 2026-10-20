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

package sbt.internal.inc

import java.nio.file.Path
import java.util.concurrent.{ Callable, Executors, ThreadFactory }
import java.util.concurrent.atomic.AtomicInteger

import sbt.internal.scripted.{ HandlersProvider, ListTests, ScriptedTest }
import sbt.io.syntax._
import sbt.io.IO
import sbt.util.{ Level, Logger }

import sbt.inc.ScriptedMain._

object ScriptedRunnerImpl {
  private[this] val random = new java.util.Random()

  /**
   * Runs the tests on `parallelism` threads, each of which takes the next test as soon as it is
   * done with the last, so that a few slow tests do not hold up the rest.
   */
  def run(
      baseDir: Path,
      bufferLog: Boolean,
      tests: Seq[ScriptedTest],
      handlersProvider: HandlersProvider,
      parallelism: Int
  ): Unit = {
    val globalLogger = sbt.internal.util.ConsoleLogger()
    val logsDir = IO.temporaryDirectory / s"scripted-logs-${Integer.toHexString(random.nextInt())}"
    IO.createDirectory(logsDir)
    val outLevel = Level.Debug // if (bufferLog) Level.Info else Level.Debug
    val runner = new ScriptedTests(baseDir, bufferLog, outLevel, handlersProvider, logsDir.toPath)
    val scriptedTests = if (tests.isEmpty) listTests(baseDir, globalLogger) else tests
    val scriptedRunners = runner.scriptedRunners(scriptedTests)
    val pool = Executors.newFixedThreadPool(math.max(parallelism, 1), threadFactory)
    try {
      ThreadLocalOut.install {
        val results = scriptedRunners.map { r =>
          pool.submit(new Callable[Option[String]] { def call(): Option[String] = r() })
        }
        reportErrors(results.flatMap(_.get()), scriptedRunners.size)
      }
    } finally {
      pool.shutdownNow()
      globalLogger.info(s"Log files of all scripted tests run: ${logsDir.absolutePath}")
    }
  }

  private val threadFactory: ThreadFactory = {
    val ids = new AtomicInteger
    new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"scripted-${ids.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
  }

  private def reportErrors(failed: Seq[String], size: Int): Unit = {
    if (failed.nonEmpty) {
      val msg =
        s"""${failed.size} (out of $size) scripted tests failed:
           |${failed.mkString(s"${IO.Newline}\t", s"${IO.Newline}\t", IO.Newline)}""".stripMargin
      object ScriptedFailure extends RuntimeException(msg, null, false, false)
      throw ScriptedFailure
    }
  }

  def listTests(baseDir: Path, log: Logger): Seq[ScriptedTest] = {
    val foundScriptedTests = detectScriptedTests(baseDir.toFile)
    def isScriptedTest(test: ScriptedTest) = {
      foundScriptedTests(test.group).contains(test.name)
    }
    new ListTests(baseDir.toFile, isScriptedTest, log).listTests
  }
}
