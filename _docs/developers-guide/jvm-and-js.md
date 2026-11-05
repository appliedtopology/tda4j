---
layout: main
title: JVM and Scala.js
---

Almost all of the library compiles for the JVM and for Scala.js from one set of sources. The
[architecture page](architecture.md#two-platforms-the-jvm-and-scala-js) describes the two builds and the seams between
them. This page is for changing shared code: what you may not use, how Scala.js differs at run time, and how to write
and size specs that run on both.

## Where code goes

| directory | compiled for |
|---|---|
| `src/main/scala`, `src/test/scala` | both: write here first |
| `src/main/scalajvm`, `src/test/scalajvm` | the JVM only |
| `src/main/scalajs`, `src/test/scalajs` | Scala.js only |

A name that exists on both (`FileAccess`, `CubicalImagePlatform`, `TestPlatform`) is defined once in each platform
directory with the same API, and never in the shared one. A shared failure on Scala.js is the shared code's bug.

**Scala.js compiles against the JDK on the classpath, and finds a missing `java.*` class when it links**, not when it
compiles. After touching shared code, run `sbt tda4jJS/testOnly *SomeSpec` (or `fastLinkJS`), not just
`tda4jJS/compile`.

## What shared code may not use

- **No JVM-only libraries**: `cask`, the parallel collections (`.par`; use `Parallel`), `commons-math3`, `jvptree`,
  `miniball`. Where the library needs the algorithm, it has its own pure Scala version, and the Java library is a JVM-only
  test oracle for it.
- **No files, threads or processes**: `java.nio.file`, `java.io.File`, `scala.io.Source.fromFile`, `PrintWriter(path)`,
  `Thread`, `LinkedBlockingQueue`, `java.util.Properties`, class-loader resources, `System.exit`. Use the seam
  (`FileAccess`, `Parallel`, `cli.ProcessExit`); the version string is the generated `LibraryVersion`.
- **No `Locale`**, `ThreadLocal.withInitial`, `TrieMap` or `LinkedBlockingDeque`. A public signature takes no `Path`; a method
  that saves a file returns the absolute path as a `String`.
- **Never test the runtime class of a number**: every boxed number is a `Double` on Scala.js, so an `Fp` matches `case d: Double`.
  Ask the `Field`.
- A Scallop option names itself (`opt[Int](name = "max-dimension", ...)`); Scala.js cannot guess names.
- Do not bind `js.Dynamic.global` to a value (`val g = js.Dynamic.global` does not compile): select from it in place and
  test existence with `js.typeOf`.

A new need that only the JVM can meet gets a seam like the existing ones, and the Scala.js side throws an
`UnsupportedOperationException` that says what to do. Never a silent stub.

## How Scala.js behaves differently

| | what happens | what to do |
|---|---|---|
| `Long` | emulated; the packed engines run, and run slower | keep an `Int` where it does the job |
| `java.util.Arrays.sort(a, from, to)` | allocates a scratch array as long as the whole `a` on every call | sort ranges with `Parallel.sortInts` and `sortLongs`; a loop of range sorts is quadratic |
| `java.util.ArrayDeque` as a stack | repeats elements once it grows | an `Int` array, or `mutable.ArrayDeque` |
| threads | one: `Parallel` runs everything in order | every user of `Parallel` must give the same result for any split |
| `Double.toString(1.0)` | `"1"`, not `"1.0"` | build expected strings in shared specs with `${1.0}` |
| `Math.pow`, number formatting, sort stability | last-digit differences | compare with a tolerance, or avoid the operation |
| specs2 `throwA[E]("message")` | reports a mismatch for a message that matches | `SpecThrows.throwWith[E]("message")` |

A spec that passes on the JVM and fails on Scala.js is a finding. Find the cause. Do not loosen the spec for JavaScript
without a comment that names the reason.

**The Scala.js link keeps the runtime checks** (array bounds, null, casts): do not switch it to optimized semantics to save
test time.

## Sizing specs for both platforms

The Scala.js suite is run to find differences between platforms, not to search for rare inputs: the JVM does that. Code on
Scala.js is three to sixty times slower, so a spec that is cheap on the JVM can set the wall clock of the whole job.
sbt runs the Scala.js specs on one Node process per core, and the slowest single example decides when it ends.

- **`TestPlatform.propertyCases(n)`**: all `n` cases on the JVM, a tenth on Scala.js. Use it for a property that hunts a rare
  input (2000 clouds because a bug appeared once in thousands).
- **`PlatformScalaCheck`**: the mixin to use instead of `ScalaCheck`; its default is 100 cases on the JVM and a tenth on
  Scala.js. A spec's own `given Parameters` wins. (ScalaCheck's own `mintestsok` command-line option has no effect here.)
- **`TestPlatform.perPlatform(jvm = ..., js = ...)`**: sizes a loop over seeds, primes, groups or option combinations, or
  chooses a case from a table.
- **Shrinking on the JVM needs a mutation argument; on Scala.js it does not.** The JVM run is the search for a bug, and
  the Scala.js run only has to show a difference between platforms, which any input shows. Before reducing a JVM grid,
  mutate the code under test (flip a comparison or a sign), run the old spec and the reduced one, and keep the reduction
  only if the reduced spec fails on every mutant the old one failed on.

Specs that need a JVM (temporary files, `java.io`, the viewer, the benchmarks, the tutorial scripts generated from `_docs/`,
reflection over the MATLAB facade) are in `src/test/scalajvm`. A replacement for a JVM library has its comparison
with the library there, on the JVM, and a property spec in `src/test/scala` that runs on both.

## Running the Scala.js suite

```
sbt tda4jJS/test                      # needs Node.js 20.16 or newer
sbt "tda4jJS/testOnly *SimplexSpec"   # one spec
```

**Run the full Scala.js suite before a pull request that changes shared code.** `test-js` is a CI job, but a failure there
costs a round trip, and the full suite catches the unlinked `java.*` class, the number formatting and the
single-thread difference that a JVM run cannot show. Note that `tda4jJS/test` runs only the specs that depend on what
changed since its last run; for a clean answer, `sbt tda4jJS/clean` first.

To see which Scala.js specs are slow, specs2's own "Finished in" is wrong on Scala.js (13 ms for a twelve-minute spec).
Run with specs run one at a time, `sbt 'set LocalProject("tda4jJS") / Test / parallelExecution := false' tda4jJS/test`,
timestamp the output, and take the gap between the "Total for specification" lines. One example can be left out with
`testOnly * -- ex ^(?!its.description).*`.

## Performance on Scala.js

- A JavaScript run is the facade's, since the npm package is the facade. Compare it with the JVM facade, not the verb, or
  you measure different work ([Measuring performance](performance.md)).
- Profile with `node --cpu-prof` on a worker that loads the linked `main.mjs` (`bench/workers/tda4j_js_worker.mjs`).
- The memory of a Node process follows its heap closely, so its RSS is a fair comparison with native tools, where a
  JVM's is not.

## Known limits

One thread (a Web Worker is the user's way out); no image files on Node.js; no file access in a browser; MiMa checks the
JVM artifact only; no coverage for Scala.js; the documentation is built from the JVM row.
