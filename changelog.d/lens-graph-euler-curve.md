### Added

- `EulerCharacteristicCurve(points)`: the Euler characteristic of the Vietoris-Rips complex as a step function of the
  scale, up to simplices of dimension `maxSimplexDimension` (default 2). It counts the cliques of each edge's lens graph
  and builds no simplices; `curve(t)` and `curve.sample(tMin, tMax, resolution)` read it, and
  `EulerCharacteristicCurve.ofStream(stream)` counts the cells of any other stream (#191).
- The Euler characteristic curve from MATLAB, JavaScript and the command line (#248): `TDA4j.eulerCurveFromPoints` and
  `eulerCurveFromDistanceMatrix` (an `EulerCurveResult` with `scales()`, `values()`, `at(t)` and
  `sample(tMin, tMax, resolution)`), `eulerCurve` and `eulerCurveFromDistances` in the npm package, and `tda4j
  --euler-curve` with `--max-simplex-dimension`, which writes `scale,value` lines. The user guide's Euler curve section
  has a MATLAB tab.
