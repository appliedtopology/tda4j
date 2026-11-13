package org.appliedtopology.tda4j.demo

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.{URL, URLClassLoader}

/** What the web page (`web/index.html`) calls. The page runs this jar in CheerpJ, a Java runtime in the browser, and
  * calls the methods below by name; they take and return strings and numbers only, which cross from JavaScript to Java
  * as they are.
  *
  *   - `start(classpath)` once: the jars as the page's Java runtime sees them, in class path form (`a.jar:b.jar`).
  *   - `isIncomplete(input)`: the page asks before sending, and keeps the prompt open for more lines when it is.
  *   - `eval(input)`: runs it, returns what a terminal would have shown.
  *   - `nextPlot()`: each plot the last input `view()`ed, as a page to put in a frame; empty when there are no more.
  *
  * The work is [[WebSession]]'s. This object only gives it a class loader: a plain `URLClassLoader` over `classpath`,
  * in which the compiler, the library and the user's lines all live. Run in the loader the page's runtime gives this
  * object itself, the first line that needed a class of the library not loaded yet never came back.
  */
object WebRepl:
  private var session: Option[Class[?]] = None

  /** The library version in this jar, for the page's heading. */
  def version(): String = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Starts the session on `classpath`. Returns what the imports printed: nothing, unless one failed. */
  def start(classpath: String): String =
    val jars: Array[URL] = classpath.split(File.pathSeparatorChar).map(File(_).toURI.toURL)
    // The parent is the platform loader, not this object's own: nothing of the session may come from there.
    val loader = URLClassLoader(jars, ClassLoader.getPlatformClassLoader)
    session = Some(Class.forName("org.appliedtopology.tda4j.demo.WebSession", true, loader))
    call("start", classpath).asInstanceOf[String]

  def isIncomplete(input: String): Boolean = call("isIncomplete", input).asInstanceOf[java.lang.Boolean]

  def eval(input: String): String = call("eval", input).asInstanceOf[String]

  def nextPlot(): String = call("nextPlot").asInstanceOf[String]

  // `WebSession` in the session's loader is another class than the one this file could name, so its methods are found
  // by name. It is an object: each method has a static twin on the class.
  private def call(method: String, arguments: String*): AnyRef =
    val target = session.getOrElse(throw IllegalStateException("start(classpath) has not been called"))
    try target.getMethod(method, arguments.map(_ => classOf[String])*).invoke(null, arguments*)
    catch case wrapped: InvocationTargetException => throw wrapped.getCause

  /** The session from a terminal, one argument per input, as the page would drive it: for checking the driver without a
    * browser (`java -cp tda4j-demo.jar org.appliedtopology.tda4j.demo.WebRepl tda4j-demo.jar '1 + 1'`).
    */
  def main(args: Array[String]): Unit =
    print(start(args.head))
    for input <- args.tail do
      println(s"scala> $input${if isIncomplete(input) then "   (incomplete without an empty line)" else ""}")
      print(eval(input))
      Iterator.continually(nextPlot()).takeWhile(_.nonEmpty).foreach(page => println(s"[a plot: ${page.length} characters]"))
