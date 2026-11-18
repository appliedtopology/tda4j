### Added

- `edgeTies` chooses how edges of equal length are ordered in the two Ripser engines, and with it which of a simplex's
  longest edges it is filed under: `Persistence(points, engine = Persistence.Engine.Ripser, edgeTies =
  Persistence.EdgeTies.ReverseColexicographic)` (`lexicographic`, `reverse-lexicographic`, `colexicographic`,
  `reverse-colexicographic` or `shuffled`; MATLAB and Java `edgeTies`, the command line `--edge-ties`, JavaScript
  `edgeTies`). The bars are the same in every order. On inputs with many equal distances (lattices, integer metrics) the
  reverse orders leave fewer columns to reduce, and `ripser.cpp`'s `EDGE_LEXICOGRAPHIC` is `reverse-colexicographic`.
  Unset, `ParallelRipser` orders them lexicographically as before and `Ripser` has no edge order.
