### Added

- `PersistentLaplacian.Result.kernel`: an orthonormal basis of the kernel as chains on the cells at `from`, the persistent
  harmonic chains, one for each persistent Betti number. The facade result has `kernel()`, JavaScript `kernel`, Python
  `kernel` (an array of shape `(betti_number, len(cells))`), and the CLI writes `kernel-0`, `kernel-1`, ... lines (#307).
