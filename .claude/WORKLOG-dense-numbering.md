# Dense cell numbering for the general cohomology engine: A/B (2026-10-04)

Point-in-time snapshot.

## The idea

The profile (`WORKLOG-homology-profile.md`) put most of the homology time into looking up filtration values of
`Simplex` objects: hashing and comparing red-black-tree sets. `DenseCohomologyEngine` (`homology/DenseCohomology.scala`,
`private[tda4j]`) runs `CellularCohomologyEngine`'s algorithm unchanged, on dense numbers:
- each dimension's cells are numbered `0, 1, ...` in the engine's own order, oldest first, so a comparison is an `Int`
  compare;
- values sit in an array, and coboundaries are precomputed as arrays of numbers;
- the reduction still uses `Chain[Int, C]` with its `TreeMap` accumulator, so only the key representation changed.

It is generic over `CellT` (simplices, cubes, simplicial-set generators), keeping full generality. Its limit is 2^31
cells per dimension; memory runs out first.

## Gate

`DenseCohomologySpec`: the same bars and the same cocycles, term for term, as the general engine (`includeZeroLength =
true`), on a VR grid (ties), the all-zero torus, a random 3-D cloud and a 9x9 image. Passes.

## A/B

Setup:
- one JVM per run, `-Xmx2G`, F_17, median of three;
- "total" includes building the stream (both engines read it once);
- "peak heap" is the sum of the heap pools' peak usage.

| input | general total | dense total | dense: precompute (incl. stream) / reduction | peak heap general -> dense |
|---|---|---|---|---|
| VR noisy-circle (60), cells to dim 3 | 15.72 s | 6.31 s | 5.37 s / 0.66 s | 1010 -> 921 MB |
| Cech noisy-circle (60), cells to dim 3 | 37.43 s | 17.79 s | 15.84 s / 0.99 s | 1260 -> 1447 MB |
| 200x200 synthetic image | 3.75 s | 2.30 s | 1.30 s / 0.69 s | 768 -> 343 MB |
| alpha (helix), flat-torus (120 points in R^4) | 0.56 s | 0.36 s | 0.08 s / 0.22 s | about equal |

Readings:
- **End to end:** 1.6-2.5x faster.
- **The reduction alone:** an order of magnitude faster. This is inferred, not separately measured: on Cech, ~16 s of
  the general total is the complex's construction (one Miniball per simplex, measured earlier), which leaves ~21 s of
  general reduction against 1.0 s dense.
- **Where the time is now:** in the precompute, which includes enumerating the stream (VR enumeration ~4-5 s, Cech
  Miniball ~16 s).
- **Memory:** lower on the image, about equal on VR, +16% on Cech.

The alpha row does not match an earlier probe of the same file (43 s through `Persistence(points, complex =
AlphaShapes)`, 512 bars). This probe builds `AlphaShapes(pts)` directly and reports 1029 bars, so the two set up the
complex differently. They are not comparable, and the row stands only as general against dense on the same stream.

## Next, not done

- Make the dense path the implementation of `CellularCohomologyEngine`. The outputs are identical, so it is an internal
  change that keeps the public engine and its generality. The involution paths could take the same numbering.
- The construction is now the bottleneck: the VR coface enumeration and Cech's Miniball calls.
