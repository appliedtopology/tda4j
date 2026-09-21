# 3D cubical capacity sweep (2026-09-20)

Exploratory sweep, not a bug hunt: "how large can we comfortably go with 3D voxel cubical?" -- a direct
follow-up to `.claude/SHOWCASE-APPLICATIONS.md`'s cubical directions (MRI segmentation, porous-media micro-CT,
cosmic-web density fields), all of which need real-sized 3D grids to be more than a toy demo. Uses
`CubicalProfileDriver` (single-JVM-process, invoked directly via `java -cp`, bypassing sbt's own 1GB heap and
the sbt-hosted benchmark harness's timeout/daemon-thread risk -- same reasoning as every other driver in this
family). Machine: 8 cores, 32GB RAM, other processes (IDE, bloop daemon) holding ~17-18GB RSS at the time of
the sweep.

## Driver change

`CubicalProfileDriver` gained an `engine` CLI argument (`naive` default, `chunks`) so the SAME random grid shape
can be swept through both `CellularHomologyContext` (`CubicalHomologyContext`) and
`CellularPersistenceInChunksContext[Cube, Double]` with one tool -- needed because CLAUDE.md's own cubical
benchmark numbers already show chunks is the substantially faster engine on `Cube` (25/18/23 vs 84/48/109
us/cell at n=8/16/24 in 3D), so a capacity question has to measure both, not just the one the driver originally
targeted. Not committed as part of this sweep -- left for the project lead's own review, per this repo's usual
commit workflow.

## Methodology

Random dense grid (uniform `[0,1)` per-voxel value, seed 42), `dims=3`, `n` = grid extent along each axis
(cell count = `(2n+1)^3`, the T-construction's doubled-coordinate convention already documented in CLAUDE.md).
Same phase-separated timing the driver already did (coface-enumeration sort / global sort / `advanceAll`
reduction), plus `/usr/bin/time -l`'s peak RSS ("peak memory footprint") wrapped around each run, since capacity
is a memory question as much as a time one. Each `n` is a fresh JVM process.

## Naive engine (`CellularHomologyContext`/`CubicalHomologyContext`)

| n  | cells   | total time | total us/cell | peak RSS |
|----|---------|-----------:|---------------:|---------:|
| 8  | 4,913   | 0.48s      | 98.1           | 387 MB   |
| 16 | 35,937  | 2.45s      | 68.3           | 803 MB   |
| 24 | 117,649 | 11.0s      | 93.5           | 2.15 GB  |
| 32 | 274,625 | 36.4s      | 132.7          | 2.13 GB  |
| 40 | 531,441 | 126.4s     | 237.8          | 3.12 GB  |
| 48 | 912,673 | 262.2s     | 287.3          | 5.00 GB  |

Per-cell cost is clearly super-linear past n=24 (68→94→133→238→287 us/cell) -- consistent with
`.claude/WORKLOG-ripser-profiling.md`'s already-characterized `Chain.reduceLoop`/`TreeMap` accumulator growth
under larger reductions, not a new pathology. **Not pushed past n=48** (912k cells, 4.4 minutes, 5GB) --
extrapolating the trend, n=64 (2.15M cells) would likely be 10+ minutes and threaten an 8GB heap.

## Chunks engine (`CellularPersistenceInChunksContext[Cube, Double]`)

| n  | cells     | total time | total us/cell | peak RSS | heap cap |
|----|-----------|-----------:|---------------:|---------:|---------:|
| 8  | 4,913     | 0.45s      | 92.1           | 255 MB   | 8g       |
| 16 | 35,937    | 1.26s      | 35.1           | 455 MB   | 8g       |
| 24 | 117,649   | 3.65s      | 31.0           | 1.62 GB  | 8g       |
| 32 | 274,625   | 7.87s      | 28.7           | 2.30 GB  | 8g       |
| 48 | 912,673   | 31.2s      | 34.2           | 3.69 GB  | 8g       |
| 64 | 2,146,689 | 90.2s      | 41.3           | 10.26 GB | 12g      |
| 80 | 4,173,281 | 211.5s     | 50.5           | 13.35 GB | 12g      |
| 96 | 7,189,057 | 477.3s     | 66.3           | 13.53 GB | 12g      |

Confirms CLAUDE.md's existing finding that chunks is much flatter per-cell than naive through moderate sizes
(29-35 us/cell from n=8 to n=48, vs. naive's climb to 133+ over the same range) -- but **memory, not time, is
the actual binding constraint at real scale**: n=64 already needed a 12GB heap to avoid GC thrashing (an
earlier attempt at n=64/80 with an 8GB heap showed `user` time exceeding `real` time by 1.5x+, the signature of
heavy concurrent GC under memory pressure -- re-run at 12GB to get a fair measurement, not a GC-inflated one).
By n=80-96 the process is pinned at the 12GB heap ceiling (peak RSS ~13.3-13.5GB, i.e. heap fully saturated
plus ~1.3-1.5GB of ordinary non-heap JVM overhead) and per-cell time is climbing again too (41→51→66 us/cell) --
both trends say this is real memory pressure, not slack. Rough memory cost: ~4.8 KB/cell at n=64 down to ~1.9
KB/cell at n=96 (the ratio shrinking as fixed overhead amortizes, but the per-cell cost itself stays
substantial) -- plausible given `HomologyState` carries several parallel mutable maps/sets keyed by cell
(`boundaries`, `R`, `activeRows`, `cleared`, `paired`, `essentialSimplices`, `barcode`), each potentially holding
a `Chain[Cube, Double]` with several terms, not just a scalar per cell.

**Not pushed past n=96** (7.19M cells, 8 minutes, 12GB heap already saturated) -- going further would need a
substantially larger heap (20GB+), which risked contending with this machine's other live processes (IDE, bloop
daemon, ~17-18GB RSS already in use out of 32GB total) rather than a real, controlled measurement.

## Reading against the showcase directions

- **A single functional MRI volume** (e.g. ~64x64x34, ~140k voxels) is comfortably within chunks' fast, cheap
  range (n≈32-48 equivalent) -- no scaling work needed for that specific case.
- **A full-resolution anatomical MRI** (e.g. 256x256x256, ~16.7M voxels) or a **micro-CT porous-media scan**
  (often tens of millions of voxels) are both well beyond what was measured comfortable here even with the
  faster engine -- a real showcase at that scale needs either a cropped/downsampled region of interest, or
  further engine work (the `CubicalRipser`/Wagner-Chen-Vuçini union-find-plus-discrete-Morse approach CLAUDE.md
  already names as a valid, unattempted future direction).
- **A modest cosmological simulation grid** (128^3 = 2.1M cells) sits right at the n=64 data point above --
  workable as a batch job (~90s, ~10GB heap) but not interactive; 256^3 (16.7M cells) is out of reach without
  more work, same conclusion as the MRI case.

## Decision

- Sweep stopped at n=96 (chunks) / n=48 (naive) -- both for genuine resource reasons (memory ceiling
  approaching, wall time crossing into many-minutes territory), not an arbitrary cutoff.
- No code fix attempted here -- this was a capacity *measurement*, not a bug hunt. The memory-per-cell cost of
  `CellularPersistenceInChunksContext`'s several parallel per-cell maps is a real, now-quantified target for a
  future memory-reduction pass, if a showcase application actually needs volumes in the multi-million-voxel
  range.
- `.claude/SHOWCASE-APPLICATIONS.md`'s cubical "honest caveat" section is updated to cite these concrete
  numbers instead of a vague "needs realistic-sized volumes" note.
