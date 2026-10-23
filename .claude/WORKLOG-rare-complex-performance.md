# Worklog: Cech, DTM-Rips, SparseRips and Dowker against GUDHI (#181)

Started 2026-10-23, cloud session, 4 cores, JVM `-Xmx3g`. Point-in-time notes; the result is summarized in
`rules/filtered-complexes.md`. The slow rows are `.claude/DESIGN-rare-complex-benchmarks.md` (update section).

## Data

`bench/bench.py prepare_rare` regenerated with numpy (`default_rng(20261021)`: `unif3d_80`, `circle_outliers_600`,
`relation_300x100` are the real bench files). `sphere3_96` is the first 96 rows of Ripser's `sphere_3_192_points.dat`
(downloaded from the ripser-benchmark repository), so also the real file.

## Method

`PaperBenchmarkDriver task=... warmup=0 trials=1 p=2` under `-XX:StartFlightRecording=settings=profile`, one JVM per
row, baseline classes of `96ec81c` plus the #182 deletion copied aside for the A/B. The leaf-frame table of
`jfr-summarize.sh` shows `TreeSet` iteration and `TrieMap` lookups and nothing else, so an inclusive-frame table was
written (`jfr print --events jdk.ExecutionSample --stack-depth 96`, count samples that have a given tda4j method
anywhere in the stack).

## Findings (baseline, three rows running at once, so slower than the issue's single-row times)

| row | time | in `RipserCofaceSimplexStream.iterateDimension` (the stream) | in the reduction |
|---|---|---|---|
| Dowker 300x100, dim 1 | 41.6 s | 89.8 % | ~10 % |
| DTM-Rips, 600 pts, dim 1, 0.6 | 43.7 s | 89.0 % | ~10 % |
| Sparse Rips, 96 pts, dim 2, eps 0.3 | 57.0 s | 87.3 % | ~12 % |

**None of these is the reduction.** The time is in generating the complex, in the coface loop all five rarer complexes
share. Three things in it:

1. **The candidate list is every `(simplex, i < min)` pair**, whether or not the edges to `i` exist. Dimension `d` is
   `|layer d-1| * n / (d+1)` candidates, each a new `TreeSet` (`Simplex` is a `SortedSet[Int]`), each looked up in a
   `TrieMap` whose key hash iterates the tree. For a flag-like complex that is far larger than the complex itself
   (Dowker: 300 vertices, DTM: 600, sparse: a sparse complex). The complex is closed under faces, so `i` has to be
   adjacent to every vertex of the simplex: a bitset of the kept edges prunes the list before any filtration value is
   computed.
2. **`sortedByFiltration` looks every cell up in two `HashMap[Simplex]`s (value, index) and a third time in the
   filtration function's own `TrieMap`**, hashing a `TreeSet` each time (`MurmurHash3.setHash`, 4-6 % alone,
   `TreeSet.equals` 8 %).
3. The filtration function itself: Dowker's `min_w max_x R(x,w)` is 100 witnesses times the size, Sheehy's recomputes
   all edge births of a simplex from the metric space.

## Fix 1 (commit 60a6306): candidates from the kept-edge table, sort on primitive keys

`RipserCofaceSimplexStream` keeps a bitset table of the kept edges (built from dimension 1 as it is computed, or on
first use when dimensions are asked for out of order; skipped past 2^25 words, about 40000 vertices). A candidate for
dimension `d >= 2` must be adjacent to every vertex of its facet, so the candidates of a simplex are the set bits of the
AND of its vertices' rows below its smallest vertex. `sortedByFiltration` reads each cell's value and index once into
arrays and sorts a permutation, where it hashed each `TreeSet` in two `HashMap`s.

Alone on 4 cores, one cold JVM per row, `PaperBenchmarkDriver`, p = 2, same `out=` barcode files (md5 equal):

| row | before | after fix 1 |
|---|---|---|
| Dowker 300x100 | 23.8 s | 6.9 s |
| DTM-Rips | 31.7 s | 5.8 s |
| Sparse Rips | 38.1 s | 13.6 s |

