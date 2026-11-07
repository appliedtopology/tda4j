### Added

- `complex` for circular and toroidal coordinates from MATLAB, Python and JavaScript: `h1Bars(points, numLandmarks,
  complex)`, `circularCoordinates(points, r, cocycleIndex, prime, numLandmarks, complex)` and
  `toroidalCoordinates(points, r, cocycleIndices, prime, reduce, numLandmarks, complex)` take `'cech'` or `'alpha'`
  (default `'vr'`, the route the shorter forms take) and work on the loops of the diagram of that complex over the field
  with `prime` elements, as `CircularCoordinates(diagram, ...)` does in Scala; Python and JavaScript take `complex="cech"`
  and `complex: 'cech'`, and `h1Bars` a `prime`. A Cech or alpha loop has radii for its bar, where the Vietoris-Rips
  loop has diameters; neither has a landmark form, so `numLandmarks` must be 0 with them. The TypeScript declarations of the npm package list these options, `numLandmarks` of the three
  functions included, and export `CoordinatesComplex` (#237).
