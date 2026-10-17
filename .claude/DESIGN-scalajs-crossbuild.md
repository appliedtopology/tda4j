# Design: the Scala.js build, the JavaScript API and the npm package

Current state, kept in step with the code (`rules/scalajs.md` has the working rules; `WORKLOG-scalajs-crossbuild.md` is the
history of how it was reached; `WORKLOG-scalajs-native-feasibility.md` is the probe that came first, issue #81 the plan).

## Goals (project lead, 2026-10-17)

A tutorial attendee asked for JavaScript. The library cross-builds to Scala.js; the MATLAB surface is not a goal for JS
(the facade stays an implementation detail of the CLI and of the JS API), the CLI is welcome. Two audiences: Scala.js
programs (a Maven artifact) and plain JavaScript/TypeScript programs (an npm package with an exported API). Where a
JVM-only library has no Scala.js build, the library gets its own implementation on both platforms and the Java library
becomes a JVM test oracle. Layout: sbt 2's default project-matrix layout (first the conventional `shared/jvm/js` layout
was chosen, then dropped for the default by the project lead the same session).

## The build

`build.sbt` is one `projectMatrix` with two rows: `tda4j` (JVM, artifact `tda4j_3`) and `tda4jJS` (Scala.js 1.22,
artifact `tda4j_sjs1_3`); sbt-crossproject is not supported on sbt 2. Source directories:

| directory | compiled for |
|---|---|
| `src/main/scala`, `src/test/scala` | both |
| `src/main/scalajvm`, `src/test/scalajvm` | the JVM row |
| `src/main/scalajs`, `src/test/scalajs` | the JS row |

JVM-only settings (docs, assembly, MiMa, tutorial generator, cask) are in `jvmSettings`. The JS row links as ES modules:
`main.mjs` (the exported API), `cli.mjs` (the `tda4j` command, a second module with `ModuleInitializer.withModuleID`) and an
`internal-*.mjs` that holds the shared code; `.mjs` so Node needs no `package.json`. `tda4jJS/npmPack` packs them.

## What carries over unchanged

Everything under `algebra/`, `cells/`, `homology/`, `barcode/`, `sset/`, most of `streams/` and `alpha/`, `plot/` (SVG
and HTML are string building), the `matlab` facade's logic and the `cli`: they are plain Scala with cats, kittens and
scallop, all of which have Scala.js builds. Exact alpha predicates use `java.math.BigDecimal`, which Scala.js provides.

## What had no Scala.js counterpart, and what replaced it

| JVM-only | used by | replacement | check |
|---|---|---|---|
| commons-math3 SVD, LU, conjugate gradient, `RealMatrix`/`RealVector`, `Erf` | alpha (Helix, Bowyer-Watson), circular coordinates, `Barcode` algebra, `Vectorization` | `DenseMatrix`, `DenseVector` (LU with partial pivoting, one-sided Jacobi SVD), `ConjugateGradient`, `Erf` in `algebra/` | JVM oracle specs against commons-math3 |
| commons-numbers `BinomialCoefficient` | `SimplexIndexing`, `SimplexStream` | exact `Long` binomial in `SimplexIndexing` | oracle spec |
| jvptree | `EuclideanMetricSpace`, `SparseMetricSpace`, DTM, DQP alpha | `VantagePointTree` | oracle spec |
| miniball | `CechFiltration` | `MinimumEnclosingBall` | oracle spec |
| `scala-parallel-collections` | 3 `.par` sites | `Parallel` (a pool on the JVM, a loop on JS) | the same answer either way |
| `scala.collection.concurrent.TrieMap` | filtration caches of six streams | a platform cache (`TrieMap` on the JVM, `HashMap` on JS) | |
| `LinkedBlockingDeque` | the Helix frontier queue | `mutable.ArrayDeque` | |
| cask, `java.awt.Desktop` | `plot.Viewer` | JVM: unchanged; JS: the page, or a temporary file opened from Node | |
| `javax.imageio`, `java.awt.image` | `CubicalImage.fromFile` | JVM: unchanged; JS: `fromImageData` (canvas pixels) | |
| `java.nio.file`, `java.io.File`, `scala.io.Source.fromFile` | `io/`, `Plot.save`, the result file, the CLI | `FileAccess`: `java.nio.file` on the JVM, Node's `fs` on JS | the io specs |
| a classpath resource for the version | `matlab.ResultFile` | a generated source file `LibraryVersion` | |

Candidates that were weighed and not taken: for linear algebra on JS, `ml-matrix`, `mathjs` or `numeric.js` through facades
(a runtime dependency to keep in step with a numerically delicate corner of the library; the alpha complex degeneracy
handling was tuned against SVD behaviour); spire has no SVD; breeze and smile are JVM-only; EJML and other Java libraries
cannot be compiled by Scala.js. For a spatial index, `kdbush`/`flatbush`; for PNG in Node, `pngjs`, `sharp` or `jimp`
(open, issue filed); for 2-D Delaunay, `delaunator` (the library has its own exact Bowyer-Watson).

## The JavaScript API

`javascript.Exports` (JS row only) wraps `matlab.TDA4j`: arrays and an options object in, a `Result` out (bars as
`{dim, birth, death}`, representatives, distances, landscapes, plots as `Chart`). The option names are the facade's. A
Scala exception becomes an `Error` with its message. `npm/index.d.ts` is hand-written and must follow the facade's option
keys. The package is ESM only (`"type": "module"`), Node >= 20.16 (file access finds `fs` through
`process.getBuiltinModule`, so the bundle also loads in a browser, where file calls throw).

## CI and release

`test.yml`: `test` (JVM), `test-js` (Node 22), `npm-package` (install the tarball into an empty project, run the library and
`npx tda4j`), `docs-build`, `mima` (JVM). `release.yml`: builds the tarball, attaches it, publishes it to npm when the
`NPM_TOKEN` secret exists; `sbt release` publishes both Maven artifacts. Account steps: `RELEASE.md`.

## Limits and open questions

One thread; no file access in a browser; image files not read on Node; Long arithmetic is emulated (the packed engines'
cost on large inputs is unmeasured on JS; the WebAssembly backend is worth a measurement); `Double.toString`, `Math.pow`,
`sin` and `cos` differ from the JVM in formatting and last digits, so some outputs differ textually and a tie-heavy input
can differ by an ulp-level bar; no MiMa for the Scala.js artifact; no JS coverage. The open items are issues.
