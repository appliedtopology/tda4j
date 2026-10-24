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

## Addendum 3 (2026-10-24): `Engine.ParallelRipser` with representatives; Auto picks it with threads (#220)

Mikael: "for this one engine let's make cocycles on demand; a flag to compute all of them can be the default; can the dispatch
discover whether it is multi threaded and go to the current default if single?" This replaces the loud bars-only engine of
Addendum 1 (`ParallelRipserBarsOnlyNoRepresentatives` no longer exists; nothing was released with it).

- **Shape**: `Persistence.Engine.ParallelRipser`; `Persistence(..., computeAllRepresentatives = true)` (read only by this
  engine: `false` builds each representative when first read). The shared `Persistence` code sees the engine through
  `ParallelRipserRun` (keys, logs, pairs) from the seam `ParallelRipser.run` (JVM: `ParallelPackedCohomology` with
  `cocycles = true`, the pairing kept for cycles; Scala.js: refuses, `supports` false). Bars carry `LazyChain`s.
- **Cocycles**: built from the reduction logs of the bar's column when read. **Cycles**: `ParallelRipserCycles`, one pass of
  `Involution.cycles` on the `Long` keys with the shortcut of `CellularCohomologyEngine.cycleBars` (a death simplex whose
  youngest face is its birth is not reduced), one death dimension per thread; the first cycle read computes all of them.
- **Auto**: `ParallelRipser` instead of `Ripser` when `Parallel.threads > 1`, the input is a Vietoris-Rips metric space, no
  `includeZeroLength`, and `ParallelRipser.supports` (prime <= 46,000 since the engine's coefficients are Ints, < 46,341
  points for the `n x n` rank table, indices within 63 bits, and for cycles also one-word keys). Else `Ripser`.
- **Measured** (4 cores, 1,200-point torus in R^3, degrees 0..2, threshold 1.2, p = 17, 3 trials after 1 warm-up, through the
  driver): `Ripser` cocycles 2.3-2.5 s, `ParallelRipser` cocycles 0.44-0.65 s; `Ripser` cycles 3.5-3.8 s, `ParallelRipser`
  cycles 3.5-4.0 s (the generic involution without the shortcut was 7-13 s). So cocycles are 4-5x, cycles (the default) break
  even: the cycle pass is sequential per dimension.
- **Bugs met on the way**: (1) `Log` read its `ArrayBuilder`s twice (fixed earlier, Addendum 2). (2) Inside `cycles`, the
  `given Ordering[Long]` (reversed, for the chains) also drove `.max` and `.sorted` on arrays of keys: the youngest face came
  out as the oldest and the deaths were visited newest first, which showed as "the pairing and the order disagree". Those
  two calls say `Ordering.Long`. (3) The engine's inverse table was a quadratic search (46,000 steps squared at the largest
  prime); it is Fermat now, and `ParallelPackedCohomology.MaxPrime` = 46,000 guards the Int arithmetic.
- **Caveat for users**: ties between simplices of equal diameter are ordered differently from `Ripser`, so the bars are equal
  but the representatives can differ, and with `Auto` they now depend on the number of threads. Tests that compare
  representatives of `Auto` against `Ripser` exactly would see it; none in the suite does (1753 specs pass on 4 cores).
- **Not done**: MATLAB (`matlab.TDA4j`) and the CLI (`cli.TDA4jConf`) still call `PackedRipserCohomologyEngine`; wide keys
  with cycles; a parallel cycle pass.

## Addendum 4 (2026-10-24): a parallel cycle pass, the `threads` option, MATLAB and the CLI (#220)

Mikael: "wire in parallel when we have multiple cores into MATLAB and CLI; a flag everywhere relevant (persistence, MATLAB,
cli) for the number of cores; and yes, parallelize the cycle pass when possible."

- **Where the cycle pass spent its time** (torus1200, degrees 0..2, p = 17, 4 cores; per-phase timers, since removed): the
  death dimension 3 had 877,167 death simplices of which 5,522 needed a reduction, and the loop over all of them called
  `faces` and `youngestFace` sequentially (2.4-2.9 s of the 3-4 s pass); the two `LongMap[Long]` tables (`pairedBirth`,
  `deathOf`, boxed, 1.85M inserts) cost 0.6-1.1 s more.
- **Now**: `Dimension.scan()` classifies every death in parallel (`Parallel.ranges`, grain 4096; `faces` and `youngestFace`
  are pure), keeps the 5.5k that need a reduction, and `reduceDeaths()` reduces only those, in order, one death dimension per
  thread as before. The tables are `LongIntMap`s of pair indices (primitive), built while the scan runs (`Parallel.both`).
  `processed` now advances only at reduced deaths, which gives the same answer: `trivialDeath` asks for deaths below
  `processed`, and a trivial one between two reduced ones is below the later one. The cycle pass went from 3.5 s to 0.6-0.7 s.
