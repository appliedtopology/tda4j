---
paths:
  - "src/**/matlab/**"
  - "src/**/cli/**"
  - "src/**/io/**"
  - "src/**/barcode/PersistenceFilter*.scala"
  - "_docs/user-guide/matlab.md"
  - "_docs/user-guide/cli.md"
  - "examples/matlab_example.m"
---

# The MATLAB facade, the CLI and file I/O (and which bars they report)

Loads when you work in `matlab/`, `cli/` or `io/`, or on the persistence threshold. Project-wide rules are in `.claude/CLAUDE.md`.

## Which bars are reported (persistence threshold)

**Zero-length bars (`birth == death`) are dropped by default everywhere** -- engines, `Persistence`, facade, CLI
(project lead: seeing them is the opt-in). Engines take `includeZeroLength = false` and filter on the true pairing,
never after truncation; the facade asks engines for everything and drops them in `ThresholdSpec` unless option
`includeZeroLength` (CLI `--include-zero-length true`) is set. Tests that check `#bars` against `#cells` must opt in
(`HomologyFixtures.totalBarsAccountForAllCells`); stream specs check `respectsOrderingContract` instead of counting
bars. `ZeroLengthBarsSpec`, `WORKLOG-zero-length-and-docs-audit.md`. Beyond that, the facade
(`matlab.TDA4j`, hence CLI + MATLAB) hides bars by default: **kept iff essential or persistence > 1% of the
input's minimum enclosing radius** (`metricSpace.minimumEnclosingRadius`, Ripser's enclosing radius, NOT the
connectivity radius; for a cubical image / Dowker relation, the range max − min of its values; 0 for a single point;
non-finite → the barcode's own finite range), in the units the complex reports (VR diameters, Cech/alpha radii);
the scale is passed by-name and only computed when a fraction of it is needed. Options `minPersistence` (absolute) / `minPersistenceFraction` (default `0.01`),
at most one; **`0` keeps every bar of positive length**; CLI `--min-persistence`/`--min-persistence-fraction`
(mirrored, no Scallop default; stderr note when bars were hidden; rejected with `--select-landmarks`/`--distance-to`).
Logic lives in `PersistenceFilter` (opt-in for Scala callers: `PersistenceFilter.longerThanFraction`). Invariants:
filter is post-hoc, applied by the thin `dispatch*` wrappers (validated before computing); `PersistenceResult` keeps
the FULL arrays + a `visible` index, and **distances/landscapes/persistence images and `--distance-to` always use the
complete barcode**; new facade option keys must go in every strict allowlist except `landmarkSelectionKeys`.
**Tests asserting on complete barcodes (every positive-length bar) must call the test-only shims `FullBarcode` (matlab) / `CliFull` (cli)**, not
`TDA4j`/`TDA4jCLI` directly — and a one-line search/replace misses call sites split across two lines (this bit once).
`h1Bars`/`circularCoordinates` are unfiltered (`cocycleIndex` indexes `h1Bars`). `WORKLOG-persistence-threshold.md`.

## File I/O

`io`, `WORKLOG-io-module.md`. Every format was verified against its project's primary source; unverified formats
(Perseus simplicial toplex, PHAT, sparse triplet distance matrices) are deliberately not implemented.
- Ripser binary distance matrices are **float32**, not double.
- DIPHA/Perseus cubical axis order is **first axis fastest** — opposite `fromFlatArray`; readers/writers reverse
  shape.
- Diagram readers rebuild finite bars via `PersistenceBar.apply(dim, lower, upper)` so round trips compare equal.
  Perseus `-1` → `NaN` (the mask: missing in a superlevel reading too; `rules/cubical.md`).

## CLI executable

