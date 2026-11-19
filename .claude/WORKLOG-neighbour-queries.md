# Neighbour lists by tree queries (#313)

Snapshot of 2026-11-18. The packed Ripser engines (`PackedRipserCohomologyEngine`, `ParallelRipser`, and
`Engine.Ripser` with an `edgeTies` order) collected the edges within the threshold with one serial pass over all `n(n-1)/2`
pairs (`NeighbourLists.within`, `EdgeRanking`), and the verb read the longest edge with a pass of its own
(`longestDistanceWithin`). Above the distance cache (`MaxCachedPoints` = 8,192) that pass is the part of the run that does
not use the threads.

## What was built

- `VantagePointTree.indicesAndDistancesWithin(query, radius, maxMeasurements): Option[(Array[Int], Array[Double])]`: the
  range query with each distance beside its index, ascending by index, `None` once a query has measured more than
  `maxMeasurements` points (a private `Within` collector; `sorted()` sorts `(index << 32) | position` so the distances
  stay aligned).
- `FiniteMetricSpace.neighbourListsWithin(threshold, maxEntries): Option[NeighbourLists]`, a hook, `None` by default.
  `EuclideanMetricSpace` overrides it: `None` up to the cache, else `neighbourListsByQueries` (public): one task per
  point on all threads (`Parallel.tasks`), the point itself removed by binary search, the rows assembled into a
  `NeighbourLists` and nulled as they are copied. The give-up rule is `longestDistanceByQueries`'s: more than
  `max(1, n/4)` measurements for one query means the threshold keeps most pairs and the pass is cheaper; a shared counter
  gives up when the directed entries pass `min(maxEntries, Int.MaxValue - 8)`, as `NeighbourLists.within` does.
- The lists are the pass's lists to the last bit: the distance is a sum of squared coordinate differences, symmetric in
  the two points, so `distance(i, j)` computed from either end is the same double. Rows come out ascending.
- `PackedRipserCohomologyEngine` asks the space first (`.orElse` the pass), and `longestListedEdge` reads the longest edge
  off the same lists; `Persistence` uses it for the ripser path and `ParallelRipserRun.longestEdge` for the parallel
  path, so the second pass is gone. `ParallelPackedCohomology.EdgeRanking` and `Space` take the lists (`edgeLists`) and
  `lowerEdges` reads the `i < j` half of every row; `EdgeRankedMetricSpace` passes the base space's lists.

## Checks

`NeighbourQueriesSpec` (10 examples): the range query against a linear scan with distances; the lists against
`NeighbourLists.within` at thresholds around every distance (boundary included) on a cloud with repeated points; the give-up
rules; the cache boundary; the packed engine and `ParallelRipser` (every `EdgeTies` order on the JVM) with and without the
shortcut: same bars, cocycles, cycles and longest edge; the verb's last filtration value on both Ripser engines. Mutation
checks (each mutant run against the spec): self entry kept, `>=` for the cap, strict radius, unaligned distances, no
give-up, `min` for the longest edge, `head` for the parallel engine's longest edge all fail an example; `<=` in `lowerEdges`
survives and is equivalent (a list has no self entry).

## A/B on the Clifford torus

n = 50,000 points `(cos a, sin a, cos b, sin b)` for uniform angles (seed 1), maximum dimension 1, field F_32003, one
JVM per run (serial GC, `-Xmx6g`), pinned to three of the four cores of the container (the fourth ran an unrelated job),
three trials, medians. The lists are identical (entry counts and a checksum of the targets); the bars are identical
(count and a checksum).

| radius | entries | lists, serial pass | lists, queries on 3 threads | engine, pass | engine, queries |
|---|---|---|---|---|---|
| 0.10 | 1,991,546 | 6.83 s (6.49-7.15) | 0.44 s (0.42-0.47) | 12.57 s (12.24-13.63) | 5.65 s (5.30-5.67) |
| 0.15 | 4,481,292 | 7.09 s (6.60-7.25) | 0.66 s (0.59-0.82) | 24.40 s (24.28-26.04) | 15.66 s (15.33-17.34) |

One thread of queries: 0.94 s at radius 0.10 (0.85-1.03), so most of the gain is the tree, not the threads. The engine's
share is what is left after the reduction: 2.2x end to end at radius 0.10, 1.6x at 0.15. The serial pass costs about
7 s whatever the threshold (n^2/2 distances); the queries cost follows the edges.

Not measured: the compute server with its full thread count; Scala.js (the shared specs run there with smaller clouds;
the queries run in order on one thread).

## A trap in the A/B

The first engine A/B switched the shortcut off by wrapping the space in a bare `FiniteMetricSpace` (distances forwarded).
That also moved the engine's cofacet distance reads from the concrete `EuclideanMetricSpace.distance(Int, Int)` to the
boxing generic one: 18.4 s against 5.2 s at radius 0.10, a difference three times what the lists explain. The comparison
that isolates the lists subclasses `EuclideanMetricSpace` and overrides only `neighbourListsWithin` (`NeighbourQueriesBenchDriver`).
The specs use the bare wrapper for correctness, where speed does not matter.

## Left

Nothing owed. The 8,192-point cache boundary and the n/4 rule are the existing ones; the lists above the threshold of a
dense input still go through the pass, as before.
