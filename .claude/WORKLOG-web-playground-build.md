# WORKLOG: the demo REPL as a web page and as a container image (build-out)

2026-11-12/13, cloud session. Follows `WORKLOG-web-playground.md` (the feasibility round: a Scala 3 REPL with tda4j
runs in a static page on CheerpJ 5.0). The project lead's ask after it: "wire up the cheerpj route as well as see
whether something can be built with a container for tutorial participants to download that gives them a local thing to
run for a local web terminal." Both are built. Open items: #334 (what has not been run), #335 (gh-pages size), #336
(upstream reports), #337 (ulp-long H2 bars in the first example), #338 (the page's limits).

## What exists now

| piece | where |
|---|---|
| `Plot.viewWith(target)(body)`: `view()` inside the block goes to `target`, not to the viewer | `plot/Plot.scala`, `PlotViewWithSpec` (JVM and JS), `PlotSpec` |
| `tda4j.plot.host` / `TDA4J_PLOT_HOST`: the viewer's listen address (default `127.0.0.1`) | `plot/Viewer.scala` (JVM), `PlotSpec` |
| the page's driver: `WebRepl` (what the page calls) and `WebSession` (the compiler, one input at a time) | `demo/src/...` |
| `WebPatch`: rewrites the classes CheerpJ cannot run into a second jar | `demo/src/...` |
| the page, a Range-capable local server, `sbt webSite` | `demo/web/`, `demo/build.sbt` |
| the container image: Java 21, the jar, ttyd, dtach, tini | `demo/container/` |
| two browser checks (Playwright) | `.claude/scripts/check-web-playground.mjs`, `check-demo-container.mjs` |
| `demo.yml`: build, check, publish page (`gh-pages/playground/`) and image (ghcr.io); called by `release.yml` | `.github/workflows/` |

## The page: what had to be found out

1. **The REPL must not run in the class loader CheerpJ gives the entry class.** With `ReplDriver`'s class loader set
   to that loader (so that the user's lines share the library with the driver), the first line that needed a library
   class not loaded yet never returned: `Persistence(points)` hung until the 240 s timeout. With a plain
   `URLClassLoader(jars, platformLoader)` made by us it returns in about 4 s. Hence two objects: `WebRepl` is loaded by
   the runtime, makes that loader, loads `WebSession` in it reflectively and forwards strings. Why CheerpJ's own loader
   hangs was not found.
2. **One copy of the library, or `view()` is not captured.** `Plot.viewWith`'s target lives in the `Plot` object of the
   loader that set it. The REPL makes a second copy in two ways by default: its own `URLClassLoader` over the class
   path, and `-Xrepl-interrupt-instrumentation` (default on), which re-defines every class a line touches, rewritten
   for Ctrl-C. `WebSession` passes its own loader and switches the instrumentation off. With instrumentation on (and
   the loader of point 1) the first library call also hung.
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
   rs$line$N`): the half-loaded wrapper object's index is reused. `WebSession.eval` advances `objectIndex` and adds the
   old one to `invalidObjectIndexes`.
6. **`ParseResult.isIncomplete` needs the raw text.** An indented block is "incomplete" until an empty line, as in the
   terminal. The page asks with the textarea's value untrimmed, so the empty line reaches the compiler; a right-trimmed
   text made an indented `def` incomplete forever.

## The page: measurements

Headless Chromium (Playwright 1.63), cloud container, jar on localhost, CheerpJ 5.0 from its CDN.
`check-web-playground.mjs`: 22 checks pass in 72 s.

| step | time |
|---|---|
| page load to first prompt (runtime, jar, compiler start, imports) | 21.1 s |
| `1 + 1` (first line) | 1.1 s |
| `val points = Array.tabulate(50)(...)` | 7.0 s |
| `Persistence(points)` | 4.4 s |
| `Plot.barcode(diagram).view()` | 1.9 s |
| two plots in one input | 0.8 s |
| a call of an earlier definition | 0.4 s |
| an exception with its trace | 1.8 s |
| the library's own `require` failure | 0.7 s |
| the first type error | 30.6 s |
| `println` after the failures | 0.8 s |

The "about 40 MB fetched on a first visit" in the README and the page is the feasibility round's figure (42 MB of the
jar, Range requests counted). The check script now prints the megabytes fetched from the site; that line has not
been read yet, because the run that would have shown it ended with the session's machine restarting (below).

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
  on disk, 185 MB compressed, 183 MB as `docker save | gzip`. `docker run` to first prompt: 2.6 s.

`check-demo-container.mjs`: 13 checks pass in 12 s (title, imports and `Persistence`, Tab completion, `view()` and the
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
  Files on disk survived, the Docker daemon and its images did not. Commit before heavy runs.
- `pgrep -f pattern` inside a command whose own text contains the pattern kills the shell: use
  `ps -eo pid,args | grep "[p]attern"`.
