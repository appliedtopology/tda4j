# Scala.js scaling: the alpha sweep (#228) and the cubical sweep (2026-10-25)

Point-in-time snapshot. The compute-server benchmark thread saw two Scala.js scaling problems in sweeps at commit
f70191c: dual-QP alpha grew from 3.4x to 493x the JVM time as the ambient dimension d rose (n = 40 interior cube points,
d = 10: 1697 s against 3.4 s, #228), and `js-fastcubical` went from 2.2x the JVM at 256² to 11x at 4096². Neither was a
Scala.js numerics problem in the way it looked.

## What the two sides ran

`bench/workers/tda4j_js_worker.mjs` calls the npm API, so `matlab.TDA4j`, the facade. The JVM side
(`PaperBenchmarkDriver`) calls the `Persistence` verb. Those are different computations, and the facade did strictly more:

- **(A) Alpha ignored `maxDimension`.** The facade passed `Int.MaxValue` to `fromBars` and built the whole alpha complex;
  DQP built simplices up to the ambient dimension d. The JVM driver builds `Truncated(AlphaShapes(pts, backend,
  maxRadius, maxDimension = dim), dim)`. The JS bars at d = 5 had degrees 0..4 although `maxDimension` was 2. So the 493x
  compared the 3-skeleton on the JVM with the whole d-dimensional complex on JS.
- **(B) Zero-length bars were built and then dropped.** Every facade engine ran with `includeZeroLength = true`, built a
  representative for each, and `result.withoutZeroLength` dropped them afterwards; the `cycleProvider` closure kept all
  of them alive. A 256² random-noise image gave the engine 131 585 bars for 20 117 reported ones. Measured on a 2048²
  random image, JVM: the verb 8.5 s, the old facade 37.6 s and 3 GB of heap.
- **(C) A JS-only cubical hot spot.** Scala.js's `java.util.Arrays.sort(a, from, to)` is a generic merge sort over boxing
  accessors that allocates a temporary array the length of the WHOLE array on every call, for `Int` and `Long` alike.
  `FastCubicalHomologyEngine.regionBoundary` sorted a slice of a growing `sortedScratch: Array[Long]` once per reported
  bar, so the cost was quadratic in the bar count. The profile (`node --cpu-prof`): `ju_Arrays.sort__AJ__I__I__V` 27.7%
  inclusive, `$ac_J` (the `Long` array constructor) 13% self, GC 28-45%. The `Long` keys of `FacetSums` and the region
  arithmetic are emulated and slow on top of that.

## What changed

1. `matlab/TDA4j.scala`: `alpha` and `dtm-alpha` are built to `maxDimension + 1` and reported to `maxDimension`, like every
   other complex (`AlphaShapes.upToEnclosingRadius(..., requestedMaxDimension)` plus `Truncated(.., requestedMaxDimension)`
   for the naive/cohomology engines; `AlphaComplexDQP.dtm(.., min(ambientDim, requestedMaxDimension + 1), ..)`;
   `engine=fast-alpha` still takes the whole triangulation and is post-filtered by `fromBars`). Every engine call takes
   `includeZeroLength` from the options (`wantsZeroLength`, default false) instead of `true`.
2. `FastCubicalHomology.scala`: `regionBoundary` is `packedRegionBoundary` (`Int` keys: `IntFacetSums`, strides from
   `GridRanks.strides`, an `Int` scratch array sorted with `Parallel.sortInts`; the coefficients re-probed by key) when the
   stream has a packed decoder, and `heapRegionBoundary` (the old `Long` path) otherwise.
3. `Parallel.sortInts(xs, from, to)` (new seam): `java.util.Arrays.sort` on the JVM; on JS an in-place introsort
   (median-of-3 Hoare partition, insertion sort below 16 elements, heap-sort fallback at depth 2·log₂ n). An attempt
   through `toTypedArray.subarray(from, to).sort()` was slower (103 s at 2048²) because `toTypedArray` copies the whole
   array.

Specs: `ParallelSortSpec` (ranges leave the outside untouched, extremes, sorted/descending/constant/two-valued/organ-pipe
inputs, a timing guard of 20 000 sorts of 20 elements in a 1M array under 3 s, out-of-range rejection) and
`AlphaMaxDimensionSpec` (capped runs against the complete run for naive/cohomology/fast-alpha, DQP, dtm-alpha, plus the
cell counts of the boundary matrix by dimension). Mutation check: all four `AlphaMaxDimensionSpec` examples fail against
the old facade.

## Measurements (one machine, 4 cores, cold single runs, Node 22.22)

| case | before | after | JVM, same work |
|---|---|---|---|
| JS fast-cubical, random noise 256² / 512² / 1024² / 2048² | 0.55 / 1.79 / 12.2 / 129.5 s | 0.36 / 0.80 / 3.09 / 15.4 s | verb 0.33 / 0.72 / 1.82 / 8.5 s |
| JS DQP alpha, d = 5 / 6 / 7 / 8 | 1.46 / 3.17 / 8.72 / 90 s | 0.75 / 0.97 / 1.51 / 2.4 s | facade 1.07 / 1.21 / 1.79 / 2.2 s |
| JS DQP alpha, d = 10 | 1697 s | 4.6 s | facade 3.5 s, verb 4.0 s |
| JS Helix alpha, d = 8 / 10 | | 9.5 / 261.7 s | facade 5.0 / 48.4 s (3.2 GB), verb 7.2 / 36.5 s |
| JVM facade, 2048² random | 37.6 s, 3 GB used | 8.9 s, 730 MB used | |

- The JVM facade and the verb driver now report the same number of bars at d = 8 and 10 (1050 at d = 8, 1557 for DQP
  and 1558 for Helix at d = 10), and the JS/JVM ratio for DQP is 1.3x at d = 10 instead of 493x. Helix at d = 10 is 5.4x
  on JS; it is Helix's own cost (the JVM facade needs 3.2 GB there), which this change does not touch.
- Barcodes of the JS facade and the JVM verb are identical in order and value at 256² and 1024², and the bar counts match
  at every size.
- What is left at 2048² on JS (fastopt profile, 29 s with profiling): GC 25%, `computeDualTopDimension` 9% self, `computeH0`
  5%, `quickSort` 3%, `GridRanks.radixOrder` 3%. About 1.2 KB of heap per reported bar (typed arrays and a `PackedChain`
  per bar) is the memory floor on V8; the random 2048² image reports 466 235 H0 and 838 086 H1 bars, and building the
  result objects for them is part of the time. Not pursued: the remaining gap to the JVM verb is 1.8x at 2048².
- The blob image of the sweep (`bench.py` `blob`, seed 20261005): 2048² 13.2 s on JS after (the old facade ran past
  21 minutes and was killed; the same quadratic sort is the likely cause, not re-run on the old build); 4096² 96.7 s and
  5.8 GB of heap on JS, 1.86M H0 and 3.35M H1 bars. The sweep's `js-fastcubical` took 621 s there against 54.5 s on its JVM
  (a different machine, so the ratio is not comparable; rerun the sweep).

## Recipes

- **Scala.js linking in a cloud session** (Maven Central answers 429 for `closure-compiler` and `scala-library`): a
  repositories file `[repositories]\nlocal\ngmirror: https://maven-central.storage-download.googleapis.com/maven2` and
  `sbt -Dsbt.override.build.repos=true -Dsbt.repository.config=<file> tda4jJS/npmPack` (also `fastLinkJS`, `tda4jJS/testOnly`).
- **Profile a JS run**: `tda4jJS/fastLinkJS`, then `node --cpu-prof --cpu-prof-dir=<dir> bench/workers/tda4j_js_worker.mjs
  task=cubical input=<npy> dim=1 p=2 warmup=0 trials=1 engine=fastcubical reps=cycles` and summarise the `.cpuprofile`
  by self and inclusive time per function (a 40-line script over `nodes`, `samples` and `timeDeltas`). A heap snapshot
  of a 256² image is readable; a 2048² one is too large.
- **Compare the facade with the verb on the JVM**: a small Java driver calling `matlab.TDA4j.computeFromPoints` /
  `computeFromCubicalImage` with the same option strings the worker passes, against `PaperBenchmarkDriver`. A bench
  that compares JS and JVM must compare like with like: the worker's facade path against the JVM facade, or the
  Scala.js verb against the JVM verb.
- `pkill -f` with a pattern that appears in the shell's own command line kills the shell: use `pgrep` and kill by PID.

## Open

- Other `java.util.Arrays.sort(a, from, to)` sites in shared code have the same per-call allocation on JS. Called once
  (`PackedRipserCohomology.ClearedSet.seal`) it costs one extra array; called per block, as in
  `ParallelPackedCohomology` (`within`, one call per block), it is quadratic in the block count (#229).
- Helix alpha at d = 10 (5.4x the JVM), the ~1.2 KB of heap per bar, the `Long` arrays of `GridRanks.radixOrder` (#230).
- The bench compares the JS facade with the JVM verb: a JVM facade run keeps the two comparable (#232).
- The bench worker's comment "the API's maxDimension does both" is corrected in the same change.
- CLAUDE.md and four rule files are over their size budget (#231).