- **Whole verb** (cold JVM per row, 9 rounds, the last 7 sorted): `Ripser` cycles 3.33-3.85 s; `ParallelRipser` on 4 threads
  1.02-1.35 s (two of seven rounds 2.9-3.1 s, unexplained, probably GC with `-Xmx6g`); `ParallelRipser` with representatives
  left lazy 0.44-0.54 s. On 1 thread `ParallelRipser` cycles took 1.6-2.8 s, cocycles 0.83-0.85 s against `Ripser`'s 2.07: the
  engine is faster than `Ripser` on one thread too, which this session did not look into (an unconfirmed lead: it keeps the
  edge ranks in a table, `Ripser` computes them per cofacet).
- **`threads`**: `Parallel.withThreads(n)(body)` (JVM: a `ForkJoinPool(n)`, the caller waits, `n = 1` is `sequentially`, a body
  already on a pool of `n` just runs; the body's exception comes back itself, since `ForkJoinTask.get` hands the waiting thread
  a copy wrapped around it), `Parallel.limit` and `Parallel.limited` to carry the count into work that outlives the call (the
  `LazyChain`s of `ParallelRipser`), and `Parallel.threads` reads the pool it runs on. `Parallel.foreach` and `map` are on
  `IntStream.parallel()` now, not `.par` (scala-parallel-collections runs on the common pool whatever pool called it, so a
  custom pool would have been ignored); the dependency is gone from `build.sbt`. `Persistence(..., threads = n)` wraps its
  body in `withThreads`; MATLAB and Java option `threads` and CLI `--threads` wrap `dispatch`, `dispatchWitnessFromLandmarks`,
  `dispatchDowker` and `dispatchCubical`, with the value checked before anything runs.
- **MATLAB and CLI**: `dispatchFull` sends the Vietoris-Rips complex over `Z/p` to `Persistence(Engine.ParallelRipser,
  computeAllRepresentatives = false)` (`parallelVR`, then the same `fromBars` as the other engines) when `engine` is unset,
  `Parallel.threads > 1`, no `includeZeroLength`, no `edgeCollapse`, and `ParallelRipser.supports`; `engine=parallel-ripser`
  asks for it and refuses with a message naming what failed; `engine=ripser` is always `PackedRipserCohomologyEngine` (as
  `Engine.Ripser` is for the verb). Other complexes and the witness/Dowker/cubical paths are untouched; `EngineKind` has no new
  case (the one spelling is mapped to `ripser` plus a flag at the top of `dispatchFull`).
- **Not done**: wide keys with cycles; the apply side of the cycle reductions (the 5.5k hard deaths are still reduced in order);
  `BlockParallelReduction` stays unreachable from the verb. The weekly MATLAB-in-browser checks (#215 family) have not run on
  this wiring: the facade is checked through `TDA4jParallelSpec` from Scala, not through MATLAB's Java bridge.

## Addendum 5 (2026-10-24): why `ParallelRipser` is faster than `Ripser` on ONE thread (Mikael's question on #222)

Setup: scratch driver, one engine per JVM, 7-9 rounds, median, both engines on the same `EuclideanMetricSpace` (distances
cached), p = 17, degrees 0..2, cocycles with representatives left lazy. Torus 1,200 points in R^3, threshold 1.2: `Ripser`
(`PackedRipserCohomologyEngine`) 2.05-2.15 s, `ParallelPackedCohomology` on one thread (`threads = 1`, one unit) 0.83-0.85 s
(Space 0.03-0.1 s included). Random 600 points in R^4, threshold 0.55: 1.91 s vs 0.66 s. Same bars.

**Not the algorithm's work.** Both resolve almost every column without reduction: `Ripser` 920,373 apparent + 4,279 emergent
of 977,731 simplices; the parallel engine 871,645 apparent + 5,522 claimed (degree 2: 926,249 columns). Neither reduces more
than the other, so this is constant factors per column. Toggles (torus): the parallel engine's apparent-pair method
(`ripserApparent`, Ripser's way instead of from the block's edge) 0.83 -> 0.85 s, no difference; `Ripser` with
`useApparentPairs = false` 2.15 -> 1.73 s (faster: its apparent test costs more than it saves here), without emergent pairs
2.39 s (8.5 s with neither), without neighbour lists 4.25 s (lists on: 1.97).

**Where the time goes** (JFR execution samples, innermost matching frame, scaled to the median run; sampling and inlining make
each row +-50 ms). `Ripser` / `Ripser` after the decode patch below / parallel engine, in ms:

| | Ripser | patched | parallel |
|---|---|---|---|
| decoding a simplex from its index | 647 | 384 | 135 |
| cofacet enumeration | 523 | 450 | 75 (+55 apparent test) |
| diameters (`maxPairwiseDistance`, `diameterWithout`) | 142 | 145 | 0 (a rank lookup, in "other") |
| index sort (comparator over an index permutation) | 204 | 312 | 108 (primitive `Long` keys) |
| hash maps and sets (`LongIntMap`, `ClearedSet`) | 177 | 190 | 7 |
| working column / heap | 97 | 89 | 128 |
| rest | 357 | 197 | 332 |

Why each differs:
1. **Edge ranks.** The parallel engine ranks the edges once (length, then ends) and puts the rank of a simplex's first
   longest edge, its block, in the high bits of its key. Diameter of a cofacet or facet is a max of ranks (no pairwise
   distance scan), "same diameter" is an integer comparison, the sort is on `Long` keys, and sets of columns are sorted arrays.
   `Ripser` keeps `(diameter, index)` pairs: each cofacet's diameter is computed, each facet's by `maxPairwiseDistance`, the
   order is a comparator sort of an index array, and cleared columns, pairs and emergent rows are hash maps probed per column.
2. **Decoding.** `Ripser`'s apparent test decodes the same simplex three times per column (`zeroPivotCofacet`, `zeroPivotFacet`'s
   `vertices`, the facet cursor), each with a binary search over the whole vertex range and a final sort; the parallel engine
   decodes once, searching below the previous vertex, and its table is dense.
