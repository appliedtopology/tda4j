# The Scala.js cross build (2026-10-17)

Request (project lead, after the 2026-10-16 tutorial, where JavaScript was asked for): cross-build to JavaScript; the
MATLAB surface is not needed on JS, the CLI is welcome. Find out (1) what carries over as it is, (2) what needs new
libraries, with candidates; (3) set up the code tree, the build and CI for the cross build, and prepare distribution (accounts
come later). The earlier probe is `WORKLOG-scalajs-native-feasibility.md` (issue #81); this arc did the work. Design and
working rules now: `DESIGN-scalajs-crossbuild.md`, `rules/scalajs.md`. Numerics derivations:
`WORKLOG-scalajs-numerics-linear-algebra.md`, `WORKLOG-scalajs-numerics-spatial.md`.

## Decisions

Asked up front (the project lead was going to sleep): **audience: both** Scala.js programs (Maven `tda4j_sjs1_3`) and
JavaScript/TypeScript programs (an npm package with an exported API); **JVM-only libraries: our own shared pure-Scala code,
the Java libraries kept as JVM test-scope oracles**; layout: first `shared/jvm/js` was chosen, then **changed mid-session to
sbt 2's default project-matrix layout** (`src/*/scala` shared, `scalajvm`, `scalajs`), so no `git mv` of the tree ever
landed. Later: the npm package is **`@appliedtopology/tda4j`** (the project lead created the scope); the benchmark harness
should measure the JS build on the same machine as the other tools.

## Toolchain (verified on Maven Central, 2026-10-17)

- sbt 2 has the project matrix built in; **sbt-crossproject is not supported on sbt 2** (its `_sbt2_3` artifact exists but
  the Scala contributors thread and the Scala.js plugin point to `projectMatrix`). `sbt-scalajs` 1.22.0 has an `_sbt2_3` build.
- `scala3-library_sjs1_3` has 3.9.0 and 3.8.4. sjs1_3 builds exist for cats-core/kernel 2.13.0, kittens 3.5.0, scallop 6.0.1,
  specs2 5.9.1 (core, matcher-extra, scalacheck), scalacheck 1.20.0. None for scala-parallel-collections, cask or the four Java
  libraries (and Scala.js cannot compile Java sources).
- Project ids are `tda4j` (JVM) and `tda4jJS`; `target/out/{jvm,sjs1}/scala-3.9.0/tda4j`; `Test/update` is not an sbt 2 task (the
  cloud setup script's warm-up step had been failing silently on it; fixed to `Test/externalDependencyClasspath`).
- Scala.js compiles against the JDK on the classpath: **missing `java.*` classes only show at link time**
  (`-Dorg.scalajs.linker.maxlinkingerrors=100000 tda4jJS/Test/fastLinkJS` lists them all).
- `val g = js.Dynamic.global` does not compile on Scala.js (the global scope cannot be a value).
- Maven Central 429s on cold dependency resolution (`install-sbt.sh` documents the paced retry).

## What carries over unchanged (answer to 1)

Everything under `algebra/`, `cells/`, `homology/` (all engines), `barcode/`, `sset/`, most of `streams/`, `alpha/` (exact
predicates use `java.math.BigDecimal`, which Scala.js provides), `plot/` (string building), the `matlab` facade's logic, the
`cli`, with cats, kittens and scallop. On the first link attempt only the pieces below failed.

## What needed replacing (answer to 2, 2.a)

| JVM-only | replacement | candidates weighed |
|---|---|---|
| commons-math3 (SVD, LU, CG, `RealMatrix`, `Erf`) | `DenseMatrix`/`DenseVector`/`ConjugateGradient`/`Erf` (one-sided Jacobi SVD, partial-pivot LU, Cody's erf) | npm `ml-matrix`, `mathjs`, `numeric.js` through facades (runtime dependency on a numerically delicate corner); spire (no SVD); breeze, smile (JVM only); EJML (Java, cannot be compiled by Scala.js) |
| commons-numbers `BinomialCoefficient` | exact `Long` binomial in `SimplexIndexing` | none needed |
| jvptree | `VantagePointTree` (fixes a bug: jvptree's k-NN can return fewer than k points) | `kdbush`/`flatbush` (npm) |
| miniball | `MinimumEnclosingBall` (Wolfe's active set; Welzl is exponential in the boundary size) | none |
| scala-parallel-collections | `Parallel` (pool on the JVM, loop on JS) | Web Workers (open issue) |
| `TrieMap` (does not link on Scala.js) | `FiltrationCache` | |
| `LinkedBlockingDeque` in the Helix walk | `mutable.ArrayDeque` | |
| `java.util.Locale` (absent) | `plot.RootText` (`Intl.NumberFormat` for a locale's separators) | `scala-java-locales` (locale data to configure) |
| `java.nio.file`, `java.io.File`, `scala.io.Source.fromFile` | `FileAccess` (Node `fs`, found with `process.getBuiltinModule`) | `scalajs-dom` for browsers has no file system anyway |
| cask viewer, `javax.imageio`, `java.awt.Desktop` | JVM only; JS: page/iframe or temporary HTML, `CubicalImage.fromImageData` | `pngjs`/`sharp`/`jimp` for PNG on Node (open) |
| `System.exit`, `ThreadLocal.withInitial`, `FileNotFoundException` | `ProcessExit`, a `ThreadLocal` subclass, `FileAccess.noSuchFile` | |
| Scallop name guessing (reflection) | every option names itself (help text byte-identical, checked) | |
| a classpath resource for the version | generated `LibraryVersion` source | |

Public API that moved (all in the changelog under Breaking): `Plot.save`/`PersistenceResult.save` return `String`;
`Barcode.*` matrices and `AlphaShapes.Point` are `DenseMatrix`/`DenseVector`; `JVPTree` is `VantagePointQuery`;
`Hyperplane.from` has a fixed normal sign; `nearestNeighbors` ties follow element order.

## Method

Built the skeleton and the platform seams myself (`Parallel`, `FileAccess`, `Viewer`, `CubicalImagePlatform`, then
`FiltrationCache`, `RootText`, `ProcessExit`), delegated the two independent numerics workstreams to sub-agents in worktrees
(linear algebra + erf; VP-tree + ball + binomial), each with oracle specs against the Java library, merged both and checked
the merge here (JVM suites, then the whole JS suite). Both agents were interrupted once by an API rate limit and resumed; their
reports were checked against the merged tree, not taken as given.

## Verified

- JVM: `sbt tda4j/testFull`: 1532 examples, 1524 passed, 8 skipped (benchmarks), 3 min 17 s. Lint (`scalafmtSbtCheck ;
  scalafmtCheck ; Test / scalafmtCheck`) clean on both rows; `TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc` builds (it caught a
  non-compiling fence on the new page); `sbt tda4j/assembly` and the CI smoke run (one degree-1 bar on the noisy circle);
  `tda4j/mimaReportBinaryIssues` (empty baseline in this series).
- Scala.js on Node 22.22: **`tda4jJS/testFull`: 1056 of 1056 examples pass, 16 min 34 s** (the JVM takes 3 min 17 s; the JS
  figure includes linking and specs2's slower JS runtime; not a like-for-like speed measure).
- The first full JS run had 31 failures in three groups: specs2's `throwA[E]("message")` reports a mismatch on Scala.js for
  messages that match (21; fixed with `SpecThrows.throwWith`, same regex-find semantics), `Double.toString` printing `1.0` as
  `1` (10; expectations now build their numbers with `${1.0}`), and none in the library. The new shared `FileAccessSpec`
  (13 examples, byte-level: UTF-8, little-endian Ripser float32, DIPHA magic) found a real bug in the JS `readHead`.
- The packaged npm tarball (`tda4jJS/npmPack`, 754 KB; `internal-*.mjs` 4.6 MB unpacked), installed into an empty project:
  library API (bars, representative, SVG, error message on a bad option, image input), `npx tda4j` (text output, missing-file
  message and exit code 1, `--help`, `--plot --output` SVG), strict **TypeScript** against `index.d.ts` (including two
  `@ts-expect-error` checks), and **headless Chromium** loading `main.mjs` as an ES module (Vietoris-Rips and image
  persistence, `save` throwing the "no file system" message, `plot.show()` appending an iframe).
- Benchmark harness (`bench/`): `run.sh setup python tda4j tda4j-js ripser`, then `run` and `sweep-cubical` on small synthetic
  inputs under the case names: every `tda4j-js` barcode agreed with ripser.cpp or GUDHI at p = 2 and 17, the summary has the
  new JS-against-JVM section, an unset JS build gives `missing` rows. The numbers of that run mean nothing (tiny inputs, a shared
  sandbox); the harness is ready for the real data on the compute server.

## Not verified

- **No GitHub Actions run.** The new `test-js`/`npm-package` jobs, `actions/setup-node@v6` (version not checked) and the
  release changes have not run; the `npm publish` step never ran (no token). The YAML parses.
- Node 20.16 (the stated minimum) was not run, only 22.22. Windows and macOS not run.
- **Performance on real sizes**: the issue-#81 estimate (about 3x on small VR) is the only figure; the packed engines' `Array[Long]`
  columns are emulated on Scala.js; the WebAssembly backend is unmeasured. The harness measures it.
- `fullLinkJS` makes some checks unchecked (array bounds, casts); the npm package ships that build, the tests run `fastLinkJS`.
- `Math.pow`, `sin` and `cos` last digits and `Double.toString` differ from the JVM (the CLI prints `1` where the JVM prints
  `1.0`); no spec depends on the difference any more, a user's script might.
- Bundle size (4.6 MB unpacked, 754 KB packed): `withMinify(true)` changes nothing (names are already minified by `fullLinkJS`);
  the Closure Compiler was not tried.

## Open items

Filed as issues (see #81 for the plan this arc executed): Java-style `Double` formatting on JS; image files on Node; browser
smoke test in CI; JS performance on real sizes and the WebAssembly backend; Web Worker parallelism; string-based parsers for
the file formats; circular/toroidal coordinates and landmarks in the JS API; bundle size; `rules/engines.md` over its size budget;
the demo's `JVPTree` references; MiMa/coverage for the Scala.js artifact.
