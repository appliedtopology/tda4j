# Colexicographic tie-break instead of a Long index in the Vietoris-Rips streams (#198)

Point-in-time snapshot, 2026-10-28 (thread "Work through issues #187 to #206"). The inventory that preceded it is
`WORKLOG-wide-indices.md` (2026-10-04), item 1; the two-word packed engine is `WORKLOG-two-word-indices.md`.

## What was wrong

`EnumeratingCofaceSimplexStream.filtrationOrdering` broke ties by `simplexIndexing(simplex)`, a `Long`, and
`sortedByFiltration` computed that index for EVERY cell before sorting. `SimplexIndexing.binomial` throws past 2^63, so
`Engine.Chunks` and `Engine.Naive` (which order cells by `stream.filtrationOrdering`) died on a Vietoris-Rips stream with
`binomial(999, 8) overflows Long` once a cell of 8 vertices had a vertex near 1,000, although the complex was a handful
of cells. `Engine.Cohomology` and the default `Engine.Ripser` were not affected (flat layers, and the two-word packed
engine).

## The fix

`SimplexIndexing.colexOrdering` / `colexCompare` compare ascending vertex arrays from the top. On sets of one size the
combinatorial number system is order-isomorphic to colex, so nothing about the order changes; there is no width limit.
- `filtrationOrdering` uses it. It costs the same as the index: `Simplex.underlying.toArray` per side, then a compare that
  usually stops at the first element.
- `sortedByFiltration` keeps its primitive `Long` key while `SimplexIndexing.fitsLong(n, largest cell)` holds (the path
  that was measured fast), and sorts by vertex arrays beyond that. The memoized out-of-domain branch uses `colexOrdering`.

## Tests

`ColexOrderingSpec`: index order == colex order for random sets of up to 12 of up to 40 vertices (500 cases); colex order
== the order of a `BigInt` index for random 12-sets of a million vertices; the prefix rule for different sizes; and
Chunks, Naive, Cohomology and Ripser on 1,000 points whose last nine are an 8-simplex (the top cell's index is about
2e21): the barcode is 8 bars (0, 1) and 992 essential. Putting the old tie-break back into `filtrationOrdering` makes the
last example throw `binomial(999, 8) overflows Long`. (A first version put the simplex on vertices 0..8, whose indices are
tiny: it passed on the old code too, so it discriminated nothing.)

## What stays one Long

- `EnumeratingCofaceSimplexStream.iterateDimension` and the out-of-order fallback of `RipserCofaceSimplexStream`
  enumerate `0 until C(n, d + 1)` indices: bounded by `Int.MaxValue` candidates by construction (the reference, and a
  path nothing takes through `.iterator`).
- `RipserCohomologyEngine` (the test oracle) and `ParallelPackedCohomology` (index = one `Long`; only its edge block rides
  beside it): `Engine.ParallelRipser` refuses what does not fit (`ParallelRipser.supports`), 300 points to degree 11 throw in
  `binomial`. Left open on #198.

## Observed on the way, not investigated

`Engine.Ripser` on a cycle of 1,000 points with cliques of 9 (256k cells to degree 8, two-word indices) took 58 s against 8 s
for `Engine.Cohomology`: the cost of the two-word path (#200), not touched here.

## Speed

The tie-break runs inside every chain comparison of the generic engines, so it was timed (not isolated A/B beyond this):
`Persistence(grid, maxDimension = 2, maxFiltrationValue = 1.5)` on the 125 points of the 5 x 5 x 5 integer grid, where
distances tie everywhere, median / min of 7 trials in one JVM, old tie-break then new, this container: Chunks 120 / 87 ms
then 100 / 85 ms; Naive 116 / 77 ms then 126 / 77 ms. No difference outside the noise of the machine (unconfirmed either way).
