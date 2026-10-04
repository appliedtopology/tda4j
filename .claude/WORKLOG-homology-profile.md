# Where homology time goes: chunks at degree 2 on Vietoris-Rips (2026-10-04)

Point-in-time snapshot.

The question (project lead): is there much margin left in the homology engines, or is the gap to Ripser structural?

## Setup

- Data: `noisy-circle.csv`, 60 points in R².
- Call: `Persistence(points, engine = Chunks)` at degree 2; the complex is cut at the enclosing radius 1.95.
- Measurement: one JVM, `-Xmx2G`, JFR `settings=profile`, 7,972 execution samples, 97 s wall time.
- Pitfall: `jfr print` shows five frames per stack unless given `--stack-depth 64`. Without it, two thirds of the
  samples look as if they have no library frame.

## The structural part (from Ripser's pairing with `includeZeroLength = true`)

| cell dimension | cells | negative (death) columns | zero columns |
|---|---|---|---|
| 1 (edges) | 1,543 | 59 | |
| 2 (triangles) | 23,108 | 1,484 | the other 21,624 are positive; clearing skips them |
| 3 (tetrahedra) | 231,961 | 21,624 | 210,337 (91%), each reduced to zero |

Homology with clearing still has to reduce every column of the top dimension. Cohomology clears them.

## The constant-factor part (inclusive sample share)

| frame | share |
|---|---|
| `CellularPersistenceInChunksEngine.advanceAll` | 96% |
| `Chain.reduceByUntil` / `reduceLoop` | 84% / 78% |
| `FiltrationOrdering.compare` | 87% |
| `EnumeratingCofaceSimplexStream.filtrationValue.apply` (`SimplexStream.scala:396`) | 67% |
| `SimplexOps.forall` (`MaximumDistanceFiltrationValue.isDefinedAt`) | 9% |
| `SimplexIndexing.apply` (the tie-break) | 4.5% |

- **`filtrationValue.apply`** is a `TrieMap[Simplex[Int], Double]` lookup. Each lookup hashes a `TreeSet` of boxed
  `Int`s (`MurmurHash3.unorderedHash` over a red-black-tree iterator) and compares keys with `TreeSet.equals`, which
  walks two tree iterators through `BoxesRunTime.equals2`.
- **Arithmetic** (field operations, `TreeMap` updates of the accumulator) is a few percent.
- **The comparator** calls `isDefinedAt` and `apply` on both cells for every comparison inside the `TreeMap`
  accumulator.

## Reading

- The time is dominated by looking up filtration values of `Simplex` objects, not by reduction.
- A packed cell representation would remove most of it: a combinatorial index (`Long`) carrying its diameter, as
  `PackedRipserCohomologyEngine` does. My estimate is 3-10x; it is unmeasured, and needs an A/B before it is claimed.
- The 210k zero columns stay: even a free comparator leaves homology doing about 10x the column work of cohomology
  here.

## Cycles from cocycles: involuted homology

Čufar and Virk, "Fast computation of persistent homology representatives with involuted persistent homology",
arXiv:2105.03629; Ripserer.jl implements it as `alg = :involuted`.

1. Compute the pairing with cohomology (Ripser).
2. Reduce only the death columns of the boundary matrix. A column whose reduced form is zero never contributes a pivot,
   so the restricted reduction yields the same `R` columns.
3. A finite bar's cycle is `R_τ` for its death cell `τ`. An essential class's cycle is the reduced `V` column of its
   birth cell (one column each).

Here that means 1,484 triangle columns for H1 cycles instead of all 23,108, and 21,624 tetrahedron columns for H2
instead of 231,961.

Caveat: the pairing depends on the total order. The homology pass must use exactly the order Ripser paired under:
diameter, then colexicographic index, with the same direction for ties.

## Size limits of a packed (combinatorial-index) cell, computed (2026-10-04)

A packed simplex is a `Long` index in the combinatorial number system, so a `k`-simplex on `n` vertices fits while
`C(n, k + 1) <= 2^63 - 1`. The largest `n` for each `k`, computed exactly:

| simplex dim k | max points n | needed for |
|---|---|---|
| 1 | 4,294,967,296 (but vertices are `Int`: 2^31) | H0 |
| 2 | 3,810,779 | H1 |
| 3 | 121,977 | H2 |
| 4 | 16,175 | H3 |
| 5 | 4,337 | H4 |
| 6 | 1,733 | |
| 8 | 534 | |
| 10 | 265 | |

- The limit is on the number of **input points**, not on the size of the complex.
- `SimplexIndexing.binomial` throws on `Long` overflow, so going past it fails loudly, never silently.
- The packed form covers only simplices on `Int` vertices.
  - Cubes, simplicial-set generators and explicit streams of other cell types need a different encoding.
  - The general engines must keep a generic path (project lead).

Alternative that keeps generality: each cell gets a dense `Int` id per stream in filtration order, with boundaries
precomputed as id arrays.
- It works for any `CellT`, and its limit is 2^31 cells; memory runs out first.
- It attacks exactly the profiled overhead: order and value become array lookups.
- Unmeasured; needs an A/B before any claim.
