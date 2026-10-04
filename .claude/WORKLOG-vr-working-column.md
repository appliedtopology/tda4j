# WORKLOG: VR path — heap working column, lazy cocycles, packed involution (2026-10-04)

Lever 3 of the paper-benchmark plan (`PLAN-paper.md`): the Vietoris-Rips path, cocycles and cycles, in
`PackedRipserCohomologyEngine`.

## Starting point

`PaperBenchmarkDriver task=vr` on `sphere_3_192_points.dat` (192 points on S³, degrees 0..2, F₂, enclosing-radius
threshold), JFR: `Chain.reduceLoop` 62% of samples, `TreeMap.get` 37.7%. The working column of every reduction was a
`mutable.TreeMap` keyed by `DiameterIndex` under `compareDiamThenIndex`; every reduced column and every V-column was a
`Chain` (a `PriorityQueue`); cycles came from the generic `Involution.cycles`, which also reduces on `TreeMap`s and
builds a V-column `Chain` for every death column.

## What changed (all in `PackedRipserCohomology.scala`)

1. **Heap working column** (`WorkingColumn`): binary min-heap of (diameter, index, coefficient) in three primitive
   arrays (coefficients boxed as `Any`: `CoefficientT` is generic). Adding a column pushes; `pivot()` pops the top and
   every entry with the same index, sums, pushes the sum back if non-zero. Same pivots and log as `Chain.reduceBy`
   (each pivot occurs once per reduction: after subtracting a column with pivot `p`, everything left is past `p`).
   Correct only if a simplex always gets ONE diameter, i.e. a symmetric distance (ripser.cpp assumes the same). The
   old comparator comment mentions a non-symmetric `distance` giving one index two diameters; no test or worklog for
   that case could be found. Documented, not guarded (a symmetry check is O(n²) distance calls).
2. **Reduced columns stored unsorted** (`Column`): pivot first (combined), the rest as they lie in the heap, NOT
   combined. Re-pushing them combines them in the next working column. Sorting/combining on store (`drain` popping
   everything) was a large share of the first heap version's profile.
3. **Lazy cocycles**: each column keeps (seed, reduction log); `expandV` builds a V-column only for bars that are
   reported, depth-first without recursion, memoized per dimension. `pairedCohomology(cocycles, zeroLengthCocycles)`;
   `persistentCohomology(false)` skips zero-length bars' cocycles, `persistentHomology` builds none.
4. **Packed involution** (`involutedCycles`): `Involution.cycles` specialised — same heap with the order reversed,
   boundaries pushed straight from `FacetCursor` (`pushBoundary`, same `maxPairwiseDistance` so diameters are
   bit-identical), V-columns only from logs where an essential bar needs one, cycles built only for reported pairs.
5. Coboundaries pushed straight from the cofacet cursor (`inline forEachCofacet`); `coboundaryOf` keeps its
   signature via the same helper.

## Gates

- `PackedWorkingColumnSpec` (new): against `ChainPackedRipserReference` (HEAD's engine, copied to the test tree),
  over F₃ and F₁₇, apparent pairs on/off, random clouds and integer grids (ties everywhere): `pairedCohomology()` and
  `persistentCohomology()` term for term in list order; `persistentHomology(true)` and `persistentHomology()`; and
  thresholded runs (0.3, 0.6) where essential degree-1/2 cycles exist (asserts > 5 of them occur, so the lazy-V path
  is exercised).
- Mutation checks: sign of the logged coefficient flipped → cocycle example fails; `-` → `+` in the memoized
  V-expansion → essential example fails; sign of `addScaled` flipped → the reduction never terminates (OOM).
- `PackedRipserCohomologySpec`, `RipserCohomologySpec`, `InvolutionSpec`, `PersistenceVerbSpec`, `ZeroLengthBarsSpec`
  pass.

## Measurements

Same session, same machine, `-Xms8g -Xmx8g -XX:+UseParallelGC`, 1 warm-up + 5 trials, medians; HEAD = the engine
before this change (`git show HEAD:...` swapped in, recompiled).

| case | HEAD | after | speedup |
|---|---|---|---|
| sphere3_96 cocycles | 0.815 s | 0.449 s | 1.8x |
| sphere3_96 cycles | 1.11 s | 0.698 s | 1.6x |
| sphere3_192 cocycles | 11.30 s | 5.51 s | 2.0x |
| sphere3_192 cycles | 19.2 s | 6.73 s | 2.9x |

Step by step at 192 points (medians, same settings): heap + sorted store 8.9 / 16.3 s (cocycles / cycles); unsorted
store 6.4 / 13.4; packed involution 6.4 / 12.9 (barely moved: the cocycles it still built were the cost); lazy
cocycles + skip zero-length 6.0 / 9.2; direct boundary pushes 5.9 / 7.5; direct coboundary pushes 5.5 / 6.7. The last
step is within noise.

Memory, one trial, `-Xmx` capped: at 2 GB HEAD 10.8 / 23.1 s, after 6.2 / 8.5 s. At 1 GB, HEAD runs out of memory
for both cocycles and cycles; after, cocycles finish (15.7 s, GC-bound) and cycles still run out of memory (in
`involutedCycles`: it keeps a reduced column and a log for every death column).

Against ripser.cpp's 0.82 s at 192 points (earlier session's smoke run): from ~14x to ~6.7x for cocycles, ~8x for
cycles. For the paper's "quantifiably low slowdown" this is still the largest gap of any segment.

## Not done / next

- Involution memory: apparent pairs' death columns could be recomputed instead of stored (Ripser stores nothing for
  them); that is what would let cycles run at 1 GB.
- The remaining cohomology time is spread over the heap, `SimplexIndexing` cursors and `zeroApparentCofacet`; no
  single hotspot left above ~15%. Boxed coefficients (`Array[Any]`) are the obvious next representation cost
  (an `Fp` specialisation to `Int` arrays), not attempted.
- Emergent pairs (Ripser Def 3.11) are still not implemented.