3. **Cofacet enumeration** in `Ripser` visits every cofacet of every column (through neighbour lists) to find the oldest tie.

**Causal check on the decode.** `SimplexIndexing.decodeToArray` now looks each row up once, narrows each search to below the
previous vertex and fills the array from the back (no sort): 306 -> ~180 ns per call (random indices, n = 1,200, size 3), and
`Ripser` 2.15 -> 1.75-1.79 s on the torus, 1.91 -> 1.57 s on the cloud, bars identical (`SimplexIndexingSpec`, full suite).
That removes about 0.35 s of the 1.3 s gap and confirms the sampling attribution for this row; the other rows are NOT yet tested
by a change.

**What is left** (about 2.4x on one thread): cofacet enumeration and the per-column apparent test, diameters, the comparator
sort and the hash maps, all of which edge-rank blocks make cheap. Moving `Ripser` onto blocks would change its tie-breaking
(its representatives would then equal the parallel engine's, not today's), so it is a decision, not a patch: filed as #223.

## Addendum 6 (2026-10-24): ParallelRipser in shared code, one thread on Scala.js

Mikael: "Do A, write up an issue for more serious js parallelism." Done: `ParallelPackedCohomology` and `ParallelRipser`
moved from `src/main/scalajvm` to `src/main/scala`, so both platforms compile them; the JS refusal stub is gone.

What had to change to make that compile and keep the JVM identical:
- the engine no longer takes a `ForkJoinPool`: it uses the `Parallel` seam (`Parallel.tasks(count)(f)` pulls tasks off an
  atomic counter on at most `threads` workers; `Parallel.sortLongs` is `Arrays.sort` / `parallelSort`; block-wise sorts use
  `Parallel.ranges`). Callers that want a thread count wrap the call in `Parallel.withThreads(n)` (the spec, the benchmark
  driver and the former `main`, now `ParallelPackedCohomologyDriver` in `src/test/scalajvm`).
- on Scala.js `Parallel.tasks` is a plain loop, `sortLongs` is `Arrays.sort`, so the result is the one-thread result of the
  JVM engine: bars equal `Ripser`'s (`ParallelRipserSpec`, shared, replaces `ParallelRipserRefusalSpec`).
- `Auto` still picks it only when `Parallel.threads > 1`, which never holds on JS: JS users get it only by naming it.

Not measured: the JS speed. JS cannot be linked in this container (Maven Central 429 for the linker's dependencies), so the
first real JS run of the engine is CI's `test-js`. `ParallelRipserSpec` has a temporary timing example that prints
`TIMING <platform> <engine>` lines (110 points in R^3, degree 2, cocycles) for both engines on both platforms; JVM here:
Ripser median 129 ms, ParallelRipser 70 ms. Remove the example once the JS line is read off the CI log, and take the
numbers to #223 before any change to `Auto`.

Real JS multi-core parallelism: #224 (workers + `SharedArrayBuffer`, `Long` not a typed-array element, shared pivot
ownership needs a redesign).

**JS numbers (CI `test-js`, d536286; the timing example was removed afterwards).** 110 points in R^3, degree 2, cocycles, median of 5 after a warm-up:
Scala.js (Node on the CI runner): `Ripser` 995 ms, `ParallelRipser` 624 ms (1.6x). JVM on the same runner: 239 ms vs 68 ms (3.5x).
The first CI link also failed once, on `ThreadLocal.withInitial` (not in Scala.js's `java.lang`); the shared engine now uses an
`initialValue` subclass. `tda4jJS/Test/compile` does not link, so it cannot catch missing JDK methods: only `fullLinkJS` (CI
`npm-package`) does. Whether `Auto` should pick `ParallelRipser` on JS is for #223.
