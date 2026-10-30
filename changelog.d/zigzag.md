### Added

- `ZigzagPersistence`: the persistent homology of a sequence of complexes in which each is included in the next or the
  next in it, as intervals over the positions of the sequence, with a representative cycle at each bar's birth. The input
  is a list of `ZigzagStep`s (`Add`, `Remove`) from a starting complex, or a list of complexes (`ofComplexes`, with
  `Link.Union` or `Link.Intersection` between complexes that are not nested, for a changing point cloud).
- `zigzagFromSteps` and `zigzagFromComplexes` in the MATLAB/Java interface and in JavaScript, `tda4j.zigzag_from_steps` and
  `tda4j.zigzag_from_complexes` in Python.