`cli`, `WORKLOG-cli-executable.md`. `sbt assembly` → `java -jar tda4j-<version>-assembly.jar` (sbt 2: under `target/out/jvm/scala-3.9.0/tda4j/`).
Scallop (zero deps). Every compute flag mirrors a `matlab.TDA4j` option key 1:1 with **no Scallop default** —
omitted keys let `TDA4j` apply its own defaults (one source of truth). `--output-format=perseus` refused for
non-integral filtrations. `TDA4jCLI.run(args, out): Int` is testable in-process, but Scallop's `onError` calls
`System.exit` on any parse error or `--help`/`--version` — `CLISpec` must never pass malformed flags. Scallop
`opt[Boolean]` has always-supplied toggle semantics (`WORKLOG-naming-and-dispatch-expansion.md`). **Scallop gives a
short flag to the FIRST declared option with that letter**: a new option declared earlier silently takes a letter users
already type (`--nan-mask` took `-n` from `--num-landmarks`); new options take `noshort = true`, and `CLISpec` pins
every 0.5.0 short flag (`WORKLOG-nan-mask.md`).
`--distance-to`
(+`--distance-format`/`-order`/`-ground-norm`) mirrors `BarcodeDistance` instead; only
`--output-format=text` works with it.
**`--plot`/`--save-result`/`--input-format result`** (`WORKLOG-facade-plots.md`): a saved result (sniffed when the
file starts with `{` and `--input-format` is not given) refuses every flag `buildOptions` passes, so a new computation
flag is refused automatically; plot flags are checked in `run`, never with Scallop `choices`/`validate` (they exit the
JVM); a plot flag a kind does not use is an error. `--plot a,b` (2-D kinds) is a `Board`: `Plot.side`, or `Plot.layout` filled
row by row with `--layout RxC`; look flags go through `Board + tweak` (`--title` titles the board), `--tag` is refused for it
(`--tagged` letters the panels), 3-D kinds stay alone (`WORKLOG-plot-issues-sweep.md`). `--plot` without `--output` writes a temporary page and
`Viewer.browse`s it: specs set `tda4j.plot.browser=none` (`PlotCLISpec`). `CliFull` prepends `--min-persistence 0`,
which a saved result refuses: call `TDA4jCLI.run` directly there.

## MATLAB API

`matlab` (`TDA4j.scala`, `PersistenceResult.scala`), `WORKLOG-matlab-api.md`. Java-facing facade: public methods
TAKE only `double`, `int`, `String` and arrays of those — no `Map`, generics, `Option` or default arguments (project
lead rejected a `Map`-based design) — and return those or a library object MATLAB calls methods on (project lead
2026-10-12: MATLAB holds any library object, it cannot instantiate parametrized types): a result class, a
`plot.Figure`/`Scene` (`view()`, `save(path)`, the no-default overload Java needs). Options are a flat key/value `String[]`; `dispatch` parses each
once into a private `ComplexKind`/`EngineKind`/`CoefficientKind` enum before anything runs.
- Error messages and `--help` text say what to do, in user terms: never point at `.claude/`, CLAUDE.md or a private
  class's doc.
- `computeFromPoints`/`computeFromDistanceMatrix`: `complex` = `vr`/`alpha`/`cech`/`witness`/`dtm-rips`/`dtm-alpha`/
  `sparse-rips`; `engine` = `ripser`/`naive`/`chunks`/`cohomology` (Alpha and dtm-alpha refuse `ripser`/`chunks`;
  Cech, dtm-rips, sparse-rips, and witness/general refuse `ripser`, witness/general also refuses `chunks` — see
  `persistence-engines.md`'s streams-vs-engines table). `dtm-rips`/`dtm-alpha` need `dtmK`; `sparse-rips` needs
  `sparseEpsilon` (strictly `(0,1)`); those two alone also work from `computeFromDistanceMatrix`. A sixth
  `engine`, `fast-alpha`, is valid ONLY for `complex=alpha`+`alphaBackend=helix`. `computeFromCubicalImage`/
  `computeFromImage` — same four base engines plus `fast-cubical`.
- **`threads` and `parallel-ripser`** (#220): every `computeFrom...` takes `threads` (checked before anything runs; the
  `dispatch*` wrappers call `Parallel.withThreads`). For `vr` over `Z/p` an UNSET `engine` goes to `Persistence`'s
  `ParallelRipser` (`parallelVR`) when `Parallel.threads > 1`, no `includeZeroLength`, no `edgeCollapse`, and
  `ParallelRipser.supports`; `engine=ripser` is always `PackedRipserCohomologyEngine`; `engine=parallel-ripser` is mapped to
  `ripser` plus a flag at the top of `dispatchFull` (no `EngineKind` case) and refuses what it cannot do. The CLI mirrors
  both (`--threads`, `--engine parallel-ripser`). Specs: `TDA4jParallelSpec` (JVM; Scala.js has one thread).
- **Two-step witness recipe**: `selectLandmarksFrom{Points,DistanceMatrix}` → `LandmarkSelectionResult`, then
  `computeFrom{Points,DistanceMatrix}AndLandmarks` (takes that `int[]` directly, never re-selects);
  `coveringRadiusFrom{Points,DistanceMatrix}` queries R for a hand-picked set. CLI: `--select-landmarks`/
  `--landmarks-file`. `WORKLOG-witness-two-step-api.md`.
- `representativeType` = `cycles` (default) / `cocycles`: every engine gives both via `naiveFor`/`chunksFor`/
  `cohomologyFor` and Ripser's `persistentHomology`, except `fast-cubical`/`fast-alpha` (cycles only; cocycles throw,
  naming `engine=cohomology`). With cocycles an image defaults to `cohomology`. In every allowlist but
  `landmarkSelectionKeys`.
