package org.appliedtopology.tda4j.plot

import io.undertow.Undertow
import org.xnio.{Xnio, XnioWorker}

import java.io.OutputStream
import java.net.{BindException, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CopyOnWriteArrayList, LinkedBlockingQueue, ThreadFactory, TimeUnit}
import scala.collection.mutable

/** The live viewer: one browser tab that shows every plot `view()`ed from this JVM, newest first, as it arrives.
  *
  * The first `view()` starts a small web server on `127.0.0.1` (port 8337, or a free one when that is taken; set
  * another with the system property `tda4j.plot.port` or the environment variable `TDA4J_PLOT_PORT`) and opens the page
  * in the default browser. Where there is no desktop to open it on (a remote machine, a container), it prints the
  * address instead: open it yourself, forwarding the port if needed (`ssh -L 8337:localhost:8337 host`). Set
  * `TDA4J_PLOT_BROWSER=none` (or the system property `tda4j.plot.browser=none`) to never open a browser. The page keeps
  * a list of every plot shown, and reconnects by itself when the JVM restarts on the same port.
  *
  * The server's threads are daemon threads: a REPL or script exits as usual, and the page then waits for a new one.
  * Nothing here uses Swing or AWT windows, so it works the same from `sbt console`, `scala` and scala-cli on any OS.
  */
