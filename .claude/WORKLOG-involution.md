# Cycles or cocycles on request: involuted persistent homology (2026-10-04)

Point-in-time snapshot.

## The ask (project lead)

An interface that gives users cycles or cocycles, whichever they want; implement the involution. The packed
representation is to be explored only with its size limits quantified: the general engines keep full generality.

## What was built

- **`Involution.cycles`** (`homology/Involution.scala`), after Čufar and Virk (arXiv:2105.03629):
  - takes a pairing (birth cell, death cell or none), the order it was computed under (youngest first) and a boundary
    function;
  - reduces only the boundary columns of death cells, oldest first, per death dimension;
  - throws if a reduced column's pivot is not the paired birth cell.
- **Its result:**
  - a finite bar's cycle is `R_τ`;
  - an essential bar's cycle is the V-column of the reduction of `∂σ` to zero (a vertex for degree 0).
- **Engines:**
  - `CellularCohomologyEngine` and `PackedRipserCohomologyEngine` gained `pairedCohomology` (zero-length pairs
    included) and `persistentHomology` (cycles);
  - `persistentCohomology` is unchanged;
  - the packed engine's boundary decodes a `DiameterIndex`, drops the `i`-th vertex with sign `(-1)^i`, and
    re-encodes with the facet's diameter.
- **Interface:**
  - `Persistence(..., representatives = Representatives.Cycles | Cocycles)`, default `Cycles`;
  - MATLAB `representativeType`, CLI `--representative-type`, both default `cycles`;
  - `Chunks`, `Naive` and `FastCubical` with cocycles throw, naming the fix;
  - images go to fast cubical for cycles and to cohomology for cocycles.

## Facts checked before relying on them

- **`Chain`'s leading term** is the smallest cell under its `Ordering` (`PriorityQueue` built with `ord.reverse`).
  Homology therefore needs youngest = smallest, i.e. the engine's oldest-first order reversed.
- **Packed order:** diameter ascending; on ties the larger combinatorial index is older. This matches the VR stream's
  canonical tie-break.
- **`CellularCohomologyEngine`'s own order** (`Ordering.by(fv).orElse(filtrationOrdering)`) breaks ties the other way
  round from the homology engines. The involution therefore uses the engine's own order, never `filtrationOrdering`.
- **The naive engine is not a cell-for-cell oracle.** It reports `V_σ` after its own negative-cell substitutions; the
  involution reports `R_τ`. Both are valid. The tests check validity instead:
  - `z` is closed;
  - its youngest cell is `σ`;
  - `z = ∂c` with `c`'s youngest cell `τ`;
  - the inputs are tie-heavy: an all-zero 7-vertex torus, integer grids in 2-D and 3-D;
  - a wrong order must throw.

## Cost

Packed engine, degree 2, three runs each in separate JVMs, `-Xmx2G`:

| data | cocycles | cycles |
|---|---|---|
| noisy-circle (60) | 0.28 s | 0.67 s |
| circle-with-outliers (95) | 0.59 s | 1.80 s |
| flat-torus (120) | 1.20 s | 3.95 s |

For comparison, chunks takes 113 s, more than 180 s, and runs out of memory on the same inputs.

## Observations that changed docs

- **find-a-loop:** the default cycle has 18 edges, against 52 from chunks and a 121-edge cocycle.
  - `R_τ` uses only edges born by the loop's birth (0.59), so it shows the loop at its birth, like chunks' cycle; it is
    just a different and shorter one.
- **noise-and-outliers:** the default cycle of the real loop has 25 points, 8 of them outliers (chunks: 66 and 2). It
  takes shortcuts through strays near the ring. The page now quotes 17 of 25 on the ring.
  - The claim "the runner-up is mostly outliers" still holds: 5 of 7.
- **Cycle quality:** representatives are not unique, and nothing here optimizes them; shortest or optimal cycles are a
  separate topic.
