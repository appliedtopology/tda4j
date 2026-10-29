### Changed

- `PackedRipserCohomologyEngine` (the engine behind `Persistence` for Vietoris-Rips on points) decodes each simplex once
  when it tests for an apparent or emergent pair, and finds the youngest facet of a cofacet that has its diameter from the
  cofacet's longest edges instead of recomputing every facet's diameter. The bars and cocycles are unchanged (same digests
  over F_2 and F_17). Runs take 1.2 times less on 1,000 points in R^3 (degrees 0..2, threshold 0.4), 1.3 to 1.5 times less on
  1,000 points on S^2, 1.5 to 1.7 times less on 40 to 60 points in 10 and 16 dimensions (4 cores, median of 5 to 7
  trials, two JVMs per variant) (#194).
- `SimplexIndexing.cofacetCursor`/`facetCursor` (and the two-word `WideSimplexIndexing` ones) accept the already decoded
  vertices of the simplex, and `FacetCursor.position` says where in the simplex the removed vertex was.
