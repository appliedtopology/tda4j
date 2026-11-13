# A Scala prompt in a web page (2026-11-12)

Ask from the project lead: a web version of the demo jar, a page that gives tutorial participants a Scala prompt with
the library, nothing to install. This is the feasibility round: which routes exist, what was run, what was not.

## Result

**The demo jar's own REPL runs in a browser tab**, on CheerpJ 5.0 (a JVM compiled to WebAssembly, loaded from the
vendor's CDN), once ONE class of the Scala compiler is rewritten. No server: a static page, the jar next to it.
Measured in headless Chromium in the cloud container, the jar served from localhost (so no network time):

| step | browser JVM | HotSpot 21, same machine |
|---|---|---|
| runtime start + jar opened | 2 s + 8 s | - |
| REPL created, the four imports run | 12.6 s | 2.5 s |
| `1 + 1` (first line) | 3.1 s | 0.6 s |
| `val points = Array.tabulate(50)(...)` | 5.8 s | 0.6 s |
| `Persistence(points)` first / again | 5.4 s / 0.75 s | 0.8 s |
| `diagram.dim(1)`, `2 + 2` | 0.9 s, 0.2 s | - |
| `Plot.barcode(diagram).svg()` | 2.2 s | 0.3 s |
| VR, 200 Gaussian points, degree 1 | 4.7 s | - |
| VR, 300 points on a noisy circle, degree 1 | 4.2 s | - |
| alpha, the same 300 points | 2.7 s | - |
| VR, 60 points in R^3, degree 2 | 1.4 s | - |
| a type error (`val oops: Int = "three"`) first / second | 29 s / 10.5 s | under 1 s |

`Persistence` alone (no compiler involved), 50 points: 2.3 s first call and 0.63 to 0.83 s after, against 0.28 s and 0.08 s
on HotSpot: the computation is roughly 8 to 10 times slower. One processor (`availableProcessors` is 1), 2048 MB heap.
A session fetched about 42 MB of the 72 MB jar, by range requests.

## What had to be done to get there

- **`dotty.tools.dotc.core.Flags$.<clinit>` uses local slots above 255 for `long`s** (`lstore_w 256` at pc 3290, its
  first). CheerpJ 5.0 stops there, on Java 17 and 21 (`runWideOpcode 37, PC: 3291`, then `TypeError: Cannot convert
  object to primitive value` on the page); 4.2 and 4.3 print nothing and never return (waited 390 s and 130 s). A probe
  that called `Properties.versionString`, `Persistence` and then `Flags.flagsString(Flags.Private)` showed the first two
  working and the third failing, on both versions. `Flags` is the compiler's flag sets: nothing compiles without it.
