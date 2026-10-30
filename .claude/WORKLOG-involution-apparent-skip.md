# The packed Ripser engine's cycles pass no longer reduces apparent pairs nobody reports (2026-10-29, #223, #194)

Point-in-time snapshot. Started as a re-measurement of #223 (`Ripser` against `ParallelRipser` on one thread) after the
decode-once work (`WORKLOG-ripser-decode-once.md`); it found a larger cost than any of the rows #223 lists, in the row
that is the default.

## Finding

`Persistence(points)` runs `Engine.Ripser` with `Representatives.Cycles`: `PackedRipserCohomologyEngine.persistentHomology`,
which pairs by cohomology and then derives the cycles with its copy of `Involution.cycles` (`involutedCycles`). That pass
reduced the boundary of EVERY finite pair's death simplex, zero-length ones included ("they are pivots for the others"),
including the apparent pairs, which are about 90% of all pairs: for each it decoded the death simplex, ran the facet
cursor, computed every facet's diameter (`maxPairwiseDistance`), pushed them on the heap, found the pivot (always the birth
simplex, by the definition of an apparent pair) and drained the column to throw it away. `PackedCubicalCohomologyEngine`
already skipped this (its comment: "the reduction would stop at once with an empty log"); `ParallelRipserCycles` too.

Torus of 1,000 points in R^3, threshold 1.2, degrees 0..2, p = 17, one thread (`PaperBenchmarkDriver task=vr engine=ripser
sequential=true`, 4-core sandbox, in-JVM trials after 3 warm-up runs):

| reps | before | after |
|---|---|---|
| cocycles | 1.0-1.1 s | (unchanged, pairing only) |
| cycles (the default) | 1.81; 1.83 s | 0.99; 1.12 s (7 trials each, two JVMs) |

so the cycles pass cost about as much as the pairing. `ParallelRipser` through the verb on the same input: roughly
0.6-0.9 s (cocycles and cycles; the machine's noise is +-20%). The steady-state JFR profile before the change (`delay=` past the JIT warm-up, which dominates a naive profile:
the first three runs of a JVM take 3-5 s against 1 s steady here) had `involutedCycles` and its `reduce` at 37%, of which
`decodeToArray` (inside `pushBoundary`) 33%.

## Change

`involutedCycles`: a finite row that is apparent and not reported is recorded in `byBirth` (so another column that needs
it rebuilds its boundary from the death simplex, as before) and nothing else: no push, no pivot check, no drain. A
reported apparent row (`includeZeroLength`, or a bar with length) is reduced as before. Output: identical (SHA-256 over
every bar and every cycle term, `SingleEngineProfileDriver packed-cycles`, new engine name for the digest of cycles):
torus 1,000 (t = 1.2), 10-d cube 40 points (degrees 0..3), 16-d cube 60 points (degrees 0..2), p = 17.

## Measurements (cycles, in-JVM median of 9 trials after 3 warm-up runs, two JVMs per variant, seconds)

| input | before | after |
|---|---|---|
| torus 1,000 pts R^3, t = 1.2, degrees 0..2 | 1.81; 1.83 | 0.99; 1.12 |
| S^2 1,000 pts, t = 0.5, degrees 0..2 | 1.15; 1.33 | 0.78; 0.82 |
| 10-d cube, 40 pts, degrees 0..3 | 0.098; 0.095 | 0.061; 0.050 |
| 16-d cube, 60 pts, degrees 0..2 | 0.055; 0.045 | 0.037; 0.032 |

## Mutation checks

Skipping every unreported row (not only apparent ones) throws the pivot check in `PackedWorkingColumnSpec`; skipping every
apparent row, reported or not, leaves bars without a cycle (`NoSuchElementException` in `PackedWorkingColumnSpec`). Both
are caught by the existing specs (`PackedWorkingColumnSpec` compares with the `Chain`-based reference, with and without
zero-length bars; `ZeroLengthBarsSpec`, `PackedRipserCohomologySpec`, `InvolutionSpec`).

## Tried and not kept (no effect measured)

- `SortIndices.sort` with `before` inlined and runs of 16 sorted by insertion first: isolated, 1M random (diameter, index)
  pairs: 210 ms against 205 ms (12 rounds in one JVM, medians). The merge sort over an index permutation is bound by the
  random reads of the key arrays, not by the call; it is about 19% of a steady run (comparator frame 16% on top). Only a
  sort that moves (key, index) pairs, or a primitive key that fits a `Long` (a diameter rank in the high bits, as
  `ParallelRipser` does), would change it; the pair sort costs 16 n extra bytes against the permutation's 8 n.
- `Array.tabulate` in `SparseCofacetCursor` replaced by a loop: JFR over the first nine runs of a JVM blamed
  `ClassTag.apply` + `array_update` for 24%, but that profile is the warm-up; warm A/B: no difference.

## Left (profile of the steady cycles run after the change, 1.0 s on the torus)

`SparseCofacetCursor` step/advance 18%, `decodeToArray` (once per column and once more when the next degree is built) 18%,
the two comparator sorts 19%, `ClearedSet.contains` (binary search over about 1M longs) 8%, `zeroPivotFacet` 8%. With
cocycles the `rowOfDeath` hash map is another 10% (`LongIntMap.update`, one random insert per pair; the apparent rows
could be found again from their death cell instead). None is more than a few percent to gain on its own except the sort.
