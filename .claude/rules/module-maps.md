---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/barcode/MapAlgebra.scala"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/PersistenceMap.scala"
  - "src/main/scala/org/appliedtopology/tda4j/barcode/Barcode.scala"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/MapAlgebraSpec.scala"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/PersistenceMapSpec.scala"
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
- **A sign mutation test.** `simplicial` sorts the image vertices and signs by the permutation; the spec numbers the target
  by a random permutation so the signs differ from simplex to simplex (an overall sign changes no rank and would pass).
