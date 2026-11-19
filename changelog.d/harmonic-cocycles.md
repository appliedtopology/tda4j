### Added

- `HarmonicRepresentatives(..., representatives = Representatives.Cocycles)` gives each bar of the ordinary barcode its
  harmonic cocycle, born at the last value of the bar, instead of its harmonic cycle. The MATLAB facade, the CLI
  (`--harmonic representatives --representative-type cocycles`), JavaScript and Python take `representativeType`
  (`cycles` or `cocycles`) for the representatives; the harmonic chain barcode stays cycles only (#307).

### Changed

- `HarmonicRepresentatives.ofDiagram` refuses a diagram with a bar of length zero with a message that says why: such a
  bar is not a class of the persistence module and has no harmonic representative (#307).
