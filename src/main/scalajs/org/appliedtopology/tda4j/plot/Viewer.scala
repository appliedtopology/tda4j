package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import scala.scalajs.js

/** Where a plot goes when it is `view()`ed. The JVM build has a live viewer in a browser tab (`src/main/scalajvm`);
  * here a plot has two homes:
  *
  *   - In a browser page, `view()` appends the plot to the page, in a frame of its own, below the plots shown before.
  *   - In Node.js, `view()` writes the plot to an HTML file in the temporary directory and opens it in the default
  *     browser, or prints its path where there is no desktop (`TDA4J_PLOT_BROWSER=none` never opens one).
  */
object Viewer:
  /** Shows `plot` where this program runs (see above). */
  def show(plot: Plot): Unit =
    if js.typeOf(js.Dynamic.global.document) != "undefined" then appendToPage(plot)
    else writeAndOpen(plot)

  /** Opens `address` in the default browser from Node.js, unless browsers are turned off (`TDA4J_PLOT_BROWSER=none`).
    * Returns whether it started one; a browser page cannot open a local file, so there it returns `false`.
    */
  def browse(address: String): Boolean =
    val wanted = js.typeOf(js.Dynamic.global.process) != "undefined" &&
      js.Dynamic.global.process.env.TDA4J_PLOT_BROWSER.toString != "none"
    wanted && (try
      val platform = js.Dynamic.global.process.platform.toString
      val (command, arguments) = platform match
        case "darwin" => ("open", js.Array(address))
        case "win32"  => ("cmd", js.Array("/c", "start", "", address))
        case _        => ("xdg-open", js.Array(address))
      val child = js.Dynamic.global.process
        .getBuiltinModule("node:child_process")
        .spawn(command, arguments, js.Dynamic.literal(detached = true, stdio = "ignore"))
      child.on("error", (_: js.Any) => ())
      child.unref()
      true
    catch case _: js.JavaScriptException => false)

  private def appendToPage(plot: Plot): Unit =
    val existing = js.Dynamic.global.document.getElementById("tda4j-plots")
    val host =
      if js.isUndefined(existing) || (existing: Any) == null then
        val created = js.Dynamic.global.document.createElement("div")
        created.id = "tda4j-plots"
        js.Dynamic.global.document.body.appendChild(created)
        created
      else existing
    val frame = js.Dynamic.global.document.createElement("iframe")
    frame.srcdoc = plot.html(Theme.Auto)
    frame.style.cssText = "width:100%;height:520px;border:0;display:block"
    host.appendChild(frame)

  private def writeAndOpen(plot: Plot): Unit =
    val page = FileAccess.tempFile("tda4j-plot-", ".html")
    FileAccess.writeText(page, plot.html(Theme.Auto))
    if browse(FileAccess.fileUri(page)) then Console.err.println(s"TDA4j plots: $page (opened in your browser)")
    else Console.err.println(s"TDA4j plots: open $page in a browser")
