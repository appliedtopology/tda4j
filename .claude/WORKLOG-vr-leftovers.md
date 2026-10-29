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

## 4. Forwarding `keepCriterion` to the generator: not done

No entry point (`Persistence`, MATLAB, CLI) takes a `keepCriterion`; only code that subclasses or constructs a
`RipserCofaceSimplexStream` does. The coface loop and the generator choose different generating facets, so they agree
only for a criterion that keeps every face of what it keeps (downward closed). Forwarding would make the stream's
result depend on a property of the criterion it cannot check, to speed up a path nobody reaches from the verbs. The
streams that do carry criteria already reach the generator through the route that needs only the edges:
`DtmRipsSimplexStream` and `SheehyRipsSimplexStream` (`SparseRips`) give a `flatGenerator` built on `FlagGenerator`,
and Dowker and Cech have their own array constructions (#259). If an entry point takes a criterion, that edge-list
route is the shape to extend, not the criterion on higher simplices.