## Fix 2

Second profile (after fix 1): the filtration function is still 55-65 % of the time, not its arithmetic but the
`TrieMap` lookups keyed by a `TreeSet`, and `isDefinedAt` (a `forall` over the vertices) before every `applyOrElse`.

- `FiltrationCache` (JVM) hashes and compares a `TreeSet` key by a traversal, not through the allocating iterators of
  `TreeSet.hashCode`/`equals`. **A first version summed `x * c` per element, which collides for every simplex with the
  same vertex sum; sparse Rips ran for more than 7 minutes. The element hash is MurmurHash3's finalizer.**
- `MemoizedFiltration`: `applyOrElse` looks in the cache before it checks the domain. Used by the default Vietoris-Rips
  function and by Cech, DTM-Rips, Dowker and Sheehy (the recursive general witness function calls itself and keeps its
  own).
- Sheehy's value computed its edge births through `for` comprehensions over `Seq`s and a `Map[Int, Double]`; it is now
  loops over an array of insertion radii.
- `Persistence` iterated the stream a second time after the engine to find the largest filtration value, which built
  the complex twice (12-18 % of a row). `CellularCohomologyEngine.largestFiltrationValue` is recorded from the values the
  run reads anyway.

## Fix 3 and the result

Third profile (Cech, after fix 2): `cohomologyOrder`'s consistency check of the stream's tie order made one
`filtrationOrdering` comparison per cell, each calling `isDefinedAt` and `apply` on both cells: 31 % of Cech's time,
while the ball (`computeRadius`) was 6 %. `FiltrationOrdering.canonical` now calls `applyOrElse` once per cell
(`Undefined` marks a cell outside the domain; the semantics are the same: such a cell is ordered by dimension and
tie-break alone).

Final table, one cold JVM per row, alone on 4 cores, p = 2, `PaperBenchmarkDriver` (`out=` bars files md5-identical to the
pre-change build for all four, and for the Dowker dual, which has the same bars as the primal):

| row | before | after | warm (1 warm-up, median of 2) after |
|---|---|---|---|
| Cech, 80 pts in R^3, dim 2 | 101 s | 16.2 s | 15 s |
| Dowker 300x100, dim 1 | 23.8 s | 3.9 s | 3.0 s |
| Dowker dual | 1.35 s | 0.71 s | |
| DTM-Rips, 600 pts, dim 1, 0.6 | 31.7 s | 3.3 s | 2.2 s |
| Sparse Rips, 96 pts, dim 2, eps 0.3 | 38.1 s | 4.9 s | 3.8-5.7 s |

`tda4j/testFull`: 1653 specs, 0 failures. Scala.js: the changed shared code compiles and the stream specs pass there
(the JS `FiltrationCache` is unchanged: a `HashMap`).

## What the profiles say is left (filed as #206)

