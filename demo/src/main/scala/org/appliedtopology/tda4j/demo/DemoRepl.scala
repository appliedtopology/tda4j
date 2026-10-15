package org.appliedtopology.tda4j.demo

import dotty.tools.repl.ReplDriver

import java.util.logging.{Level, Logger}

/** The Scala 3 REPL with tda4j on its classpath, for the demo jar.
  *
  * The imports below are run before the first prompt; the banner says so, so that nobody wonders where `Persistence`
  * came from. Anything on the command line goes to the REPL as a compiler setting and comes after our defaults, so it
  * wins (`-Xrepl-print-height:100` for longer output).
  */
object DemoRepl:
  private val predef: String =
    """import scala.language.experimental.modularity
      |import cats.syntax.show.*
      |import org.appliedtopology.tda4j.*
      |import org.appliedtopology.tda4j.plot.*
      |""".stripMargin

  // `java.util.logging` keeps its loggers weakly, so a level set on a logger nobody holds is forgotten. Public on
  // purpose: a private field that is never read is dropped by the compiler, and the loggers with it.
  val quietLoggers: List[Logger] =
    List("org.xnio", "org.jboss", "io.undertow").map { name =>
      val logger = Logger.getLogger(name)
      logger.setLevel(Level.WARNING)
      logger
    }

  def main(args: Array[String]): Unit =
    val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    Console.err.println(
      s"""TDA4j demo ($version) -- already imported:
         |  scala.language.experimental.modularity, cats.syntax.show.*, org.appliedtopology.tda4j.*, ...tda4j.plot.*
         |Try:  Plot.barcode(Persistence(points)).view()     println(someChain.show)     :quit
         |""".stripMargin
    )
    // `-usejavacp`: the REPL compiles against the classpath it runs on, which here is this jar.
    // `-Xrepl-print-height`: how many lines of a value the REPL echoes (its default, 50, floods a projector).
    val settings = Array("-usejavacp", "-Xrepl-print-height:20") ++ args
    ReplDriver(settings, Console.out, None, predef).tryRunning
