### Added

- `EulerCharacteristicCurve(points)`: the Euler characteristic of the Vietoris-Rips complex as a step function of the
  scale, up to simplices of dimension `maxSimplexDimension` (default 2). It counts the cliques of each edge's lens graph
  and builds no simplices; `curve(t)` and `curve.sample(tMin, tMax, resolution)` read it, and
  `EulerCharacteristicCurve.ofStream(stream)` counts the cells of any other stream (#191).
