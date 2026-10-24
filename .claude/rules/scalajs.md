---
paths:
  - "src/main/scalajs/**"
  - "src/main/scalajvm/**"
  - "src/test/scalajs/**"
  - "src/test/scalajvm/**"
  - "npm/**"
  - "build.sbt"
  - ".github/workflows/**"
---

# The Scala.js build, the JavaScript API and the npm package

Loads when you work on platform-specific code, `npm/`, `build.sbt` or the workflows. Project-wide rules are in
`.claude/CLAUDE.md`; the design and what was measured are in `DESIGN-scalajs-crossbuild.md` and
`WORKLOG-scalajs-crossbuild.md`.

## The build

sbt 2 has the project matrix built in (**sbt-crossproject is not supported on sbt 2**; do not add it). `build.sbt` has two
rows of one `projectMatrix`: `tda4j` (JVM, artifact `tda4j_3`) and `tda4jJS` (Scala.js 1.22 via `sbt-scalajs` for sbt 2,
artifact `tda4j_sjs1_3`). The layout is sbt 2's default, chosen over the older `shared/jvm/js` one by the project lead:
`src/{main,test}/scala` compiles for BOTH rows, `src/{main,test}/scalajvm` and `.../scalajs` for one. `%%` is
platform-aware; Java libraries use `%`. Row base directories are sbt's own `.sbt/matrix/<id>`, so `build.sbt` names
checkout files through `checkout` (the build's base directory), never `baseDirectory.value`.

- JVM-only settings (scaladoc, assembly, MiMa, the tutorial generator, `cask`, the parallel collections) are in
  `jvmSettings`; the JS row has `jsSettings` (ES modules, `main.mjs` for the exports, `cli.mjs` as a second module with
  `ModuleInitializer`, `npmPack`). `commonSettings` has only libraries with a Scala.js build (cats, kittens, scallop,
  specs2, scalacheck).
- Project-qualified commands everywhere (`tda4j/doc`, `tda4j/assembly`, `tda4jJS/test`): the root aggregates both rows, and
  `doc`/`assembly`/`mimaReportBinaryIssues` mean nothing for the other one. `Test/update` is not a task in sbt 2.
- Scala.js compiles against the JDK on the classpath, so a missing `java.*` class shows up when LINKING (`tda4jJS/test`,
  `fastLinkJS`), not at `compile`. Run a link after touching shared code.
- Scala.js forbids binding `js.Dynamic.global` to a value (`val g = js.Dynamic.global` fails to compile): select from it
  in place (`js.Dynamic.global.process`), test existence with `js.typeOf`.
- A name that exists on both platforms (an object `FileAccess`, a trait `CubicalImagePlatform`) is defined once in
  `scalajvm` and once in `scalajs`, with the same API and a restricted (`private[tda4j]`) visibility; never in `scala`.

## Shared code: what not to use

No `Locale`, `System.exit`, `ThreadLocal.withInitial`, `FileNotFoundException` (`FileAccess.noSuchFile` makes the right
one per platform), `TrieMap`, `LinkedBlockingDeque`. A Scallop option names itself (`opt[T](name = "max-dimension", ...)`;
Scallop on Scala.js cannot guess names). No `cask`, `scala-parallel-collections`, `commons-math3`/`commons-numbers`, `jvptree`, `miniball` (the Java libraries cannot
be compiled by Scala.js at all; they are JVM test-scope oracles for the pure-Scala replacements, see
`WORKLOG-scalajs-numerics-*.md`); no `java.nio.file`, `java.io.File`, `scala.io.Source.fromFile`, `new PrintWriter(path)`
(use `FileAccess`); no threads, `LinkedBlockingQueue`, `java.util.Properties`, `ClassLoader` resources (the version is the
generated `LibraryVersion`); no `.par` (use `Parallel`); no `Path` in a public signature (`Plot.save` and
`PersistenceResult.save` return the absolute path as a `String`). Never test the runtime class of a number
(`rules/algebra.md`). `Long` is emulated on Scala.js and slow: the packed engines run, not fast.

## The seams

`Parallel` (`threads`, `foreach`, `map`, `ranges`, `both`, `sort`, `sortLongs`, `tasks`, `withThreads`, `limit`, `sequentially`; in order on JS; every user must give the same result for any split, `Parallel.byDefault` is the default of the `parallelFiltrationValue` flags), `FiltrationCache` (`TrieMap` on the JVM, `HashMap` on JS: `TrieMap` does not link on Scala.js),
`plot.RootText` (`java.util.Locale` does not exist on Scala.js: lower-case keywords with `.lowerRoot`, format with
`RootText.format`, locale symbols from `Intl`), `cli.ProcessExit` (`System.exit` does not exist there), `FileAccess` (Node `fs` found with `process.getBuiltinModule`, so a bundle loads in a browser
and only the file calls fail, with a message that says why; failures are `java.io.IOException`s on both platforms),
`plot.Viewer` (JVM: cask; JS: iframe in a page, or a temporary HTML file opened from Node), `CubicalImagePlatform` (JVM:
`fromFile`, `fromBufferedImage`; JS: `fromImageData`, `fromFile` throws). A new JVM-only need gets a seam like these, with
the JS side throwing an `UnsupportedOperationException` that says what to do, never a silent stub.

## The JavaScript API and the npm package

`javascript.Exports` (`src/main/scalajs/.../javascript/`) is the npm package's API: `persistence`,
`persistenceFromDistances`, `persistenceFromImage`, `persistenceFromGrid`, the landmark functions
(`selectLandmarks`, `persistenceFromLandmarks`, `coveringRadius`), `h1Bars`, `circularCoordinates`, `toroidalCoordinates`,
the text parsers (`parsePointCloud`, `parseOff`, `parseDistanceMatrix`), and the classes `Result` and `Chart`, a thin
layer over `matlab.TDA4j`. Options are the facade's option names (an object, not a string array); a Scala exception becomes
a JavaScript `Error` with the same message (`guarded`). It is hand-typed twice: `npm/index.d.ts` must list every option
the facade accepts and every exported member (change both in one commit). `npm/package.json` (package `@appliedtopology/tda4j`; a scoped package needs `npm publish --access public`) has `@VERSION@`, filled in
by `tda4jJS/npmPack`, which also adds the shebang to `cli.mjs`; `type: module`, Node >= 20.16 (`process.getBuiltinModule`).
Pre-release versions publish under the npm tag `next` (`release.yml`). The `npm-package` CI job installs the tarball
into an empty project and runs the library and `npx tda4j`.

## Tests

Specs in `src/test/scala` run on both; one that needs a JVM (temp files, `java.io`, the viewer, benchmarks, the tutorial
scripts generated from `_docs`, the MATLAB-facing reflection) is in `src/test/scalajvm`. Replacements of JVM libraries have
their oracle comparisons there (JVM-only, against the old library) and property specs in `src/test/scala` that run on both.
A spec that passes on the JVM and fails on JS is a finding: root-cause it (`Math.pow` last digit, `Long`, number
formatting, sort stability), never loosen it for JS only without a comment naming the reason. Two known differences:
`Double.toString` prints `1.0` as `1` on JS (expected strings in shared specs build their numbers with `${1.0}`), and
specs2's `throwA[E]("message")` reports a mismatch on JS for a message that matches (use `SpecThrows.throwWith`).

**Test time on JS** (`WORKLOG-js-test-trimming.md`). sbt runs the JS specs on one Node process per core, so one long
example sets the wall clock while the other cores idle. A property that hunts rare inputs takes its count
from `TestPlatform.propertyCases(n)` (the test seam, `src/test/scalajvm` and `src/test/scalajs`): all `n` on the JVM, a
tenth on JS, whose run is there to catch platform differences (`PlatformScalaCheck` is the `ScalaCheck` mixin with that
default; `TestPlatform.perPlatform(jvm, js)` sizes a loop over seeds, primes or groups the same way). specs2's "Finished in" is wrong on JS for many specs (13 ms
for a 12-minute spec): time JS specs by the gaps between "Total for specification" lines in a run with
`set LocalProject("tda4jJS") / Test / parallelExecution := false`, or exclude one example with `testOnly * -- ex
^(?!its.description).*`.

## Benchmarks

`bench/` times the Scala.js build beside the JVM build and the other tools (`bench/run.sh setup tda4j-js`, the `tda4j-js*`
tools, `workers/tda4j_js_worker.mjs`, which speaks the same `key=value`/JSON-line protocol as `PaperBenchmarkDriver`). The
worker calls the npm package's API with the facade's option names; a new benchmark case or driver option goes in both
drivers (the JVM driver alone reports the heap pools' peak, `heap_peak_mb`: a JVM's RSS follows its collector, Node's is
close to its need). `run.sh` takes the JVM classpath from `tda4j/Test/fullClasspath`, never from the aggregating root.
`PaperBenchmarkDriver count=true` (the cells of the complex, for `bench/count_cells.py`) is JVM-only: the cell counts do not depend on the platform.
The harness's own checks run without any of the tools: `python3 bench/test_bench.py` (`WORKLOG-bench-harness-fixes.md`).

## Known limits

One thread (a Web Worker is the user's way out); no image files on Node (issue: PNG decoding); no file access in a
browser; MiMa checks the JVM artifact only; no JS coverage; docs are built from the JVM row.
