# WORKLOG: packed Ripser engine memory (2026-10-06)

Project lead: "start with the histogram and then keep going in the order of importance that you deduce from the
histogram results." Context: clifford50000 (`--dim 2 --threshold .15`; 2.24M edges, 39.3M triangles, 413M tetrahedra)
ran out of a 12 GB heap after the neighbour lists (`WORKLOG-neighbour-lists.md`) removed the all-vertex scan. Disk
spilling was considered and rejected: only the sequential simplex list could spill, and packed it is 16 bytes per
simplex; the large structures are random-access (decision recorded in the conversation, 2026-10-06).

## Method

- Histograms: `jmap -histo:live` every 30 s on the full clifford50000 run (cocycles path), forked JVM, `-Xmx12g`.
- Before/after metric for every step: clifford20k (first 20,000 points of clifford_torus_50000, threshold 0.2: 638k
  edges, 8.0M triangles, 59M tetrahedra, 8.6M cells), forked JVM `-Xms12g -Xmx12g -XX:+UseParallelGC -Xlog:gc`,
  F_2, one run; "peak" = the largest heap occupancy right after a GC in the log. Both paths: cocycles
  (`persistentCohomology`, the `tda4j-cocycles` row) and cycles (`persistentHomology`, the `Persistence` default and the
  `tda4j` row). No `jmap` during timed runs (it forces full GCs).

## Histogram (full clifford50000, cocycles, last sample before the OOM, ~11.5 GB live)

| structure | what | size |
|---|---|---|
| bar bookkeeping | PersistenceBar + 2 endpoints + 2 boxed Double + Involution.Pair + Tuple2 + Some, per pair (12.8M pairs, nearly all zero-length; ~25k reported for the 20k case) | ~2.7 GB and growing one per triangle |
| levels | `Iterator.toSeq` (a List: 79M cons cells) of boxed DiameterIndex (54M) + sorted copy | ~3.6 GB |
| cleared set | `mutable.HashSet[Long]`: node + boxed Long per entry | ~0.9 GB |
| columns, logs | `long[]`, `Object[]`, `double[]` | ~2.5 GB (already primitive-ish) |

Then a first cycles baseline on clifford20k showed the involution doubled memory: 7.07 GB cycles vs 3.54 GB cocycles.

## Steps (each its own commit, gated term for term by NeighbourListsSpec, PackedWorkingColumnSpec, InvolutionSpec,
PackedRipserCohomologySpec, ZeroLengthBarsSpec, PersistenceVerbSpec, RepresentativeKindSpec)

| step | change | cycles peak / time | cocycles peak / time |
|---|---|---|---|
| baseline | | 7.07 GB / 91.1 s | 3.54 GB / 55.7 s |
| 1 | pairing as primitive rows (`Pairing`), bars built only when returned, seed = birth of the row a pivot closes (`LongIntMap`), only non-empty logs | (unchanged path) | 1.62 GB / 48.4 s |
| 2 | involution on the rows: `SortIndices` per death dimension, apparent pairs' columns not kept but rebuilt from the death simplex's boundary, cycles only for reported rows | 1.73 GB / 62.8 s | 1.60 GB / 45.3 s |
| 3 | levels as parallel `Double`/`Long` arrays (`Level`), sorted by `SortIndices` under exactly `packedOrdering.reverse` | 1.14 GB / 61.1 s | 1.56 GB / 43.4 s |
| 4 | cleared set as a sorted `Long` array with binary search (`ClearedSet`) | 1.02 GB / 56.7 s | 1.15 GB / 41.8 s |
| 5 | pair rows reserved per degree (no doubling slack); `LongIntMap`s sized from their count, load 0.75 | 0.84 GB / 55.3 s | 1.17 GB / 42.2 s |

Totals: cycles 7.07 -> 0.84 GB (-88%), 91 -> 55 s; cocycles 3.54 -> 1.17 GB (-67%), 56 -> 42 s. Single runs in the
sandbox: the memory figures are deterministic enough to rank steps; the times are indicative only.

