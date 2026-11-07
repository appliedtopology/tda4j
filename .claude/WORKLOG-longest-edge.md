# The longest-edge pass of `Persistence` (#312), 2026-11-07

Point-in-time snapshot. Found while measuring #288 (`WORKLOG-memory-measurements.md` on the triage branches); fixed before the
final timing run because it is the first quadratic step a 10^5-point cloud meets.

## The problem

`Persistence(points, ...)` with the Ripser or ParallelRipser engine sets the diagram's `lastFiltrationValue` to the longest edge
within the threshold. It found it with a serial loop over all `n^2/2` pairs (`Persistence.Input.longestEdgeWithin`), whatever the
threshold, with no distance cache above 8,192 points. The issue's four stack samples of a 100,000-point run (80.6 s, from second
25: three in the loop, one in the engine) put the loop at most of it; the isolated pass below takes 15.7 s on the same kind of
cloud, so the samples and the isolated time are not reconciled (the run's own timing was not repeated after the fix).

## What replaced it

- `FiniteMetricSpace.longestDistanceWithin(threshold)`: a public method, the old loop as the default (over `elements`, so it
  is right for any vertex type), overridable like `scanEnclosingRadius`. `Persistence` calls it at both sites.
- `EuclideanMetricSpace` overrides it. Up to `MaxCachedPoints` (8,192) points it keeps the default loop (cached lookups, a
  few tens of ms). Above, `longestDistanceByQueries`: one `VantagePointTree.largestDistanceWithin` per point, the points
  spread over all threads (`Parallel.tasks`). A query that would measure more than `n / 4` distances is given up (the walk
  stops measuring, returns `NaN`) and with it the whole search: `None`, and `longestDistanceByRows` runs instead, the old pass
  with one task per point on all threads.
- `VantagePointTree.largestDistanceWithin(query, radius, maxMeasurements)`: the walk of `indicesWithinDistance` keeping only the
  largest distance within the radius, no list of elements; `NaN` past the cap, `-infinity` for none.
- The pass queries a private tree over point indices (`indexTree`: a `VantagePointTree[Int]` whose metric reads the space's own
  coordinate arrays), not the public `vpt`. `vpt` keeps cloning every point: it hands its stored points to callers
  (`nearest`, `withinDistance`), so a tree over the space's own rows would let a caller write into the space. A first version
  wrapped the rows in `vpt` and was caught in review for exactly that; `MetricSpaceSpec` now writes into what `vpt` returns and
  checks the longest edge afterwards. The index tree stores one `Int` per point, so the pass adds no second copy of the cloud;
  the `vpt` clone is still made when someone asks for `vpt` (that is #288's 94 MB at 10^6 points, unchanged by this change).

The per-query cap is the density switch the issue asked for. It is not "threshold below the enclosing radius": that test
needs the radius (itself a scan when the caller gave a threshold) and does not say whether queries pay. The cap counts what
the queries cost, so it also covers a high-dimensional cloud where a vantage-point tree prunes nothing (every query would
measure `n` distances; the queries would cost twice the loop). Work wasted before a give-up is at most about `n / 4`
measurements per thread that was running, plus the tree build (`O(n log n)`).

The value is the same number as the loop's, bit for bit: the tree's metric is `sqrt` of the same squared sum, and a squared
difference does not depend on the order of the two points.

## Checks

`LongestDistanceWithinSpec` (new) and `VantagePointTreeSpec` (extended) pin the value against a plain scan of `distance` at
thresholds equal to a distance in the data, just below it, `0`, negative, `NaN` and infinite; the queries return `Some` for a
sparse and `None` for a dense threshold; the coincident-point cloud and a heap with two far points are given up and still
right; the diagram's `lastFiltrationValue` on both Ripser engines equals a hand-derived 2.0 on 8,200 points on a line. A
count of measured distances shows the walk stops at its cap. Eleven mutants (`<` for `<=` in the tree, in the rows and in the
default loop; the farther child skipped; the cap never spent, unlimited, or ignored; the queries' result replaced by 0; the
fallback replaced by 0; either call in `Persistence` replaced by 0) each fail at least one example. A twelfth mutant (`vpt` wrapping the space's rows without cloning, the first version) fails the
`MetricSpaceSpec` snapshot example: the caller's write to a returned point changes the longest edge. Scala.js: the same spec
passes; its big examples run fewer thresholds there (a scan of 33 million distances is seconds on JS).

## A/B

A: the old serial loop, written out in a small Java driver over `EuclideanMetricSpace.distance`. B: `longestDistanceWithin`.
One pass per JVM (`-Xmx8g`, serial GC), the point generation and, where given, the enclosing radius excluded, tree build
included in B, three trials each, 4-core cloud container. The longest edge was identical to the last digit in every run. The
driver was a throwaway Java program, not kept: the old loop is twelve lines. The A column and the first B version (a tree
wrapping the rows) were alternated; B below is the shipped index-tree version, timed afterwards in its own runs, so the ratios
compare runs from different moments. The index tree measured slightly slower than the wrapped one (about 0.35 s to 0.45 s on
the torus, 0.33 s to 0.37 s on the uniform cloud; the give-up rows 3.1 s to 3.0 s and 2.4 s to 2.6 s): a difference of that
size is within what this machine's noise produced between sessions, so it is reported as unconfirmed, not as a cost.

| cloud | threshold | A, seconds (3 trials) | B, seconds (3 trials) | median ratio |
|---|---|---|---|---|
| Clifford torus in R^4, 50,000 points | 0.15 (about 180 neighbours) | 6.1, 6.6, 4.4 | 0.45, 0.47, 0.41 | 14x |
| uniform in [0,1]^3, 100,000 points | 0.0288 (about 10 neighbours) | 15.7, 17.3, 15.6 | 0.37, 0.42, 0.35 | 43x |
| 100,000 identical points | 0.5 (every pair) | 15.0, 15.9, 15.5 | 3.0, 3.1, 3.0 | 5.2x |
| uniform in [0,1]^3, 50,000 points | enclosing radius (0.858) | 10.6, 10.2, 8.8 | 2.6, 2.6, 2.5 | 4.0x |

The last two rows are the give-up path: a failed attempt (tree build and a few capped queries) followed by the parallel pass,
which is the four threads of the container against one; on one thread (`Parallel.sequentially`, or Scala.js) B there costs the
old loop plus the failed attempt, which is small next to `n^2 / 2`. The torus is the shape of `clifford50000` but my own
sample, not the benchmark's file; the benchmark's rows pay this pass once each, serially, so they lose about 4 to 6 seconds
each.

Not measured: the whole `Persistence` run on these clouds (minutes), so no claim about the end-to-end share; Scala.js timings
of the new path.

## Left over

The engines have their own serial `n^2/2` pass over the distances: `NeighbourLists.within` (the packed Ripser engine, when the
threshold keeps few pairs) and the edge collection in `ParallelPackedCohomology.Run` (`for b <- 0 until n; a <- 0 until b`).
They cost what the old longest-edge loop cost, once each, so at 100,000 points the verb now spends one serial pass where it
spent two. The same neighbour queries could feed them (and could give the longest edge for free): #313.
