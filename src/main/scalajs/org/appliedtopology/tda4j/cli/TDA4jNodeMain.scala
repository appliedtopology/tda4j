package org.appliedtopology.tda4j.cli

import scala.scalajs.js

/** The entry point of the `tda4j` command on Node.js: the linker puts it in `cli.mjs` (`build.sbt`), and the arguments
  * after the script name go to [[TDA4jCLI]] as they are on the JVM. The JVM build starts [[TDA4jCLI.main]] itself.
  */
object TDA4jNodeMain:
  def run(): Unit =
    val argv = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]]
    TDA4jCLI.main(argv.drop(2).toArray)
