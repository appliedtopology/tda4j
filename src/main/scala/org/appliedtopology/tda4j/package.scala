package org.appliedtopology

import math.Ordering.Implicits.sortedSetOrdering

/** Package for the Scala library TDA4j
  */
package object tda4j {
  class TDAContext[VertexT: Ordering, CoefficientT: Fractional]
      extends MapChainOps[AbstractSimplex[VertexT], CoefficientT],
        SimplexContext[VertexT] {
    given Conversion[Simplex, MapChain[Simplex, CoefficientT]] = MapChain.apply
  }
}
