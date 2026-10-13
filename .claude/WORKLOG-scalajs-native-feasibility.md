# Scala.js and Scala Native feasibility (2026-10-13)

Question (project lead): how much work would it take to build tda4j for Scala.js and Scala Native, and where are the
major blockers? This session made no change to the build or the library. It ran probes in scratch copies of HEAD
(`3b06a78`) and measured. Each statement below is marked **measured** or **predicted** (known platform behaviour,
not observed here).

## Toolchain: not a blocker (measured)

- On Maven Central for sbt 2 (`_sbt2_3`): `sbt-scalajs` 1.22.0, `sbt-scala-native` 0.5.12, `sbt-crossproject`,
  `sbt-scalajs-crossproject` and `sbt-scala-native-crossproject` 1.4.0. Native's `nscplugin_3.9.0` is 0.5.12.
- **The whole codebase, main and test, compiles with the Scala.js backend** under Scala 3.9.0 with `-source:future
  -language:experimental.modularity -preview`. **Main compiles to NIR with the Native plugin** (3,327 `.nir` files),
  so `into`, `is`-typeclasses, opaque types and deferred givens are fine on both backends.
- sbt 2 has no `%%%`. With the platform plugin enabled, `%%` appends the `_sjs1`/`_native0.5` suffix itself (the
  plugin sets `platform`).
- Dependencies: cats 2.13.0, kittens 3.5.0 and scalacheck 1.20.0 are published for both platforms. specs2 5.5.1 is
  published for JS but **not for Native**: Native needs specs2 >= 5.9.0 (see "specs2 5.9" below). scallop 6.0.1 is
  published for JS only: its Native artifacts are Scala 2.12/2.13 only (no `scallop_native0.5_3`).
  `scala-parallel-collections` 1.2.0 is published for Native, not for JS. commons-math3, commons-numbers, jvptree
  and miniball are Java-only.

## Scala.js link of the test suite (measured)

Probe: the real sources with the Java-only jars on the compile classpath (`intransitive()`), so the code typechecks
and the **linker** reports every reachable symbol that has no JS implementation. The cap on reported errors is a
system property: `sbt -Dorg.scalajs.linker.maxlinkingerrors=100000 Test/fastLinkJS` (the default shows 20).
741 errors. The missing classes, grouped by the tda4j code that reaches them:

| missing | reached from | where |
|---|---|---|
| `commons.numbers...BinomialCoefficient` | `RipserCofaceSimplexStream`, `InorderCoface...`, `EnumeratingCoface...`, `SimplexIndexing` | **core VR path** |
| `scala.collection.concurrent.TrieMap` internals (`MainNode`, `INodeBase`, `AtomicReferenceFieldUpdater`) | filtration caches in `SimplexStream` (VR), Cech, DTM, Witness, Sheehy, Dowker streams | **core VR path** |
| `scala.collection.parallel.*` | `RipserCofaceSimplexStream`, `CubicalGridStream`, `AlphaComplexDQPBuilder` (3 `.par` sites) | core |
| `java.util.concurrent.LinkedBlockingDeque` | `HelixDelaunayBuilder` (single-threaded `put`/`take`) | alpha |
| `commons.math3.linear.*` (SVD, LU, `RealMatrix`/`RealVector`, CG) and `special.Erf` | `AlphaShapes`, Helix, `BowyerWatson*`, `Hyperplane`/`Hypersphere`, `Barcode`, `CircularCoordinates`, `Vectorization` | alpha, barcode algebra, coordinates, vectorization |
| `jvptree` | `JVPTree` (DTM, DQP alpha, `SparseMetricSpace`) | streams |
| `miniball` | `CechFiltration` | Cech |
| `java.nio.file.*`, `java.io.File`, `scala.io.Source` (needs `PushbackReader`) | `BinaryIO`, `CSV`/`Gudhi`/`Ripser`/`Perseus`/`Dipha`, `plot.Plot` (save) | io, plot |
| `java.awt.image`, `javax.imageio` | `CubicalImage.fromBufferedImage`/`fromFile` | leaf |
| `com.sun.net.httpserver`, `java.awt.Desktop`, `java.net` | `plot.Viewer`, reached from `Plot.view` / `Figure` | plot |
| `java.lang.Class` resources, `java.io` | `matlab.ResultFile`, `cli.*` | facades |
| test-only: `java.net.http`, `javax.xml` DOM, `Thread`/`Executors`/`ForkJoinPool`, `ProcessBuilder`, reflection | `PlotSpec`, benchmark specs, `RipserPaperBenchmarkSpec`, `PersistenceResultPlotSpec`, IO specs | tests |

Two items the static grep predicted wrongly: `TrieMap` does not link (I had expected Scala.js to provide it), and
`LinkedBlockingDeque` sits behind a wildcard `java.util.concurrent.*` import in `AlphaShapes.scala`.

## Scala Native link of the test suite (measured)

