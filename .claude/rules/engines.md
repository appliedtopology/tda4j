---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/homology/**"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/**"
  - "src/test/scala/org/appliedtopology/tda4j/homology/**"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/**"
---

# Persistent homology engines, barcode distances, benchmark specs

## Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives: a fix in one doesn't imply the others need it. `maxDim`/
`maxDimension` = top homological degree in the engines and the facade (engines build one dimension higher internally);
the stream constructors differ (`IncrementalVietorisRipsSimplexStream.maxDimension` is the top SIMPLEX dimension, coface
streams have none): use `VietorisRips` (`DESIGN-stream-naming.md`, `WORKLOG-maxdim-semantics-fix.md`).
`CellularCohomologyEngine` has no such param: callers truncate via a stream with a `homologyDegreeLimit`
(`VietorisRips(.., k)`, `Truncated`), and `persistentCohomology` (and the packed cubical engine's) leaves out the bars
above it before building any representative (`CohomologyDegreeLimitSpec`, `WORKLOG-generic-engine-profile.md`). **Every engine that reads a stream stops at its declared limit**, whatever degree it was asked for (`min(asked, limit)`):
naive (both cursors), chunks, cohomology, `Involution`. A hand-built complex that wants its top degree must not be a
`Truncated`/`LimitedCofaceSimplexStream` (use `ExplicitStreamBuilder`). `TruncatedTopDegreeSpec` runs every way (factories,
direct engines, the verb with each engine and representative kind, images, alpha) against the longer stream's bars up to
the limit; `FastCubicalHomologyEngine`/`FastAlphaHomologyEngine` take `maxDimension` and skip the dual graph below the top.

1. **Naive** (`CellularHomologyEngine`/`SimplicialHomologyEngine`) — single-pivot-table reduction, no clearing; the
   reference baseline. Incremental; a raw-UnionFind fast path was rejected.
   `persistentHomology(stream, k)` CAPS it at degree `k` (the verb and `PersistenceEngine.naive`/`naiveCocycles` too, at
   the stream's `homologyDegreeLimit`): cells above dimension `k + 1` are not read, and a `k + 1` cell keeps only its
   pairing and is not reduced while no class of degree `k` is open. The one-argument cursor reads a stream with no
   declared limit whole (`NaiveCappedSpec`'s reference). Cells are compared
   by rank (`CellRanks`; `WORKLOG-naive-cap-review-2026-10-15.md`).
2. **Chunks** (`CellularPersistenceInChunksEngine`) — clear-and-compress chunked algorithm, walks `0..maxDim+1`,
   filters essentials to `<=maxDim`. Dims 0/1 via raw union-find (`unionFindDim01`, `DESIGN-unionfind-in-chunks.md`): a
   zero edge closes a cycle; an edge not `c·(v-u)` (`edgeShape` Irregular) hands dims 0/1 to the general reduction
   (`WORKLOG-union-find-robustness.md`). `barcodeAt` via memoized `vcolOf` (a second full naive engine was **rejected by
   the project lead**, don't revive: `WORKLOG-chunks-representatives-incremental.md`). Invariants: a paired cell never
   becomes a pivot; `compress` runs to a fixpoint; "in limbo" cells are resolved first (`WORKLOG-chunks-pairing-bug.md`).
   At `maxDimension = 1` triangles go in filtration order against a count of open
   degree-1 classes (`reduceTrianglesInOrder`) and one entering while none is open is skipped; from 2 every top cell is
   reduced (#91). Cells are compared by RANK (`CellRanks`, shared with naive), only if EVERY cell has a filtration value
   (`ranked`; #88); a fresh cell from `boundary` goes through `boundaryChain`. `WORKLOG-chunks-top-cells.md`.
3. **`RipserCohomologyEngine`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/
   reference oracle only** — production uses `PackedRipserCohomologyEngine`. Clearing is required;
   apparent pairs with lazy substitution; no emergent pairs (Def 3.11, only in engine 4); `memoizeFiltrationValue`
   defaults **false** (project lead: memory over speed) (`WORKLOG-packed-ripser-engine.md`).
   The PACKED engine numbers simplices with one `Long` or, past 2^63 (C(n + maxDim + 2, maxDim + 2)), two words
   (`WideSimplexIndexing`, `usesWideIndices`, `WideKeys` for the maps keyed by index); the second word exists only in a
   two-word run, every new index-keyed structure carries it, and `WideIndicesSpec` / `WideIndicesPastLongSpec` hold both
   paths equal (`WORKLOG-two-word-indices.md`).
   The PACKED engine's apparent-pair test decodes a column once (`WORKLOG-ripser-decode-once.md`, #194; its pair count
   equals the reference engine's: `EmergentPairsSpec`). It reduces on a primitive-array heap, expands cocycles lazily from reduction logs (only for reported
   bars) and derives cycles with its own copy of `Involution.cycles`, which does not reduce an apparent pair nobody reports
   (`WORKLOG-involution-apparent-skip.md`). It must equal the `Chain`-based engine in the test
   tree (`ChainPackedRipserReference`) term for term, essentials included (`PackedWorkingColumnSpec`): re-run it after any
   change to the reduction (`WORKLOG-vr-working-column.md`). The heap needs ONE diameter per simplex: non-trusted metrics
   are read as `d(max, min)` (else an asymmetric one grows the column until OOM); Euclidean/Explicit are read as given
   (canonicalizing breaks the distance cache's row-wise walk). Edge-block units on threads are exact (#192, #194);
   `Engine.ParallelRipser` runs them from the verb (`Auto` picks it with `Parallel.threads > 1` when it applies: prime
   <= 46,000, no `includeZeroLength`; edge ranks are dense up to `DenseRankLimit` = 4,096 points, sorted neighbour lists
   above: `WORKLOG-sparse-ranks.md`; facade and CLI likewise for an unset `engine`; `threads` runs under
   `Parallel.withThreads`). Its representatives are `LazyChain`s (cocycles from the logs; cycles from one parallel
   `Involution` pass), so they may differ from `Ripser`'s in ties; shared code (one thread on Scala.js). Compare pivots
   with `Ordering.Long` explicitly where the chains' reversed `given` is in scope (`WORKLOG-parallelism.md`). Under a sparse threshold cofacets come from per-vertex
   NEIGHBOUR LISTS (`NeighbourLists`) in the all-vertex scan's order with bit-identical diameters (`NeighbourListsSpec`).
   A uniform sign flip of every coboundary changes no output: sign mutations must be non-uniform. Per-simplex state is
   PRIMITIVE (`Level`, `Pairing` rows, `ClearedSet`, `LongIntMap`; `WORKLOG-packed-engine-memory.md`): no per-pair object.
   Emergent pairs (`useEmergentPairs`, Def 3.11): a column whose oldest same-diameter cofacet no earlier column holds
   (`basis`, `emergentRow`, the apparent partner of its youngest facet) is paired unreduced and rebuilt on demand;
   <= 4 % time, up to -22 % heap (`WORKLOG-emergent-pairs.md`, #57).
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
   `WORKLOG-layered-inorder.md`). `PackedCells` finds a face by its vertices packed in one `Long` when they fit (a vertex
   above the level's largest is rejected, never wrapped), else by hash; the coboundary is transposed in column ranges on
   all threads; degrees above the stream's limit are not reduced (`WORKLOG-cohomology-face-lookup.md`, #251).

**Cycles from cohomology (`Involution`, `homology/Involution.scala`)**: both cohomology engines expose
`pairedCohomology` (bars + birth/death cells, zero-length pairs INCLUDED: their columns are pivots) and
`persistentHomology` (same bars, cycles). Only death columns are reduced, under the engine's own order reversed;
a pivot that is not the paired birth cell throws (a pairing/order mismatch is a
bug, never fall back). Finite bar cycle = `R_τ`; essential = `V_σ`. The dual direction (`Involution.cocycles`) starts
from a homology pairing (naive/chunks, identical, union-find included: `InvolutionSpec`) and reduces only birth cells'
coboundaries (`coboundariesOf`), youngest first. Fast cubical's union-find ties by value, not the stream order: no dual path, image cocycles go
through the cohomology engine. The native kind is faster (`WORKLOG-involution.md`). Engines report different valid
cycles: never compare representatives cell for cell across engines (`InvolutionSpec` checks validity).

Every engine takes `includeZeroLength` (default `false`) on its bar-returning methods and drops `birth == death` pairs
from the pairing itself (`PersistenceBar.dropZeroLength`). Naive and chunks `diagramAt(f)` report a class alive at `f` as
`[birth, f]` (closed end), so a class born exactly at `f` survives the filter; a finished bar is `[b, d)`. Fast
cubical/alpha drop equal-value pairs at construction.
`ZeroLengthBarsSpec`.

`Chain.reduceBy`/`reduceByUntil` throw (`IllegalStateException`) when the pivot moves back or stays put for 64 steps (a
basis column whose leading cell is not its key, or an order mismatch; `WORKLOG-compact-representatives.md`).

Testing lessons: F2 hides sign errors; signed-field fixtures need ≥5 vertices (`Set1..Set4` hash-order past 4
elements, `SignedFieldBarcodeSpec`). Two engines agreeing isn't proof if they share a code path: hand-derived fixtures
are the real oracle.

**`ParallelPackedCohomology`** is JVM-only experimental machinery. Its reduction-log graph is acyclic by construction;
`cocycle` expands it with an explicit depth-first stack and memo so long substitutions cannot overflow the call stack,
and reports a cycle as an invariant violation (`ParallelPackedCohomologySpec`, `WORKLOG-code-review-fixes-2026-10-30.md`,
`WORKLOG-code-review-verification-2026-10-30.md`).

**`BarcodeDistance`/`Vectorization`/`BootstrapBand`/`ToMATo`**: distances (matchings are `None` on an essential-count
mismatch), sliced Wasserstein, landscapes, images, Betti curves (`birth <= t < death`), silhouettes, entropy on
`PersistenceBar[Double,_]`. Ground norm matches Hera/GUDHI, images `persim`. Essential bars: matched by birth for distance
(mismatch → `+Infinity`); kept by landscapes and Betti curves, dropped by the rest. `longerThanFraction` is a display rule,
not a test; `BootstrapBand.threshold` (2 x width, Fasy 2014) is the estimate. The facades are in `facade.md`. `WORKLOG-diagram-statistics.md`.

## Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing; alpha and VR bar counts are never compared (radius vs diameter).

**Benchmark specs** (`*BenchmarkSpec`, `ProfilingSpec` in `homology`) print timing tables; only an exception fails. All
`skipAll` unless `-DrunBenchmarks=true`; scope with `testOnly` (`EngineComparisonBenchmarkSpec`: 15+ min;
`RipserPaperBenchmarkSpec` also needs `-DdataDir`: `.claude/scripts/run-ripser-paper-benchmark.sh`); cross-tool ones in `bench/`.

**`BarcodeRegressionSpec` runs on 25 - 30 points in 2 - 6 dimensions**: larger clouds cannot be enumerated
(`WORKLOG-chunks-top-cells.md`).

**Query contract (naive + chunks, `DiagramQuerySpec`, `WORKLOG-cursor-and-verb.md`)**: `diagramAt(f)`/`barcodeAt(f)`/
`diagramWithGeneratorsAt(f)` give the diagram truncated at `f` (bars born `<= f`, deaths capped at `f`, a class alive at
`f` dying at `f` unless no cell enters after `f`, then essential) wherever the cursor is. The cursor is kept on purpose
(project lead): `advanceFor(budget)` runs time slices,
`processedCells`/`totalCells` report progress.
