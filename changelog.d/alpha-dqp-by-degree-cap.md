### Changed

- `Persistence(points, complex = AlphaShapes)` and the MATLAB, CLI, Python and JavaScript `complex=alpha` build the alpha
  complex with the DQP backend, instead of a Delaunay triangulation, when the points have at least 8 coordinates and
  `maxDimension` is at most 2 below that (`AlphaShapes.prefersDQPAtEnclosingRadius`, `AlphaShapes.dqpMinAmbientDimension`).
  DQP builds only the simplices up to dimension `maxDimension + 1`, a triangulation every dimension. For 40 uniform points
  and `maxDimension = 4` it is 3 times faster in 8 dimensions, 8 times in 10 and 28 times in 12 (with a fifth of the
  memory), and in 15 dimensions the only backend that finishes (66 s; the triangulations were stopped after an hour).
  The top degree `d - 1` stays on the triangulation, where DQP needs up to 2.5 times the memory. The barcode is
  the same in general position; `AlphaBackend` given explicitly, and the whole complex, are unchanged.
