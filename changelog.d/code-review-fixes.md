### Breaking

- `insertionDiameter` is package-private and no longer part of the public wildcard-import surface; use the public
  `VietorisRips` and `Persistence` entry points instead (#279).
- The `EuclideanMetricSpace` constructor parameter is now named `coordinates`; positional construction is unchanged
  (#276).
- `EuclideanMetricSpace.pts` returns a copy of all the points on every call, and constructing a space rejects ragged
  rows and NaN or infinite coordinates, as `PointCloud` always has (#275, #276). Read one point with `point(index)`.

### Added

- `EuclideanMetricSpace.point(index)` reads one point's coordinates, copied (#276).

### Changed

- `EuclideanMetricSpace` takes a snapshot of its coordinates on construction, so changing the arrays passed in, or any
  array it hands out, no longer changes its distances, its minimum enclosing radius or the filtrations built on it
  (#276).
- `BootstrapBand` builds and keeps no representatives, only the bar endpoints it compares (#281).

### Fixed

- `EuclideanMetricSpace` rejects ragged coordinate arrays, naming the point and the dimension, instead of silently
  dropping coordinates from the distance (#275).
- `ParallelPackedCohomology` expands cocycle logs iteratively, avoiding stack overflow on deep substitution chains, and
  reports a cyclic log as an error (#277).
- `PersistenceMap` converts integer coefficients in logarithmic time, and `Int.MinValue` no longer becomes zero (#280).

### Build, CI and Documentation

- `Persistence.computeAllRepresentatives` documents that cycle representatives are assembled together on first read
  (#278).