About ten `TrieMap` lookups per cell, each keyed by a `TreeSet` (hash plus a cache miss into a table of millions); the
`Simplex` allocation per candidate; `SimplexIndexing.apply` per cell for the sort's tie-break (3-12 %). The remaining gap to
GUDHI is 5-15x for Dowker, DTM-Rips and sparse Rips and, for Cech, the construction (every `dim + 2`-subset against the
Delaunay triangulation's few cells). Only generating layers on arrays, as `LayeredInorderSimplexStream` does, removes
it. Not measured: the witness complexes (same loop), Scala.js timings, the 60x200 Dowker case.

## Round 2 (2026-10-23): dimensions on flat arrays (#206)

The profiles above ended at "only generating layers on arrays removes it". Done in the same coface loop, not a new
stream: `RipserCofaceSimplexStream` builds a dimension as a `FlatLayerStream.Layer` (ascending vertex tuples, values;
cells in the order a cohomology reduction takes them: value ascending, equal values colex ascending) when its protected
`flatFiltration` is defined.

- **Generation**: the candidates of a simplex are read off the kept-edge bitset table (as before) into chunked arrays
  (`2^15` candidates, each with its parent's value), valued by the stream's `FlatFiltration` (in parallel parts of `2^11`
  with `parallelFiltrationValue`), filtered by `keptValue` and the criterion, then sorted on primitives (`LayerSorter`: a
  merge sort on the value's bits, then colex of the vertices; a boxed `Integer` sort with a comparator was a third of a
  large Cech run). Two layers are cached; dimensions asked out of order rebuild from 0.
- **Value functions on arrays** (`FlatFiltration.value(vertices, from, width, parentValue)`): the default Vietoris-Rips
  value and the lazy witness complex (`FlatFiltration.flag`: the new vertex's distances on top of the parent's value),
  DTM-Rips (weights as an array, `edgeValue` directly), Dowker (`DowkerGeometry.filtrationValue(Array, from, width)`),
  sparse Rips (`edgeBirth` of the smallest vertex's edges, the vanish check over all vertices). Cech and the general
  witness complex use `FlatFiltration.ofSimplices` (a `Simplex` per candidate for their function). **The incremental
  forms are exact because every one of these values is a max (or min-max) over edges or witnesses**; a hook must equal
  the stream's `filtrationValue` bit for bit, and `FlatLayersSpec` checks that against the same stream built from cells
  (a mutation of the sparse Rips hook, dropping the parent's value, fails four of its five examples).
- **Engine**: `CellularCohomologyEngine` asks a `FlatLayerStream` for its layers first and builds `PackedCells` from them
  (cells and boxed values only on demand, for the representatives), so no `Simplex`, no `cohomologyOrder` pass, no
  second read of the stream; `largestFiltrationValue` is the last value of the last layers. `Truncated` and
  `LimitedCofaceSimplexStream` pass the layers on (`take(maxDim + 1)`). The cell path is the fallback for every other
  stream and for a non-natural `Ordering[Double]`.
- `keepCriterion` defaults are now the shared `CofaceSimplexStream.keepAll`: a criterion that is that very function costs
  nothing per candidate, any other is asked about a `Simplex` per candidate (the flat path stays correct for it).
  `keptValue(value)` is the value half of `keptByThresholdAndCriterion` (Dowker's finite-only override moved there).
- The `Simplex` listing (`iterateDimension`) of a flat stream comes from the same layers (each run of equal values
  reversed), and still fills `currentDimension`, `lastDimensionCache` and `currentDimensionCache`.

Same barcode files (md5) as the PR #205 build for all four rows. One cold JVM each, alone on 4 cores, p = 2,
`PaperBenchmarkDriver`; warm = 1 warm-up, median of 3:

| row | PR #205 build | flat layers | warm, flat layers |
|---|---|---|---|
| Dowker 300x100, dim 1 | 4.2 s | 1.6 s | 1.0 s |
| DTM-Rips, 600 pts, dim 1, 0.6 | 3.4 s | 1.0 s | 0.35 s |
| Sparse Rips, 96 pts, dim 2, eps 0.3 | 6.6 s | 1.1 s | 0.45 s |
| Cech, 80 pts in R^3, dim 2 | 20 s | 13.6-14.7 s | |

(The PR #205 column was re-measured in this session's last runs, so it is slower than the table above, which was taken
on a quieter machine; compare within a column pair.) Against the start of #181: Dowker 24 to 1.6, DTM 32 to 1.0, sparse
38 to 1.1, Cech 101 to 14.

What is left: Dowker's value is 64 % of its time (the min over witnesses, per candidate, from scratch: a per-parent
vector of maxima would make it a third); Cech is the ball solve (`distanceToCenter`, `computeRadius`, `Solver`, ~30 %),
the `Simplex` the adapter builds, the sort (17 %) and the engine's face lookups (`slotOf`, 10 %), over millions of
cells that GUDHI's Delaunay-Cech does not have. Not done: Cech's radius on arrays (needs the facet values from the
previous layer, and a facet missing for another reason than the threshold changes the answer), a JS `FiltrationCache`
that is not a `HashMap`, timings on Scala.js.
