# WORKLOG: the demo REPL as a web page and as a container image (build-out)

2026-11-12/13, cloud session. Follows `WORKLOG-web-playground.md` (the feasibility round: a Scala 3 REPL with tda4j
runs in a static page on CheerpJ 5.0). The project lead's ask after it: "wire up the cheerpj route as well as see
whether something can be built with a container for tutorial participants to download that gives them a local thing to
run for a local web terminal." Both are built. Open items: #334 (what has not been run), #335 (gh-pages size), #336
(upstream reports), #337 (ulp-long H2 bars in the first example), #338 (the page's limits), #339 (the library counts
two threads on one processor, which hung the page).

## What exists now

| piece | where |
|---|---|
| `Plot.viewWith(target)(body)`: `view()` inside the block goes to `target`, not to the viewer | `plot/Plot.scala`, `PlotViewWithSpec` (JVM and JS), `PlotSpec` |
| `tda4j.plot.host` / `TDA4J_PLOT_HOST`: the viewer's listen address (default `127.0.0.1`) | `plot/Viewer.scala` (JVM), `PlotSpec` |
| the page's driver `WebRepl`: the compiler, one input at a time, each on one thread | `demo/src/...` |
| `WebPatch`: rewrites the classes CheerpJ cannot run into a second jar | `demo/src/...` |
| the page, a Range-capable local server, `sbt webSite` | `demo/web/`, `demo/build.sbt` |
| the container image: Java 21, the jar, ttyd, dtach, tini | `demo/container/` |
| two browser checks (Playwright) | `.claude/scripts/check-web-playground.mjs`, `check-demo-container.mjs` |
| `demo.yml`: build, check, publish page (`gh-pages/playground/`) and image (ghcr.io); called by `release.yml` | `.github/workflows/` |

## The page: what had to be found out

1. **Every input runs inside `Parallel.sequentially`, or the page hangs at random.** `Persistence(points)` (50 points
   on a circle) never came back in 7 of 10 fresh sessions of the same build: one renderer thread at 100% of a core,
   memory flat near 650 MB, until the 240 s timeout. The cause is in the library: `Parallel.threads` is the common
   pool's parallelism plus the caller, the JDK never reports a parallelism below 1, so on CheerpJ's one processor the
   count is 2, `Engine.Auto` chooses `ParallelRipser`, and its fork-join threads never finish there. `WebRepl.eval`
   wraps the driver's run in `Parallel.sequentially`; the library's count itself is #339. Trials, each a new browser
   and a new session, the input `val diagram = Persistence(points)` after the same `points`:

   | build | result |
   |---|---|
   | as first committed | 3 of 10 came back |
   | `Parallel.threads` forced to 1 (probe) | 8 of 8, 4.6 to 5.0 s |
   | `Parallel.sequentially` around the run (the fix) | 8 of 8, 4.3 to 4.6 s |
   | the fix, and no class loader of our own (below) | 8 of 8, 4.3 to 4.7 s |
   | the final build (one object, `WebRepl`) | 6 of 6, 4.4 to 4.7 s |

   What did not find it: a CDP pause of the spinning tab shows CheerpJ's compiled frames as `J_00xxxxxx`, no Java
   names; `Thread.getAllStackTraces` returns an empty map there, so a watchdog thread in Java cannot dump the others.
   What found it: asking which code path differs between a browser and the terminal check of the same jar (the
   processor count), then the two-line probe.
2. **A wrong conclusion the same hang produced, and its removal.** The first build ran the session in a class loader of
   its own (`WebRepl` made a `URLClassLoader(jars, platformLoader)`, loaded a second object `WebSession` in it
   reflectively and forwarded strings), because "with the loader CheerpJ gives the entry class the first library call
   hangs, with our own it returns in 4 s". Each side of that comparison was one or two runs of a session that hangs
   7 times in 10 whatever its loader. With the fix of point 1 the page calls the session object directly, loaded by
   `cheerpjRunLibrary(classPath)`: 8 of 8, and the whole check script passes. The second object and the reflection are
   gone. The same goes for "with `-Xrepl-interrupt-instrumentation` on the first library call also hung": not shown.
   **Rule: a hang seen once is a rate until it has been repeated; compare variants over several fresh sessions.**
   What stands: **one copy of the library, or `view()` is not captured.** `Plot.viewWith`'s target lives in the `Plot`
   object of the loader that set it, and the REPL makes a second copy in two ways by default: its own `URLClassLoader`
   over the class path, and `-Xrepl-interrupt-instrumentation` (default on), which re-defines every class a line
   touches, rewritten for Ctrl-C. `WebRepl` passes its own loader to the driver and switches the instrumentation off.
