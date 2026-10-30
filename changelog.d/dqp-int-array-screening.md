### Changed

- The DQP alpha backend (`AlphaShapes` with `AlphaBackend.DQP`, and `Auto` when a radius is given) is several times faster
  in high ambient dimension with a high degree cap: 60 uniform points in R^7 up to degree 6 take 5.5 s instead of 32.9 s,
  in R^8 up to degree 7 23.5 s instead of 105 s, and the peak heap in R^8 halves. The complex, its order and its barcodes
  are unchanged (#135).

### Breaking

- `AlphaComplexDQPBuilder.buildCandidates`, `solveAtVertex` and `clampMonotone` work on int-array rows
  (`AlphaComplexDQPBuilder.CandidateRows`) instead of `Simplex` sets, `Found` carries the vertex array and its witness, and
  `allFacetsPresent` is gone. Callers of these builder internals pass the face tables of
  `DelaunayAlphaShapes.FaceTable`; `AlphaComplexDQP.euclidean` and `AlphaShapes` are unchanged (#135).
