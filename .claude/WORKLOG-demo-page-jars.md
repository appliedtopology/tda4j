# The demo page's jars and the size of gh-pages (2026-11-14, #335)

Ask: #335, "`demo.yml` commits the 72.7 MB `tda4j-demo.jar` to `gh-pages/playground/` on every publish". The page had not
been published yet (no `playground/` in `gh-pages` at 71147d22; `demo.yml` has never run, #334), so there is no history
to rewrite: the work is on what the first and every later publish adds.

## What was measured

The demo jar of 0.7.0-SNAPSHOT (sbt-assembly, `demo/`): 73,148,985 bytes, 24,465 files plus folders.

| part of the jar | compressed |
|---|---|
| `org/appliedtopology/tda4j/` (the library and the demo's own classes) | 5.9 MB (8%) |
| the Scala compiler and REPL (`dotty/tools`, the Scala.js back end of 0.9 MB included) | 22.3 MB |
| the Scala standard library (`scala/`) | 10.2 MB |
| cats, kittens, alleycats, shapeless3-deriving | 9.0 MB |
| coursier's `interface` jar: the resolver the compiler uses for `:dep` (4.9 MB) and `libzstd-jni` for 20 platforms (7.4 MB); the page never resolves anything | 12.3 MB |
| `io/undertow`, `org/xnio`, jboss, wildfly (the viewer's web server; the page never starts it) | 3.0 MB |
| jline (0.8 MB), scallop, upickle, pprint, cask, the rest | 4.9 MB |

The dependency jars on the class path add up to 72.4 MB in 43 files (scala3-compiler 24.8, coursier `interface` 13.0,
scala-library 9.8, cats-core 7.1, tda4j itself 6.2).

**What git stores for a re-published fat jar.** The issue's "about 73 MB of history growth per run" is the size of one
publish, not what git keeps: git delta-compresses a blob against the one it replaces. Three versions of the fat jar, the
library's part replaced each time by 6 MB of incompressible bytes (every library class changed, the worst case for the
library's part), committed and `git gc`ed in a scratch repository: 78.8 MB, the first jar's 66.9 MB and 5.9 MB per later
version. That holds while the rest of the jar keeps its bytes. sbt-assembly's output is repeatable (same inputs, same
bytes) but not independent of the time zone: the same build under `TZ=Pacific/Auckland` gave another fat jar, with
the same content and other entry timestamps. CI is UTC throughout, so the unsplit jar would have delta-compressed.
**What was not measured: what GitHub keeps.** Pack storage there is out of reach of this session (the page has not been
published, #334); the arithmetic above is git's, with a thin pack as `git push` sends it.

## What was built, and why it is still worth it

`demo/build.sbt`'s `webSite` cuts the assembly in two by package (`splitAssembly`):

- `tda4j-demo-project.jar`: everything under `org/appliedtopology/tda4j/`, with the assembly's manifest (the page reads
  the version from it) minus `Main-Class`. 6.3 MB. New with every publish.
- `tda4j-demo-libs.jar`: everything else, with a manifest that is `Manifest-Version: 1.0` and nothing that changes
  between builds. 66.2 MB. The same bytes on every build until a dependency changes: entries sorted by name, one time
  for every entry (2000-01-01 00:00 local), the same compression.
- `tda4j-demo-web-patch.jar` as before (`WebPatch` reads the whole assembly), 0.16 MB.

The task checks that the two jars hold every file of the assembly exactly once with the same CRC. The assembly itself
is still what `java -jar` and the container use; only the page's folder changed.

Reasons, in the order of how much they weigh: (1) a publish that does not change a dependency stores the libs jar by
**identity** (same SHA), not by a delta that git's heuristics and GitHub's repacking decide: 0 bytes, certain; (2) the
largest file on `gh-pages` is 66 MB instead of 73 MB, against the 100 MB limit (`demo.yml` checks every jar now) with
the Scala compiler and cats still growing; (3) the page starts and downloads what it did. What it does not do: make a
publish cheaper than git's delta already would, in the case where the delta works (about 6 MB either way). If a later
look at GitHub's repository size shows nothing to gain, `splitAssembly` can go and `index.html` goes back to one jar.

Not done, deliberately: **cutting the libs jar down** (the coursier resolver with its native libraries, undertow, the
Scala.js back end of the compiler). That would be about 16 MB less (13 for the resolver, 3 for the web server) in the
one-time cost of each dependency change and nothing for a visitor (the runtime reads a jar by Range requests and never touches what the compiler does not load: the
first visit downloads 50.2 MB from a 73 MB jar). Each cut risks a `NoClassDefFoundError` in a path the compiler loads
lazily, in a session that costs 30 seconds to start and has no stack traces to read (point 1 of
`WORKLOG-web-playground-build.md`). Not worth that for a one-time saving.

Also not done: deploying Pages from a workflow artifact (`docs.yml`, `release.yml` and `demo.yml` each commit to
`gh-pages`; moving the site to Pages' artifact deployment replaces the whole publishing and needs the repository's
Pages source setting changed by an owner), rewriting `playground/` history (there is none), another static host (it
must answer Range requests from the page's own origin).

## Page check with the split jars (headless Chromium 1243, the cloud container)

`check-web-playground.mjs`: 21 of 21 checks, 68 s; to the first prompt 18.7 s (the single jar: 24.0, 21.1, 26.3 s in
three runs); `1 + 1` 1.0 s, `Persistence(points)` 3.9 s, first type error 30.8 s (the single jar: 1.1, 4.2, 32.6 s);
**50.2 MB fetched** (the single jar: 51.3 and 51.7 MB), in 190 Range requests for the libs jar and 17 for the project
jar. `WebRepl` on the JVM with `-Xverify:all` over the three jars: the square's loop is found, one plot is handed to
the page.

## Reproducibility of the libs jar

`sbt webSite` from nothing (`rm -rf target/out target/web`, `sbt shutdown` first), three times: the libs jar's SHA-256
matched across UTC, `TZ=Pacific/Auckland` and `TZ=America/Los_Angeles`, and across a rebuild; the project jar changed
only when the demo's sources did. **The trap:** with the entry time set to `1980-01-01 00:00:00` the libs jar differed
between time zones. The zip classes take exactly that DOS time for "before 1980" and add an extended-timestamp field
with a UTC instant, which is the local midnight in the machine's zone. Any other time avoids it.
Not tested: another JDK's `zlib` (CI's Temurin 21 against the Ubuntu 21.0.12 here). A different compressor output would
make the libs jar new once, and the workflow says so (below).

## The workflow

`demo.yml`'s publish step, run against a scratch bare repository five times (`$R/publish.sh`, the step's own script
with the paths changed):

| publish | what changed | result |
|---|---|---|
| 1 | first | the three jars and the page, 67 MB pack |
| 2 | the project jar (6 MB of new bytes) and the libs jar by 2 bytes | notice that the libs jar changed; pack 73.1 MB |
| 3 | the libs jar by 2 bytes | notice; pack 73.1 MB (git deltas the near-identical libs) |
| 4 | the project jar (6 MB of new bytes) | no notice; pack 79.0 MB (+5.9 MB) |
| 5 | nothing | "No changes to publish." |

The notice says the publish "adds up to N bytes" when the libs jar differs from the published one, so a change of the
compressor or a dependency shows in the run's summary.

## Left open

Nothing new: what is unverified about the page is #334 (never run on GitHub, Firefox, Safari, phones) and #338 (limits).
The GitHub-side size after the first real publish is added to #334's checklist.
