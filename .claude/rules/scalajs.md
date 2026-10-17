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

No `cask`, `scala-parallel-collections`, `commons-math3`/`commons-numbers`, `jvptree`, `miniball` (the Java libraries cannot
be compiled by Scala.js at all; they are JVM test-scope oracles for the pure-Scala replacements, see
`WORKLOG-scalajs-numerics-*.md`); no `java.nio.file`, `java.io.File`, `scala.io.Source.fromFile`, `new PrintWriter(path)`
(use `FileAccess`); no threads, `LinkedBlockingQueue`, `java.util.Properties`, `ClassLoader` resources (the version is the
generated `LibraryVersion`); no `.par` (use `Parallel`); no `Path` in a public signature (`Plot.save` and
`PersistenceResult.save` return the absolute path as a `String`). Never test the runtime class of a number
(`rules/algebra.md`). `Long` is emulated on Scala.js and slow: the packed engines run, not fast.

## The four seams

`Parallel` (a loop on JS), `FileAccess` (Node `fs` found with `process.getBuiltinModule`, so a bundle loads in a browser
and only the file calls fail, with a message that says why; failures are `java.io.IOException`s on both platforms),
`plot.Viewer` (JVM: cask; JS: iframe in a page, or a temporary HTML file opened from Node), `CubicalImagePlatform` (JVM:
`fromFile`, `fromBufferedImage`; JS: `fromImageData`, `fromFile` throws). A new JVM-only need gets a seam like these, with
the JS side throwing an `UnsupportedOperationException` that says what to do, never a silent stub.

## The JavaScript API and the npm package

`javascript.Exports` (`src/main/scalajs/.../javascript/`) is the npm package's API: `persistence`,
`persistenceFromDistances`, `persistenceFromImage`, `persistenceFromGrid`, and the classes `Result` and `Chart`, a thin
layer over `matlab.TDA4j`. Options are the facade's option names (an object, not a string array); a Scala exception becomes
a JavaScript `Error` with the same message (`guarded`). It is hand-typed twice: `npm/index.d.ts` must list every option
the facade accepts and every exported member (change both in one commit). `npm/package.json` has `@VERSION@`, filled in
by `tda4jJS/npmPack`, which also adds the shebang to `cli.mjs`; `type: module`, Node >= 20.16 (`process.getBuiltinModule`).
Pre-release versions publish under the npm tag `next` (`release.yml`). The `npm-package` CI job installs the tarball
into an empty project and runs the library and `npx tda4j`.

## Tests

Specs in `src/test/scala` run on both; one that needs a JVM (temp files, `java.io`, the viewer, benchmarks, the tutorial
scripts generated from `_docs`, the MATLAB-facing reflection) is in `src/test/scalajvm`. Replacements of JVM libraries have
their oracle comparisons there (JVM-only, against the old library) and property specs in `src/test/scala` that run on both.
A spec that passes on the JVM and fails on JS is a finding: root-cause it (`Math.pow` last digit, `Long`, number
formatting, sort stability), never loosen it for JS only without a comment naming the reason.

## Known limits

One thread (a Web Worker is the user's way out); no image files on Node (issue: PNG decoding); no file access in a
browser; MiMa checks the JVM artifact only; no JS coverage; docs are built from the JVM row.
