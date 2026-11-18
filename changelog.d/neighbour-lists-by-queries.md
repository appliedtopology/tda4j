### Added

- `FiniteMetricSpace.neighbourListsWithin(threshold, maxEntries)`, a hook that returns a space's neighbour lists when it
  finds them faster than the pass over all pairs (`None` by default), and `VantagePointTree.indicesAndDistancesWithin`,
  the range query that gives each distance beside its index (#313).

### Changed

- A Euclidean point cloud above the distance cache (8,192 points) gets its neighbour lists, and the longest edge the
  verb reports, from one vantage-point-tree query per point on all threads, instead of a serial pass over all pairs, in
  `Engine.Ripser` and `Engine.ParallelRipser`. The lists are the same to the last bit; where a query would measure more
  than a quarter of the points, the pass runs as before (#313).