Same probe with `ScalaNativePlugin`, specs2 5.9.1 and the Native `scala-parallel-collections`. `Test/nativeLink`:
**85 unreachable symbols**, 53 owners. Unlike JS, `java.nio.file`, `java.io.File`, `scala.io.Source`, `TrieMap`,
`LinkedBlockingDeque`, `ForkJoinPool`/`Executors`/`Thread`, `java.util.Properties` and parallel collections all link.
What remains: the four Java libraries (as above), scallop (`cli.*`), `javax.imageio`/`java.awt.image`
(`CubicalImage`), `com.sun.net.httpserver` + `java.awt.Desktop` (`plot.Viewer`, reached from `Plot`/`Figure`), and
test-only `java.net.http`, `javax.xml`, `Class.getMethod`. Code generation (LLVM) was never reached, so nothing was
**run** on Native.

## Running the main path on Node.js (measured)

In the JS probe, three shims stood in for a real port: a Scala `BinomialCoefficient.value` (exact `Long`,
`ArithmeticException` on overflow), `import scala.collection.mutable.HashMap as TrieMap` in the six stream files, and
an identity `.par`. With these, `Persistence(points)` (VR, Auto = Ripser engine, degrees 0..2) and
`Persistence(Image(...))` (cubical) **link and run on Node 22**. The same main ran on the JVM with unmodified sources:

- **Identical input gives bit-identical output**: on a 40-point curve in R^4 snapped to a 1/1024 grid, 108 bars
  (40/41/27 in degrees 0/1/2) have equal endpoint bit patterns and representatives of equal total size.
- **`math.cos`/`math.sin` differ by ulps between V8 and HotSpot**: the unsnapped version of that curve has different
  input bits, and the diagrams differ by one H2 bar whose birth and death are 1--4 ulps apart. That is tie-breaking on
  a highly symmetric input, not a porting bug. Cross-platform tests should use exactly representable inputs.
- **`Double.toString` differs**: `1.0`, `1.0e-7` print as `1`, `1e-7` on JS (`1.0`, `1.0E-7` on JVM). About 33 test
  assertions compare formatted doubles (`ShowSpec`, `ShowInstancesSpec`, `ResultFileSpec`, ...), and every io writer
  using `v.toString` produces different bytes (still parseable).
- **`Plot.number` misreads `Fp` on JS**: its `x.asMatchable match case d: Double => d` is `true` for a boxed `Fp`
  (any JS number is a `Double`), so on JS it would plot the raw `Int` form instead of the balanced representative.
  Verified with `Fp(16)` in `FiniteField(17)`: matches `case d: Double` on JS, not on JVM. The fix is to ask the
  `Field`, not the runtime class.
- **Rough timing, one run each**: VR on 250 points in R^3, degrees <= 2, `maxFiltrationValue = 0.35` (372 bars),
  5 repetitions: JS 420/344/357/322/290 ms, JVM 210/115/143/115/95 ms (after warm-up), about 3x. This is a small
  input. The packed engines' working columns are `Array[Long]`, and Scala.js emulates `Long`. The gap on large
  inputs is **unmeasured**.

**Predicted, not observed**: Scala.js `fullLinkJS` makes array-bounds and cast errors unchecked (undefined behaviour),
so run tests on `fastLinkJS`. Property specs with thousands of cases (`AlphaComplexSpec`) will be slower on JS.

## specs2 5.9 typed `===` (measured, Native probe)

Upgrading specs2 5.5.1 -> 5.9.1 (required for Native) made 6 test lines fail to compile: `Long` vs `Int` in
`VietorisRipsSpec`/`SimplexStreamSpec`, and two in `FiniteFieldSpec` "units". **Those two properties are vacuous on
every platform**: `prop { (x: Int) => x - x === Fp(0) }` and `x + (-x) === Fp(0)` are `Int` arithmetic, never `Fp`
arithmetic (compare the neighbouring `associativity` properties, which wrap with `Fp(x)`), so they cannot fail.

## Work breakdown (judgment, from the measurements above)

1. **Build** (~1 week incl. 2): a cross build that keeps `src/main/scala` as the shared source set, plus platform
   source directories. MiMa, assembly, docs, scoverage and the tutorial generator stay JVM-only. CI gets a JS job
   (Node) and a Native job (clang; Native linking of the suite is slow). `TDAlab`'s generated re-exports name
   JVM-only types (`CSV`, `Dipha`, `JVPTree`, `CubicalImage`), so `tdalab-exports.py` must become per-platform, or
   those names must exist everywhere. specs2 5.9 (6 test lines). About 40 specs (io, matlab, cli, plot viewer,
   benchmarks, tutorials) move to JVM-only tests.
2. **Main path, the cheap milestone** (< 1 day of code): binomial function, a platform alias for the concurrent
   cache, a `.par` shim, `ArrayDeque` for the Helix frontier queue. These alone give VR/cubical/engines/diagrams/
   sset on JS and Native (the Node run above).
