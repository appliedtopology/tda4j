---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/**"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/**"
  - "src/test/scala/org/appliedtopology/tda4j/homology/**"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/**"
---

# Persistent homology engines, barcode distances, circular coordinates, benchmark specs

## Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives: a fix in one doesn't imply the others need it. `maxDim`/
`maxDimension` = top homological degree in the engines and the facade (engines build one dimension higher internally).
The stream constructors differ: `IncrementalVietorisRipsSimplexStream.maxDimension` is the top SIMPLEX dimension and
coface streams have none; use `VietorisRips` (`DESIGN-stream-naming.md`, `WORKLOG-maxdim-semantics-fix.md`).
`CellularCohomologyEngine` has no such param: callers truncate via `LimitedCofaceSimplexStream(stream, k+1)` and drop
`dim==k+1` bars. Over a field, cohomology and homology barcodes coincide.

1. **Naive** (`CellularHomologyEngine`/`SimplicialHomologyEngine`) — single-pivot-table reduction, no clearing; the
   reference baseline. Incremental (`advanceOne`/`advanceTo`/`advanceAll`); a raw-UnionFind fast path was measured and
   rejected.
   `persistentHomology(stream, k)` CAPS it at degree `k` (the verb does): cells above dimension `k + 1` are not read, and a
   `k + 1` cell keeps only the pairing it makes and is not reduced while no class of degree `k` is open (its boundary
   would reduce to zero; its faces must still have been seen). The verb and
   `PersistenceEngine.naive`/`naiveCocycles` (MATLAB, CLI) cap at the stream's `homologyDegreeLimit`. The uncapped cursor
   stays the reference. Cells are compared by rank (`CellRanks`, below; `WORKLOG-chunks-top-cells.md`,
   `WORKLOG-naive-cap-review-2026-10-15.md`; `NaiveCappedSpec`).
2. **Chunks** (`CellularPersistenceInChunksEngine`) — clear-and-compress chunked algorithm, walks `0..maxDim+1`,
   filters essentials to `<=maxDim`. Dims 0/1 via raw union-find (`unionFindDim01`, `DESIGN-unionfind-in-chunks.md`): an
   edge zero in the field closes a cycle, its own representative; an edge not `c·(v-u)` (`edgeShape` Irregular) hands
   dims 0/1 to the general reduction. Degree-1 representatives (tree paths) are built on `barcodeAt`'s first request
   (`WORKLOG-union-find-robustness.md`). `barcodeAt` via memoized `vcolOf` (a second full naive engine was **rejected by
   the project lead**, don't revive: `WORKLOG-chunks-representatives-incremental.md`). Invariants: a paired cell never
   becomes a pivot; `compress` runs to a fixpoint; a reconciliation step resolves "in limbo" cells first
   (`WORKLOG-benchmark-and-chunks-bug.md`, `WORKLOG-chunks-pairing-bug.md`). A parallel redesign was invalidated
   (`WORKLOG-parallelization-survey.md`). `advanceAll` runs the reduction ONCE (`advanced`). At `maxDimension = 1` the triangles
   go in filtration order against a count of open degree-1 classes (`reduceTrianglesInOrder`) and one entering while none
   is open is skipped, as in the capped naive engine; from `maxDimension = 2` every top cell is still reduced (#91).
   Cells are compared by RANK (`CellRanks`, shared with naive: the stream's cells sorted once, looked up by identity, the
   stream's ordering for any other object); a cell fresh from `boundary` goes through `boundaryChain` (`canonical`). Ranks exist only if EVERY cell has a filtration value
   (`ranked`; #88), else the stream keeps its own ordering. `WORKLOG-chunks-top-cells.md`.
3. **`RipserCohomologyEngine`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/
   reference oracle only** — production uses `PackedRipserCohomologyEngine`. Clearing required for correctness;
   apparent pairs with lazy substitution; emergent pairs (Def 3.11) not implemented (#57); `memoizeFiltrationValue`
   defaults **false** (project lead: memory over speed) (`WORKLOG-ripser-profiling.md`,
   `WORKLOG-packed-ripser-engine.md`).
   The PACKED engine reduces on a primitive-array heap, expands cocycles lazily from reduction logs (only for reported
   bars) and derives cycles with its own copy of `Involution.cycles`. It must equal the `Chain`-based engine in the test
   tree (`ChainPackedRipserReference`) term for term, essentials included (`PackedWorkingColumnSpec`): re-run it after any
   change to the reduction (`WORKLOG-vr-working-column.md`). The heap needs ONE diameter per simplex: non-trusted metrics
   are read as `d(max, min)` (else an asymmetric one grows the column until OOM); Euclidean/Explicit are read as given
   (canonicalizing breaks the distance cache's row-wise walk). Edge-block units on threads are exact (#192, #194,
   `WORKLOG-layered-inorder.md`). Under a sparse threshold cofacets come from per-vertex
   NEIGHBOUR LISTS (`NeighbourLists`) in the all-vertex scan's order with bit-identical diameters (`NeighbourListsSpec`).
   A uniform sign flip of every coboundary changes no output: sign mutations must be non-uniform. Per-simplex state is
   PRIMITIVE (`Level`, `Pairing` rows, `ClearedSet`, `LongIntMap`; `WORKLOG-packed-engine-memory.md`); apparent pairs'
   involution columns are rebuilt, not stored. Never reintroduce a per-pair object.
4. **`CellularCohomologyEngine`** (`Cohomology.scala`) — generic over `CellT: OrderedCell`, fully-materialized streams
   only, no `maxDim`/apparent pairs. Only essential bars' V-columns are cocycles; finite bars' V-columns are their reduced
   pivot chain. Representatives don't match Ripser term-for-term (tie direction) but bar values do. Sign-tested on RP²
   over Fp (3) (`WORKLOG-generic-cohomology.md`). Reduces over **dense cell numbers** (position in the engine's order),
   its involution too; must equal the cell-keyed reduction in the test tree (`CellKeyedCohomologyReference`) bar for bar,
   pair for pair, term for term (`CohomologyNumberingSpec`): re-run it after any change to the reduction
   (`WORKLOG-dense-cohomology.md`). On a `CubicalGridStream` the verb runs `PackedCubicalCohomologyEngine`, which must
   equal this engine there (`rules/cubical.md`). `Int`-vertex simplices under `simplexIsOrderedCell` (marked
   `StandardSimplexBoundary`) are reduced on flat arrays inside this class and must equal `packSimplices = false` term for
   term (`PackedSimplexCohomologySpec`); a column needing an addition still goes through `Chain.reduceBy` (#193,
   `WORKLOG-layered-inorder.md`).

**Cycles from cohomology (`Involution`, `homology/Involution.scala`)**: both cohomology engines expose
`pairedCohomology` (bars + birth/death cells, zero-length pairs INCLUDED: their columns are pivots) and
`persistentHomology` (same bars, cycles). Only death columns are reduced, under the engine's own order reversed
(youngest = `Chain` leading term); a pivot that is not the paired birth cell throws (a pairing/order mismatch is a
bug, never fall back). Finite bar cycle = `R_τ`; essential = `V_σ`. The dual direction (`Involution.cocycles`) starts
from a homology pairing (naive/chunks, identical, union-find included: `InvolutionSpec`) and reduces only birth cells'
coboundaries (`coboundariesOf`), youngest first. Fast cubical's union-find ties by value, not the stream order: no dual path, image cocycles go
through the cohomology engine. The native kind is faster (cycles ~1.1-2.5x, cocycles 2.5-3x; `WORKLOG-involution.md`). Engines report different valid
cycles: never compare representatives cell for cell across engines (`InvolutionSpec` checks validity).

Every engine takes `includeZeroLength` (default `false`) on its bar-returning methods and drops `birth == death` pairs
from the pairing itself (`PersistenceBar.dropZeroLength`). Naive and chunks `diagramAt(f)` report a class alive at `f` as
`[birth, f]` (closed end), so a class born exactly at `f` survives the filter; a finished bar is `[b, d)`. Fast
cubical/alpha drop equal-value pairs at construction (a pair at `+Inf, +Inf` looks like an essential class).
`ZeroLengthBarsSpec`.

The opt-in pairing checks (`totalBarsAccountForAllCells` with `includeZeroLength = true`) stay in the engine specs
(project lead, 2026-10-03); nothing else counts bars against cells.

`Chain.reduceBy`/`reduceByUntil` throw (`IllegalStateException`) when the pivot moves back, or stays put for 64 steps
(a basis column whose leading cell is not its key, or an order mismatch; `WORKLOG-compact-representatives.md`).

Testing lessons for every engine: F2 hides sign errors; signed-field fixtures need ≥5 vertices (`Set1..Set4` hash-order
past 4 elements, `SignedFieldBarcodeSpec`, `WORKLOG-code-critique.md` §1.1). Two engines agreeing isn't proof if they
share a code path: hand-derived fixtures are the real oracle.

`Barcode.scala`: `BarcodeEndpoint` (open/closed/±∞), `PersistenceBar`, persistence-module algebra.

**`BarcodeDistance`/`Vectorization`**: bottleneck/Wasserstein distance and persistence landscapes/images on
`PersistenceBar[Double,_]`. Ground norm and aggregation match Hera/GUDHI; persistence images match `scikit-tda/persim`.
Essential bars: matched by sorted birth for distance (count mismatch → `+Infinity`); included by landscapes, dropped by
images. `matlab.PersistenceResult` exposes both; CLI `--distance-to` only distance.
`WORKLOG-bottleneck-wasserstein-vectorizations.md`.

**`CircularCoordinates`** (de Silva-Morozov-Vejdemo-Johansson 2011): `h1Bars` lists persistent H¹ `(birth,death)` by
persistence descending (pick `r` from this first); `compute(metricSpace, r, cocycleIndex, prime=47,...)` computes
cohomology of the *static* truncated complex `K_r` (essential there, matched to the full-filtration bar by birth value).
Odd prime field only (p=2 can hide torsion); the integer lift is checked **exactly** per triangle
(`NoIntegerCocycleException` otherwise). Harmonic smoothing by `ConjugateGradient` on the cocycle's connected
component, anchored at `g=0`; `theta(v)=frac(g(v))`. MATLAB mirrors it; no CLI. `WORKLOG-circular-coordinates.md`.

**Toroidal coordinates** (`computeToroidal`, Scoccola et al. 2022, arXiv:2212.07201): combines `k` *simultaneously*-alive
H¹ classes (common `r`, same connected component of `K_r`, checked) into one torus-valued map: `LatticeReduction` (LLL,
`delta=3/4`) of the classes' harmonic-cochain Gram matrix's Cholesky factor, the unimodular `U` applied to the per-class
`theta`s. Not a port of DREiMac's `toroidalcoords.py` (its `_gram_schmidt` bug: `.claude/BUGS-IN-REFERENCES.md`). MATLAB: `toroidalCoordinates`/`ToroidalCoordinatesResult`; no CLI.
`WORKLOG-toroidal-coordinates.md`.

## Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing over point count, dimension and `maxDim`
(construction and reduction apart, per-cell timeout); alpha and VR bar counts are never compared (radius vs diameter).

**Benchmark specs** (`*BenchmarkSpec` and `ProfilingSpec` in `homology`) print timing tables; only an exception fails.
All `skipAll` unless `-DrunBenchmarks=true` (a JVM property, not specs2 `--` syntax); scope with `testOnly`
(`EngineComparisonBenchmarkSpec`: 15+ min; `RipserPaperBenchmarkSpec` also needs `-DdataDir`, optionally
`-DripserBin=<path>`: `.claude/scripts/run-ripser-paper-benchmark.sh`). Cross-tool benchmarks live in `bench/`.

**`BarcodeRegressionSpec` (`HomologySpec`) runs on 25 - 30 points in 2 - 6 dimensions**: larger clouds cannot be enumerated
by the constructions whatever the engine (`WORKLOG-chunks-top-cells.md` has the sweep).

**Query contract (naive + chunks, `DiagramQuerySpec`, `WORKLOG-cursor-and-verb.md`)**: `diagramAt(f)`/`barcodeAt(f)`/
`diagramWithGeneratorsAt(f)` give the diagram truncated at `f` (bars born `<= f`, deaths capped at `f`, a class alive at
`f` dying at `f` unless no cell enters after `f`, then essential) wherever the cursor is. The cursor is kept on purpose
(project lead): long runs stay inspectable and keep their output if they die; `advanceFor(budget)` runs time slices,
`processedCells`/`totalCells` report progress.
