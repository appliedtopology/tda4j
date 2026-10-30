# Profile of the generic engines, and the cocycle path that built every top-degree bar (2026-10-28, #199)

Point-in-time snapshot. #199 asked whether `Chain.reduceBy`'s `TreeMap` working column is the resource hog of the generic
engines (`Naive`, `Chunks`, `Cohomology`, and `Involution`'s cycles) before replacing it with a heap. It is not. The
profile also showed a different hog on the way: `CellularCohomologyEngine.persistentCohomology` built a bar and a
cocycle for every cell of a truncated stream's top degree.

## Method

`GenericEngineProfileDriver` (test scope, JVM): `<engine> <workload> <Cycles|Cocycles> [trials] [seed] [terms]`,
workloads `sphere:<n>` (n random points on S^2), `cloud:<n>:<d>`, `image:<side>`, all through
`Persistence(.., maxDimension = 2, engine, representatives)`, so the stream and the verb's defaults are what a user gets
(F_17, zero-length bars dropped). One engine and workload per JVM, `-Xmx4g`, JFR `settings=profile` (10 ms samples of Java
frames), 4 cores, seed 17. Shares are of all samples; "reduceBy" is the samples with `Chain.reduceLoop`/`reduceByUntil`
anywhere on the stack; "updateMap" is the topmost tda4j frame being `Chain.updateMap` (the `TreeMap` get/update/remove
of a column addition). The aggregation scripts were throwaway: frames from `jfr print --json`, a regex per marker.
`jfr print` (text) throws `StringIndexOutOfBoundsException` on some of these recordings; use `--json`.

## Results (single run each; the sample count is the precision)

| engine, workload | wall | samples | reduceBy | updateMap | next largest |
|---|---|---|---|---|---|
| Chunks, sphere:60, cycles | 32.9 s | 4727 | 35.8% | 15.3% | `CellRanks.canonical` 15.0%, `Chain.from` 7.6%, `CellRanks` compare 6.4% |
| Naive, sphere:60, cycles | 11.4 s | 1703 | 30.4% | 11.8% | `HeapChain.collapseAll` 8.6%, `HomologyState.<init>` 6.9% |
| Chunks, cloud:50:3, cycles | 6.0 s | 394 | 39.3% | 6.3% | `CellRanks` compare 11.4% (inside reduceBy), `HeapChain.collapseHead` 7.4% |
| Naive, image:128, cycles | 2.8 s | 101 | 19.8% | n/a | `HeapChain` frames 39.6% |
| Chunks, image:128, cycles | 2.9 s | 95 | 3.2% | n/a | |
| Cohomology, sphere:60, cycles | 1.1 s | 48 | 16.7% | n/a | `LayerSorter.sort` 31%, `PackedCells.matches` 6% |
| Cohomology, sphere:150, cycles | 12.2 s | 1059 | 6.7% | 5.8% | `PackedCells.matches` 27.9%, `LayerSorter.sort` 18.9% and `.less` 18.1% |
| Cohomology, cloud:250:3, cycles | 28.0 s | 3154 | 0.5% | 0.2% | `PackedCells.matches` 26.5%, `LayerSorter` sort+less 41% |
| ParallelRipser, sphere:150, cycles | 2.7 s | 163 | 2.5% | n/a | its own `Heap.pop` 28% |

Verdict on #199: the working column is not the hog. On the two slowest generic engines `updateMap` is 6 to 15% of the run
and the whole of `reduceBy` 30 to 39%, which includes the ordering comparisons and the `HeapChain` reads that a different
working column would still pay; the engines that matter for speed (`Cohomology`, `Ripser`, `ParallelRipser`, cubical)
spend 0 to 7% there. A heap working column could save at most the `updateMap` share, about 15% on `Chunks` and `Naive`
for point clouds, and nothing where the time goes. Not built. `Chunks` on 60 points is 30 times slower than `Cohomology`
on the same input, for reasons that no working column changes.

## What the profile found instead

`Cohomology` with `Representatives.Cocycles` on `sphere:150` ran 45 s and ended with `OutOfMemoryError` at `-Xmx8g`
(needed about 8.5 GB live), where the cycle run took 9 to 12 s in under 4 GB. A class histogram at the peak showed 8.2
million `PersistenceBar`, `HeapChain`, `PriorityQueue` and `TreeSet` instances, against 190 bars returned. The stream is
`VietorisRips(.., maxDimension = 2)`, so it has tetrahedra; the cells of that top degree that kill no triangle class are
unpaired in the coboundary reduction, so each is an essential bar of degree 3 with a cocycle (itself). The verb dropped
them (`bars.filter(_.dim <= maxDimension)`) after `PackedReduction.cocycleBars` had built every one.
`persistentHomology` and `Involution.cocycleBars` already cut at the stream's `homologyDegreeLimit` before building
representatives, and `PersistenceEngine`'s scaladoc promised the same of the cohomology engine, so the cut is now in
`CellularCohomologyEngine.persistentCohomology` (both its paths) and in `PackedCubicalCohomologyEngine.persistentCohomology`
(which reduces degrees up to the limit only, as its `persistentHomology` does). The total size of the 190 representatives
was 9,085 terms: the memory was entirely the discarded bars.

After the fix, same cloud and seed, one JVM per run: 8.9 s at `-Xmx1g` (was 44.8 s and 48.9 s, and out of memory at 8g), the
same 190 bars. `CohomologyDegreeLimitSpec` fails on the old code (4 of 6 examples, both paths) and passes after;
`InvolutionSpec`'s "leave out the degree above a truncated stream's limit" asserted the old cocycle behaviour
(`exists(_.dim == 2) must beTrue`) and now asserts that neither kind reports the degree.

## Leads for speed (unverified; filed as #251)

On the `Cohomology` runs above, 40 to 60% of the time is `RipserCofaceSimplexStream$LayerSorter` (sorting each layer of
the Rips stream) and `CellularCohomologyEngine$PackedCells.matches` (the face hash lookups); neither was A/B tested.
`Chunks` spends 15% in `CellRanks.canonical`.

## All-engines audit of the top degree (2026-10-28, #252)

Request: check every engine, not only cohomology, for what it does with the cells of a truncated stream's top
dimension (`VietorisRips(.., maxDimension = k)` holds cells of dimension `k + 1`; the ones that close nothing are not
homology). `TruncatedTopDegreeSpec` runs each way to a barcode on a random cloud and an integer grid (many ties) and
compares the truncated stream's bars with the same way on the stream one degree longer, filtered to degree `k`, with and
without zero-length bars. The ways: `PersistenceEngine.{naive, naiveCocycles, chunks, chunksCocycles, cohomology,
cohomologyCycles}`, chunks at its default `maxDim = 5`, the direct engines (chunks at its default, the naive cursor on the
whole stream, the cohomology engine's two methods), the verb with `Naive`, `Chunks`, `Cohomology`, `Ripser`,
`ParallelRipser` (both representative kinds), alpha, and a 3-D image with `FastCubical`, `Cohomology`, `Chunks`, `Naive`,
and the fast cubical engine directly with a degree cap in 2, 3 and 4 dimensions.

Run against the code without the fixes below, three ways failed (the same three, on both inputs): chunks at `maxDim = 5`
(and `PersistenceEngine.chunks(5)`), `CellularPersistenceInChunksEngine()` and the one-argument naive cursor. Each
reported the unpaired cells of the stream's top dimension as essential classes of a degree the stream was not built for
(for `chunksCocycles(5)` the bars were already cut, by `Involution.cocycleBars`). The verb was never wrong for them: it
throws when the requested degree exceeds the stream's limit and otherwise passes the limit as the degree.

Fixes: the chunks engine reads `min(maxDim, homologyDegreeLimit)` per stream (the state's `maxDim`/`internalMaxDim`); the
naive cursor with no degree reads a stream with a limit as the cursor with that degree, and the cursor with a degree takes
the smaller of the two; `PersistenceEngine.naive` no longer picks the cap itself. `FastCubicalHomologyEngine` had no
degree argument and computed every degree, the dual-graph top degree with its representatives included, for the verb to
filter; it now takes `maxDimension` as `FastAlphaHomologyEngine` does. Nothing else needed a change: Ripser and
ParallelRipser take the degree and read cofacets one dimension up themselves; `FastAlphaHomologyEngine` already capped.

Specs that used a stream with a declared limit as the reference "uncapped" input were wrong about what they tested and
changed: `NaiveCappedSpec` wraps the Rips stream in a view without the limit; `PersistenceInChunksSpec`'s three-cycle graph
was a `LimitedCofaceSimplexStream` of dimension 1 (declares degree 1 an artifact) and is an `ExplicitStreamBuilder` now.

Not measured: the saving of the fast cubical cap (the dual graph skipped for a 3-D image at `maxDimension = 1`).