- Default `engine`: `ripser` for `vr`/lazy witness, `fast-cubical` for images of dimension >= 2, **`cohomology` for
  everything else** (relations, general witness included): the homology engines reduce every top cell and take minutes at degree 2
  (`WORKLOG-default-degree-2.md`); `TDA4jSpec` pins the default against an explicit `engine=cohomology`.
- `maxDimension` (default 2) = top homological degree. `ripser`/`chunks` pass it straight through; `naive`/
  `cohomology` wrap the stream in `LimitedCofaceSimplexStream(..., k+1)`. `alpha` and `dtm-alpha` are built to k+1
  and reported to k as well (`AlphaMaxDimensionSpec`; the facade used to build the whole complex, `WORKLOG-js-scaling.md`).
- Field: `Z` (prime, default `prime=17` = `FiniteField.DefaultPrime`, was 2) or `R` (`Field.DoubleApproximated`, internal specs' own default).
- `PersistenceResult`: `toArray()` eager; `cycleVertices`/`cycleCoefficients` lazy, throwing
  `UnsupportedOperationException` for a bar with no representative (every engine records one — an engine
  bug, not an expected gap).
- **Boundary-matrix export**: `numCells`/`boundaryRows`/`boundaryCols`/`boundaryValues`/`columnDimension`/
  `columnVertices`/`columnFiltrationValue`, lazy, byte-identical across engines.
- **Circular coordinates**: `h1Bars(points)`/`circularCoordinates(points, r[, cocycleIndex, prime])` →
  `CircularCoordinatesResult`. **Toroidal coordinates**: `toroidalCoordinates(points, r, cocycleIndices[, prime,
  reduce])` → `ToroidalCoordinatesResult` (separate class). Neither has a CLI mirror.
- **Euler curve** (#248): `eulerCurveFrom{Points,DistanceMatrix}(.., options)` → `EulerCurveResult` (`scales`, `values`,
  `at`, `sample`); options `maxSimplexDimension`, `maxFiltrationValue`, `threads`. JS `eulerCurve`/`eulerCurveFromDistances`
  → `EulerCurve`. CLI `--euler-curve` (+ `--max-simplex-dimension`) is a mode like `--distance-to`: it refuses every flag
  that would change a diagram, and `CliFull` leaves it without `--min-persistence 0`.
- **Plots and saved results** (#63, `WORKLOG-facade-plots.md`). A result records its `ResultSource` (method, options,
  complex, cocycles, field, landmarks, points or ORIGINAL image values), attached LAST at the public entry points (the
  `dispatch*` wrappers do not all see the input: `dispatchCubical` sees negated values). `plot...` methods draw the
  result's ARRAYS (`Plot.Terms`), never typed chains, so a loaded result draws the same SVG. `plotRepresentative(i,
  at)` uses the result's own complex (vr/cech/alpha) at `at`, in its units, never Euclidean diameter (wrong for radii).
  Refusals are `UnsupportedOperationException` worded in user terms, no method names (the CLI prints them as they are).
  The file (`ResultFile`, JSON, format version 1): every bar, `reported`, threshold, reported bars' representatives,
  source; NOT hidden bars' representatives or the boundary matrix. Non-finite numbers as strings; bare `Infinity`/`NaN`
  and a BOM read too; only format, version and the bar arrays are required; unknown keys are skipped (a new key needs
  no version bump, a changed meaning does). `ResultFileSpec`: re-save byte-identical, bars bit for bit (`-0.0`), every
  truncation an `IllegalArgumentException`.
- Unverified: MATLAB's bundled JVM version and actual marshalling (plots and `view()` from MATLAB included).