## Mutation checks

- step 2: disabling the rebuild of apparent pairs' columns fails 4 cycles examples (the pivot check throws);
- step 3: flipping the level tie-break (larger index first) fails NeighbourListsSpec and PackedWorkingColumnSpec;
- step 4: never clearing fails both (cleared simplices become spurious essential bars).

## The full clifford50000 (2.24M edges, 39.3M triangles, 41.6M cells), F_2, -Xmx12g

- after step 4, cycles: completes, 268 s (lists 27 s), 64,142 bars;
- after step 5: cycles 272 s, cocycles 188 s.
- The "peak after GC" figures here (7.5 / 6.5 GB) are an UPPER bound: after a young GC the occupancy still includes
  old-generation garbage, and these runs had a single full GC at an arbitrary moment. The honest memory figure is the
  smallest heap that completes (bisection, below). For the 20k steps the metric is used for ranking only.
- Histogram of the full cycles run after step 4: everything left is primitive arrays (`long[]` 2.9 GB, `double[]`
  1.8 GB, `int[]` 0.44 GB, `Object[]` 0.32 GB): the pair rows with doubling slack, the involution's birth->row map at
  load 0.5, and the sort permutations -- which step 5 addresses.

## Step 6: the boxing hypothesis, tested

Building the lists looked slow (~65 ns per pair in the memory runs). Hypothesis: `metricSpace.distance` through
`FiniteMetricSpace[Int]` erases to `(Object, Object)` and boxes both vertices. Calling the concrete spaces through their
own type (commit f929fbd), stash A/B, forked JVM `-Xms8g -Xmx8g`, median of 3 (sphere of 7): lists for 20,000 points
1.8 -> 1.3 s; o3_1024 at 1.8 9.71 -> 9.31 s; clifford20k 46.4 -> 45.4 s; sphere3_192 4.15 -> 4.17 s. Small: the JIT
already removed most of the boxing. The ~13 s setups in the memory runs came from those runs' settings (`-Xms12g` on a
15 GB machine, GC logging), not from boxing: with `-Xms8g` the same build takes 1.3-1.8 s. The 65 ns/pair diagnosis was
wrong.

## Heap bisection, full clifford50000, cycles (the `Persistence` default), F_2, after step 6

| `-Xmx` | outcome |
|---|---|
| 4g | `OutOfMemoryError: Java heap space` |
| 6g | completes, 259 s (lists 10 s), 64,142 bars |
| 12g, before this work | `OutOfMemoryError` (GC overhead limit) after ~9 min |

So the case now needs between 4 and 6 GB of heap. For scale only (different machine, different run): GUDHI used 28.5 GB
RSS and 660 s on the lead's machine. ripser.cpp's p = 2 figure for this case is not known yet; it comes from the lead's
next harness run.

## Not done

- The cocycles path's remaining largest item is its seed map (`rowOfDeath`, one entry per pair). For an apparent pair
  the seed is recomputable (`zeroApparentFacet`), as the involution now does for columns; ~200 MB at clifford20k.
- Building the lists is still O(n^2) distance evaluations (10-27 s at 50,000 points depending on JVM settings); a
  spatial grid for low-dimensional point clouds would avoid it.

## The harness's own path (PaperBenchmarkDriver -> Persistence verb, p = 17), full clifford50000, -Xmx8g

| row | wall | bars (H0 / H1 / H2) | heap used at end |
|---|---|---|---|
| `tda4j` (cycles) | 344 s | 50,000 / 14,006 / 136 | 4.9 GB |
| `tda4j-cocycles` | 252 s | 50,000 / 14,006 / 136 | 4.0 GB |

Same bar counts as the direct F_2 runs. Correctness at this size is checked only by tomorrow's agreement column
(bottleneck distance against GUDHI and ripser.cpp); the gates use small inputs.