3. **Full core** (~1--2 weeks; the risk is here): replace commons-math3 with a small dense numerics kit (one-sided
   Jacobi SVD, LU, CG, erf: the matrices are ambient-dimension sized). This changes public signatures
   (`AlphaShapes.Point = RealVector`, `Barcode`'s `RealMatrix` functions), and the alpha/Helix degeneracy handling
   was tuned against commons-math3's SVD, so it must be re-validated. Plus a Scala VP-tree (jvptree) and a
   minimum-enclosing-ball solver (miniball; Cech values must agree to the ulp level that `CechStream` already
   absorbs).
4. **Platform leaves** (~1 week): JS io as parse/format on strings with path wrappers on JVM/Native. `Plot.view`
   behind a platform hook (on JS, render the SVG into the DOM). `CubicalImage` file/AWT loaders JVM-only. Native CLI
   needs a scallop replacement (or `for3Use2_13`, untested) and has no image decoding. `Plot.number` and the
   double-formatting test expectations.

Total for both platforms with the suite green on all three: roughly 3--4 weeks. Native alone skips the JS-only items
(`TrieMap`, `.par`, `LinkedBlockingDeque`, io restructuring, formatting) and is perhaps 60% of that.

**Untested alternative**: if the Native goal is a fast-starting CLI or a no-JVM binary, GraalVM `native-image` on
the existing fat jar needs no core changes.

## Reproducing

Probe builds: a minimal `build.sbt` with `enablePlugins(ScalaJSPlugin)` (or `ScalaNativePlugin`), the project's
`scalacOptions`, cross-published deps via `%%`, Java-only jars `% ... intransitive()`, and the `Test /
sourceGenerators` block copied from `build.sbt`. sbt 2's launcher here rejects `-batch`. Maven Central rate-limits
parallel fetches (HTTP 429): retry `sbt "Test/dependencyClasspath"`, and after each failure `curl` every 429'd URL
into `~/.cache/coursier/v1/https/...` (this converged in 1--2 attempts).

## Follow-up (same session): the goals, and a Native speed measurement

Project lead's goals: **JS** = persistence in the browser (no CLI, no MATLAB); **Native** = more speed by leaving
the JVM, CLI important, MATLAB not needed.

**Native throughput (measured).** The main path links on Native with one shim (`BinomialCoefficient`; `TrieMap`,
`.par` and the rest are fine there). `BenchMain` = `Persistence(points, maxDimension = 2, representatives = ...)` on
Ripser's `sphere_3_192_points.dat`, 5 repetitions in one process, one 4-core sandbox, ms:

| build | cycles | cocycles |
|---|---|---|
| JVM 21 forked, `-Xms8g -Xmx8g -XX:+UseParallelGC` | 5639, 6881, 4813, 4603, 4615 | 3894, 4829, 3672, 3494, 3388 |
| Native 0.5.12 `releaseFull` + thin LTO, Immix | 6291, 5947, 5768, 5504, 5398 | 4884, 5575, 5516, 5348, 5185 |
| same, `GC_INITIAL_HEAP_SIZE=8G` | 16401, 22175, 5754, 5845, 5791 | 5205, 5028, 4966, 4750, 4924 |
| same, Commix | 6253, 5573, 6076, 6150, 6284 | 6860, 10402, 13545, 5472, 4866 |

The JVM is about 1.2× (cycles) and 1.45× (cocycles) faster at steady state, and faster even on its first, cold
repetition. Bars are 192/53/1 everywhere. Native peak RSS was 2.4 GB. The release link takes about 77 s.

**Startup (measured).** 30 points, whole-process wall time, 3 runs each: JVM 523/554/519 ms, Native 27/27/27 ms.

**Reading.** Leaving the JVM buys startup, not throughput. The gap to ripser.cpp (about 6.7–8× on sphere3_192,
`WORKLOG-vr-working-column.md`) lies in algorithm and representation (emergent pairs, boxed `Array[Any]`
coefficients, apparent-pair memory), and those carry over to every platform. The reason Native is slower here is a
hypothesis (allocation and boxing, where HotSpot's escape analysis and generational GC are strong); it was not
profiled. Re-measure Native once the hot path is unboxed.

Reproducing: `nativeConfig ~= { _.withMode(Mode.releaseFull).withLTO(LTO.thin).withGC(GC.immix) }`,
`Compile / mainClass := Some("BenchMain")`, `sbt nativeLink`. sbt 2's `export Runtime/fullClasspath` prints virtual
paths (`${OUT}`, `${CSR_CACHE}`, `>sha256-...` suffixes), which must be rewritten before `java -cp`.

## Open items (filed 2026-10-13)

- #79 `FiniteFieldSpec` "units" properties are vacuous.
- #80 `Plot.number`'s runtime type test misreads `Fp` on Scala.js.
- #81 Scala.js build: persistence in the browser (plan, measured blockers, unverified performance).
- #82 Packed engines: boxed coefficients (`Array[Any]`), `performance` + `unverified`.
- #83 Scala Native build: fast-starting CLI, no throughput gain yet (the table above; GraalVM `native-image`
  unmeasured; the test suite never run on Native).
- Already open and relevant: #57 emergent pairs.
