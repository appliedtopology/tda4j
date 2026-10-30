### Breaking

- `insertionDiameter` is package-private and no longer part of the public wildcard-import surface; use the public
  `VietorisRips` and `Persistence` entry points instead (#279).
- The `EuclideanMetricSpace` constructor parameter is now named `coordinates`; positional construction is unchanged,
  and `pts` remains available as a defensive-copy accessor (#276).

### Changed

- `EuclideanMetricSpace` snapshots input coordinates, and `pts` returns defensive copies, so caller mutation no longer
  changes the metric or its caches (#276).
- `BootstrapBand` skips representative construction when it only needs barcode endpoints (#281).

### Fixed

- `EuclideanMetricSpace` rejects ragged coordinate arrays instead of silently truncating dimensions (#275).
- `ParallelPackedCohomology` expands cocycle logs iteratively, avoiding stack overflow on deep substitution chains
  (#277).
- `PersistenceMap` converts integer coefficients in logarithmic time and handles `Int.MinValue` (#280).

### Build, CI and Documentation

- `Persistence.computeAllRepresentatives` documents that cycle representatives are assembled together on first read
  (#278).
