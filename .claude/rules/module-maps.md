---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/barcode/MapAlgebra.scala"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/PersistenceMap.scala"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/Barcode.scala"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/MapAlgebraSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/PersistenceMapSpec.scala"
  - "src/main/scala/org/appliedtopology/tda4j/streams/ProductComplex.scala"
  - "src/main/scala/org/appliedtopology/tda4j/cells/ProductCell.scala"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/BarcodeProductsSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/streams/ProductComplexSpec.scala"
  - "src/main/scala/org/appliedtopology/tda4j/homology/PersistentLaplacian.scala"
  - "src/test/scala/org/appliedtopology/tda4j/homology/PersistentLaplacianSpec.scala"
---

# Maps of persistence modules: `MapAlgebra`, `Barcode.image/kernel/cokernel`, `PersistenceMap`

(`WORKLOG-more-inputs.md`, "Maps of persistence modules")

- **Convention.** A bar is `[b, d)`; a map of interval modules `I[b, d) -> I[b', d')` is nonzero only if
  `b' <= b < d' <= d` (`MapAlgebra.isLegal`). `Barcode.isMap` is that test on rank-compressed endpoints (open/closed make
  no difference), strict at `b = d'`.
- **`MapAlgebra`** is the one implementation, generic over `Field`: image by the elder rule over the source generators in
  order of birth (components led by max death); cokernel by relations in time order, killing the youngest generator;
  kernel = cokernel of the transposed map between the reflected modules (`0.0 - x`, never `-x`: no negative zero).
  `Barcode.image/kernel/cokernel` rank-compress the endpoints and call it over `Field.DoubleApproximated(1e-9)`, and
  `require(isMap)`. The earlier matrix-reduction versions were wrong (28 of 150 random legal maps for the image, 10 for
  the kernel, 3 for the cokernel, crashes in the rest of the kernel and cokernel cases); `MapAlgebraSpec` is the oracle.
- **Oracle for any module map: ranks of the structure maps.** The number of bars with `b <= s` and `t < d` is the rank
  of `X_s -> X_t`; computed from the matrices by independent `Z/p` linear algebra (`ZpLinearAlgebra`) at every pair of
  critical values. A discriminating spec compares barcodes this way, never bar by bar.
- **`PersistenceMap` needs representatives that die with their bars** (`PersistenceDiagram.representativesDieWithTheirBars`):
  the cycles of `Cohomology`, `Ripser`, `ParallelRipser` (finite bar = `R_tau`, the reduced boundary of the death cell) are
  module generators; the cycles of `Naive`/`Chunks` (the `V` column of the birth cell) are a basis at each time but stay
  classes after the bar ends, and a matrix in them is wrong **without any error** (found by `PersistenceMapSpec` in degree
  0). Any new diagram source says `true` only if its finite cycles are boundaries from the death on. Cocycles are refused.
- **The reduction** (`degreeOf`): push the source cycle through the cell map; clear the target's birth cells in the support
  from the highest rank down (rank = depth in the "birth cells in my support" DAG, computed from the supports, so no
  engine order is needed); a bar alive at the source bar's birth `t` records the coefficient, a dead one is a boundary and
  records nothing; a birth cell born after `t`, a nonzero remainder or an illegal entry means the map is not filtration
  preserving, the target lacks zero-length bars, or it is not a chain map (all `IllegalArgumentException`s that say so).
- **Both diagrams need the same `characteristic` and `maxFiltrationValue`** (the default is the enclosing radius, different
  for a subsample). The target needs `includeZeroLength = true` (so `ParallelRipser` can only be a source). The coefficients
  of the target are cast to the source's: the same prime means the same `Int` underneath.
- Integer coefficients in a cell map convert by repeated doubling, with the magnitude taken in `Long` so `Int.MinValue` works (`PersistenceMapSpec`, `WORKLOG-code-review-followup.md`).
- **A sign mutation test.** `simplicial` sorts the image vertices and signs by the permutation; the spec numbers the target
  by a random permutation so the signs differ from simplex to simplex (an overall sign changes no rank and would pass).

## Products, Hom, dual (`Barcode.tensor/convolve/tor/kunneth/hom/dual`, `ProductComplex`; `WORKLOG-more-inputs.md`, "Products, Hom and duals")

- **Formulas** (bars `[a, b)`, `[c, d)`; only the numbers count). `tensor` (pointwise): `[max(a,c), min(b,d))`. `convolve`
  (Day, `M ⊗_{k[R≥0]} N`): `[a+c, min(b+c, a+d))` -- written with the two sums, never `a+c+min(b-a, d-c)`, so the doubles equal the
  product complex's cell values. `tor` (finite bars only): `[max(b+c, a+d), b+d)` in degree `p+q+1`. `hom` (internal, right
  adjoint of `convolve`, the underived one): `[max(c-a, d-b), d-a)` in degree `q-p`, none from a dying bar to an immortal one.
  `dual`: the reflected interval `(-d, -b]`, an involution (endpoint kinds are kept, not reset).
- **Künneth.** `Combine.Max` (cell enters at `max(f, g)`): `H_n(X×Y)_t = ⊕ H_p(X)_t ⊗ H_q(Y)_t` = `tensor`. `Combine.Sum`: `convolve`
  plus `tor` (the Tor term is real: two segments, `Sum`, give the loop `[1, 2)`). Right below the smaller degree limit of the factors.
- **Oracles.** `BarcodeProductsSpec`: grid modules built from matrices over `Z/101` (Kronecker ranks for `tensor`; the natural-transformation
  system and its post-composition for `hom`), the adjunction by counting legal pairs, infinite bars by hand. `ProductComplexSpec`:
  `∂∂ = 0` over `Z/3`, the torus, two segments, and Künneth through the engines on Rips complexes, over `Z/2, Z/3, Z/5`. The facade
  spec and `python/tests/test_algebra.py` use an oracle with no product complex at all: the VR complex of the product point cloud with
  the max of the two distances is homotopy equivalent to the product at every scale. 8 mutants (min for max, wrong Tor degree, ...)
  all fail the specs.
- **`ProductComplex`** materializes both streams (a cell for every pair), orders each dimension by `FiltrationOrdering.canonical` with
  the pair of positions as tie-break, and cuts at `min(homologyDegreeLimit) + 1`. Its bars carry `ProductCell` chains, which no facade
  turns into vertex arrays: the interfaces other than Scala get the barcode algebra on rows, not the product complex.
- Chosen: names `Barcode.kunneth` (not `product`), `ProductCell`/`ProductComplex` (a top-level `Product` would shadow `scala.Product`).

## Persistent Laplacian (`homology/PersistentLaplacian.scala`; `WORKLOG-more-inputs.md`, "Persistent Laplacian")

- `Delta_k^{s,t} = D (1 - R^+ R) D^T + A^T A` (D: boundary rows in `K_s`, R: rows entering after `s`, A: boundary of `K_s`'s k-cells);
  built as `X X^T` from `DenseMatrix.svd`, never by forming and inverting `R R^T`. Kernel dimension = `beta_k^{s,t}` = bars with `b <= s`,
  `t < d`; `from`/`to` compare with `<=`, so the cells are those with value `<= s` (a `<` here is the first thing the barcode oracle catches).
- The chains are those of `K_t` with boundary in `K_s`, NOT the cells of `K_s`: the projector is the whole content (`PersistentLaplacianSpec`,
  the three-vertex example). Real coefficients, dense matrices, orthonormal cell basis; the stream needs its cells one dimension up.

