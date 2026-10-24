# Cubical persistence: peak memory (#56), 2026-10-24

Point-in-time snapshot. The rules that came out of it are in `rules/cubical.md` ("Memory of the grid engines").

## The question

#56: peak RSS of the image engines was 4-10x CubicalRipser and GUDHI (2-D noise 2048²: 5.4 GB against 1.0 / 1.4 GB; 3-D
noise 96³: 4.2 GB against 0.40 / 0.51 GB). Done is: within about 2x of CubicalRipser on both, every bar still with a
representative, measured with `bench/` on one machine against both references, time not worse in an A/B.

## What RSS measured (the finding that shaped the work)

`bench/` runs the JVM with `-Xmx16g -Xms16g -XX:+UseParallelGC`. With a heap that large the collector never has to run
during a 1-3 GB computation, so RSS is the run's total allocation until the first young collection (eden is ~5 GB), and
`sweep-cubical`'s number is `max(cold, warm)`, the warm run being a warm-up and several trials in one JVM: several
computations' garbage, up to the size of eden whatever one of them needs. Measured with one cold run on this machine (4
cores, 15 GB), original code, `-Xmx12g -Xms12g`:

| input | RSS | heap used at the end | allocation (JFR) | live set at the end |
|---|---|---|---|---|
| 2-D noise 2048² | 2.5 GB | 2.7 GB (no collection ran) | 2.4 GB | ~0.6 GB |
| 3-D noise 96³ | 3.4 GB | 3.3 GB (no collection ran) | 3.1 GB | ~0.2 GB (peak ~0.43 GB during the cycles pass) |

So a pinned-heap RSS cannot be brought near a native tool's by any library change: it needs the allocation volume of a
whole run to be below the reference's footprint. `bench.py run` and `sweep-cubical` got **`--jvm-compact`** (serial
collector, `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20`, no pinned `-Xms`): the heap follows the live data and RSS is
about what the computation needs. Its times are the serial collector's, so times come from the default policy. Other
collectors tried on 96³ (RSS): serial with those ratios 526 MB, G1 with them 514 MB, parallel with a 64-128 MB young
generation 676 MB (promoted garbage is never collected in a 12 GB old generation). The smallest heap at which 96³ still
runs at its speed (`-Xmx` pinned, parallel collector): 290 MB (RSS 400 MB, +5 % time); 2048² runs at 900 MB (RSS 1.0 GB).

## Where it went (both profiles: JFR `jdk.ObjectAllocationSample` aggregated by class and first tda4j frame, and
`jcmd <pid> GC.class_histogram` back to back during a run, which collects first and so shows live data)

