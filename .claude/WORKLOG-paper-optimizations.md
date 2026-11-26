# The edge-block paper's optimizations in `ParallelRipser` (2026-11-26)

Request (Mikael): the edge-block paper (draft with Ulrich Bauer and Jordan Matuszewski) is close to final, and Ulrich
benchmarks it, so check that TDA4j has every optimization Ripser and RedZeD get in the paper's workout, and add the
missing ones. Ulrich's compute server runs the timings; the numbers below are local (4 cores, JVM 21, `-Xmx3g`, one
thread, median of 5 after 2 warm-ups, `PaperBenchmarkDriver engine=parallel`).

## Inventory: what the paper does, and where TDA4j stands

| item of the paper | status |
|---|---|
| edge-lex / edge-colex orders of equal lengths, direction inside a block | present: `EdgeTies`, `BlockOrder` (`WORKLOG-edge-ties.md`, `WORKLOG-block-order.md`); the paper's programs are `Colexicographic` + `SmallerIndexFirst` |
| apparent pairs from the maximal facet and the minimal cofacet (instead of enumerating facets and cofacets) | present: `apparentFacetLarger/Smaller`, `apparentCofacet`; pinned against ripser.cpp and ripser-forest counts (`ParallelBlockOrderSpec`) |
| clearing, emergent pairs, H0 by union-find | present (`rules/engines.md`) |
| simplices in filtration order from a prefix forest, no sorting | replaced: keys carry (block, index) and a level is a sorted `Long` array; with forest columns a level is not listed at all |
| columns = leaves of the maximal-facet forest that are not minimal children; one depth-first pass with neighbour bitsets; in the top degree the children joined to the minimal child skipped as a whole | **added**: `ForestColumns` |
| coboundary as a stream of cofacets in filtration order (own block by added vertex, then by completing edge), merged in a heap, started after the pivot, lune computed lazily | **added**: `CofacetStream`, `StreamColumn` |
| RedZeD hybrid: enumerate along the forest with the adjacency matrix, cancel an apparent pair at enumeration, top-degree children found together | covered by `ForestColumns` (cohomology never visits an apparent column); RedZeD's forward reduction itself is F2 only and not a goal here |
| omit simplices with a child from the coboundaries (Section 6, Table 4 row 7) | **not added**, see below |
| continuation of long columns by steps (App. D.3), horizon | **not added**, see below |
| engineering: huge pages, radix heap, radix sort, vector instructions, batched list walks | edge sort now on primitive arrays (merge, not radix); the others not added, see below |

## What was built

- `ForestColumns.compute(space, threads)` returns the sorted keys of the columns of each degree 1..maxDim. A step (the
  simplices filed under one edge `e`) is `e` plus a clique of the lune graph of `e`; `node(t, ..)` recurses over the
  candidate sets (common neighbours before every residual vertex, in the vertex order: label order for
  `SmallerIndexFirst`, reversed for `LargerIndexFirst`); a leaf that is not a minimal child is emitted. One adjacency bit
  matrix grows edge by edge, so tasks take ranges of edges and each rebuilds the matrix up to its first edge. `applies`
  needs one-word keys, dense ranks (n <= 4,096), apparent pairs from the block's edge, `maxDim >= 1`, and a run that does
  not keep its pairs: cycles list the simplices as before, because the pairing holds the apparent pairs the involution
  needs as pivots. The columns cleared by a non-apparent pivot of the degree below are still skipped where the column is
  reduced. `stats.columns`/`apparent` are therefore the forest's leaves and 0.
- `CofacetStream` yields the cofacets of one column in filtration order: the ones in the column's own block first (ordered
  by the added vertex, up or down with the block order), then one per block by walking the vertices' neighbour lists (by
  edge rank, `RankedNeighbours`) together, taking a cofacet at its last edge. A stream starts after `startKey`; the lune
  is computed when a stream first reaches its own block (the stream is a `Bound` until then). `StreamColumn` is the heap of
  streams; the pivot is the smallest key whose summed coefficients (a `Long` total) do not cancel.
- A column added to a working column is the owner's reduced column, i.e. the coboundary of the owner's `V`, added as one
  stream per entry of `V`, starting after the pivot (entries up to it cancel). An `Owner` stores `V` (`vKeys`, `vCoefs`)
  and `lead` (R's coefficient at the pivot), never `R`. Deferred columns carry their streams and the owners added so far
  between the phases; the reduction logs are term for term those of the heap run.
- `EdgeRanking` sorted 500,000 edges with a boxed comparator (half the run on an O3-like input). `EdgeTies.sortByLength`
  arranges the ties (the listing is already colexicographic; reverse, or one counting sort, gives the others) and
  `SortIndices.stableByKey` sorts by length on primitive arrays; `Shuffled` and any other listing keep the comparator.
- `Space(forestColumns = true, streams = true)` switch both off for the A/B and for the oracle in the specs. The bench
  driver's `engine=parallel` takes `forest=false streams=false`; `work=true` counts every simplex (`forestColumns = false`,
  `forest=true` to count the leaves), so the `simplices`/`apparent` fields keep their cross-tool meaning.

## Verification

- `ParallelForestColumnsSpec`: the column set against the definition by brute force on the engine's keys (every simplex
  that is neither an apparent facet nor the minimal child of one in the degree below), the set again from tasks over edge
  ranges, and a whole run against the run that lists simplices: bars, cocycles, essential columns, additions, columns left;
  9 inputs (ties everywhere, a generic cloud), all 10 combinations of order and direction, F_17/F_3/F_2, 1 and 3 threads.
