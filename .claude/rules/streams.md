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
   dimension, then `tieBreak` (colex via `simplexIndexing` for VR, matching Ripser's Def 3.2) — rather than
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

**VR constructions** (same output contract, alternate engines): `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream` (+ `SimplexIndexing`), `InorderCofaceSimplexStream`,
`RecursiveStackVietorisRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR, arXiv:2301.07191
— cross-validation baseline, not a fast engine); `CofacetIterator` for lazy coboundaries. `FiniteMetricSpace` has
a VP-tree (`jvptree`) impl and `SparseMetricSpace` (+∞ past its cutoff rather than excluding — don't use it as a
thresholded oracle).

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

**Distance matrices are read from the lower triangle** (project lead, matching ripser.cpp and GUDHI):
`ExplicitMetricSpace.distance(x, y)` is `dist(max)(min)`, and the packed Ripser engine reads any metric that is not
symmetric by construction as `d(max, min)`. A non-symmetric file (fractal-r) then gives the same input to every tool.
`MetricSpaceSpec` pins it with a matrix whose two triangles have different `H_1`.