3. **`wide` instructions for `long`, `float`, `double` stop CheerpJ 5.0**, not only in `Flags$` (the feasibility
   round's one patch). The current library's `plot.Svg$.render` has `double` slots above 255, so every plot died.
   `WebPatch` now scans the whole jar: any method with a `long`/`float`/`double` variable instruction on a slot above
   255. First it removes store/load pairs of a slot used exactly twice with the load right after the store (all of
   `Flags$`); then it renumbers the remaining variables by kind, two-slot and `float` ones first, parameters fixed,
   and drops the local-variable debug tables; it fails if a method still has one. Frames are recomputed
   (`COMPUTE_FRAMES`); `java -Xverify:all` accepts the result. Six classes today: `Flags$`, `StackTraceOps$`, three of
   `dotty.tools.sjs.ir` (`Hashers$TreeHasher`, `Printers$IRTreePrinter`, `Serializers$Serializer`), `plot.Svg$`.
   **Correction to `WORKLOG-web-playground.md`**: its remark that `Tuples$` and `RefinedPrinter.toTextCore` "ran" with
   wide slots was an inference from the REPL working. What was verified is a `javac --release 17` class with 300
   locals: `wide` `iload`/`istore`/`iinc`/`aload`/`astore` work, `wide fload` stops the page
   (`BUGS-IN-REFERENCES.md`).
4. **The REPL's error printer fails on a frame with no file name**, which is every frame in CheerpJ: `fansi.Str(null)`
   in `StackTraceOps`. Reproduced on HotSpot with a hand-made `StackTraceElement`. `WebPatch` substitutes
   `"Unknown Source"` (`BUGS-IN-REFERENCES.md`).
5. **After the driver itself throws, the next definition breaks** (`NoClassDefFoundError: Could not initialize class
   rs$line$N`): the half-loaded wrapper object's index is reused. `WebRepl.eval` advances `objectIndex` and adds the
   old one to `invalidObjectIndexes`.
6. **`ParseResult.isIncomplete` needs the raw text.** An indented block is "incomplete" until an empty line, as in the
   terminal. The page asks with the textarea's value untrimmed, so the empty line reaches the compiler; a right-trimmed
   text made an indented `def` incomplete forever.

## The page: measurements

Headless Chromium (Playwright 1.63), cloud container, jar on localhost, CheerpJ 5.0 from its CDN.
`check-web-playground.mjs` on the final build (one thread, no loader of our own): 21 checks pass in 76 s.

| step | time |
|---|---|
| page load to first prompt (runtime, jar, compiler start, imports) | 24.0 s (21.1 and 26.3 s in two other runs) |
| `1 + 1` (first line) | 1.1 s |
| `val points = Array.tabulate(50)(...)` | 7.1 s |
| `Persistence(points)` | 4.2 s |
| `Plot.barcode(diagram).view()` | 2.1 s |
| two plots in one input | 0.9 s |
| a call of an earlier definition | 0.4 s |
| an exception with its trace | 1.7 s |
| the library's own `require` failure | 0.8 s |
| the first type error | 32.6 s |
| `println` after the failures | 0.7 s |

The whole session fetched 51.3 MB from the site (51.7 MB in another run): the Range requests for the two jars and the
page, summed by the check script. The README and the page say "about 50 MB on a first visit"; the feasibility round's
"about 40 MB" was a shorter session.

## The container: decisions

- **ttyd in front of the terminal REPL**, not a server for our own page. The ask was a local web terminal, and the
  terminal REPL keeps what the CheerpJ page lacks: Tab completion, history, multi-line editing, paste, Ctrl-C, full
  speed. A local back end for the CheerpJ page's interface (plots inline, at JVM speed) is possible, not built.
- **dtach between ttyd and the JVM.** ttyd starts its command once per WebSocket and kills it when the socket closes.
  A reload, or a laptop that slept, would lose the session. `dtach -A socket ...` attaches to the running REPL or
  starts it, so every page shares one session and one viewer port. Chosen over tmux: no second terminal emulator, so
  the browser's scrollback and selection stay native. On attach nothing is redrawn but the prompt (`-r winch`).
- **`:quit`**: the JVM ends, dtach ends, ttyd closes the socket with code 1006, and ttyd's page reconnects by itself:
  a new session within about two seconds.
- **tini as process 1.** The dtach master is re-parented to process 1 when its client goes; with ttyd as process 1
  every `:quit` left a `java` and a `dtach` zombie. With tini there are none (checked by the script).
- **The viewer's address.** Docker publishes ports of the container's network address, and the viewer listened on
  loopback only, so the published 8337 answered nothing. Fixed in the library (`tda4j.plot.host`), set to `0.0.0.0` by
  the image; a port forwarder in the image would have been the alternative. The viewer still announces
  `http://127.0.0.1:8337/`, which is right from the host when the port is published under the same number.
- **Any user id.** `/work` and the home folder are world-writable, so `--user "$(id -u):$(id -g)"` with a mounted
  folder works (checked with uid 12345: `Plot.save` wrote a file owned by it).
- **Base image** `eclipse-temurin:21-jre` (Ubuntu 26.04: ttyd 1.7.7, dtach 0.9, tini from its archive). Image 602 MB
  on disk, 185 MB compressed, 183 MB as `docker save | gzip`. `docker run` to first prompt: 2.6 s, and 8.1 s for the
  first container of a newly built image.

`check-demo-container.mjs`: 13 checks pass in 12 to 19 s, the last time on the image with the final jar (title, imports and `Persistence`, Tab completion, `view()` and the
plot on the published port, the viewer's page, non-ASCII in and out, a pasted block, reload keeps the session, `:quit`
starts a new one, the container survives, no zombies).

Seen and left alone: keys typed within milliseconds after Enter on an empty prompt can be lost (the script typed
`kept` right after an Enter and the REPL saw `t`). The frames left the browser; the loss is between the REPL's
`readLine` calls. A person does not type that fast and a paste arrives as one bracketed block, which the script checks.
Whether the plain terminal REPL does the same was not tried.

## The workflow

`demo.yml` (dispatch with a ref, or called by `release.yml` after the release job): publish the library locally, build
`webSite`, run the session on the JVM with `-Xverify:all`, install Playwright, run both checks, publish the page, then
build and push the image for amd64 and arm64 with plain `docker` commands (no third-party actions).

- No `concurrency` group: `release.yml` holds `gh-pages-publish` for its whole run, and a called workflow asking for
  its caller's group is cancelled as a deadlock. The page's push rebases and retries instead. Tried against a scratch
  bare repository with a racing push from a `pre-push` hook: first push refused, rebase, second push in, both changes
  present, stale files of the old `playground/` gone.
- `docs.yml` now keeps `playground/` when it replaces the site's root.
- **Never run on GitHub** (#334). The ARM build could not be tried here either: installing binfmt emulation needs a
  privileged container, which this session is not allowed to start.

## Working in the cloud container

- Docker: `dockerd` is installed and not running; `nohup dockerd &` as root works, pulls go through the proxy.
  Inside a build, apt cannot reach the archive: the proxy refuses plain HTTP (405) and containers do not trust its CA.
  A local base image with the CA, `Acquire::https::Proxy` and `https://` sources, passed as `--build-arg BASE=...`
  with `--network host`, builds the real Dockerfile unchanged (the `BASE` argument exists for this).
- Running `docker save | gzip` and then the Chromium check in one command ended with exit 137 and a restarted machine.
  Files on disk and Docker's images survived; the daemon had to be started again. Commit before heavy runs.
- `pgrep -f pattern` inside a command whose own text contains the pattern kills the shell: use
  `ps -eo pid,args | grep "[p]attern"`.

## Addendum, 2026-11-13: #339 fixed on the same pull request

The library now counts one thread on one processor (`WORKLOG-one-processor-threads.md`). With that fix and without
`WebRepl`'s `Parallel.sequentially`, 8 of 8 fresh sessions came back from `Persistence(points)`: the count was the
cause of point 1's hang. The guard stays in `WebRepl`, for a runtime that reports more processors.
