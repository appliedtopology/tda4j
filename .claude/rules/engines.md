---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/**"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/**"
  - "src/test/scala/org/appliedtopology/tda4j/homology/**"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/**"
---

# Persistent homology engines, barcode distances, circular coordinates, benchmark specs

Loads when you work in `homology/` or `barcode/` (main or test). Project-wide rules are in `.claude/CLAUDE.md`.

## Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives — a fix in one doesn't imply others need it. `maxDim`/
`maxDimension` = top homological degree in the engines and the facade (engines build one dimension higher internally; NOT the stream constructors: `IncrementalVietorisRipsSimplexStream.maxDimension` is the top SIMPLEX dimension, coface streams have none — use `VietorisRips`, which takes the homological degree; `DESIGN-stream-naming.md`;
`WORKLOG-maxdim-semantics-fix.md`). Naive and `CellularCohomologyEngine` have no such param: callers truncate
via `LimitedCofaceSimplexStream(stream, k+1)` and drop `dim==k+1` bars. Over a field, cohomology/homology
barcodes coincide.

1. **Naive** (`CellularHomologyEngine`/`SimplicialHomologyEngine`) — single-pivot-table reduction, no clearing;
   the reference baseline. Incremental (`advanceOne`/`advanceTo`/`advanceAll`, `diagramAt`/`barcodeAt` via
   V-columns). A raw-UnionFind fast path was measured and rejected (`WORKLOG-autonomous-session-2026-09-19.md`).
   `persistentHomology(stream, k)` CAPS it at degree `k` (the verb does): cells above dimension `k + 1` are not read, and a
   `k + 1` cell keeps only the pairing it makes (no V-column, generator, fallback entry or class of degree `k + 1`) and is
   not reduced at all while no class of degree `k` is open (a cycle's reduced boundary has an open cell as pivot, so it
   would reduce to zero; a skipped cell's faces must still have been seen, else it throws as `reduceCell` does). The verb
   and `PersistenceEngine.naive`/`naiveCocycles` (MATLAB, CLI) cap at the stream's `homologyDegreeLimit`; a complete
   complex is read whole. The uncapped cursor stays the reference: the opt-in pairing checks count every cell. Cells are
   compared by rank (`CellRanks`, under Chunks) (`WORKLOG-chunks-top-cells.md` Steps 4-5,
   `WORKLOG-naive-cap-review-2026-10-15.md`; `NaiveCappedSpec`).
2. **Chunks** (`CellularPersistenceInChunksEngine`) — clear-and-compress chunked algorithm, walks `0..maxDim+1`,
   filters essentials to `<=maxDim`. Dims 0/1 via raw union-find (`unionFindDim01`, `DESIGN-unionfind-in-chunks.md`): an
   edge zero in the field (no entries, a loop's `(v,c),(v,-c)`) closes a cycle, its own representative; an edge not
   `c·(v-u)` (`edgeShape` Irregular) hands dims 0/1 to the general reduction. Degree-1 representatives (tree paths) are
   built on `barcodeAt`'s first request, never in the edge pass (`WORKLOG-union-find-robustness.md`).
   `barcodeAt` via memoized `vcolOf` (a second full naive engine was **rejected by the project lead**, don't
   revive — `WORKLOG-chunks-representatives-incremental.md`). Invariants: a paired cell never becomes a pivot;
   `compress` runs to a fixpoint; a reconciliation step resolves "in limbo" cells first
   (`WORKLOG-benchmark-and-chunks-bug.md`, `WORKLOG-chunks-pairing-bug.md`). Parallel redesign attempted and
   invalidated by measurement (`WORKLOG-parallelization-survey.md`). Edge-block units on threads are exact: 1.0x on
   4 cores on explicit coboundaries, 2.2-3.3x on implicit ones, where the packed engine's own costs (decoding, sort)
   dominate (#192, #194, `WORKLOG-layered-inorder.md`).
   `advanceAll` runs the reduction ONCE (`advanced`). At `maxDimension = 1` (union-find has the edges) the triangles go in
   filtration order against a count of open degree-1 classes (`reduceTrianglesInOrder`): one that enters while none is
   open reduces to zero and is skipped, as in the capped naive engine; from `maxDimension = 2` every top cell is still
   reduced (#51). Cells are compared by RANK (`CellRanks`, shared with the naive engine: the stream's cells sorted once by
   `stream.filtrationOrdering`, an `IdentityHashMap`, falling back to that ordering for any other object); a cell fresh
   from `boundary` must go through `boundaryChain` (`canonical`: the stream's own object) or it takes the slow path.
   Ranks exist only if EVERY cell has a filtration value (`ranked`; #88): the ordering is a weak order on those, and a
   stream with a value-less cell keeps its own ordering. `WORKLOG-chunks-top-cells.md`.
3. **`RipserCohomologyEngine`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/
   reference oracle only** — production uses `PackedRipserCohomologyEngine`. Clearing required for correctness;
   apparent pairs with lazy substitution; emergent pairs (Def 3.11) not implemented; `memoizeFiltrationValue`
   defaults **false** (project lead: memory over speed) (`WORKLOG-ripser-profiling.md`,
   `WORKLOG-packed-ripser-engine.md`).
   The PACKED engine numbers simplices with one `Long` index or, when C(n + maxDim + 2, maxDim + 2) passes 2^63, two
   words (`WideSimplexIndexing`; `usesWideIndices`, chosen in the constructor; `WideKeys` numbers two-word cells for the
   pivot maps and cleared set). Every new index-keyed structure carries the high word, and only in a two-word run
   (`WideDiameterIndex`, `WideColumn`, null arrays; a one-word run has no second word in memory), and `WideIndicesSpec` /
   `WideIndicesPastLongSpec` hold the two paths equal; a comparison of the high word is observable only when two
   simplices share their low 63 bits, so it has unit tests only (`WORKLOG-two-word-indices.md`).
   The PACKED engine reduces on a primitive-array heap, expands cocycles lazily from
   reduction logs (only for reported bars), and derives cycles with its own copy of `Involution.cycles`. It must
   equal the `Chain`-based engine kept in the test tree (`ChainPackedRipserReference`) term for term, cocycles and
   cycles, essentials included (`PackedWorkingColumnSpec`): re-run it after any change to the reduction
   (`WORKLOG-vr-working-column.md`). The heap needs ONE diameter per simplex: non-trusted metrics are read as
   `d(max, min)`, the lower triangle like `ExplicitMetricSpace` (else an asymmetric one grows the column until
   OOM); Euclidean/Explicit are read as given (canonicalizing breaks the distance cache's row-wise walk). Under a sparse threshold cofacets come from per-vertex NEIGHBOUR LISTS (`NeighbourLists`,
   `SparseCofacetCursor`), which must give the all-vertex scan's cofacets in the same order with bit-identical
   diameters (stored distances are the engine's own `distance`); `NeighbourListsSpec` pins it term for term
   (`WORKLOG-neighbour-lists.md`). A uniform sign flip of every coboundary changes no output (a real symmetry): sign
   mutations must be non-uniform to test anything. Per-simplex state is PRIMITIVE (`WORKLOG-packed-engine-memory.md`):
   levels (`Level`), pairs (`Pairing` rows; bars built only when returned), cleared set (sorted `ClearedSet`), seeds
   (`LongIntMap`); apparent pairs' involution columns are rebuilt, not stored. Never reintroduce a per-pair object.
4. **`CellularCohomologyEngine`** (`Cohomology.scala`) — generic over `CellT: OrderedCell`, fully-materialized
   streams only, no `maxDim`/apparent pairs. Only essential bars' V-columns are cocycles; finite bars' V-columns
   are their reduced pivot chain. Representatives don't match Ripser term-for-term (tie direction differs) but
   bar values do. Sign-tested on RP² over Fp (3) (`WORKLOG-generic-cohomology.md`).
   Reduces over **dense cell numbers** (per-dimension position in the engine's order), its involution too; must
   equal the cell-keyed reduction kept in the test tree (`CellKeyedCohomologyReference`) bar for bar, pair for pair,
   term for term and in list order (`CohomologyNumberingSpec`): re-run it after any change to the reduction
   (`WORKLOG-dense-cohomology.md`). On a `CubicalGridStream` the verb runs `PackedCubicalCohomologyEngine`,
   which must equal this engine there (`rules/cubical.md`). Simplices with `Int` vertices under an instance from
   `simplexIsOrderedCell` (marked `StandardSimplexBoundary`) are reduced on flat arrays inside this class; the result
   must equal `packSimplices = false` term for term (`PackedSimplexCohomologySpec`), so a column needing an addition
   still goes through `Chain.reduceBy` (#193, `WORKLOG-layered-inorder.md`).

**Cycles from cohomology (`Involution`, `homology/Involution.scala`)**: both cohomology engines expose
`pairedCohomology` (bars + birth/death cells, zero-length pairs INCLUDED: their columns are pivots) and
`persistentHomology` (same bars, cycles). Only death columns are reduced, under the engine's own order reversed
(youngest = `Chain` leading term); a pivot that is not the paired birth cell throws (a pairing/order mismatch is a
bug, never fall back). Finite bar cycle = `R_τ` (closed, youngest cell σ, `= ∂V_τ` with youngest τ); essential = `V_σ`.
Dual direction (`Involution.cocycles`/`cocycleBars`): from a homology pairing (naive/chunks `pairing`, under
`stream.filtrationOrdering`), reduce only birth cells' coboundaries (built by `coboundariesOf` from the stream),
youngest first; representative `V_σ` (as the cohomology engines report). Chunks' and naive's pairings are identical,
union-find included (`InvolutionSpec`). Fast cubical's union-find ties by value, not the stream order: no dual path,
image cocycles go through the cohomology engine. Native kind is faster (derived cycles ~1.1-2.5x,
cocycles 2.5-3x; `WORKLOG-involution.md`). Different valid cycles from chunks/naive (they report `V_σ` after their own
substitutions): never compare representatives cell for cell across engines; `InvolutionSpec` checks validity on
tie-heavy inputs.

Every engine takes `includeZeroLength` (default `false`) on its bar-returning methods and drops `birth == death` pairs
from the pairing itself; `PersistenceBar.dropZeroLength` is the shared filter. The naive and chunks engines' `diagramAt(f)`
report a class alive at `f` as `[birth, f]` (closed end), so a class born exactly at `f` survives the filter; a finished bar is
`[b, d)`. Fast cubical/alpha drop equal-value pairs at construction (a pair at `+Inf, +Inf` is otherwise indistinguishable
from an essential class). `ZeroLengthBarsSpec`.

The opt-in pairing checks (`totalBarsAccountForAllCells` with `includeZeroLength = true`) stay in the engine specs
(project lead, 2026-10-03): they test the pairing invariant on purpose; nothing else should count bars against cells.

`Chain.reduceBy`/`reduceByUntil` throw (`IllegalStateException`) when the pivot moves back, or stays put for 64 steps
(rounding over the reals may keep it for a step or two): a basis column whose leading cell is not its key, or an order
mismatch, used to loop forever (`WORKLOG-compact-representatives.md`).

Testing lessons for every engine: F2 hides sign errors; signed-field fixtures need ≥5 vertices (`Set1..Set4`
hash-order past 4 elements, `SimplexBoundarySpec`/`SignedFieldBarcodeSpec`, `WORKLOG-code-critique.md` §1.1).
F3-vs-F2 agreement is a cheap sign oracle. Two engines agreeing isn't proof if they share a truncation/code path
— hand-derived fixtures are the real oracle.

`Barcode.scala`: `BarcodeEndpoint` (open/closed/±∞), `PersistenceBar`, algebra on finitely-presented persistence
modules.

**`BarcodeDistance`/`Vectorization`**: bottleneck/Wasserstein distance and persistence landscapes/images,
`PersistenceBar[Double,_]`-specialized. Ground-norm/aggregation matches Hera/GUDHI; persistence-image matches
`scikit-tda/persim`. Essential bars: matched by sorted birth for distance (count mismatch → `+Infinity`);
included by landscapes but dropped by images. `matlab.PersistenceResult` exposes both; CLI `--distance-to`
mirrors only distance. `WORKLOG-bottleneck-wasserstein-vectorizations.md`.

**`CircularCoordinates`** (de Silva-Morozov-Vejdemo-Johansson 2011): `h1Bars` lists persistent H¹
`(birth,death)` by persistence descending (pick `r` from this first); `compute(metricSpace, r, cocycleIndex,
prime=47,...)` computes cohomology of the *static* truncated complex `K_r` directly (essential there by
construction, matched to the full-filtration bar by birth value). Odd prime field only (p=2 can hide torsion);
integer lift checked **exactly** per triangle, `NoIntegerCocycleException` otherwise. Harmonic smoothing via
our `ConjugateGradient` (`algebra/`, `rules/algebra.md`), restricted to the cocycle's connected component, anchored at `g=0`;
`theta(v)=frac(g(v))` directly, no path integration. MATLAB mirrors this; no CLI (picking `r` is two-step/
data-dependent). `WORKLOG-circular-coordinates.md`.

**Toroidal coordinates** (`computeToroidal`, Scoccola-Gakhar-Bush-Schonsheck-Rask-Zhou-Perea 2022,
arXiv:2212.07201): combines `k` *simultaneously*-alive H¹ classes (common `r`, same connected component of
`K_r` — checked) into one torus-valued map, via `LatticeReduction` (hand-rolled LLL on the classes'
harmonic-cochain Gram matrix's Cholesky factor, `delta=3/4`), applying the resulting unimodular `U` to the
already-computed per-class `theta`s (linearity of harmonic smoothing). Not a port of DREiMac's `toroidalcoords.py`
(its `_gram_schmidt` bug: `.claude/BUGS-IN-REFERENCES.md`; claim no more than is checked there). MATLAB:
`toroidalCoordinates`/
`ToroidalCoordinatesResult` (separate class, MiMa); no CLI. `WORKLOG-toroidal-coordinates.md`.

## Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing over point count/dimension/`maxDim`
(construction and reduction apart, per-cell timeout). Alpha and VR bar counts are never compared (radius vs diameter).

**Benchmark specs** (`ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `ThresholdBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, `EdgeCollapseBenchmarkSpec`, all in
`homology`) print timing tables; only an exception fails. All `skipAll` unless `-DrunBenchmarks=true` (a JVM property, not
specs2 `--` syntax); scope with `testOnly` (`EngineComparisonBenchmarkSpec`: 15+ min; `RipserPaperBenchmarkSpec` also needs
`-DdataDir`, optionally `-DripserBin=<path>`: `.claude/scripts/run-ripser-paper-benchmark.sh`).

**`HomologySpec`'s `BarcodeRegressionSpec` runs on 25 - 30 points in 2 - 6 dimensions.** The original 25 - 150 points in
2 - 10 dimensions cannot be enumerated by the constructions (unthresholded Vietoris-Rips to dimension 4 is out of memory
at 100 points, alpha at ambient dimension 10 takes 20+ s at 25 points), whatever the engine
(`WORKLOG-chunks-top-cells.md` has the sweep).

**Query contract (naive + chunks, `DiagramQuerySpec`, `WORKLOG-cursor-and-verb.md`)**: `diagramAt(f)`/`barcodeAt(f)`/
`diagramWithGeneratorsAt(f)` give the diagram truncated at `f` -- bars born `<= f`, deaths capped at `f`, a class alive
at `f` reported as dying at `f` unless no cell enters after `f` (then essential) -- REGARDLESS of where the naive
cursor is. The cursor is kept on purpose (project lead): long runs must be inspectable and keep their
output if they die; `advanceFor(budget)` runs it in time slices, `processedCells`/`totalCells` report progress.
