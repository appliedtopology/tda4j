package org.appliedtopology.tda4j.demo

// Every file that names the library needs this (its definitions are experimental: the `C: Field` context bounds).
import scala.language.experimental.modularity

import dotty.tools.repl.{ParseResult, ReplDriver, State}
import org.appliedtopology.tda4j.plot.{Plot, Theme}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** The demo REPL driven one input at a time, for the web page: the same compiler, the same imports and the same session
  * state as [[DemoRepl]], which owns a terminal. [[WebRepl]] is what the page calls; it loads this object in a class
  * loader of its own and passes every call on.
  *
  * A browser tab has no sockets, so the live viewer cannot start there: a plot that a line `view()`s is handed to the
  * page with `Plot.viewWith`.
  */
object WebSession:
  private val buffer = ByteArrayOutputStream()
  private val out = PrintStream(buffer, true, StandardCharsets.UTF_8)
  private val plots = mutable.Queue.empty[String]
  private var session: Option[(ReplDriver, State)] = None

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
    case None => "The session has not started."
    case Some((driver, state)) =>
      buffer.reset()
      val next =
        try
          Plot.viewWith(plot => plots.enqueue(plot.html(Theme.Auto))) {
            Console.withOut(out)(Console.withErr(out)(driver.run(input)(using state)))
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
