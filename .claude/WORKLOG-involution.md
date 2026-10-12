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

## Follow-up the same day: the general engine on truncated streams

`CellularCohomologyEngine.pairedCohomology` runs through the stream's top dimension. On a stream truncated for degrees
`0..k`, every unpaired top cell becomes an "essential" pair of degree `k + 1`. The involution reduced each of their
boundaries to zero, which is exactly the work it exists to skip, and the verb and facade then dropped those bars.

| Cech, noisy-circle (60 points), degree 2 | before | after |
|---|---|---|
| cocycles | 47.6 s | 48.7 s |
| cycles | 374.1 s | 50.7 s |

The full test suite went from 256 s to 533 s before the fix.

Fix: `persistentHomology` skips pairs above the stream's `homologyDegreeLimit`. Essential pairs are never pivots, so no
other cycle changes. `InvolutionSpec` pins it. The packed engine never had the problem: its loop stops at
`maxDimension`.

## Cocycles from a homology pairing (2026-10-04)

The project lead asked whether the involution runs the other way, and wanted both kinds of representative everywhere.
It does.

**The algorithm, `Involution.cocycles`:**
- Reduce the coboundaries of the birth cells of finite pairs, youngest first. The leading term is the oldest cell, so
  the `Chain` ordering is oldest-first.
- Each pivot must be the paired death cell.
- An essential birth's coboundary must reduce to zero.
- The representative is `V_σ`, the convention the cohomology engines already use.
- Coboundaries come from inverting the boundaries of the stream's cells one dimension up (`coboundariesOf`).

**Checks:**
- On the cohomology engine's own pairing, it reproduces that engine's cocycles term for term.
- It gives valid cocycles on an all-zero torus triangulation.
- Chunks' and naive's `pairing` agree exactly on tie-heavy inputs (integer grid, all-zero torus), chunks' union-find
  included.

**Fast cubical:** no dual path. Its degree-0 union-find breaks ties by value only, and its top degree comes from the
dual graph, so its pairing need not follow the stream's tie-break. Image cocycles go through the cohomology engine;
`FastCubical` / `fast-cubical` / `fast-alpha` with cocycles throws, naming the fix.

**Cost** of native against derived, medians of three in separate JVMs, `-Xmx2G`:

| engine, input | native | derived |
|---|---|---|
| chunks, noisy-circle VR degrees 0-1 | cycles 4.40 s | cocycles 5.65 s (1.3x) |
| naive, same | cycles 4.52 s | cocycles 5.52 s (1.2x) |
| Ripser, same | cocycles 0.31 s | cycles 0.38 s (1.2x) |
| Ripser, noisy-circle degrees 0-2 (earlier) | cocycles 0.28 s | cycles 0.67 s (2.4x) |
| cohomology, 200x200 image | cocycles 4.96 s | cycles 5.48 s (1.1x) |
| chunks, 200x200 image | cycles 27.3 s | cocycles 30.0 s (1.1x) |
| fast cubical, 200x200 image | cycles 2.55 s | none |

"Slightly faster" holds in degrees 0 and 1. For VR cycles in degree 2 the derived kind costs about 2.5x.
