### Added

- `HarmonicChainBarcode` and `HarmonicRepresentatives` take `orthogonalization`: `GramSchmidt` (the default) or
  `LaplacianSolve`, which keeps only the boundaries of a degree and solves for the chain a positive cell creates
  instead of keeping an orthogonal basis of all the cycles. Over the rationals the two give the same chains; the
  solve uses a fraction of the memory on a larger complex. Over the reals the two can pair a few bars differently
  when many values nearly tie. The MATLAB facade, the CLI (`--orthogonalization gram-schmidt|laplacian-solve`),
  JavaScript and Python take the option (#305).
