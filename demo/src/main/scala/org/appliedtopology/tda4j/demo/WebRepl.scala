package org.appliedtopology.tda4j.demo

// Every file that names the library needs this (its definitions are experimental: the `C: Field` context bounds).
import scala.language.experimental.modularity

import dotty.tools.repl.{ParseResult, ReplDriver, State}
import org.appliedtopology.tda4j.Parallel
import org.appliedtopology.tda4j.plot.{Plot, Theme}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** The demo REPL driven one input at a time, for the web page (`web/index.html`): the same compiler, the same imports
  * and the same session state as [[DemoRepl]], which owns a terminal. The page runs this jar in CheerpJ, a Java runtime
  * in the browser, and calls the methods below by name; they take and return strings and numbers only, which cross from
  * JavaScript to Java as they are.
  *
  *   - `start(classpath)` once: the jars as the page's Java runtime sees them, in class path form (`a.jar:b.jar`).
  *   - `isIncomplete(input)`: the page asks before sending, and keeps the prompt open for more lines when it is.
  *   - `eval(input)`: runs it, returns what a terminal would have shown.
  *   - `nextPlot()`: each plot the last input `view()`ed, as a page to put in a frame; empty when there are no more.
  *
  * A browser tab has no sockets, so the live viewer cannot start there: a plot that a line `view()`s is handed to the
  * page with `Plot.viewWith`. Every input runs on one thread, the only kind of run that is reliable in the page's
  * runtime.
  */
object WebRepl:
  private val buffer = ByteArrayOutputStream()
  private val out = PrintStream(buffer, true, StandardCharsets.UTF_8)
  private val plots = mutable.Queue.empty[String]
  private var session: Option[(ReplDriver, State)] = None

  /** The library version in this jar, for the page's heading. */
  def version(): String = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Starts the compiler on `classpath` and runs the imports. Returns what they printed: nothing, unless one failed. */
  def start(classpath: String): String =
    // The user's lines must run on the classes this object runs on: one copy of the library, so that the target
    // `Plot.viewWith` sets below is the one the user's `view()` asks, and so that the page's slow runtime loads and
    // warms every class once. Two defaults of the driver each make a second copy:
    //   - its class loader, a new one over `classpath`: this object's own loader is passed instead;
    //   - `-Xrepl-interrupt-instrumentation`, which defines every class a line touches again, rewritten to stop on
    //     Ctrl-C. A page has no Ctrl-C to send.
    // No `-usejavacp`: the runtime's own class path is not the jar. `-color:never`: the page shows plain text.
    val settings = Array(
      "-classpath",
      classpath,
      "-color:never",
      "-Xrepl-print-height:20",
      "-Xrepl-interrupt-instrumentation:false"
    )
    val driver = ReplDriver(settings, out, Some(getClass.getClassLoader), "")
    session = Some((driver, driver.initialState))
    eval(DemoRepl.predef)

  /** Whether `input` is the beginning of something and not a whole input: an open brace, a `def` with no body yet, or
    * an indented block that no empty line has closed (the compiler's own rule, the one the terminal REPL follows).
    */
  def isIncomplete(input: String): Boolean = session match
    case Some((_, state)) => ParseResult.isIncomplete(input)(using state.context)
    case None             => false

  /** Runs `input` in the session and returns what a terminal would have shown: the value, what the line printed, an
    * error.
    */
  def eval(input: String): String = session match
    case None                  => "The session has not started."
    case Some((driver, state)) =>
      buffer.reset()
      val next =
        try
          Plot.viewWith(plot => plots.enqueue(plot.html(Theme.Auto))) {
            // On one thread, whatever the runtime reports. Fork-join threads never finish in the page's runtime:
            // while the library counted two threads on its one processor, `Persistence(points)` came back in 3 of
            // 10 sessions. The library counts one there now, which alone was enough (8 of 8); this keeps the page
            // on one thread if the runtime ever reports more processors
            // (`.claude/WORKLOG-web-playground-build.md`, `.claude/WORKLOG-one-processor-threads.md`).
            Parallel.sequentially {
              Console.withOut(out)(Console.withErr(out)(driver.run(input)(using state)))
            }
          }
        catch
          case failure: Throwable =>
            // The driver reports the user's errors itself; this is the driver failing. The line's wrapper object may
            // have been loaded half-way, so its number is not used again (the next line would get the broken class).
            out.println(s"The REPL could not finish this input: $failure")
            state.copy(
              objectIndex = state.objectIndex + 1,
              invalidObjectIndexes = state.invalidObjectIndexes + state.objectIndex
            )
      session = Some((driver, next))
      out.flush()
      buffer.toString(StandardCharsets.UTF_8)

  /** The next plot the last input `view()`ed, as an HTML page, or the empty string when every one has been taken. */
  def nextPlot(): String = if plots.isEmpty then "" else plots.dequeue()

  /** The session from a terminal, one argument per input, as the page would drive it: for checking the driver without a
    * browser, with the page's class path (`sbt webSite` writes the jars; run it in that folder):
    * {{{
    * cp=tda4j-demo-web-patch.jar:tda4j-demo-project.jar:tda4j-demo-libs.jar
    * java -cp $cp org.appliedtopology.tda4j.demo.WebRepl $cp '1 + 1'
    * }}}
    */
  def main(args: Array[String]): Unit =
    print(start(args.head))
    for input <- args.tail do
      println(s"scala> $input${if isIncomplete(input) then "   (incomplete without an empty line)" else ""}")
      print(eval(input))
      Iterator
        .continually(nextPlot())
        .takeWhile(_.nonEmpty)
        .foreach(page => println(s"[a plot: ${page.length} characters]"))
