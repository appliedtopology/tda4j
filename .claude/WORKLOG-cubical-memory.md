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
representatives between the original and the final classes on both inputs: see the numbers on #56.

RESULTS_TABLES

## Limits, not done

- A noise image is the worst case for bars (a bar for every few pixels); smooth images need far less. The bars themselves
  cost ~330 B each (`PersistenceBar`, two endpoints with their boxed `Double`s and `Ordering` field, `Some`, the chain);
  the 2048² diagram is ~0.5 GB of its ~0.6 GB live set. Shrinking that is an API question (`BarcodeEndpoint[Double]` boxes;
  `PersistenceBar` carries an `Ordering`), not an engine one, and was left alone.
- The Scala.js build is not measured; the shared code changed only in primitive-array idioms available there.
- The two block-parallel experiments are untouched.

## Method notes

- One cold run per JVM through `PaperBenchmarkDriver task=cubical`, RSS from `/proc/<pid>/status` VmHWM sampled every 20
  ms, `heap_peak_mb`/`old_peak_mb` from the driver's pool peaks. `hold=N` keeps the diagram reachable for a histogram.
- Aggregating JFR by class and source line (the first tda4j frame of `--stack-depth 6`) found every item above in one
  pass; read the line, not the method.
- A/B at one policy, baseline classes with the new driver (the driver changes alter RSS in warm runs).
