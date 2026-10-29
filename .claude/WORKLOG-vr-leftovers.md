# Vietoris-Rips leftovers from #251 and #184 (2026-10-29, issue #268)

Four items, each decided here with its numbers. Machine: the cloud sandbox, noise about +-20%, slow first trials; one JVM
per variant, class snapshots A (baseline) to E (final), medians of 4-6 trials, two rounds.

## 1. Unboxed, row-wise edge pass: adopted

`LayeredInorderSimplexStream.Generator.edgesWithin` took `metricSpace.distance(_, _)` of a `FiniteMetricSpace[Int]`; the
generic `distance(VertexT, VertexT)` boxes both arguments once per pair (n^2/2 pairs). `distanceOf(space)` now returns
a `(Int, Int) => Double` that calls the concrete class's `distance(Int, Int)` for `EuclideanMetricSpace` and
`ExplicitMetricSpace` (any other space, or a subclass override, keeps the generic call, so an override is honoured).

Second effect: the loop is `b` outer, `a < b` inner. `distance(a, b)` walked the cached matrix with stride n;
`distance(b, a)` is one contiguous row. It is also the entry `ExplicitMetricSpace` reads (lower triangle, max then
min) and what ripser.cpp reads, so the result is the same on every symmetric space and consistent on an asymmetric
function. `ParallelPackedCohomology.Space` and `ParallelRipser` use the same two conventions.

Edge pass of the flat layers (degree 0, so edges only), baseline A to final E, warm medians:

| space | n | edges kept | A | E |
|---|---|---|---|---|
| Euclidean, R^3, radius .14 | 3,000 | 43,804 | 125 ms | 31 ms |
| Euclidean, R^3, radius .045 | 8,000 | 11,594 | 900 ms | 130 ms |
| explicit matrix | 3,000 | 43,804 | 145 ms | 76 ms |

Cold (first trial) ratios are 2.3x, 7x, 1.7x. Bars, cells and their order are identical (`allAgree` on the layered
stream against the coface loop; `ParallelPackedCohomologySpec` reads the lower triangle of an asymmetric function).

## 2. Batched probing of `PackedCells` lookups: dropped

A prototype in `CellularCohomologyEngine.coboundaries` issued the reads of a block of faces before resolving them. On
the lattice and cloud workloads the end-to-end difference was about 2%, inside the noise; the issue's bar for adopting
it was 10%. Reverted, nothing shipped. Lookups are one read since #262, so there is little latency left to overlap.

## 3. Scala.js against the JVM: a facade problem, not a platform one

Measured through the npm package (`persistence(points, { maxDimension: 2, engine: 'cohomology' })`) on uniform clouds in
R^3 (the JS side reproduces `java.util.Random(17)`, so the clouds are the JVM's):

| points | JS before | JVM facade before | JS after | JVM after |
|---|---|---|---|---|
| 60 | 5.9 s | 2.2 s | 0.16 s | 0.11 s |
| 100 | 60 s | 22 s | 1.05 s | 0.4 s |
| 150 | | | 3.9 s | 1.2 s |

JS is 2.5 to 3 times the JVM on this workload, with and without the fix: no platform-specific scaling problem (as #228
had). The large gap was on both platforms: the MATLAB/CLI/JS facade (`TDA4j.vrStream`) built the complex for
`engine=cohomology` and `engine=naive` with `LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(...))`, the
per-simplex enumeration, while `Persistence` builds it with `VietorisRips` (the flat, in-order layers). `vrStream` now
is `Truncated.ofCofaces(RipserCofaceSimplexStream(...), maxDimension)`. The two streams list the same cells with the same
values in the same order (`VietorisRipsDispatcherSpec`), so bars and representatives are unchanged; `TDA4jSpec` checks
the facade's cohomology and naive bars and representative cells against `Persistence` on a seeded cloud. The streams
already defaulted `maxFiltrationValue` to the enclosing radius, so the radius was not part of the gap.

The facade default, `engine=ripser` (one thread), on the same clouds: 100 points JS 0.40 s, JVM 0.15 s; 300 points JS
9.0 s, JVM 2.8 s: 2.7 to 3.2 times, the same ratio. Alpha and the other complexes on JS: #233.

## 4. Forwarding `keepCriterion` to the generator: done, at Mikael's request

First decided against (no entry point takes a criterion; the coface loop and the generator choose different generating
facets, so they agree only for a criterion closed under faces, which a stream cannot check). Mikael asked for it anyway
(2026-10-29): tda4j is a lab and experiments platform, and a restricted complex should keep the fast path, the way a
new idea like the apparent pairs needs a place to be tried. He also settled the contract: closure under faces is a
written-out contract, not a regular test (a check costs a facet lookup per simplex), and breaking it gives results
nobody can predict.

What was done. `RipserCofaceSimplexStream` no longer requires `keepsEverything` to use `flatGenerator`; each generated
layer (edges, then every cofacet layer) is cut by the criterion in the same order, with the `root` of each kept simplex,
before the next layer is generated from it (`pruned`, also on the rebuild path for dimensions asked out of order). With
a criterion closed under faces a simplex's first full-diameter facet, which generates it, is kept, so the cut layers
generate exactly the kept simplices, in the order of the full complex. Candidates still come from the balls of all edges
within the threshold, so a rejected edge is not used to prune candidates (the final test rejects what contains it).

Found on the way, fixed here: `EnumeratingCofaceSimplexStream` and the cell listing of `RipserCofaceSimplexStream`
ignored the criterion for vertices (and Enumerating for edges), while the array layers applied it; the reference stream
could not serve as an oracle for a criterion that removes a vertex or an edge. `FlatLayersSpec`'s criterion
(`size <= 2 || min is even`) was not closed under faces (it keeps `{0,1,2,3}` and rejects its face `{1,2,3}`; the coface
loop's answer differs from the generator's by geometry); it is now `size <= 2 || all vertices even`.

Checks. `LayeredInorderSimplexStreamSpec`: five closed criteria (a forbidden pair, a window, a removed vertex, a
dimension cap, odd vertices only in edges) against the enumerating stream and against the coface loop, cell for cell,
values and order, on random clouds, the octagon, a grid and a three-valued metric, with the generator asserted to have
been used; and equal bars from the Cohomology engine. Mutations caught: generator gated off, layers not cut, roots of
the wrong simplices.

Timing, generator route against the coface loop with a window criterion (3-D uniform clouds, median of the last trials,
sandbox noise +-20%): n=600, 20k cells: 25-35 ms against 50-60 ms (slower, fixed cost of the edge pass); n=2000, 11k
cells: 70-150 ms against 70 ms; n=2000, 533k edges: 340-440 ms against 300-330 ms; n=1500, 6.2 million cells with a
criterion that keeps all: 2.0-2.1 s against 1.5-1.8 s (1.2x). The criterion is called with a `Simplex` per candidate in
both routes and dominates both, so the gain is small. Forwarding is for what it keeps (the same machinery and order as
the unrestricted stream, a base for criterion-aware generation), not for speed.

Not done: `filtrationValueOverride` stays on the coface loop (the first half of #184 was this criterion; the second is a
value function that the generator cannot read from edges).
