### Changed

- Degree 0 is paired by union-find over the edges in `PackedRipserCohomologyEngine`, `CellularCohomologyEngine`,
  `PackedCubicalCohomologyEngine` and the parallel Ripser engine (`Engine.ParallelRipser`), not by reducing the vertices'
  coboundaries as columns. The bars and representatives are the same, term for term. H0 of 4,000 random points in the
  unit cube takes 2.7 s instead of 12.3 s and runs in a 400 MB heap, where 800 MB was not enough. `CellularCohomologyEngine`
  does it when every edge is `c·(v - u)` between two cells of the stream or has no boundary, and reduces degree 0 by
  columns on any other complex (#194).
- `PackedRipserCohomologyEngine.apparentPairCount` and `emergentPairCount` count the simplices of degrees 1 and up: a
  vertex is no longer paired as an apparent or an emergent pair (#194).

### Added

- `useUnionFind` (default `true`) on `PackedRipserCohomologyEngine`, `CellularCohomologyEngine` and
  `PackedCubicalCohomologyEngine`: `false` reduces the vertices' coboundaries, with the same output (#194).
