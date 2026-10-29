# Edge collapse on a matrix with an incremental dominator test (#256, 2026-10-29)

Request: #256 (filed in the #190 session) said `EdgeCollapse.collapse` on 1,200 points at the enclosing radius ran over
four minutes without finishing and that nothing was profiled.

## Where the time went

`FlatLayersTimingDriver` is not the right place to time it (the cloud's distances are computed inside the clock there), so
`EdgeCollapseTimingDriver` times `collapse` alone, distances prefilled, one JVM per run. The earlier map form, uniform points
in the unit cube of R^3 at their enclosing radius (about 0.83 to 0.88):

| n | edges before / after | map form |
|---|---|---|
| 100 | 3,921 / 419 | 0.23 s |
| 200 | 15,201 / 1,257 | 0.50 to 0.65 s |
| 400 | 59,839 / 3,657 | 3.7 to 4.0 s |
| 600 | 130,116 / 5,566 | 12.1 s |
| 800 | 233,669 / 8,461 | 28.9 s |
| 1,200 | 527,672 / 15,292 | 98.5 s |

So 1,200 points took 98.5 s here, not "over four minutes". The earlier run was stopped, not finished, inside the layers
driver (where the cloud's distances are computed inside the clock); why it was slower there was not investigated, so the
four minutes is not a measurement of the collapse. The growth of the map form is about `n^3`.
JFR at 400 points: 96 % of the samples were `TreeMap` lookups (`get` and `contains`), spread over `dominatedAt`, the
`active` filter and the candidate-time search. The reason is the work, not the container: for each edge with `c` common
neighbours the old code tested every candidate time (up to `c + 1` of them) by rebuilding the active set and testing every
pair of it, `c^3` lookups for an edge that is dominated at every time, which is most of the edges here (89 to 97 % are removed).

## What changed

The decisions are the reference's (a port of GUDHI's single pass, edges in descending order of their original value, each
decided once against the graph as it stands); the cost per decision is not.

- **Matrix and bits** (up to 4,096 points, 134 MB): `n x n` doubles, `+Infinity` for no edge, `-Infinity` on the diagonal,
  and a bit set of neighbours per vertex. The common neighbours of `{u, v}` are the AND of two rows of bits; every value is
  one array read. Above 4,096 points the sorted-map form is kept, unchanged apart from reading the edges from arrays.
- **Incremental test**: the candidate times are `f0` and each larger `t(w)`, ascending; the active set only grows. The
  vertex that dominated at one time dominates at the next if it is adjacent, by that time, to the vertices that joined
  since (the earlier ones were adjacent by a smaller time). A new dominator is searched only when it fails, latest to join
  first, each tested against the latest to join first. The result is the same sequence of yes/no answers, so the same edge
  decisions. An edge none of whose common neighbours is there at `f0` is decided without sorting.
- The processing order is the old `sortBy((i, j, d) => (-d, i, j))` done by `RipserCofaceSimplexStream.layerOrder` on the
  negated values with lexicographic ties.
- The result is a `CollapsedGraph`: rows of sorted neighbours and values, the surviving edges only (the dense matrix is
  dropped). `EdgeCollapsedMetricSpace.distance` binary-searches a row; `edges` is unchanged.
- The vertices of the active set are sorted by `t` with a small merge sort on positions (insertion sort of runs of 8):
  rank-by-binary-search plus a `long` sort was a third of the run.

## Checks

`EdgeCollapseDenseSpec` runs both forms (`collapseWith(.., denseLimit)`, the map form with 0) on 105 inputs: clouds of 3 to
27 points with and without a bound, symmetric matrices with 2, 3 or 5 distinct distances (so ties are the rule), a repeated
point, points on a line, 40 points in space, and each of those collapsed a second time (whose removed pairs are infinite),
and compares edges and values bit for bit, the counts, and every `distance`. `EdgeCollapseStreamSpec` (barcode agreement
with plain Rips) passes unchanged. Mutations, each caught by the new spec: a strict instead of non-strict dominance test,
the active vertices' largest values not updated (before the incumbent shortcut existed), the last candidate time treated as
not dominated, no copy back after an odd number of merge passes, a reversed insertion-sort comparison, an incumbent trusted
without testing the vertices that joined. A mutation that stopped vertices from joining the active set looped forever
(`time` never advanced) and was abandoned.

## Measurements (indicative, one JVM per run, median of three trials unless noted, 4-core sandbox, `-Xmx4g`)

| n | map form | matrix form, tests every pair | + incumbent dominator | + merge sort on positions |
|---|---|---|---|---|
| 200 | 498 ms | 150 ms | | |
| 400 | 3,724 ms | 1,613 ms | 712 ms | 338 ms |
| 800 | 28,894 ms | 21,216 ms | 5,578 ms | 2,804 ms |
| 1,200 | 98,540 ms (one trial) | | | 10,311 ms (one trial) |
| 1,600 | | | | 29,667 ms (one trial) |

About ten times at 400 to 1,200 points; the matrix alone was 1.4 to 3.3 times, which is why the work, not the container,
was the cost. Growth of the new form is about `n^3` (edges times common neighbours). Not measured: other dimensions or
radii (a small radius gives sparse graphs, where the old form was already cheap), memory, Scala.js, the end-to-end
`Persistence` call, noise.

## Left

- The map form (above 4,096 points) still has the cubic test; a sparse input that large has small neighbourhoods, so it was
  left alone. The matrix form's incremental test would need a fast `getOrElse` for it.
- GUDHI's `gudhi-collapse` timing against ours on the same inputs (#174's row) has not been taken.
