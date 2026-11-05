### Added

- `HarmonicChainBarcode(stream, maxDimension)`: the harmonic chain barcode of a filtered complex (Hou, Parsa and Wang),
  each bar a cycle orthogonal to every boundary that stays so for the whole bar, over the real numbers or the exact
  rationals (`Coefficients.Rationals`). `HarmonicChainBarcode.harmonicChains(stream, dimension, value, coefficients)` is
  a basis of the harmonic chains at one value.
- `HarmonicRepresentatives(stream, maxDimension)`: the ordinary barcode with the Basu-Cox harmonic cycle of each bar's
  birth as its representative, independent of how cells of equal value are ordered.
- The same through `harmonicChainBarcodeFromPoints` and `harmonicRepresentativesFromPoints` in the MATLAB/Java interface
  (with `...FromDistanceMatrix` twins), `harmonicChainBarcode` and `harmonicRepresentatives` in JavaScript,
  `tda4j.harmonic_chain_barcode` and `tda4j.harmonic_representatives` in Python, and `--harmonic chains` and
  `--harmonic representatives` on the command line.

### Fixed

- `PersistenceResult.cycleVertices(i)` and `cycleCoefficients(i)` read a bar's chain in two separate steps and could
  pair a coefficient with another cell's vertices when the chain's storage reordered between the two reads. They read the
  chain in cell order now. (Seen with the harmonic chains; the chains of the other engines were not seen to be affected.)
