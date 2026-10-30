# Sparse edge ranks for ParallelRipser (2026-10-24)

Question (Mikael): the engine's `Space.rank` is a dense `Array[Int](n * n)`, which caps n at 46,340 and costs 4 n^2 bytes.
Measure it against sorted per-vertex neighbour lists. Prototype (the patch file was removed once shipped; it was a
`-Dtda4j.sparseRanks=true` switch in `ParallelPackedCohomology.Space`, not shipped). Two lookups: a binary search per
`(a, b)` (first try), and a per-column-vertex cursor into each list, since every hot loop scans the candidate vertex `v`
downwards (`startDown`/`rankDown`). Only the cursor variant is in the patch.

Setup: `PaperBenchmarkDriver task=vr engine=parallelverb sequential=true reps=cocycles p=17`, one JVM per row, ParallelGC,
4-core container, snapshot of the classes per build. Bars compared by md5 of the written barcode: identical in every pair.

| input | threshold | dense | sparse (binary search) | sparse (cursor) | peak heap dense / sparse |
|---|---|---|---|---|---|
| torus, 3000 pts in R^3, H1 | 0.8 | ~1.45 s | ~2.4 s | ~1.52 s (dense 1.28-1.35 in the same session); later 6-trial medians 0.93 vs 1.25 s | 0.8 / 0.75 GB |
| 10-d cube, 26 pts, H9 | inf | 2.55 s | | 3.1 s (+20%) | 1.2 / 1.1 GB |
| uniform box, 20,000 pts in R^3, H1 | 1.2 | 10.5-16 s | | 11.2-13.2 s (noise exceeds the difference) | 7.4 / 2.2-2.8 GB |
| uniform box, 40,000 pts in R^3, H1 | 1.2 | 36-74 s (74 = GC under a 10 GB heap) | | 49-51 s | 10.3 / 2.0 GB |

Reading: on dense graphs (small n, most pairs are edges) the dense table is 20-35% faster, because the lookup is an array
read against a search or cursor step. On sparse graphs the two are about equal in time and the neighbour lists need a fifth
of the memory; and only they can take n > 46,340. At n = 40,000 more than half the time is the O(n^2) construction (H0 only:
27 s sparse, 31 s dense warm), which neither variant touches: the distance loop over all pairs is the next cost, not the rank.

10-d cube, 40 points, H9 (Mikael's request): out of memory at an 8 GB heap in the level build, whichever rank layout: the engine
materialises a whole level of keys and C(40,10) = 8.5e8 of them is 7 GB. 26 and 30 points were run instead (30 points: dense
18-20 s, sparse 17-26 s over 2 trials, heap peak 5.4 / 6.2 GB: the level arrays dominate, not the rank table). Only 3 bar
dimensions (0..2) occur for these random clouds, so H3..H9 timing is the cost of proving there are no bars.

Not measured: the binary-search variant everywhere (only on the torus), n > 46,340, the generic hash-map alternative, Scala.js.

Recommendation: keep the dense table up to a moderate n (say 8,192, 268 MB) and switch to neighbour lists above it or whenever
n^2 > Int.MaxValue; that keeps small-n speed and lifts the 46k limit. Not done: waiting for Mikael.

## Shipped (same day): the hybrid

Mikael: "Yes, build the hybrid approach. Small slowdowns that save dramatically on memory requirements will increase what
computations can be done far more than the other way around." `Space` uses the dense table up to `DenseRankLimit` = 4,096
points (64 MB) and the neighbour lists (cursor variant) above; `forceSparseRanks` forces the lists for specs. The
`n * n <= Int.MaxValue` limit is gone from `ParallelRipser.run`/`supports`; `Space` refuses more than `MaxEdges` edges with a
message naming the threshold. Specs: `ParallelRipserSpec` (both layouts equal on pairs, cocycle logs and bars, including a
tie-heavy grid; 4,200 points on a line, past the limit, equal to `Ripser`'s bars).
60,000 points in a uniform box (threshold 1.2, H1, cocycles, one thread): 113 s, 2.4 GB peak heap, 84,084 bars; the dense table
would have needed 14.4 GB and cannot be allocated (n^2 > Int.MaxValue).
The switch by edge density and dimension (rather than by point count) is #225, with what to measure and how.
