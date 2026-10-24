# Emergent pairs in the packed Ripser engine (#57, 2026-10-24)

Point-in-time snapshot. Question (#57): Bauer's emergent pairs (arXiv:1908.02518, Def. 3.11) are the other shortcut of
ripser.cpp besides apparent pairs; does `PackedRipserCohomologyEngine` gain from them?

## What was built

`useEmergentPairs` (last constructor parameter, default `true`). In the column loop, after the apparent test:

- `tie` = the oldest cofacet of the same diameter (`zeroPivotCofacet`, already computed for the apparent test): the pivot
  of the unreduced coboundary column. `youngestFacet` = `zeroPivotFacet(tie)`.
- apparent: `youngestFacet == sigma` (unchanged).
- emergent: `tie` exists and is no earlier column's pivot, i.e. not a key of `basis`, not of `emergentRow`, and not the
  apparent partner of its own youngest facet (`zeroPivotCofacet(youngestFacet) == tie`). This is exactly the first step of
  the reduction loop finding "pivot not taken", so the pair, the bar and the V-column (just `sigma`: empty log) are those
  of the reduced column. The cofacets never go on the working column and no `Column` is stored.
- A later column that needs the pivot gets the column rebuilt from `rows.birth(emergentRow(key))` through `basisFallback`
  (same path as an apparent pair's substitution).
- `tie` is also the pivot ripser.cpp's `init_coboundary_and_get_pivot` returns; ripser keeps apparent pairs out of its
  `pivot_column_index` and tests `is_in_zero_apparent_pair(cofacet)` for the same reason as the third condition.
- The involution (cycles) is untouched: emergent rows are `apparent = false` (their boundary column is not known to be
  reduced), so cycles come out of the same pairing.
- The reference engine (`RipserCohomologyEngine`) has no emergent pairs; it is the oracle.

## Correctness

`EmergentPairsSpec`: on/off agree on pairs (birth and death cells), bars with their cocycles term for term, and cycles,
over F2, F3, F17 x apparent on/off x neighbour lists on/off x one/two-word indices x threshold 0.3/enclosing radius, on
generic clouds, 3-valued grids and `tieGrid` (coordinates in `0 until 2..4`, coincident points); the bars equal the
reference engine's over F2 and F17; the simplices are accounted for by the bars. `PackedWorkingColumnSpec` (term for term
against the Chain reference, emergent default on) and the other packed specs pass.

Mutation checks (each fails all three examples): no `basis` check, no apparent-partner check, no `emergentRow` check,
fallback ignoring `emergentRow`, no clearing of the pair's cofacet. **The first only on the seeds 9, 36, 58, 83, 115 of
`tieGrid`** (a missing `basis` check needs a stored column whose pivot is a later column's first tie: 24 of 600 random
tie grids show it, none of the first spec's 3-valued grids did), hence `collisionSeeds`.

Big data (SingleEngineProfileDriver prints `barsDigest`/`cocyclesDigest`, SHA-256 of the sorted bars and of bars with
cocycle terms): identical with and without emergent pairs on sphere3_96, sphere3_192, dragon, o3_1024, random16,
fractal-r (symmetrized lower triangle), o3_4096 (F2), sphere3_192 and o3_1024 (F17), sphere3_96 with apparent pairs off.

## Measurements (4-core sandbox, one engine per JVM, `-Xms6g -Xmx8g -XX:+UseParallelGC`, F2, degrees as in the paper:
sphere3 2, dragon 1, o3_1024 3 at 1.8, fractal-r 2, random16 7, o3_4096 3 at 1.4)

Columns by kind (emergent off -> on):

| case | simplices | apparent | reduced (non-apparent, not cleared) | of which emergent |
|---|---|---|---|---|
| sphere3_192 | 1.09M | 98.1 % | 3,277 -> 846 | 2,431 |
| dragon | 1.39M | 99.7 % | 2,576 -> 2,576 | 0 |
| o3_1024 | 2.49M | 83.7 % | 47,129 -> 12,134 | 34,995 |
| random16 | 8.42M | 67.5 % | 49,252 -> 2,274 | 46,978 |
| fractal-r | 19.2M | 99.2 % | 24,766 -> 7,677 | 17,089 |
| o3_4096 | 48.4M | 89.8 % | 604,488 -> 145,426 | 459,062 |
| {0,1,2}^5 grid, 243 pts, deg 3 | 2.28M | 87.9 % | 76,769 -> 76,257 | 512 |

Where the time goes (nanoTime around the three parts, emergent off; seconds):

| case | total | apparent test (tie + youngest facet) | reducing the other columns | rest (levels, sorting, report) |
|---|---|---|---|---|
| sphere3_192 | 6.2 | 1.6 | 1.4 | 3.2 |
| dragon | 9.8 | 3.6 | 4.2 | 1.9 |
| o3_1024 | 9.0 | 4.8 | 0.9 | 3.3 |
| random16 | 24.2 | 12.3 | 0.5 | 11.5 |
| fractal-r | 73.8 | 33.4 | 5.3 | 35.1 |
| o3_4096 | 206.9 | 104.1 | 14.5 | 88.3 |

Time saved by the shortcut (reduce path off minus on, plus the emergent path itself): sphere3_192 0.06 s (1 %), dragon 0,
o3_1024 0.32 s (3.5 %), random16 0.35 s (1.4 %), fractal-r 2.6 s (3.6 %), o3_4096 5.7 s (2.7 %), grid 0. Whole-run wall
times, emergent off / on, medians of 2 trials where two were run: random16 23.9 / 24.0, fractal-r 70.4 / 65.8, o3_1024
8.4 / 8.5 (3 trials), o3_4096 212.6 / 204.0 (1 trial), sphere3_192 7.2 / 5.1 (3 trials, 5.0-9.4 s spread: noise).
Whole-run differences are inside the run-to-run spread; the instrumented saving is the clean number. With apparent
pairs off, emergent pairs alone halve sphere3_96 (1.48 s -> 0.72 s, 120,086 emergent pairs): they cover most of what the
apparent shortcut covers.

Live heap after a full GC at the end of each degree (`System.gc()` hook, instrumented build, not kept), MB at the last
degree, emergent off -> on: o3_4096 5,666 -> 4,441 (-22 %; stored column entries 33.8M -> 19.1M), fractal-r 2,315 ->
2,164 (-6.5 %; 27.0M -> 19.0M), o3_1024 244 -> 232 (-5 %), random16 500 -> 493 (-1.4 %), grid 326 -> 325. Stored
columns are what is saved; the rest (rows, level arrays) is common.

## Verdict

Shipped, default on: it is never slower, saves up to 4 % of the time and, where many columns are stored, 22 % of the
live heap (the project lead prefers memory over speed), for about 40 lines and a spec. It is not what makes ripser.cpp
fast on these inputs: the packed engine already takes apparent pairs, which cover 67-99 % of the columns, and what is
left to reduce is 0.1-14 s of 6-207 s.

## Where the time is (JFR, o3_1024, emergent off, inclusive)

`zeroPivotFacet` 30 %, `zeroPivotCofacet` 26 %, `SimplexIndexing.decodeToArray` 34 % (leaf `searchRow` 25 %),
`SparseCofacetCursor.step` 16 %, `reduce` 10 %, `SortIndices.sort` 5 %. The apparent test per column (enumerate cofacets to
the first tie, enumerate facets of that cofacet recomputing each diameter in O(d^2)) and the decoding of indices dominate;
that is #194's list (decoding), with the facet-diameter recomputation as a candidate not yet written down there.

## Method notes

- Instrumentation (counters, nanoTime sections, the GC hook) was added to a scratch build, snapshotted, and removed;
  the shipped code has only `emergentPairCount`. `reducedColumnCount` etc. are not kept.
- `pgrep -f name.sh` inside a command whose own text contains `name.sh` matches itself: use a pid or a log marker.
- `fractal-r` fits in `-Xmx8g` now (was a multi-hour timeout in 2026-09: `WORKLOG-packed-ripser-engine.md`); timings above are
  from this sandbox, not comparable with that file's.
