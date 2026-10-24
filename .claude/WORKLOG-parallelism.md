# Where threads pay: alpha, rare complexes, the cohomology engine's build (2026-10-24)

Point-in-time record; the rules that came out of it are in `rules/alpha.md`, `rules/scalajs.md` and the developer's guide
("Parallelism" in `architecture.md`, `alpha-complex.md`). Mikael's ask: find where multithreading pays, certainly in alpha
shapes, and implement it. Constraints kept: Scala.js has one thread (everything goes through the `Parallel` seam), every
result identical to the sequential one, no subagents.

## Method

4 cores (the container), one JVM per row, cold, one trial; the baseline is `origin/scala` at `acee772` built in its own
worktree, the new build this branch. Bars compared by md5 of the driver's `out=` file (`PaperBenchmarkDriver`), alpha
complexes by `AlphaProfileDriver dump=` (every simplex, value in hex and stream order). Both drivers take `sequential=true`
(`Parallel.sequentially`) for a one-thread run of the new code. Profiles: JFR, inclusive frames aggregated by hand.

## Measured (baseline -> now, seconds)

| row | before | after |
|---|---|---|
| Helix, 1,500 points in R^5, to H_2: whole run | 45.0 | 22.2 |
| Helix, 600 points in R^6: whole run | 83.9 | 41.4 |
| Bowyer-Watson, 100,000 points in R^3: whole run | 54.5 | 39.1 |
| `Persistence(alpha)`, 30,000 points in R^3, default radius | 15.3 | 10.1 |
| `Persistence(alpha)`, 3,000 points in R^4 | 7.4 | 5.5 |
| DQP, 600 points in R^3, no radius | 77.9 | 23.9 |
| Cech, 250 points in R^3, dim 2, r <= 0.25 | 13.6 | 6.8 |
| sparse Rips, 1,500 points in R^3, eps 0.3 | 25.7 | 15.1 |

The Helix walk alone (5,000 points in R^5, the walk 314 s, faces 29 s, sort 11 s, before the merge with `origin/scala`): 84 s,
13 s, 8 s. Bars/dumps identical in every row.

## What the threads are used for

- **Helix walk**: `evaluate(fc)` is a pure function of the points (cofacet, points on its sphere, the hyperplanes of the new
  facets: an SVD each). The walk queue is FIFO and new cases go to its back, so the next `8 * threads` queued cases can be
  evaluated together and the walk keeps taking cases in queue order. Waste: 25 % of evaluations are for a case cancelled or
  visited first. Exceptions from a speculative evaluation are swallowed (the walk evaluates the case again if it reaches it).
  At 600 points in R^6 the walk is bounded by its sequential apply side (`Simplex` TreeSet operations, hash sets): threads give
  ~20 % there, the rest of the walk speed-up was serial (below).
- **Serial wins found on the way** (they are most of the Helix gain at small n): the circumsphere point scan built a Vector of
  tuples per accepted cell (`pointsOn`, primitives, same arithmetic); `facetVertices.contains(q)` boxed per point
  (`isVertex`); `HelixDelaunay.looksValid` counted facets by `groupMapReduce` on `Simplex` objects (now flat tables; a facet of
  a tiled cospherical cluster has more vertices than the regular ones, so the two kinds are counted apart).
- **Face tables** (`FaceTable.facesOf`): each thread owns a partition of the hash space and scans all (cell, omitted vertex)
  pairs in order, finding the first occurrence of its faces; the face numbers are the ranks of the first occurrences, so they are
  those of the sequential pass. Circumspheres, the Gabriel test (pairs grouped by face by a counting sort), values, `Simplex`
  objects and the sort (`Arrays.parallelSort`) run on ranges.
- **Flat layers for alpha** (`DelaunayAlphaShapes extends FlatLayerStream`): the verb no longer builds `Simplex` objects and
  looks values up per cell; the layer order is ascending value then ascending lexicographic (the reverse of the stream's own
  listing of ties; VR's flat layers use colex because their streams list ties by colex). `None` with repeated points.
  `RadiusLimitedAlphaShapes` cuts layers by binary search.
- **Engine**: `CellularCohomologyEngine`'s `coboundaries` looks up the faces of every cell one dimension up in ranges (the
  index is built first by `PackedCells.prepare`); the cycle representatives reduce each death dimension on its own thread
  (they share nothing but the read-only levels); `LayerSorter` sorts the halves of a big layer as two tasks.
- **Defaults**: `AlphaDQPSettings.parallel` and the `parallelFiltrationValue` of Cech, DTM-Rips, sparse Rips and witness are
  `Parallel.byDefault` (threads > 1). Not the cubical pixel read (per-pixel tasks cost more than the read).
- **Seam**: `Parallel.threads/ranges/both/sort/sequentially`; `sequentially` is a thread-local, read on the calling thread,
  where every step decides to fork. Pool size: the JVM's common pool property.

## Not done, and why (issues #219 Bowyer-Watson insertion, #220 reduction and the bars-only engine, #221 Helix apply side)

- Bowyer-Watson insertion: each insertion changes the triangulation the next walks in; a parallel version needs spatial
  partitioning with merge (research). Issue.
