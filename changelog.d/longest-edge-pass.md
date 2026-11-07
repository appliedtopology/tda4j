### Changed

- `Persistence(points, ...)` on a large point cloud finds the longest edge for the diagram's `lastFiltrationValue` much
  faster. The serial pass over all pairs became `FiniteMetricSpace.longestDistanceWithin(threshold)`;
  `EuclideanMetricSpace` answers it by one neighbour query per point on all threads above 8,192 points, and falls back to
  the pass over all pairs on all threads where the threshold keeps so many pairs that the queries would not pay. The value
  is the same. On 100,000 uniform points in the unit cube with a threshold keeping about ten neighbours per point the
  pass takes 0.33 s instead of 15.7 s; on 50,000 points of a torus, 0.35 s instead of 6.1 s; where almost every pair is an
  edge, 3.2 s instead of 15.5 s (median of three trials, one JVM each, a 4-core sandbox) (#312).

### Added

- `FiniteMetricSpace.longestDistanceWithin` (override it where a space finds the longest short distance faster than by
  all pairs), `EuclideanMetricSpace.longestDistanceByQueries` and `longestDistanceByRows`, and
  `VantagePointTree.largestDistanceWithin(query, radius, maxMeasurements)` (#312).
