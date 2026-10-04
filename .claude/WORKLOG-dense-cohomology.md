# Dense cell numbering as `CellularCohomologyEngine`'s implementation (2026-10-04)

Point-in-time snapshot. Follows `WORKLOG-dense-numbering.md` (the experiment).

## What changed

- `CellularCohomologyEngine` now reduces over cell numbers. Each dimension's cells are sorted oldest first under the
  engine's order (ascending value, then the stream's `filtrationOrdering`), and a cell's number is its position.
- **Coboundary blocks** are built over numbers, one dimension at a time, and dropped after that dimension. Faces
  outside the stream are skipped; they were never reduced before either.
- **The core** is `reduction(stream): Option[Reduction]`, with `Entry(dim, birth, death, cocycle)` over numbers.
  - `pairedCohomology` maps entries back to cells.
  - `persistentHomology` runs `Involution.cycles[Int, C]` on the numbers, with `Ordering.Int.reverse` as the
    youngest-first order (comparisons only ever happen within one dimension; `Involution` already keys its bases per
    dimension). Boundaries are looked up through a per-dimension numbering, built on first use.
  - With cycles requested, no cocycle chain is ever mapped back to cells. That is why the `hom` column below can beat
    `coh`.
- **Generality kept:** any `CellT: OrderedCell`, any `FiltrationT: Ordering`, any field. The limit is 2^31 cells per
  dimension; memory runs out first.
- `DenseCohomologyEngine` (the experiment) is deleted.

## Gate

The old cell-keyed reduction moved, verbatim, to the test tree as `CellKeyedCohomologyReference`.
`CohomologyNumberingSpec` compares against it, positionally (same list order):
- bar endpoints, the `Involution.Pair`s, cocycle term sets and leading cells;
- the cycles of `persistentHomology` against `Involution.cycles` run on the oracle's cells (term sets and leading
  cells; bars above `homologyDegreeLimit` left out on both sides).

Inputs:
- a Vietoris-Rips complex on a 4x4 grid (ties everywhere);
- the all-zero 7-vertex torus;
- a random 3-D cloud under Vietoris-Rips and Cech;
- a 9x9 image (cubes);
- RP^2 and RP^4 as simplicial sets over F_3 (`Int` filtration, torsion);
- the torus over the reals.

`CohomologySpec`, `InvolutionSpec` and `PersistenceVerbSpec` pass unchanged.

## A/B

Setup:
- the same inputs as the experiment;
- one JVM per run, `-Xmx2G`, F_17, median of three;
- the stream is built and enumerated once before timing, so "time" is the engine call alone;
- `coh` = `persistentCohomology`, `hom` = `persistentHomology` (cycles, the default `Persistence` asks for).

Configurations:
- A = before (cell-keyed);
- B = dense cohomology, involution still on cells;
- C = dense cohomology and dense involution (committed).

| input | coh A | coh C | hom A | hom B | hom C | peak heap coh A -> C | peak heap hom A -> C |
|---|---|---|---|---|---|---|---|
| VR noisy-circle (60) | 16.48 s | 5.76 s | 17.53 s | 6.36 s | 5.75 s | 1072 -> 1030 MB | 1182 -> 1034 MB |
| Cech noisy-circle (60) | 39.05 s | 15.69 s | 40.46 s | 16.12 s | 14.19 s | 1294 -> 1446 MB | 1381 -> 1356 MB |
| 200x200 image | 3.71 s | 1.52 s | 4.80 s | 3.08 s | 2.36 s | 742 -> 330 MB | 814 -> 471 MB |
| alpha flat-torus (120) | 0.58 s | 0.31 s | 2.76 s | 2.54 s | 0.93 s | about equal | 383 -> 301 MB |

Readings:
- **Cocycles:** 1.9-2.9x faster.
- **Cycles:** 2.0-3.0x faster.
- **Dense involution:** most of the cycles gain on alpha (2.54 -> 0.93 s) and the image (3.08 -> 2.36 s); little
  elsewhere, where the reduction dominated.
- **Memory:** down on the image and alpha, about equal on VR.
  - Cech cocycles went up about 12% (medians 1294 -> 1446 MB, with run-to-run ranges of 1244-1350 and 1427-1573).
  - The likely cause is that every entry's numbered cocycle stays alive until the mapping back to cells at the end;
    this is not confirmed.
- **What is left on Cech:** the timed part (~14-16 s) still includes enumerating the stream inside the engine. The
  Miniball construction is ~16 s of the earlier end-to-end figure, which suggests the remaining time is mostly the
  stream, not the reduction. Inferred, not separately measured.

## Not done

- The homology engines (naive, chunks) and `Involution.cocycleBars` still work on cells.
- The packed Ripser engine already uses numbers (combinatorial indices).
