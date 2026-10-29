### Changed

- The Dowker and Cech complexes build their layers faster, with the same cells, values and order. A Dowker candidate is
  valued from the largest entry of each witness over its parent's rows, computed once per parent; building the layers of
  a 300 by 100 relation (4.5M triangles) takes 3.4 s instead of 8.1 s, a 200 by 400 relation 2.2 s instead of 8.6 s, a
  60 by 200 relation to dimension 3 0.5 s instead of 2.1 s. The Cech value of a simplex is computed from the vertices in
  an array and the values of its facets are kept in a table keyed by vertex arrays instead of a `Simplex`-keyed map;
  building the layers of 80 points in R^3 to dimension 3 takes 3.8 s instead of 15.2 s (median of three trials, one JVM
  each, a 4-core sandbox; the reduction is not included) (#206).

### Added

- `CechFiltration.Values`: the Cech value as a function of `Simplex` values and of the vertices in an array, on one
  cache. `RipserCofaceSimplexStream.FlatFiltration.perRun`: a filtration valuing the candidates of a run, one parent
  after another, can keep what it computed for the parent (#206).
