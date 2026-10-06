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
2. **Chunks** (`CellularPersistenceInChunksEngine`) — clear-and-compress chunked algorithm, walks `0..maxDim+1`,
   filters essentials to `<=maxDim`. Dims 0/1 via raw union-find (`unionFindDim01`, `DESIGN-unionfind-in-chunks.md`).
   `barcodeAt` via memoized `vcolOf` (a second full naive engine was **rejected by the project lead**, don't
   revive — `WORKLOG-chunks-representatives-incremental.md`). Invariants: a paired cell never becomes a pivot;
   `compress` runs to a fixpoint; a reconciliation step resolves "in limbo" cells first
   (`WORKLOG-benchmark-and-chunks-bug.md`, `WORKLOG-chunks-pairing-bug.md`). Parallel redesign attempted and
   invalidated by measurement (`WORKLOG-parallelization-survey.md`).
3. **`RipserCohomologyEngine`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/
   reference oracle only** — production uses `PackedRipserCohomologyEngine`. Clearing required for correctness;
   apparent pairs with lazy substitution; emergent pairs (Def 3.11) not implemented; `memoizeFiltrationValue`
   defaults **false** (project lead: memory over speed). ~19-64x behind vanilla `ripser.cpp`, gap growing with n
   (`WORKLOG-ripser-profiling.md`, `WORKLOG-ripser-comparison.md`, `WORKLOG-packed-ripser-engine.md`).
   The PACKED engine reduces on a primitive-array heap (Ripser's working column), expands cocycles lazily from
   reduction logs (only for reported bars), and derives cycles with its own copy of `Involution.cycles`. It must
   equal the `Chain`-based engine kept in the test tree (`ChainPackedRipserReference`) term for term, cocycles and
   cycles, essentials included (`PackedWorkingColumnSpec`): re-run it after any change to the reduction
   (`WORKLOG-vr-working-column.md`). The heap needs ONE diameter per simplex: non-trusted metrics are read as
   `d(max, min)`, the lower triangle like `ExplicitMetricSpace` (an asymmetric one otherwise grows the column until
   OOM); Euclidean/Explicit are read as given, since canonicalizing costs ~15% there (it breaks the distance cache's
   row-wise walk). Under a sparse threshold cofacets come from per-vertex NEIGHBOUR LISTS (`NeighbourLists`,
   `SparseCofacetCursor`), which must give the all-vertex scan's cofacets in the same order with bit-identical
   diameters (stored distances are the engine's own `distance`); `NeighbourListsSpec` pins it term for term
   (`WORKLOG-neighbour-lists.md`). A uniform sign flip of every coboundary changes no output (a real symmetry): sign
   mutations must be non-uniform to test anything.
4. **`CellularCohomologyEngine`** (`Cohomology.scala`) — generic over `CellT: OrderedCell`, fully-materialized
   streams only, no `maxDim`/apparent pairs. Only essential bars' V-columns are cocycles; finite bars' V-columns
   are their reduced pivot chain. Representatives don't match Ripser term-for-term (tie direction differs) but
   bar values do. Sign-tested on RP² over Fp (3) (`WORKLOG-generic-cohomology.md`).
   Reduces over **dense cell numbers** (per-dimension position in the engine's order), its involution too; must
   equal the cell-keyed reduction kept in the test tree (`CellKeyedCohomologyReference`) bar for bar, pair for pair,
   term for term and in list order (`CohomologyNumberingSpec`): re-run it after any change to the reduction
   (`WORKLOG-dense-cohomology.md`).

**Cycles from cohomology (`Involution`, `homology/Involution.scala`)**: both cohomology engines expose
`pairedCohomology` (bars + birth/death cells, zero-length pairs INCLUDED: their columns are pivots) and
`persistentHomology` (same bars, cycles). Only death columns are reduced, under the engine's own order reversed
(youngest = `Chain` leading term); a pivot that is not the paired birth cell throws (a pairing/order mismatch is a
bug, never fall back). Finite bar cycle = `R_τ` (closed, youngest cell σ, `= ∂V_τ` with youngest τ); essential = `V_σ`.
Dual direction (`Involution.cocycles`/`cocycleBars`): from a homology pairing (naive/chunks `pairing`, under
`stream.filtrationOrdering`), reduce only birth cells' coboundaries (built by `coboundariesOf` from the stream),
youngest first; representative `V_σ` (as the cohomology engines report). Chunks' and naive's pairings are identical,
union-find included (`InvolutionSpec`). Fast cubical's union-find ties by value, not the stream order: no dual path,
image cocycles go through the cohomology engine. Native kind is faster: derived costs ~1.1-1.3x in degrees 0-1,
~2.5x for VR cycles in degree 2 through the generic involution (the packed engine's own: ~1.25x, 192 sphere points). Different valid cycles from chunks/naive (they report `V_σ` after their own substitutions): never compare
representatives cell for cell across engines; `InvolutionSpec` checks validity on tie-heavy inputs. Cost: 2.5-3x
cocycles (`WORKLOG-involution.md`).

Every engine takes `includeZeroLength` (default `false`) on its bar-returning methods and drops `birth == death` pairs
from the pairing itself; `PersistenceBar.dropZeroLength` is the shared filter. The naive and chunks engines' `diagramAt(f)`
report a class alive at `f` as `[birth, f]` (closed end), so a class born exactly at `f` survives the filter; a finished bar is
`[b, d)`. Fast cubical/alpha drop equal-value pairs at construction (a pair at `+Inf, +Inf` is otherwise indistinguishable
from an essential class). `ZeroLengthBarsSpec`.

The opt-in pairing checks (`totalBarsAccountForAllCells` with `includeZeroLength = true`) stay in the engine specs
(project lead, 2026-10-03): they test the pairing invariant on purpose; nothing else should count bars against cells.

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
`commons-math3` `ConjugateGradient`, restricted to the cocycle's connected component, anchored at `g=0`;
`theta(v)=frac(g(v))` directly, no path integration. MATLAB mirrors this; no CLI (picking `r` is two-step/
data-dependent). `WORKLOG-circular-coordinates.md`.

**Toroidal coordinates** (`computeToroidal`, Scoccola-Gakhar-Bush-Schonsheck-Rask-Zhou-Perea 2022,
arXiv:2212.07201): combines `k` *simultaneously*-alive H¹ classes (common `r`, same connected component of
`K_r` — checked) into one torus-valued map, via `LatticeReduction` (hand-rolled LLL on the classes'
harmonic-cochain Gram matrix's Cholesky factor, `delta=3/4`), applying the resulting unimodular `U` to the
already-computed per-class `theta`s (linearity of harmonic smoothing). Not a port of `scikit-tda/DREiMac`'s
`toroidalcoords.py`: its `_gram_schmidt` has a real orthogonalization bug (invisible at k=2, non-orthogonal
intermediate result at k≥3), but an end-to-end search found no case degrading `_lll`'s final output — see
`.claude/BUGS-IN-REFERENCES.md`, don't overclaim beyond what's checked there. MATLAB: `toroidalCoordinates`/
`ToroidalCoordinatesResult` (separate class, MiMa); no CLI. `WORKLOG-toroidal-coordinates.md`.

## Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing across point count/dimension/`maxDim`,
construction and reduction timed separately, per-cell timeout on daemon threads. Alpha and VR bar counts are
never compared (circumradius vs diameter).

**Benchmark specs** (`ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `SparseRipsBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, `EdgeCollapseBenchmarkSpec`, all in
`homology`) print timing tables rather than assert; only an exception counts as a failure. All of them `skipAll` unless
`-DrunBenchmarks=true` (a JVM system property, not specs2 `--` syntax); scope with `testOnly`
(`EngineComparisonBenchmarkSpec` can take 15+ min; `RipserPaperBenchmarkSpec` also needs `-DdataDir`, optionally
`-DripserBin=<path>` — see `.claude/scripts/run-ripser-paper-benchmark.sh`).

**`HomologySpec`'s `BarcodeRegressionSpec` is `skipAll`'d unconditionally and NOT on this flag**: chunks x
`AlphaShapeDQP` on its own generator range produces enormous complexes (40 points/dim 4 → 102,090 simplices) that
stall/OOM. Don't un-skip without bounding the scale problem (`WORKLOG-benchmark-and-chunks-bug.md`).

**Query contract (naive + chunks, `DiagramQuerySpec`, `WORKLOG-cursor-and-verb.md`)**: `diagramAt(f)`/`barcodeAt(f)`/
`diagramWithGeneratorsAt(f)` give the diagram truncated at `f` -- bars born `<= f`, deaths capped at `f`, a class alive
at `f` reported as dying at `f` unless no cell enters after `f` (then essential) -- REGARDLESS of where the naive
cursor is (it used to report a class as essential after the cursor had run past `f`; chunks used to include
essentials born after `f`). The cursor is kept on purpose (project lead): long runs must be inspectable and keep their
output if they die; `advanceFor(budget)` runs it in time slices, `processedCells`/`totalCells` report progress.
