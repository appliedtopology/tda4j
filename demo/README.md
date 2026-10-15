# tda4j-demo: a REPL in one jar, for talks and teaching

`java -jar tda4j-demo-<version>.jar` opens a Scala 3 prompt with TDA4j on its classpath and these imports already run:

```scala
import scala.language.experimental.modularity
import cats.syntax.show.*            // x.show
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.plot.*
```

Nothing else to install: no Scala, no sbt, no network. A Java runtime is the one requirement.

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

`DemoRepl.scala` is the whole program: the imports above, `-usejavacp` (the REPL compiles against the jar it runs
from), the print height, and the logging of the viewer's web server turned down to warnings.

## Not verified

Tested on Linux with Java 21 only: the deck-style session (`Persistence`, `significant`, representatives, three
`view()` calls) run through the finished jar, the viewer's pages, list, 404 and event stream fetched over HTTP, and a
clean exit on `:quit` with the server running.

- **macOS and Windows.** The jar carries the line editor's native libraries for both, but nothing was run there:
  terminal behaviour, the browser opening and any firewall prompt are untested.
- **Java 17.** The class-file versions say it should start; it was not run.
- **Windows consoles that are not UTF-8** may print `?` for `∞`, `⊠` and `∆`; Windows Terminal and any Mac or Linux
  terminal in a UTF-8 locale print them. Where it matters, `java -Dstdout.encoding=UTF-8 -jar ...`.
