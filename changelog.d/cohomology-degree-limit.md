### Fixed

- `Persistence` with `Engine.Cohomology` (what `Auto` picks off Vietoris-Rips complexes and grids) and
  `representatives = Representatives.Cocycles` no longer builds a bar and a cocycle for every cell of the stream's top
  degree only to drop them: 150 points on a sphere, degrees 0 to 2, took 45 s and over 8 GB of heap and now take 9 s in a
  1 GB heap, with the same 190 bars. `CellularCohomologyEngine.persistentCohomology` and
  `PackedCubicalCohomologyEngine.persistentCohomology` leave out the bars above a truncated stream's
  `homologyDegreeLimit`, as `persistentHomology` and `Involution.cocycleBars` do (#199).