- Reduction (Chain-bound: `Chain.reduceBy`, `HeapChain.collapseAll`, Involution): the columns depend on each other. The block
  parallel experiments (`ParallelPackedCohomology`) give bars only, so they are not reachable from the verb (#192, #194); wiring
  them behind an option that drops representatives is Mikael's decision. Issue.
- The sequential apply side of the Helix walk (`Simplex` sets, `FrontierCase`): moving it to int arrays would raise the thread
  scaling in 5-6 dimensions. Issue.
- Enclosing radius scan: already 2-5 ms after #216; Dowker's value per parent (#206) not touched.

## Addendum (2026-10-24): the bars-only parallel Ripser engine in the verb (#220)

Mikael's decision on #220: "allow access to faster without representatives, but make the option obnoxious". Done as
`Persistence.Engine.ParallelRipserBarsOnlyNoRepresentatives` (an enum case with the long name, never chosen by `Auto`).

- **Wiring**: `Input.parallelRipserBarsOnly` (default throws, the metric-space input overrides) calls the seam
  `ParallelRipser.bars` (`scalajvm`: `ParallelPackedCohomology.persistence` on a pool of `Parallel.threads` daemon threads,
  8 units per thread, one thread and no pool inside `Parallel.sequentially`; `scalajs`: `UnsupportedOperationException` naming
  `Engine.Ripser`). The diagram's bars have `annotation = None`; `bar.representative` throws "carries no representative".
  `lastFiltrationValue` is the longest edge within the threshold, as for `Ripser` (the scan is now one helper).
- **Refusals** (each says what to use): `includeZeroLength` (the engine drops bars with death == birth), `characteristic = 0`
  and a composite characteristic, any `complex` but `VietorisRips`, inputs that are not points or a metric space, more than
  46,340 points (`n x n` rank table). `representatives` is ignored (cannot tell it from the default).
- **Tests**: `ParallelRipserEngineSpec` (JVM: bars equal `Engine.Ripser`'s on a 3-D cloud over F_17/F_2/F_3, a noisy circle
  with and without a threshold, tie-heavy metrics; same bars with `Parallel.sequentially`; no representatives; refusals) and
  `ParallelRipserRefusalSpec` (both platforms: the JVM answers, JavaScript refuses).
- **Measured** (4 cores, 1,200-point noisy torus in R^3, degrees 0..2, threshold 1.2, p = 17, median of 3 after 1 warm-up,
  through `PaperBenchmarkDriver`): `Engine.Ripser` with cycles (the verb's default) 3.9 s, with cocycles 2.1 s; the bars-only
  engine 0.8 s on one thread and 0.5 s on four. Same bar counts (1200 / 355 / 5). So most of the win over the default is not
  recording representatives (cycles cost a second reduction); threads add about 1.7x on this size. Peak heap 247 MB against
  564-614 MB.
- **Not done**: MATLAB (`matlab.TDA4j`) and the CLI (`cli.TDA4jConf`) do not offer it: their results are representative-first
  and a loud option there is a separate decision (listed on #220). Giving the engine representatives (the involuted cycles of
  its pairing) is still the better long-term answer.

## Addendum 2 (2026-10-24): can the bars-only engine give representatives afterwards? (Mikael's question on #222)

Yes: it already keeps what is needed, nothing is discarded.

- With `cocycles = true` every reduced column records a `Log` (the columns added to it and the factors, V = column + sum of
  factor * V(added)); apparent pairs have V = the column itself. `ParallelPackedCohomology.cocycle(degree, p, column)` builds a
  cocycle from the logs, and `ParallelPackedCohomologySpec` checks its coboundary is zero over F_17 and F_3. A key decodes to a
  simplex (`space.index`, `space.decode`). Cycles would come from the pairing (`keepPairs = true`) by `Involution`, one more
  (sequential) reduction, as for the other engines.
- Not wired: `Result.bars` forgets which column each bar came from (it keeps `(dim, birth, death)`), so the bars need to carry
  their column; the cocycles should be built lazily for the bars returned, as the packed engine does.
- **Bug found and fixed on the way**: `Log` kept its entries in two `ArrayBuilder`s and `cocycle` called `result()` on them each
  time a column was visited. `result()` on a builder whose array is exactly full hands the array over and empties the builder,
  so the next call threw a `NullPointerException` ("tried to cast away nullability"). One cocycle at a time worked; a second
  cocycle through a shared column failed. `Log.columns` / `Log.multipliers` read each once.
- **Cost** (4 cores, the 1,200-point torus above, threshold 1.2, degrees 0..2, p = 17; medians of rounds 2-4 of one JVM,
  noisy): bars only 0.4 s, with logs 0.4 s, with logs and the cocycle of every pair extracted 0.6-0.7 s (941,233 terms in all, a
  superset of the reported bars: it includes zero-length and apparent pairs); `Engine.Ripser` with cocycles 2.1 s. So the
  logs themselves are free at this size and cocycles for all bars would keep roughly a 3x lead over Ripser. Memory of the logs and
  the cost on larger inputs are unmeasured.
