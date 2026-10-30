### Changed

- The DQP alpha backend keeps its simplices and values in the same flat face tables as the triangulation backends
  instead of two `Simplex`-keyed hash maps, and the alpha streams no longer keep a witness point per simplex. On 40
  uniform points in R^9 up to degree 8, with 4 cores, the build takes 7.4 s instead of 11.1 s and the live data after it
  is 390 MB instead of 540 MB. The complex, its order and its values are unchanged, to the last bit (#135).

### Added

- `AlphaDQPSettings.keepWitnesses` (default `true`): whether `AlphaComplexDQP` keeps, for each simplex, the point of its
  restricted Voronoi cell the value comes from (`AlphaComplexDQP.witness`). `AlphaShapes` turns it off for the streams
  it builds, which never read the witnesses; set it to `false` too when only the filtration is wanted (#135).

### Breaking

- The constructor of `AlphaComplexDQP` takes the builder's face tables and value arrays instead of the two
  `Simplex`-keyed hash maps, and `AlphaShapeDQP` takes the `AlphaDQPSettings` as a second parameter (default
  unchanged). `AlphaComplexDQP.euclidean` and `AlphaShapes` are unchanged (#135).