object Viewer extends cask.MainRoutes:
  private final class Entry(val id: Int, val title: String, val page: String)

  private final class Server(val server: Undertow, val worker: XnioWorker, val port: Int):
    val entries: mutable.ArrayBuffer[Entry] = mutable.ArrayBuffer.empty
    val clients: CopyOnWriteArrayList[LinkedBlockingQueue[String]] = CopyOnWriteArrayList()
    def url: String = s"http://127.0.0.1:$port/"
    def stop(): Unit =
      try server.stop()
      finally worker.shutdown()

  @volatile private var running: Option[Server] = None

  /** The page's address, starting the server if it is not running yet. */
  def url: String = synchronized(ensure().url)

  /** Shows `plot` in the viewer's tab (starting the server and opening the tab the first time). */
  def show(plot: Plot): Unit = synchronized {
    val s = ensure()
    val e = s.entries.synchronized {
      val entry = Entry(s.entries.size + 1, plot.title, plot.html(Theme.Auto))
      s.entries += entry
      entry
    }
    s.clients.forEach(_.offer(e.id.toString))
  }

  /** Stops the server; the next `view()` starts a new one. */
  def stop(): Unit = synchronized {
    running.foreach(_.stop())
    running = None
  }

  private def configuredPort: Int =
    sys.props.get("tda4j.plot.port").orElse(sys.env.get("TDA4J_PLOT_PORT")).flatMap(_.toIntOption).getOrElse(8337)

  private val daemons: ThreadFactory = r =>
    val t = Thread(r, "tda4j-plot-viewer")
    t.setDaemon(true)
    t

  private def ensure(): Server =
    running.getOrElse {
      // Start Cask from a daemon thread so setup never keeps a REPL or script alive.
      var created: Either[Throwable, Server] = Left(IllegalStateException("viewer did not start"))
      val starter = daemons.newThread(() => created = scala.util.Try(start()).toEither)
      starter.start()
      starter.join()
      val s = created.fold(throw _, identity)
      running = Some(s)
      announce(s.url)
      s
    }

  private def start(): Server =
    try start(configuredPort)
    catch case error: Throwable if configuredPort != 0 && causedByBindException(error) => start(0)

  private def start(port: Int): Server =
    val worker = Xnio
      .getInstance()
      .createWorkerBuilder()
      .setDaemon(true)
      .setWorkerName("tda4j-plot-viewer")
      .setWorkerIoThreads(2)
      .setCoreWorkerPoolSize(1)
      .setMaxWorkerPoolSize(8)
      .build()
    val http = Undertow
      .builder()
      .setWorker(worker)
      .addHttpListener(port, "127.0.0.1")
      .setHandler(defaultHandler)
      .build()
    try
      http.start()
      val boundPort = http.getListenerInfo.get(0).getAddress.asInstanceOf[InetSocketAddress].getPort
      Server(http, worker, boundPort)
    catch
      case error: Throwable =>
        scala.util.Try(http.stop())
        worker.shutdown()
        throw error

  private def causedByBindException(error: Throwable): Boolean =
    var cause = error
    while cause != null do
      if cause.isInstanceOf[BindException] then return true
      cause = cause.getCause
    false

  private def activeServer: Server = running.getOrElse(throw IllegalStateException("viewer is not running"))

  @cask.get("/")
  def index(): cask.Response[String] =
    cask.Response(indexPage, headers = Seq("Content-Type" -> "text/html; charset=utf-8", "Cache-Control" -> "no-store"))

  @cask.get("/list")
  def list(): cask.Response[String] =
    cask.Response(
      listJson(activeServer),
      headers = Seq("Content-Type" -> "application/json; charset=utf-8", "Cache-Control" -> "no-store")
    )

  @cask.get("/plot/:id")
  def plot(id: String): cask.Response[String] =
    val page = activeServer.entries.synchronized {
      id.toIntOption.flatMap(i => activeServer.entries.find(_.id == i)).map(_.page)
    }
    page match
      case Some(html) =>
        cask.Response(html, headers = Seq("Content-Type" -> "text/html; charset=utf-8", "Cache-Control" -> "no-store"))
      case None =>
        cask.Response(
          "no such plot",
          statusCode = 404,
          headers = Seq("Content-Type" -> "text/plain; charset=utf-8", "Cache-Control" -> "no-store")
        )

  @cask.get("/events")
  def events(): cask.Response[cask.Response.Data] =
    val s = activeServer
    val queue = LinkedBlockingQueue[String]()
    s.clients.add(queue)
    val data = new cask.Response.Data:
      def headers: Seq[(String, String)] =
        Seq("Content-Type" -> "text/event-stream; charset=utf-8", "Cache-Control" -> "no-store")
      def write(out: OutputStream): Unit =
        try
          out.write("retry: 1000\n\n".getBytes(StandardCharsets.UTF_8))
          out.flush()
          while true do
            val msg = Option(queue.poll(15, TimeUnit.SECONDS))
            out.write(msg.fold(": keep-alive\n\n")(m => s"data: $m\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
        catch case _: java.io.IOException | _: InterruptedException => ()
        finally
          s.clients.remove(queue)
          scala.util.Try(out.close())
    cask.Response(data)

  /** Opens `address` in the default browser, unless browsers are turned off (`TDA4J_PLOT_BROWSER=none`, or the system
    * property `tda4j.plot.browser=none`) or there is no desktop to open it on. Returns whether it did.
    */
  def browse(address: String): Boolean =
    val wanted = !sys.props.get("tda4j.plot.browser").orElse(sys.env.get("TDA4J_PLOT_BROWSER")).contains("none")
    wanted && scala.util
      .Try {
        import java.awt.{Desktop, GraphicsEnvironment}
        !GraphicsEnvironment.isHeadless && Desktop.isDesktopSupported &&
        Desktop.getDesktop.isSupported(Desktop.Action.BROWSE) && { Desktop.getDesktop.browse(URI(address)); true }
      }
      .getOrElse(false)

  private def announce(url: String): Unit =
    if browse(url) then Console.err.println(s"TDA4j plots: $url (opened in your browser)")
    else Console.err.println(s"TDA4j plots: open $url in a browser (forward the port from a remote machine)")

  private def listJson(s: Server): String =
    def str(t: String) = "\"" + t.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
    s.entries.synchronized(s.entries.map(e => s"""{"id":${e.id},"title":${str(e.title)}}""").mkString("[", ",", "]"))

  private val indexPage: String =
    """<!doctype html>
      |<html lang="en"><head><meta charset="utf-8"><meta name="color-scheme" content="light dark">
      |<title>TDA4j plots</title>
      |<style>
      |:root{--surface:#f8f7f4;--ink:#1f2d36;--muted:#7d8a91;--line:#e4e2dc;--accent:#3c5a6b}
      |@media (prefers-color-scheme: dark){:root{--surface:#161b1e;--ink:#e8eef1;--muted:#7f8c93;--line:#252d32;--accent:#8fb4c7}}
      |html,body{margin:0;height:100%;background:var(--surface);color:var(--ink);font-family:system-ui,-apple-system,'Segoe UI',sans-serif}
      |body{display:flex}
      |nav{width:220px;border-right:1px solid var(--line);overflow:auto;padding:12px 0;flex:none}
      |nav h1{font:600 14px system-ui;margin:0 14px 10px;color:var(--accent)}
      |nav label{display:block;margin:0 14px 10px;font-size:12px;color:var(--muted)}
      |nav button{display:block;width:100%;text-align:left;border:0;background:none;color:var(--ink);padding:6px 14px;font:13px system-ui;cursor:pointer}
      |nav button:hover{background:var(--line)}
      |nav button[aria-current="true"]{font-weight:600;box-shadow:inset 3px 0 var(--accent)}
      |nav .empty{margin:0 14px;font-size:12px;color:var(--muted)}
      |iframe{flex:1;border:0;height:100%}
      |</style></head><body>
      |<nav><h1>TDA4j plots</h1><label><input type="checkbox" id="follow" checked> show the newest</label><div id="list"><p class="empty">Waiting for <code>view()</code>...</p></div></nav>
      |<iframe id="frame" title="plot"></iframe>
      |<script>
      |const list=document.getElementById('list'),frame=document.getElementById('frame'),follow=document.getElementById('follow');
      |let current=null;
      |function load(id){current=id;frame.src='/plot/'+id;for(const b of list.querySelectorAll('button'))b.setAttribute('aria-current',String(b.dataset.id==id));}
      |async function refresh(){
      |  const plots=await (await fetch('/list')).json();
      |  if(!plots.length)return;
      |  list.textContent='';
      |  for(const p of plots.slice().reverse()){const b=document.createElement('button');b.dataset.id=p.id;b.textContent=p.id+'. '+(p.title||'untitled');b.onclick=()=>{follow.checked=false;load(p.id)};list.appendChild(b);}
      |  const newest=plots[plots.length-1].id;
      |  if(follow.checked||current===null||!plots.some(p=>p.id==current))load(newest);else load(current);
      |}
      |const events=new EventSource('/events');events.onmessage=refresh;events.onopen=refresh;
      |</script></body></html>
      |""".stripMargin

  initialize()
