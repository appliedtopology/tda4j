# Two unverified memory claims measured: the cocycle memo (#287) and the coordinate snapshot (#288)

2026-11-06, thread "Triage issues before 1.0.0 and paper". Both issues asked for a measurement before any fix; neither
measurement found a reason for one. A point-in-time snapshot; the drivers are kept (`src/test/scalajvm/.../homology/
CocycleLogDepthDriver.scala`, `SnapshotMemoryDriver.scala`) to repeat them.

## #287: depth of the reduction logs of `Engine.ParallelRipser`

`CocycleLogDepthDriver <circle|torus|sphere|cube<d>> <n> <maxDim> <threshold> [threads] [seed] [p] [noise]` runs
`ParallelPackedCohomology.persistence(..., cocycles = true)` and expands every bar's cocycle with a copy of `cocycle`'s loop
that counts (the memo's columns and the terms in all its entries, the longest chain of substitutions), checking each result
against `cocycle` itself. Degree 0 has no log (its cocycles are the union-find's components). Four threads, p = 17 unless
noted, thresholds below the enclosing radius; the worst bar of the degree-1 cocycles:

| cloud | n | threshold | longest chain (all bars) | worst memo: columns / terms | that cocycle's own terms |
|---|---|---|---|---|---|
| noisy circle (0.05) | 1,000 | 0.2 | 2 | 191 / 382 | 191 |
| noisy circle | 1,000 | 0.4 | 2 | 1,115 / 2,230 | 1,115 |
| torus (0.03) | 1,000 | 0.35 | 5 | 292 / 827 | 283 |
| sphere (0.03) | 1,000 | 0.3 | 6 | 376 / 1,052 | 310 |
| cube, 3 coordinates | 800 | 0.3 | 6 | 535 / 1,665 | 502 |
| cube, 5 coordinates | 400 | 0.45 | 5 | 175 / 479 | 143 |
| noisy circle | 4,000 | 0.2 | 4 | 5,051 / 10,120 | 5,050 |
| cube, 3 coordinates, p = 2 | 3,000 | 0.2 | 7 | 2,637 / 8,557 | 2,262 |
| torus, degrees up to 3 | 3,000 | 0.3 | 7 | 3,771 / 10,215 | 3,684 |

Degree 2 chains are at most 4 (degree 3 has no bars). The median log is one substitution, p99 of the chain 2 to 5. The
memo's terms are at most 3.8 times the cocycle's own size on the bar with the largest memo (the cocycle is the output and
is held anyway), because chains are shallow: the `d²` of the issue needs a chain of `d` substitutions with non-zero factors, and the longest seen is 7. The
worst memo is about 0.7 MB at 64 bytes a term (an estimate for `Map[Long, Int]`'s boxed entries, not measured).

Verdict: no change to `cocycle`. Limits of the measurement: clouds of at most 4,000 points, degrees up to 3, p = 17 and 2;
the chain depth grew slowly with n (4 at 4,000 points on the circle, 7 on the 3,000-point cube), so a 10^5-point cloud has
not been looked at. The spec with factor 0 (`ParallelPackedCohomologySpec`) still checks the stack, not this.

## #288: what `EuclideanMetricSpace` keeps of a cloud

`SnapshotMemoryDriver <n> <d>`: retained heap after four `System.gc()` calls of the caller's array, then the space built
over it (`coordinates.map(_.clone())`), then the space after its first `neighbors` call (`vpt` clones the rows again).
10^6 points in 3 dimensions, `-Xmx8g -XX:+UseSerialGC`: the caller's array 40.8 MB (43 bytes a point), the snapshot 42.0 MB,
the tree 51.5 MB (54 bytes a point: its copy of the rows and about 10 bytes of nodes); 134 MB held in all, of which 94 MB is
what PR #286 added. At 10^5 points: 12.5 MB held in all (the 10^4 numbers are below the noise of the counter).

Against the work: `Persistence(points, maxDimension = 1)` on 10^5 points (threshold 0.0288, about 10 neighbours each)
peaks at 1,126 MB of heap, so the two extra copies (8.4 MB) are 0.7%; at 10^4 points the peak is 117 MB (0.8 MB of copies).
At 10^6 points that run was not finished (see #312: the verb's longest-edge pass alone is `n²/2` distance evaluations; the
15.7 s of the isolated pass at 10^5 points extrapolates to about half an hour at 10^6, an estimate, not a run), so no peak was
read there; any complex built on 10^6 points holds at least its edges, millions of entries
against the 94 MB.

Verdict: no change. If the 94 MB ever matters, the two options of the issue are real: `PointCloud` handing its array over
(42 MB) and `vpt` building over indices (about 44 MB); neither is worth the aliasing risk a caller's later write would carry.

## Found on the way

`Persistence.Input.longestEdgeWithin` (#312): a sequential pass over all pairs, 5.4 s at 50,000 points of a Clifford torus
(`clifford50000`'s shape) and 15.7 s on 100,000 uniform points in the isolated pass (`WORKLOG-longest-edge.md`, with the
A/B). The issue's stack samples of an 80 s run at 10^5 points put the loop at most of it; the isolated pass does not
account for that, and the two are not reconciled. Not a reason to repeat a timing run (a few seconds on rows of minutes), but
worth fixing before the final run since it costs nothing then.
