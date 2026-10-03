---
paths:
  - "src/**/matlab/**"
  - "src/**/cli/**"
  - "src/**/io/**"
  - "src/**/barcode/PersistenceFilter*.scala"
  - "_docs/user-guide/matlab.md"
  - "_docs/user-guide/cli.md"
  - "matlab_example.m"
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
Logic lives in `PersistenceFilter` (opt-in for Scala callers: `PersistenceFilter.significant`). Invariants:
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
  Perseus `-1` → `+∞` (missing cell).

## CLI executable

`cli`, `WORKLOG-cli-executable.md`. `sbt assembly` → `java -jar tda4j-<version>-assembly.jar` (sbt 2: under `target/out/jvm/scala-3.9.0/tda4j/`).
Scallop (zero deps). Every compute flag mirrors a `matlab.TDA4j` option key 1:1 with **no Scallop default** —
omitted keys let `TDA4j` apply its own defaults (one source of truth). `--output-format=perseus` refused for
non-integral filtrations. `TDA4jCLI.run(args, out): Int` is testable in-process, but Scallop's `onError` calls
`System.exit` on any parse error or `--help`/`--version` — `CLISpec` must never pass malformed flags. Scallop
`opt[Boolean]` has always-supplied toggle semantics (`WORKLOG-naming-and-dispatch-expansion.md`). `--distance-to`
(+`--distance-format`/`-order`/`-ground-norm`) mirrors `BarcodeDistance` instead; only
`--output-format=text` works with it.

## MATLAB API

`matlab` (`TDA4j.scala`, `PersistenceResult.scala`), `WORKLOG-matlab-api.md`. Java-facing facade: public methods
take/return only `double`, `int`, `String`, `double[][]`, `String[]` — no `Map`, generics, or Scala types
(project lead rejected a `Map`-based design). Options are a flat key/value `String[]`; `dispatch` parses each
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
- **Two-step witness recipe**: `selectLandmarksFrom{Points,DistanceMatrix}` → `LandmarkSelectionResult`, then
  `computeFrom{Points,DistanceMatrix}AndLandmarks` (takes that `int[]` directly, never re-selects);
  `coveringRadiusFrom{Points,DistanceMatrix}` queries R for a hand-picked set. CLI: `--select-landmarks`/
  `--landmarks-file`. `WORKLOG-witness-two-step-api.md`.
- `representativeType` = `cycles` (default) / `cocycles` (only `ripser`/`cohomology`; others throw); with
  cocycles an image defaults to `cohomology`, not `fast-cubical`. In every allowlist but `landmarkSelectionKeys`.
- Default `engine`: `ripser` for `vr`/lazy witness, `fast-cubical` for images of dimension >= 2, **`cohomology` for
  everything else** (relations, general witness included): the homology engines reduce every top cell and take minutes at degree 2
  (`WORKLOG-default-degree-2.md`); `TDA4jSpec` pins the default against an explicit `engine=cohomology`.
- `maxDimension` (default 2) = top homological degree. `ripser`/`chunks` pass it straight through; `naive`/
  `cohomology` wrap the stream in `LimitedCofaceSimplexStream(..., k+1)`. Alpha needs no +1.
- Field: `Z` (prime, default `prime=17` = `FiniteField.DefaultPrime`, was 2) or `R` (`Field.DoubleApproximated`, internal specs' own default).
- `PersistenceResult`: `toArray()` eager; `cycleVertices`/`cycleCoefficients` lazy, throwing
  `UnsupportedOperationException` for a bar with no representative (every engine records one — an engine
  bug, not an expected gap).
- **Boundary-matrix export**: `numCells`/`boundaryRows`/`boundaryCols`/`boundaryValues`/`columnDimension`/
  `columnVertices`/`columnFiltrationValue`, lazy, byte-identical across engines.
- **Circular coordinates**: `h1Bars(points)`/`circularCoordinates(points, r[, cocycleIndex, prime])` →
  `CircularCoordinatesResult`. **Toroidal coordinates**: `toroidalCoordinates(points, r, cocycleIndices[, prime,
  reduce])` → `ToroidalCoordinatesResult` (separate class). Neither has a CLI mirror.
- Unverified: MATLAB's bundled JVM version and actual marshalling.
