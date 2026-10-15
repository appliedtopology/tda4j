package org.appliedtopology.tda4j.demo

import dotty.tools.repl.ReplDriver

/** The Scala 3 REPL with tda4j on its classpath, for the demo jar.
  *
  * The three imports below are run before the first prompt; the banner says so, so that nobody wonders where
  * `Persistence` came from. Anything on the command line goes to the REPL as a compiler setting (`-Xprint:typer`, ...).
  */
object DemoRepl:
  private val predef: String =
    """import scala.language.experimental.modularity
      |import org.appliedtopology.tda4j.*
      |import org.appliedtopology.tda4j.plot.*
      |""".stripMargin

  def main(args: Array[String]): Unit =
    val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    Console.err.println(
      s"""TDA4j demo ($version) -- already imported:
         |  import scala.language.experimental.modularity
         |  import org.appliedtopology.tda4j.*
         |  import org.appliedtopology.tda4j.plot.*
         |Try:  Plot.barcode(Persistence(points)).view()      (:quit leaves)
         |""".stripMargin
    )
    // `-usejavacp`: the REPL compiles against the classpath it runs on, which here is this jar.
    ReplDriver(Array("-usejavacp") ++ args, Console.out, None, predef).tryRunning
