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
