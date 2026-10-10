package org.appliedtopology.tda4j.plot

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{BindException, InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CopyOnWriteArrayList, Executors, LinkedBlockingQueue, ThreadFactory, TimeUnit}
import scala.collection.mutable

/** The live viewer: one browser tab that shows every plot `view()`ed from this JVM, newest first, as it arrives.
  *
  * The first `view()` starts a small web server on `127.0.0.1` (port 8337, or a free one when that is taken; set
  * another with the system property `tda4j.plot.port` or the environment variable `TDA4J_PLOT_PORT`) and opens the page
  * in the default browser. Where there is no desktop to open it on (a remote machine, a container), it prints the
  * address instead: open it yourself, forwarding the port if needed (`ssh -L 8337:localhost:8337 host`). Set
  * `TDA4J_PLOT_BROWSER=none` to never open a browser. The page keeps a list of every plot shown, and reconnects by
  * itself when the JVM restarts on the same port.
  *
  * The server's threads are daemon threads: a REPL or script exits as usual, and the page then waits for a new one.
  * Nothing here uses Swing or AWT windows, so it works the same from `sbt console`, `scala` and scala-cli on any OS.
  */
object Viewer:
  private final class Entry(val id: Int, val title: String, val page: String)

  private final class Server(val server: HttpServer):
    val entries: mutable.ArrayBuffer[Entry] = mutable.ArrayBuffer.empty
    val clients: CopyOnWriteArrayList[LinkedBlockingQueue[String]] = CopyOnWriteArrayList()
    def url: String = s"http://127.0.0.1:${server.getAddress.getPort}/"

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
    running.foreach(_.server.stop(0))
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
      // Started from a daemon thread: the JDK's HTTP dispatcher thread inherits its daemon status from its creator.
      var created: Either[Throwable, Server] = Left(IllegalStateException("viewer did not start"))
      val starter = daemons.newThread(() => created = scala.util.Try(start()).toEither)
      starter.start()
      starter.join()
      val s = created.fold(throw _, identity)
      running = Some(s)
      announce(s.url)
      s
    }

  private def bind(port: Int): HttpServer =
    try HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, port), 0)
    catch
      case _: BindException if port != 0 => HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)

  private def start(): Server =
    val http = bind(configuredPort)
    val s = Server(http)
    http.setExecutor(Executors.newCachedThreadPool(daemons))
    http.createContext("/", ex => respond(ex, "text/html", indexPage))
    http.createContext("/list", ex => respond(ex, "application/json", listJson(s)))
    http.createContext(
      "/plot/",
      ex =>
        val id = ex.getRequestURI.getPath.stripPrefix("/plot/").toIntOption
        val page = s.entries.synchronized(id.flatMap(i => s.entries.find(_.id == i)).map(_.page))
        page match
          case Some(p) => respond(ex, "text/html", p)
          case None    => respond(ex, "text/plain", "no such plot", 404)
    )
    http.createContext("/events", ex => events(s, ex))
    http.start()
    s

  private def announce(url: String): Unit =
    val wanted = !sys.env.get("TDA4J_PLOT_BROWSER").contains("none")
    val opened = wanted && scala.util
      .Try {
        import java.awt.{Desktop, GraphicsEnvironment}
        !GraphicsEnvironment.isHeadless && Desktop.isDesktopSupported &&
        Desktop.getDesktop.isSupported(Desktop.Action.BROWSE) && { Desktop.getDesktop.browse(URI(url)); true }
      }
      .getOrElse(false)
    if opened then Console.err.println(s"TDA4j plots: $url (opened in your browser)")
    else Console.err.println(s"TDA4j plots: open $url in a browser (forward the port from a remote machine)")

  private def respond(ex: HttpExchange, contentType: String, body: String, status: Int = 200): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", s"$contentType; charset=utf-8")
    ex.getResponseHeaders.set("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val out = ex.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def listJson(s: Server): String =
    def str(t: String) = "\"" + t.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
    s.entries.synchronized(s.entries.map(e => s"""{"id":${e.id},"title":${str(e.title)}}""").mkString("[", ",", "]"))

  /** A server-sent event stream: one message per new plot (its id), a comment every 15 s to keep proxies awake. */
  private def events(s: Server, ex: HttpExchange): Unit =
    val queue = LinkedBlockingQueue[String]()
    s.clients.add(queue)
    ex.getResponseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
    ex.getResponseHeaders.set("Cache-Control", "no-store")
    ex.sendResponseHeaders(200, 0)
    val out = ex.getResponseBody
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