Allocation, 2048² (original): per facet in the dual union-find's loop a `Tuple2` of two boxed `Integer`s (`val (a, b) =
side match ...`, 440 MB) and three `Tuple2$mcII$sp` per merge (215 MB); `Array.fill(n)(Int.MaxValue)` boxes every
element (123 MB); the region boundary's sort buffer was sized by every facet of the region before the interior ones
cancel (430 MB for 150 MB kept). 96³: `WorkingColumn.drain` two arrays at the heap's size plus trimmed copies (770 MB); a
`Tuple2` + boxed `Integer` per reduction step in the logs (and 1.38M of them live at once), `mutable.LongMap[C]` and boxed
`Long`s in `cubeChain` and `for death <- arrayOfLongs` (~500 MB); a `Some` per basis lookup; the apparent pairs' columns
rebuilt through a heap, a `drain`, a `Column` and an `Option` at every step that met one (5M `Column`s, ~400 MB).

Live peak, 96³ (cycles pass, ~430 MB): stored `Column`s as `long[]` (8 B per entry, 147 MB) plus a second copy of the same
cells and coefficients in the reported cycles' chains; two `LongIntMap`s (row of birth cell, row of death cell) at
load factor 0.75 with doubling (~100 MB); the logs; `Rows` doubling from 1024 (cap 4.2M for 2.7M rows).

## What changed (each step measured, one cold run, `-Xmx12g -Xms12g` unless stated)

1. Fast engine: no tuples in the dual loop or the H₀ merge, primitive fills, one reused sort buffer for region
   boundaries. Packed engine: logs are `Log` (primitive pivots and coefficient references, one reused `LogBuilder`),
   `cubeChain` sorts by `SortIndices` instead of combining in a `LongMap` (its inputs are already combined),
   `cycleChain` hands a reduced column's arrays to the chain (the heap pops in the chain's order), `drain` writes into the
   heap's own freed slots and copies out once at the exact size (an empty column is shared), loops over arrays instead
   of `for x <- array`. 2048²: allocation 2.7 → 1.5 GB, RSS 2.5 → 1.3 GB, 15.2 → 7.5 s. 96³: 3.3 → 1.6 GB, RSS 3.4 → 1.5 GB,
   24.8 → 14.5 s.
2. Packed engine: a `Column` is `Int` keys (not packed `Long`s) and shares its arrays with the chain; the rank lookup that
   costs is `cellRank(key)` per entry added, and it showed no time difference (14.0-14.5 s before, 14.0-14.9 s after).
   Live peak 96³ ~390 → ~325 MB.
3. Dense `Int` arrays by cell replace the two `LongIntMap`s and `seedOfDeath` (a cell is the birth or the death of one row,
   never both); live peak → ~250 MB. At `-Xmx450m` RSS 530 MB.
4. An apparent pair's column is pushed straight from the cell's coboundary (boundary): leading coefficient from the cell,
   no heap, `drain`, `Column` or `Option`; `Rows` allocated at its upper bound (`cellsUpTo`). 96³: allocation 1.6 → 1.0 GB,
   RSS 1.4 → 0.89 GB pinned.
5. `PaperBenchmarkDriver` kept `held = diagram` and the last `bars` alive until the next run replaced them: two runs'
   results at once in every warm run. It lets go before each run now (`held` only with `hold=`).

## Verification

Cubical specs (`FastRepresentativesSpec`, `PackedCubicalCohomologySpec`, `FastCubicalHomologySpec`, `ZeroLengthBarsSpec`,
`FastAlphaHomologySpec`, `CubicalSpec`, `PackedChainSpec`): green after each step; the engine equals the generic engine
term for term, apparent pairs on and off. Seven mutations of the new code each fail them: the facet / cofacet coefficient
fixed at one (13 errors), the drain slot offset (13), the cycles' chain in the wrong order (2 failures), the seed sentinel
(5 errors), the apparent check dropped (13), the sort direction of `cubeChain` (failures). Digest checks of bars with
representatives between the original and the final classes: see "Results".

## Results: `sweep-cubical` against CubicalRipser and GUDHI (this machine, 4 cores, one run per row of the CSV)

`bench/sweep_cubical.py --dims 2,3 --images random --max-pixels 4200000 --engines fastcubical,cripser,gudhi --no-js
--trials 2 --jvm-compact`; "before" is the original classes with the fixed driver, "after" the final ones. RSS is the
sweep's `max(cold, warm)`, where warm is a warm-up and two trials in one process, so the references' numbers are above
what one cold run needs (CubicalRipser on 96³: 290 MB in one run, 432 MB here).

| image | CubicalRipser MB | GUDHI MB | tda4j before MB | tda4j after MB | after ÷ CubicalRipser | after ÷ GUDHI |
|---|---|---|---|---|---|---|
| 128x128 | 49 | 46 | 116 | 103 | 2.10 | 2.24 |
| 256x256 | 65 | 62 | 169 | 165 | 2.54 | 2.66 |
| 512x512 | 123 | 128 | 279 | 245 | 1.99 | 1.91 |
| 1024x1024 | 329 | 395 | 457 | 404 | 1.23 | 1.02 |
| 2048x2048 | 1009 | 1426 | 1356 | 1252 | 1.24 | 0.88 |
| 16x16x16 | 45 | 42 | 133 | 104 | 2.31 | 2.48 |
| 24x24x24 | 48 | 48 | 159 | 158 | 3.29 | 3.29 |
| 32x32x32 | 55 | 57 | 196 | 185 | 3.36 | 3.25 |
| 48x48x48 | 90 | 99 | 331 | 222 | 2.47 | 2.24 |
| 64x64x64 | 155 | 178 | 348 | 314 | 2.03 | 1.76 |
| 96x96x96 | 432 | 514 | 943 | 536 | 1.24 | 1.04 |
| 128x128x128 | 945 | 1121 | 2233 | 1211 | 1.28 | 1.08 |

At the issue's two sizes and at 128³ (2048², 96³, 128³): 1.24x, 1.24x, 1.28x CubicalRipser and 0.88x, 1.04x, 1.08x GUDHI. Below about 64³ and 512² the JVM's own ~100 MB is most of the number (2-3x), which is not what the issue is about.

With the default policy (the pinned 16g heap) the same sweep gives, median warm seconds and RSS:

| image | before s (median) | after s (median) | after ÷ before | before RSS MB (pinned 16g) | after RSS MB (pinned 16g) |
|---|---|---|---|---|---|
| 128x128 | 0.028 | 0.022 | 0.80 | 161 | 155 |
| 256x256 | 0.083 | 0.068 | 0.82 | 271 | 222 |
| 512x512 | 0.425 | 0.380 | 0.89 | 639 | 405 |
| 1024x1024 | 1.910 | 1.236 | 0.65 | 1968 | 1105 |
| 2048x2048 | 10.472 | 10.629 | 1.01 | 4790 | 3591 |
| 16x16x16 | 0.053 | 0.042 | 0.80 | 179 | 159 |
| 24x24x24 | 0.159 | 0.105 | 0.66 | 309 | 209 |
| 32x32x32 | 0.381 | 0.240 | 0.63 | 505 | 258 |
| 48x48x48 | 1.467 | 0.964 | 0.66 | 1267 | 439 |
| 64x64x64 | 6.348 | 2.626 | 0.41 | 2713 | 784 |
| 96x96x96 | 15.612 | 12.953 | 0.83 | 4882 | 2240 |
| 128x128x128 | 37.622 | 39.287 | 1.04 | 5884 | 4635 |


That RSS is eden: it cannot reach the references' however little a run needs, and 2048² is as high after as before relative
to its size because one run's allocation is still ~1.5 GB and the warm run is three of them. (The timing columns of that
sweep scatter by +-20 % between identical runs here; the A/B below is the time result.)

Time, interleaved A/B, one JVM per variant, warm-up then trials, original vs final classes, same driver (medians of the
trials after the first collection had run; the first young collection of a 16g heap lands in whichever run is
allocating at the time, which moved it into the timed trials of the lighter variant in a first, shorter A/B):

| input, heap | before | after |
|---|---|---|
| 3-D 96³, `-Xmx4g -Xms4g`, 5 trials, two JVMs each | 13.8 s | 11.3 s (−18 %) |
| 3-D 96³, `-Xmx16g -Xms16g`, 6 warm-ups then 4 trials, two rounds | 12.8 s | 11.3 s (−12 %) |
| 2-D 2048², `-Xmx4g -Xms4g`, 5 trials, two JVMs each | 7.7-8.1 s | 7.2-7.4 s (−7 %) |
| 3-D 128³, serial collector (the compact sweep), 2 trials | 44.3 s | 37.8 s (−15 %) |
| 2-D 2048², serial collector (the compact sweep), 2 trials | 9.1 s | 8.0 s (−12 %) |

Equality: `PaperBenchmarkDriver ... digest=true` (SHA-256 over every bar's dimension, endpoints and its representative's
keys and coefficients, in list order) is the same for the original and the final classes on six images with `engine=auto
reps=cycles` over F₃: noise 2048² and 96³, a 40³ image with 6 values (ties everywhere), a 48³ image with 8 % masked
pixels, a 600² image with ties and masks, a 10⁴ image; and with `engine=cohomology` for cocycles and for cycles over F₂ and
F₅ on the 40³, 48³, 96³ and 600² images (16 pairs, all identical).


## Limits, not done

- A noise image is the worst case for bars (a bar for every few pixels); smooth images need far less. The bars themselves
  cost ~330 B each (`PersistenceBar`, two endpoints with their boxed `Double`s and `Ordering` field, `Some`, the chain);
  the 2048² diagram is ~0.5 GB of its ~0.6 GB live set. Shrinking that is an API question (`BarcodeEndpoint[Double]` boxes;
  `PersistenceBar` carries an `Ordering`), not an engine one, and was left alone: #218.
- The Scala.js build is not measured; the shared code changed only in primitive-array idioms available there.
- The two block-parallel experiments are untouched.

## Method notes

- One cold run per JVM through `PaperBenchmarkDriver task=cubical`, RSS from `/proc/<pid>/status` VmHWM sampled every 20
  ms, `heap_peak_mb`/`old_peak_mb` from the driver's pool peaks. `hold=N` keeps the diagram reachable for a histogram.
- Aggregating JFR by class and source line (the first tda4j frame of `--stack-depth 6`) found every item above in one
  pass; read the line, not the method.
- A/B at one policy, baseline classes with the new driver (the driver changes alter RSS in warm runs).

## Follow-up (2026-10-24, later the same day): the compact policy is the benchmarks' default

Mikael's decision, on the numbers above: peak RSS under the pinned heap said nothing about need, so `bench/` now runs every
JVM tool with the compact policy (serial collector, `MinHeapFreeRatio=10`, `MaxHeapFreeRatio=20`, no `-Xms`), and the
flag is the other way round: **`--jvm-fixed-heap`** (the old `-Xmx = -Xms` with the parallel collector) on `bench.py run`,
`sweep-cubical`, `sweep-alpha` and `count-cells`. `sweep-threads` sets it itself: its multithreaded tools allocate on every
thread and a serial collector would collect that garbage on one (a reasoned exception, not measured). The README said to
take times from the pinned policy; that needed checking before the default moved, and the check says the compact
policy's times are not worse (4 cores, 15 GB, one JVM per row, warm-up then trials, the final classes, `-Xmx16g`):

| case | policy | trial times (s) | RSS MB |
|---|---|---|---|
| Rips, sphere3_96, dim 2 (2.4M cells), warm-up 1, 3 trials | pinned, parallel | 0.63 0.91 1.59 / 0.69 0.93 1.60 | 813 / 816 |
| | compact, serial | 0.72 0.53 0.56 / 0.78 0.54 0.49 | 217 / 203 |
| Rips, random16, dim 5 (4.7M cells), 2 cold trials | pinned, parallel | 9.6 14.2 / 13.4 16.3 | 2807 / 2893 |
| | compact, serial | 6.6 5.2 / 6.9 6.7 | 326 / 320 |

(Two runs of each policy, shown after the slash.) The pinned policy's trials get slower within a JVM (a young generation
of ~5 GB that is filled for the first time, which I suspect is first-touch page cost but did not isolate); the compact
one's do not. So on these cases and on the cubical images above (128³ 37.8 against 39.3 s, 2048² 8.0 against 10.6 s) the
compact policy is as fast or faster. Not measured: a live set of several GB (o3_4096, fractal-r), where the parallel
collector's threads may pay off, and a machine with more than 4 cores; `--jvm-fixed-heap` is the way to check.
A first attempt at this check used o3_1024 without a threshold (dimension 2 up to the enclosing radius): both JVMs were
killed at 13.6 GB after 4 minutes, which says nothing about either policy.
