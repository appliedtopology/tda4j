# tda4j-demo: a REPL in one jar, for talks and teaching

`java -jar tda4j-demo-<version>.jar` opens a Scala 3 prompt with TDA4j on its classpath and these imports already run:

```scala
import scala.language.experimental.modularity
import cats.syntax.show.*            // x.show
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*
```

Nothing else to install: no Scala, no sbt, no network. A Java runtime is the one requirement.

For a participant without Java there are two more forms of the same REPL: [a web page](#in-a-web-page) that needs
only a browser, and [a container](#in-a-container) that needs only Docker.

```
$ java -jar tda4j-demo-0.6.1.jar
scala> val points = Array.tabulate(50)(i => Array(100*math.cos(i * math.Pi / 25), 100*math.sin(i * math.Pi / 25)))
scala> val diagram = Persistence(points)
scala> Plot.barcode(diagram).view()
```

## Using it

- **Plots.** `view()` starts a small server on `127.0.0.1` (port 8337, or a free one when that is taken) and opens the
  page in the default browser; on a machine with no desktop it prints the address instead. `.save("barcode.svg")` and
  `.save("barcode.html")` write the plot to a file. `-Dtda4j.plot.browser=none` never opens a browser,
  `-Dtda4j.plot.port=9000` picks the port: `java -Dtda4j.plot.port=9000 -jar tda4j-demo-0.6.1.jar`.
- **Reading results.** The REPL echoes a value with `toString`. `println(chain.show)` is the readable form of a chain,
  a bar or a diagram (`Fp(1) ⊠ ∆(0,1) + ...` instead of `1⊠TreeSet(0, 1) + ...`).
- **Long output.** The REPL echoes at most 20 lines of a value (its own default is 50). Anything after the jar's name is
  a compiler setting and wins: `java -jar tda4j-demo-0.6.1.jar -Xrepl-print-height:100`.
- **Leaving.** `:quit`. The viewer's threads are daemons, so the JVM exits.
- **Memory.** `java -jar` uses a quarter of the machine's RAM as its heap. A computation that outgrows the heap throws
  `OutOfMemoryError`, and the REPL's own error printer fails on that one and ends the session (an ordinary exception, a
  `null` message included, is printed and the prompt stays). For bigger examples say how much to use:
  `java -Xmx4g -jar tda4j-demo-0.6.1.jar`.
- **A real-sized example**, 50 000 natural-image patches, in `examples/patches/`.

## In a web page

`https://tda4j.appliedtopology.org/playground/` is the REPL in a browser tab: the same compiler, the same imports, and a
session that keeps its values from line to line. Nothing is installed and nothing is sent to a server; the page
downloads the jar and runs it in [CheerpJ](https://cheerpj.com/), a Java runtime that runs in the browser.

- **Plots.** `view()` draws the plot under the line that asked for it. There is no second tab.
- **Entering.** Enter runs a line, or opens a new one while the input is unfinished; an indented block ends with an
  empty line, as in the terminal. The Run button runs what is there. The up and down arrows walk through earlier
  inputs. There is no Tab completion and no Ctrl-C: reloading the page is the way out of a computation that takes too
  long, and it starts a new session.
- **Speed.** The page needs 20 to 30 seconds to reach its first prompt and downloads about 50 MB on a first visit.
  A line then takes from half a second to a few seconds, and the first compile error of a session about half a
  minute. A computation runs roughly ten times slower than on a Java runtime, on one thread, in at most 2 GB: right
  for point clouds of tens to a few hundred points, wrong for the patches example.
- **Licence.** CheerpJ is proprietary and is loaded from its vendor's site. Its free tier covers open-source projects
  that show the "Built with CheerpJ" credit, which the page's footer does.

`sbt webSite` builds the page as a folder, `target/web`, to put on any web host:

```
(cd .. && sbt publishLocal)
sbt webSite                           # target/web: index.html and three jars
node target/web/serve.mjs             # http://127.0.0.1:8338/
```

The runtime reads the jars in pieces, with HTTP Range requests, and only from the site the page itself is on. GitHub
Pages, nginx and S3 answer Range requests; Python's `http.server` does not, which is why `serve.mjs` is there for a
try on your own machine. Opening `index.html` as a file does not work either.

The page's jars are the demo jar cut in two where it changes, so that a site kept in git (the docs site is) grows by the
changing part only when the page is published again. `tda4j-demo-project.jar` is the library and the demo's own classes,
about 6 MB, new with every publish. `tda4j-demo-libs.jar` is everything else (the Scala compiler, cats, the viewer's web
server, about 66 MB). It is written the same bytes on every build, whatever the machine's clock and time zone, until a
dependency changes, so git stores it once. `sbt webSite` checks that the two together hold exactly the files of the demo
jar.

`tda4j-demo-web-patch.jar` holds the few classes of the demo jar that the runtime cannot run as they are: CheerpJ 5.0 stops
on a method that keeps a `long`, `float` or `double` in a local variable beyond the 255th. `WebPatch` finds those
methods in the jar (the compiler's flag table and the plots' SVG writer among them), renumbers their variables, and
writes the rewritten classes to a small jar, which the page puts ahead of the other two on the class path. It also
makes the REPL's error printer accept a stack frame with no file name, which is every frame in this runtime. `WebRepl`
is what the page calls: it drives the compiler one input at a time, each on one thread.

## In a container

```
docker run --rm -p 127.0.0.1:7681:7681 -p 127.0.0.1:8337:8337 ghcr.io/appliedtopology/tda4j-demo
```

`http://localhost:7681` is then the REPL in a terminal that is a web page, at the speed of a Java runtime and with
everything the terminal REPL has (Tab completion, history, multi-line editing, paste). The first `view()` prints
`http://127.0.0.1:8337/`: open it in a second tab, and every plot appears there.

- **One session.** The container runs one REPL. A page that is reloaded, or that reconnects after the laptop slept,
  is back in the same session with its values. `:quit` ends the session and the page starts a new one.
- **Your files.** The REPL's working folder is `/work`. Mount a folder there to load data and save plots:
  `-v "$PWD":/work`, and on Linux also `--user "$(id -u):$(id -g)"` so that saved files are yours.
- **Memory.** The JVM takes a quarter of the memory Docker gives the container. `-e JAVA_OPTS=-Xmx6g` sets it.
- **In your own terminal** instead of a page: `docker run -it --rm -p 127.0.0.1:8337:8337 ghcr.io/appliedtopology/tda4j-demo repl`.
- **Without a network at the venue.** `docker save ghcr.io/appliedtopology/tda4j-demo | gzip > tda4j-demo-image.tar.gz`
  writes the image (about 190 MB) to a file for a USB stick; `docker load < tda4j-demo-image.tar.gz` reads it.
- **Who can reach it.** The terminal page runs whatever is typed into it and asks for no password, and the plots page
  has none either. `127.0.0.1:` in the two `-p` options keeps both to your own machine; leave it there unless the
  network is one you trust.

The image is a Java 21 runtime, the jar, [ttyd](https://github.com/tsl0922/ttyd) (the terminal page) and dtach (which
keeps the REPL running while no page is attached). Building it, after `sbt assembly`:

```
docker build -f container/Dockerfile \
  --build-arg JAR=target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-<version>.jar -t tda4j-demo .
```

## Requirements

Java 17 or newer: the newest class files in the jar (the Scala compiler's and the library's) are version 61.
The jar is about 74 MB because it carries the Scala compiler, the line editor and the viewer's web server.

## Building it

This is a separate sbt build (its own `project/`, not a subproject of the root), so the Scala compiler never becomes a
dependency of the library and the root build's CI, release, MiMa and coverage runs do not see it. It bundles a
*version* of the library, never the root's sources.

```
(cd .. && sbt publishLocal)           # the checkout's version.sbt, into ~/.ivy2/local
sbt assembly                          # target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-<version>.jar

TDA4J_VERSION=0.6.1 sbt assembly      # the same from a RELEASE on Maven Central
```

A release builds it: `release.yml` runs `sbt assembly` in `demo/` against the tag's own library sources (published to
`~/.ivy2/local` in the same job, so it does not depend on Maven Central having served the new version yet), runs the jar
on a small example, and attaches `tda4j-demo-<version>.jar` to the GitHub release.

`DemoRepl.scala` is the whole program: the imports above, `-usejavacp` (the REPL compiles against the jar it runs
from), the print height, and the logging of the viewer's web server turned down to warnings.

The web page and the container image are built and published by the workflow `demo.yml`: for every release, and by
hand for a branch (Actions, "Demo", "Run workflow"), which is how a tutorial gets what is on `scala` today. It builds
the jar, runs the page and the image in a headless browser (`.claude/scripts/check-web-playground.mjs`,
`check-demo-container.mjs`; both run on your machine too), then replaces `/playground/` on the docs site and pushes
the image as `ghcr.io/appliedtopology/tda4j-demo:<version>` and `:latest`.

## Not verified

Tested on Linux with Java 21 only: the deck-style session (`Persistence`, `longerThanFraction`, representatives, three
`view()` calls) run through the finished jar, the viewer's pages, list, 404 and event stream fetched over HTTP, and a
clean exit on `:quit` with the server running.

- **macOS and Windows.** The jar carries the line editor's native libraries for both, but nothing was run there:
  terminal behaviour, the browser opening and any firewall prompt are untested.
- **Java 17.** The class-file versions say it should start; it was not run.
- **Windows consoles that are not UTF-8** may print `?` for `∞`, `⊠` and `∆`; Windows Terminal and any Mac or Linux
  terminal in a UTF-8 locale print them. Where it matters, `java -Dstdout.encoding=UTF-8 -jar ...`.
- **The web page** was run in headless Chromium only, with the jar on the same machine: a session of expressions,
  `Persistence`, plots, a multi-line definition, an exception, a library error and a type error. Firefox, Safari,
  phones, a slow network and a room of thirty laptops loading it at once are untested, and so is a session of an hour.
- **The container** was built and run on Linux on an Intel machine, and used through headless Chromium: the prompt,
  Tab completion, a pasted block, non-ASCII text, a plot on the second port, a reload, `:quit`, another user id with a
  mounted folder. Docker Desktop on macOS and Windows is untested, and the ARM image has never been run.
- **`demo.yml`** has not run: its steps were run by hand where a laptop can (the session check, both browser checks,
  the image build, the push to `gh-pages` against a scratch repository), and the pushes to the real `gh-pages` and to
  ghcr.io were not.
