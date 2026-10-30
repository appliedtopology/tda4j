### Breaking

- `Barcode.imageMatrix` and `Barcode.cokernelMatrix` are gone: the reduced matrices were an implementation detail of an
  algorithm that was wrong (see Fixed). Call `Barcode.image`, `kernel` and `cokernel`, or `MapAlgebra` for another
  field.
- `Barcode.isMap` is strict where the target dies as the source is born: a source bar `[b, d)` goes only to target bars
  `[b', d')` with `b' <= b < d' <= d`. `image`, `kernel` and `cokernel` now throw `IllegalArgumentException` on a matrix
  `isMap` rejects, or of the wrong shape (a row for each target bar, a column for each source bar).
- `PersistenceDiagram.apply` takes one more parameter, `representativesDieWithTheirBars`, last and defaulted.

### Fixed

- `Barcode.image`, `kernel` and `cokernel` were wrong for many maps of barcodes. On 150 random legal maps the image was
  wrong 28 times, the kernel 10 and the cokernel 3, and the kernel and cokernel crashed on 61 each. They are rebuilt on
  `MapAlgebra`, checked against the ranks of the structure maps of the modules.

### Added

- `MapAlgebra`: image, kernel and cokernel of a map of barcodes over any `Field`, so that a map which is an isomorphism
  over `Z/3` and zero over `Z/2` comes out right.
- `PersistenceMap`: the map on persistent homology induced by a map of complexes, from the two diagrams and the map of
  cells (`PersistenceMap.inclusion`, `PersistenceMap.simplicial` with the sign of the vertex permutation, or any map
  `cell => Seq[(cell, Int)]`), with `image`, `kernel`, `cokernel` and the matrix `entries` per degree. The diagrams need
  cycles that die with their bars (the default engine, `Cohomology`, `Ripser`; `Naive` and `Chunks` are refused), the
  same `characteristic` and `maxFiltrationValue`, and zero-length bars in the target.
- `PersistenceDiagram.representativesDieWithTheirBars`: whether a diagram's finite cycles are boundaries from their
  bar's death on.