- **The scan.** 21 methods of the 26,194 classes in the jar use a slot above 255; four of them for a `long` or `double`:
  `Flags$.<clinit>` and three in the Scala.js IR (`Hashers`, `Printers`, `Serializers`: not on the JVM REPL's path). The
  other 17 use `aload`/`astore`/`iload` forms, and at least `Tuples$` and `RefinedPrinter.toTextCore` ran.
- **The patch.** Every high slot in `Flags$.<clinit>` is a temporary: `lstore_w N; lload_w N; goto next`. Removing the 56
  store/load pairs whose slot is used nowhere else and renumbering what is left gives 220 slots and no wide instruction.
  Done with `scala.tools.asm` (it is in the jar, the compiler ships it), frames recomputed. The patched class goes in a
  small jar AHEAD of the demo jar on the classpath. Checked on HotSpot with `-Xverify:all`: the REPL starts and computes.
- **A driver for a page.** `ReplDriver(Array("-classpath", jar, "-color:never", ...), out, None, "")`, then
  `state = driver.run(line)(using state)` per line with `Console.withOut/withErr` on a buffer; the page calls it through
  CheerpJ's library mode (`cheerpjRunLibrary(classpath)`, `await lib.webrepl.WebRepl`, static methods, strings in and out).
  `-usejavacp` is replaced by an explicit `-classpath /app/...jar` (`/app/` is the page's own origin in CheerpJ).
- **The server must answer range requests** (Python's `http.server` does not; a 40-line Node server did). GitHub Pages
  does. The jar must be on the page's origin: a release asset on github.com is another origin, without CORS headers.

## Edges found

- **An exception thrown by the user's line breaks the REPL's printer.** CheerpJ's stack frames have no file name
  (`Unknown Source`); `dotty.tools.repl.StackTraceOps.formatStackTracePrefix` passes the `null` to `fansi.Str` and throws a
  `NullPointerException` out of `ReplDriver.run`. `throw new IllegalStateException("boom")` at top level printed fine
  (no frame to show); `List(1, 2, 3).apply(7)` and a failed `require` inside `Persistence` did not. Not patched yet: the
  fix is the same kind (rewrite the `getFileName` calls of that one class to a null-safe helper). This is a bug of the
  Scala REPL too (any frame without a file name), not reported upstream.
- **After that failure the next definition was unusable** (`NoClassDefFoundError: Could not initialize class
  rs$line$2$`): the driver wrapper kept the old `State`, so the next line reused the failed line's object number. The
  wrapper has to advance the state when `run` throws.
- **Type errors are slow**, 29 s the first time and 10.5 s the second: the compiler's error path (implicit conversion
  search with cats and the library in scope) is cold code in a slow JVM. A room of beginners makes type errors.
- **`view()` cannot work as it is**: the JVM viewer is a cask server on 127.0.0.1 and a browser tab has no sockets.
  `Plot.html(Theme.Auto)` works (1.7 s for a diagram), so the page can show a plot in a frame; `view()` needs a way to hand
  its plot to the page instead of the server.

## The other routes

- **The Scala 3 compiler compiled to JavaScript or WebAssembly**: none exists that I could find (a web search, the Scala
  contributors forum threads it returned). The Scala.js linker runs on JS; the compiler does not. Not a project for here.
- **Scastie** (scastie.scala-lang.org, compiles on the Scala Center's servers). On paper it fits: its build lists Scala
  3.9.0 and Scala.js 1.22.0 (ours: 3.9.0, sbt-scalajs 1.22.0), and both `tda4j_3` and `tda4j_sjs1_3` `0.7.0-SNAPSHOT` are
  on the Central snapshots repository (last published 2026-11-07); only `tda4j_3` up to 0.6.2 is on Maven Central. An
  editor with a Run button, not a prompt. NOT RUN: a trial posts a snippet to an outside service, which waits for the
  project lead's word. `WORKLOG-demo-jar.md` records why the demo jar was built instead (plots, the dependency lookup).
- **A Jupyter kernel on Binder**: Almond's newest Scala 3 kernel on Maven Central is 3.8.1, which cannot read 3.9.0
  TASTy. Out until Almond catches up.
- **A hosted REPL** (a container per participant running the demo jar behind a web terminal): the real prompt at JVM
  speed; costs a machine during the session (0.5 to 1 GB per participant), a sandbox (a Scala prompt is a remote shell)
  and someone to run it.
- **A JavaScript console over the npm package** (`@appliedtopology/tda4j@0.7.0-preview` is on npm, 4.6 MB in one
  module, served by jsDelivr): a day of work, static, fast (the JS build is 2.5 to 3 times the JVM's time), but it is
  JavaScript against the facade, not Scala and not the typed API.

## Licence

cheerpj.com/licensing on 2026-11-12, for open-source projects: "Community: Free, with a 'Built with CheerpJ' credit. Load
CheerpJ from our CDN, and your users run your project for free. Organisations that self-host your project hold their own
licence", and "Bundling or self-hosting the runtime counts as redistribution". So: the runtime comes from their CDN
(the page needs the internet; the jar stays the offline route), with the credit on the page. The runtime is proprietary.

## Not checked

- Any browser but headless Chromium (Playwright's); Firefox and Safari; a phone or a tablet.
- A participant's laptop on a conference network: the 42 MB and the times above are a cloud container and localhost.
- CheerpJ's caching of the jar between visits; thirty tabs at once from one CDN.
- Longer sessions (memory growth: every line adds classes), `:load`, `:type`, tab completion (none in this driver).
- Whether Leaning Technologies fix the `wide` `lstore`/`lload` case if told; nothing was reported to them.
- Scastie in practice (see above).

## Reproduce

Scratch files only so far (not in the repository): the demo jar of `v0.7.0-preview` from its GitHub release;
`WebRepl.scala` and the patch tool compiled with the jar's own compiler (`java -cp demo.jar dotty.tools.dotc.Main
-classpath demo.jar -d out ...`); a page that loads `https://cjrtnc.leaningtech.com/5.0/loader.js`, then
`cheerpjInit({version: 17})` and `cheerpjRunLibrary("/app/patch.jar:/app/demo.jar:/app/webrepl.jar")`; Playwright's
Chromium with `proxy: {server: $HTTPS_PROXY, bypass: "127.0.0.1"}` reading the page's console.