- `ParallelCofacetStreamsSpec`: streams against heaps, pair for pair, bar, essential column, log, addition and cocycle, the
  same grid with 4 thread/unit shapes; `R = delta V` against the heap run's stored R; the whole new path against the whole
  old path; and that streams store `V` only.
- Three older specs read `d.reduced(c).keys` to check delta(V) = R: they compare against a `streams = false` run now.
- Mutation checks (flip, shift or drop one thing at a time, run the spec): 12 mutants of the stream code and 3 of its use in
  the engine die; two survive and are equivalent (the tie-break of a bound before a cofacet of the same key, which cannot
  happen because a bound's low bits are 0 and no index is; `<` against `<=` while counting `pp`, which the membership test
  next to it makes the same). Forest mutants died when `ForestColumns` was written (wrong vertex order, a missed residual,
  a dropped mask). Sort: unstable merge, unstable insertion, reversed tie arrangements and a dropped listing check die; the
  already-sorted shortcut is equivalent.
- Full JVM suite 264 specs, 0 failures; `tda4jJS/fastLinkJS` and the JS facade specs for both orders pass (Maven mirror
  flags of `cloud-env-sbt-limits`); the three lint checks are clean; MiMa has no baseline in 0.Y.

## Timings (old = `forest=false streams=false`, new = default; bars identical by md5 of the barcode)

| case (one thread, edge-lex, larger index first) | old | new | heap pools' peak, old / new |
|---|---|---|---|
| 32 x 32 lattice, threshold 5.1, to degree 3 | 5.75 s | 0.049 s | 852 / 143 MB |
| 192 points on S^2, degree 2 | 0.63 s | 0.099 s | 233 / 94 MB |
| 1,024 points of O(3) in R^9, degree 1 | 2.87 s | 0.153 s | 1,011 / 243 MB |
| semimetric of 160 points (random), degree 1 | 2.59 s | 0.074 s | 424 / 136 MB |
| torus of 1,000 points in R^3, degree 2 | 43.9 s | 1.95 s | 7,979 / 510 MB (`-Xmx8g`; old died at 3 GB) |
| 128 points on a circle, degree 1 | 0.030 s | 0.011 s | 48 / 16 MB |
| the same three under colexicographic + smaller index first | 5.94 / 0.57 / 2.81 s | 0.036 / 0.094 / 0.189 s | |

Peaks are the pools' maxima in a JVM that grows to its limit: read them as ordering, not as need. Which of the two
switches pays depends on the input (3 trials, 1 warm-up): the forest alone takes the lattice from 6.0 to 0.09 s and the
streams alone do nothing there; the streams alone take the semimetric from 2.6 to 0.12 s and the forest alone does not
(2.4 s); on the O3-like input the forest alone is slightly slower than nothing (3.6 against 3.05 s) and the streams carry
it (0.50 s, with the forest 0.30). Other engines on the same inputs, for scale: the sequential Ripser engine takes 1.1 s
(sphere), 2.6 s (O3-like) and 10.3 s (lattice); `ParallelRipser` on four threads takes 0.17 s on the O3-like input (no
better than one thread: edge ranking, the forest pass and the neighbour lists are serial now) and 1.06 s on the torus.

With cycles (`reps=cycles`, the verb's default, `withPairs`) the forest is off and a run is dominated by the involution:
O3-like 0.88 s, sphere 1.0 s, against 0.2 s and 0.11 s with cocycles.

## Not done, and why

- **Omit the simplices with a child from the coboundaries.** The paper's own numbers are mixed (0.29 to 0.24 s on
  sphere192, 0.077 to 0.061 on semimetric160, nothing on the lattice and O3-1024). It needs a child test per cofacet: the
  lune of the cofacet in its own block, which our streams, working from the rank table, would compute in O(n) per cofacet,
  and the paper's "bound" device to drop the skipped entries cheaply. Not attempted; unmeasured here.
- **Continuation of long columns by steps and the horizon** are extras of the authors' own program (Table 4's last row:
  sphere192 0.10 to 0.012 s), not something Ripser or RedZeD get in the ladder. The step argument is written for F2; over F_p
  it needs the signs worked out, a bit-matrix cochain per long column, and that state carried through the three phases.
  The horizon needs an edge order that is extended on demand while threads read the rank table. Neither is a small change.
- **Engineering items.** Huge pages are a JVM flag (`-XX:+UseTransparentHugePages`, for Ulrich's runs; nothing in the
  library). Radix sort: the remaining edge sort takes about a third of an O3-like run at one thread (500,000 edges), so a
  radix sort or a parallel sort is the next cheap gain there. A radix heap does not apply (a heap of a handful of streams).
  Vector instructions: the forest's row intersections are plain loops, not measured for auto-vectorization.
- **Cycles keep the old column listing.** `ParallelRipserCycles` looks up `pairOfBirth` of apparent pivots; serving that on
  demand from `apparentCofacet` would let the forest apply there too.
- **`Engine.Auto` on one thread still picks the sequential Ripser engine**, which is now 10 to 200 times slower than
  `ParallelRipser` with cocycles on these inputs (and 1.1 to 3 times with cycles). Moving it changes which representatives a
  one-processor user gets (valid, but different in ties) and the docs' "`threads = 1` is plain Ripser": the project lead's
  call. Orders other than the default (`SmallerIndexFirst`) already go to `ParallelRipser` on one thread.
- Inputs past 4,096 points (sparse ranks) and two-word keys list columns as before; the streams cover sparse ranks.
