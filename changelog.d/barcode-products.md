### Added

- The algebra of barcodes grows products, Hom and duals: `Barcode.tensor` (the pointwise tensor product), `convolve`
  (the tensor product over the filtration parameter), `tor`, `kunneth` (the barcode of the homology of a product of two
  filtered complexes from the barcodes of the factors, for a cell entering at the larger or at the sum of its factors'
  values), `hom` (the internal Hom, right adjoint of `convolve`), `homDimension` and `dual`.
- `ProductComplex(x, y, combine)`: the product of two filtered complexes as a stream of `ProductCell`s, for any engine; it
  is what the Künneth formulas are tested against.
- `productBars`, `homBars`, `homDimension` and `dualBars` in the MATLAB/Java, JavaScript and Python interfaces, and
  `--barcode-op` with `--barcode-with` on the command line.
