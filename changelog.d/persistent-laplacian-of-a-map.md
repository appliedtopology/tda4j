### Added

- `PersistentLaplacian.ofMap(source, target, dimension)(vertexMap)` is the persistent Laplacian of a simplicial map
  (Gülen, Mémoli, Wan and Wang): a matrix on the simplices of the target that the map hits, whose kernel has the
  dimension of the rank of `H_k(source) -> H_k(target)`. A second form takes two streams and a filtration value for each.
  Where the map sends several simplices onto one, the simplex of the target gets their number as its `weights` entry; for
  an inclusion it is the persistent Laplacian of the stream, entry for entry. `PersistentLaplacian.Spectrum` is the
  class `Result` and the new `MapResult` share (#307).
- The same from the other interfaces: MATLAB/Java `persistentLaplacianOfMapFromPoints` and `...FromDistanceMatrices`,
  JavaScript `persistentLaplacianOfMap` and `...FromDistances`, Python `persistent_laplacian_of_map` and
  `..._from_distances`, and the CLI flags `--laplacian-target` and `--laplacian-vertex-map`. Their results have
  `weights`, which is 1 for every cell of the ordinary persistent Laplacian (#307).

### Changed

- The rank of the persistent Laplacian's factor is cut at a singular value of at least `1e-9`, as well as relative to the
  largest one: a Laplacian that is zero up to rounding no longer counts its noise as rank (#307).
