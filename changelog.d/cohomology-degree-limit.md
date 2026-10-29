### Fixed

- `Persistence` with `Engine.Cohomology` (what `Auto` picks off Vietoris-Rips complexes and grids) and
  `representatives = Representatives.Cocycles` no longer builds a bar and a cocycle for every cell of the stream's top
  degree only to drop them: 150 points on a sphere, degrees 0 to 2, took 45 s and over 8 GB of heap and now take 9 s in a
  1 GB heap, with the same 190 bars. `CellularCohomologyEngine.persistentCohomology` and
  `PackedCubicalCohomologyEngine.persistentCohomology` leave out the bars above a truncated stream's
  `homologyDegreeLimit`, as `persistentHomology` and `Involution.cocycleBars` do (#199).

- Every engine that reads a stream now stops at the stream's `homologyDegreeLimit`, whatever degree it was asked for.
  `CellularPersistenceInChunksEngine` (default `maxDim = 5`, `PersistenceEngine.chunks(5)`) and the one-argument naive
  cursor `CellularHomologyEngine.persistentHomology(stream)` used to report the unpaired cells of a truncated stream's top
  dimension as essential classes of a degree the stream was not built for; the explicit-degree naive cursor now takes the
  smaller of its argument and the limit. A hand-built complex that really wants its top degree must not be a
  `Truncated`/`LimitedCofaceSimplexStream` (use `ExplicitStreamBuilder`).
- `FastCubicalHomologyEngine.persistentHomology` takes a `maxDimension` like `FastAlphaHomologyEngine`: below the top
  degree of an image it no longer walks the dual graph or builds the top degree's representatives only to drop them;
  `Persistence(image, maxDimension = k, engine = FastCubical)` and the MATLAB facade pass it.
