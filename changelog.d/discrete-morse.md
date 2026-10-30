### Added

- `DiscreteMorse(stream, strategy)`: a filtration-respecting acyclic matching of a filtered complex (pairs of a cell and a
  facet that enter together, found by reductions or by coreductions), as a `MorseComplex` with its critical cells, the
  Morse boundary between them, `lift` of a cycle back to the original complex, and `stream`, the Morse complex as a stream
  of `MorseCell`s for `Persistence`, which computes the barcode of the original complex on fewer cells.
- `morseReductionFromPoints` and `morseReductionFromDistanceMatrix` in the MATLAB/Java interface, `morseReduction` in
  JavaScript, `tda4j.morse_reduction` in Python and `--morse-reduction` on the command line: the numbers of cells and of
  critical cells in each dimension, and the bars computed on the critical cells.

### Changed

- The cohomology engine accepts a cell set with no cell in a dimension when the cells above that dimension have no
  boundary, as a Morse complex can; a missing dimension under cells that do have a boundary is still refused.
