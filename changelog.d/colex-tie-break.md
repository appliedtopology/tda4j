### Fixed

- `Engine.Chunks`, `Engine.Naive` and the other users of a Vietoris-Rips stream's `filtrationOrdering` no longer throw
  `binomial(...) overflows Long` on a complex whose vertex sets are indexed past 2^63 (1,000 points with a cell of 8
  vertices is enough): ties are broken by `SimplexIndexing.colexOrdering`, the order of the combinatorial index without
  its width limit (#198).
