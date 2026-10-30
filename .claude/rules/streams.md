---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/streams/**"
  - "src/test/scala/org/appliedtopology/tda4j/streams/**"
---

# Streams: the ordering contract and the VR constructions

Loads when you work in `streams/`. Project-wide rules are in `.claude/CLAUDE.md`; the public complex objects (`VietorisRips`, `Cech`, ...) are described there under "Public entry points".

## Streams: the ordering contract (the #1 historical bug source)

`SimplexStream.scala` defines `CellStream`/`SimplexStream`/`Filtration`/`StratifiedCellStream`: cells in
filtration order, a `filtrationValue` partial function, a `Filterable` (±∞ sentinels), and (stratified)
`iterateDimension`. Rules every stream must satisfy — violations have caused the "reduction pivot ... was not a
recorded open class" `IllegalStateException` at least five times, or worse, a silently different barcode:

1. **`filtrationOrdering` is a total order with the primary key reversed**: smaller-under-the-ordering = younger.
   Build it with `FiltrationOrdering.canonical(filtrationValue, dim, tieBreak)` — filtration value reversed, then
   dimension, then `tieBreak` (`SimplexIndexing.colexOrdering` for VR, matching Ripser's Def 3.2: the order of the combinatorial index on sets of one size, with no `Long` limit, `WORKLOG-colex-tie-break.md`) — rather than
   hand-rolling the comparator; every stream in this codebase goes through this one combinator
   (`WORKLOG-code-critique.md`). Reverse *only* the primary key — `.reverse` on a whole ascending ordering also
   flips the dimension tie-break. No tie-break at all = tied cells collide as one `SortedMap` key.
2. **`iterateDimension` bucket order must be `.sorted(using filtrationOrdering.reverse)`** — the *same* `Ordering`
   object, never an independently-built comparator. Two individually-valid orders disagreeing on ties breaks
   Algorithm 1's shared-order precondition.
3. **Monotone**: `fv(face) <= fv(coface)`, exactly (see Cech's ULP clamp in `filtered-complexes.md`).
4. **`iterateDimension`'s domain is contiguous from 0 and bounded** (`isDefinedAt` false past the top). `.iterator`
   is `Iterator.from(0).takeWhile(isDefinedAt).flatMap(iterateDimension)`; an always-true domain never terminates.
5. **`EnumeratingCofaceSimplexStream.currentDimension` defaults to `-1`, not `0`** — `0` was indistinguishable
   from "dimension 0 was genuinely computed and cached," so a direct out-of-order `iterateDimension(d)` call
   could silently read stale/empty cache instead of rebuilding. Regression-pinned in `CofaceSimplexStreamSpec`
   (`WORKLOG-sheehy-rips.md`); still prefer `.iterator` for driving a stream.

Checks for a new/changed stream: cross-validate against an independent stream/engine *cell-for-cell* with
tie-heavy fixtures; `totalBarsAccountForAllCells` alone is weaker. Filtration values consulted by `Chain`
comparisons must be cheap: `EnumeratingCofaceSimplexStream`/`CubicalGridStream` memoize them
(`WORKLOG-autonomous-session-2026-09-19.md`).

**VR constructions** (same output contract, alternate engines): `RipserCofaceSimplexStream` (+ `SimplexIndexing`), what
`VietorisRips(...)` builds by default (project lead: Enumerating is a last resort; the exclusion hooks `keepCriterion` and
`filtrationValueOverride` come with it, from the base class; `keepCriterion` is forwarded to `LayeredInorderSimplexStream`'s generator, `filtrationValueOverride` is not yet, #184),
`EnumeratingCofaceSimplexStream` (the reference the others are checked against),
`RecursiveStackVietorisRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR, arXiv:2301.07191
— cross-validation baseline, not a fast engine); `CofacetIterator` for lazy coboundaries. `LayeredInorderSimplexStream` (what
`Implementation.Inorder` builds; arXiv:2411.05495 mirrored, smallest vertex first): each simplex carries only its
first longest edge (inherited by its cofacets: diameter and case flags from it), candidates from sweep-updated bitset
balls (`n²/4` bytes), no sort above the edges in general position; tied runs are checked and sorted only when out of
order. The old `InorderCofaceSimplexStream` (tie order broken, duplicates on tied distances) is deleted (#182).
Its generator reads only a sorted weighted edge list (`FlagGenerator.ofEdges`, optional per-vertex deadlines), so a
coface stream that is the flag complex of a graph can build its flat layers from it (`flatGenerator`: DTM-Rips, sparse
Rips, edge-collapsed Rips; `filtered-complexes.md`, #190), and so does the default `RipserCofaceSimplexStream` (default
value, <= 2^15 points: `WORKLOG-cohomology-stream-sort.md`, #251); the stream keeps the generator's `root`s
(4 bytes a simplex of the top layer) and rebuilds the generator's order from its flat layer by reversing tied runs. `WORKLOG-layered-inorder.md`, `WORKLOG-flag-generator.md`. `FiniteMetricSpace` has
a VP-tree impl (`VantagePointTree`, `VantagePointQuery`; `WORKLOG-scalajs-numerics-spatial.md`) and `SparseMetricSpace` (+∞ past its cutoff rather than excluding — don't use it as a
thresholded oracle).

**Spatial search, enclosing balls and binomials are our own pure Scala** (Scala.js cannot use Java libraries; nothing in
`src/main/scala` may import one; `WORKLOG-scalajs-numerics-spatial.md`). `VantagePointTree`: `withinDistance` is
inclusive, `nearest(k)` is the first `k` of the stable sort by distance (ties by construction order, as `BruteForce`),
balanced and deterministic, pruning slack `1e-12`; `VantagePointQuery` is its `SpatialQuery`. `MinimumEnclosingBall`:
Wolfe's active-set method on the dual (Welzl is exponential in the boundary points), the radius IS the largest distance
to the returned centre. `SimplexIndexing.binomial`: exact `Long`, `IllegalArgumentException` on overflow.
`VantagePointTreeOracleSpec` (JVM) is judged by a linear scan, never by jvptree: jvptree 0.3.0 loses points exactly on the
radius and gives wrong nearest neighbours from k = 20 (none seen up to 16) on some random builds, so its answers are counted, not asserted
(`WORKLOG-vptree-oracle-flake.md`, `BUGS-IN-REFERENCES.md`); the one-ulp pruning case is pinned in `VantagePointTreeSpec`.

**`maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`** (Ripser's own `enclosing_radius`) in the
Enumerating/Ripser/Inorder/Incremental streams and both Ripser engines. Pass `Some(Double.PositiveInfinity)` for
untruncated. `RecursiveStackVietorisRipsSimplexStream` and alpha streams don't get this default
(`WORKLOG-mst-and-perf.md`).

**`ExplicitStreamBuilder`** — `Filterable` sentinels come from `Filterable.optionalFilterable` (±∞ for `Double`); before it the
builder silently used the data's own min/max, mislabelling bars. `fromFacets(facets)` / `fromFilteredFacets((value, facet)*)`
close a list of maximal cells under faces (unlisted face = min value of cells containing it). The naive engine on a VR
stream needs `maxDimension = k+1` for H_k (it sees only streamed simplices). `WORKLOG-tutorial-all-ways.md`.

**Public dispatcher thresholds are `Optional[Double]`** (`into`, `algebra/Optional.scala`): `maxFiltrationValue = 1.5`,
`2`, `Some(1.5)` and `None` all work; bodies use `.toOption`. `EuclideanMetricSpace(points: PointCloud)` accepts every
point-collection shape. `VietorisRips`/`Cech` implement `PointCloudComplex.fromPoints` for the `Persistence` verb.

**Testing a stream: check the contract, not bar counts.** `HomologyFixtures.respectsOrderingContract(stream)` checks
that every face is present, values are monotone along faces, and each dimension's bucket (dimension >= 1) is sorted by
`filtrationOrdering.reverse` (vertices are listed in index order, so dimension 0 is exempt). With zero-length bars
dropped by default, `#bars` no longer equals anything about `#cells`; a construction is pinned by comparing cells and
values against an oracle (`ComplexesSpec`). `ExplicitStream` is stratified, so the verb accepts `fromFacets(...)`.

**A truncated stream says so: `homologyDegreeLimit`.** `VietorisRips(..., maxDimension = k)` and every other
dispatcher, `Truncated`, `LimitedCofaceSimplexStream` and `IncrementalVietorisRipsSimplexStream` report `Some(k)`: the
stream holds cells up to dimension `k + 1`, so its degree-`(k + 1)` "classes" are artifacts (every unfilled top cell
looks essential). `Persistence(stream)` defaults to that `k` and throws for a larger `maxDimension`; complete complexes
(`ExplicitStream`, cubical, alpha, simplicial sets) report `None`. A new truncating wrapper must override it (taking
the min with the wrapped stream's). `PersistenceVerbSpec`, `WORKLOG-default-degree-2.md`.

**`keepCriterion` must be closed under faces** (project lead, 2026-10-29): every face of a simplex it keeps, vertices and
edges included, is kept. The generator route cuts each generated dimension by the criterion and generates the next from
what is left, which is the coface loop's answer exactly when the criterion is closed; it is a documented contract, not a
checked one (a check costs a facet lookup per simplex), and a criterion that breaks it gives unpredictable cells and
bars. Vertices and edges obey it in every stream. Timing with a criterion: about 1.2x on 6 million cells, equal to some
tens of ms slower on small complexes (the criterion call per candidate dominates both routes), `WORKLOG-vr-leftovers.md`, #268.

**Distance matrices are read from the lower triangle** (project lead, matching ripser.cpp and GUDHI):
`ExplicitMetricSpace.distance(x, y)` is `dist(max)(min)`, and the packed Ripser engine reads any metric that is not
symmetric by construction as `d(max, min)`. A non-symmetric file (fractal-r) then gives the same input to every tool.
`MetricSpaceSpec` pins it with a matrix whose two triangles have different `H_1`.
A loop over all pairs `a < b` asks `distance(b, a)` (a row of the cache), and takes the space as
`LayeredInorderSimplexStream.distanceOf(space)`, which calls the concrete `distance(Int, Int)` of `EuclideanMetricSpace`
and `ExplicitMetricSpace` instead of the boxing generic one (edge pass 2-7x, `WORKLOG-vr-leftovers.md`).

**`EuclideanMetricSpace` owns a snapshot of its points**: ragged or non-finite input is rejected, `pts` and `point(i)` copy, and the Cech stream reads `pointAt` without copying (`WORKLOG-code-review-followup.md`).

**`EuclideanMetricSpace` caches all n^2 distances only up to `MaxCachedPoints = 8192` (512 MB)**; above that, and with
`cacheDistances = false`, distances are computed on demand. Before the cap, n^2 overflowed `Int` at 46,341 points
(clifford50000 died at startup). Never collect all pairwise distances into a collection (the verb's scale is a loop).

**`FiniteMetricSpace.minimumEnclosingRadius`** (default radius of the verb and the streams; the scale of `longerThanFraction()`): an allocation-free
scan that stops on a point once its farthest distance reaches the best so far; `EuclideanMetricSpace` overrides the hook `scanEnclosingRadius`
on squared distances (exact: sqrt is monotone) so the n^2 distance cache is not filled. `MetricSpaceSpec` pins the value against the
definition (cached or not, as a matrix, with infinite distances) and the distance count. `WORKLOG-enclosing-radius.md`.

## Graph and series inputs

`TakensEmbedding` gives a `PointCloud`; `GraphMetric.geodesic` a dense metric space (`Infinity` between components).
`WeightedGraph` (neighbour lists, `Infinity` for a non-edge) is routed by `Input.ofMetricSpace` to `FlagComplex`, never to
the Ripser engines or the dense VR generators; `VietorisRips(graph)` is correct but builds `n x n` tables. Cell streams
that sort a dimension by their own `filtrationOrdering` (`FlagComplex`, `DirectedFlagStream`) keep values and the sorted
dimensions in separate `lazy val`s: sorting inside the lazy val that builds the values never returns. The directed flag
oracle is the complex of injective words (Betti numbers [1,1], [1,0,2], [1,0,0,9]). `WORKLOG-more-inputs.md`.
