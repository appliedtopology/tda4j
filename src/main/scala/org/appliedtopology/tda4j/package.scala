package org.appliedtopology

import org.appliedtopology.tda4j.barcode.{
  BarcodeEndpoint,
  ClosedEndpoint,
  NegativeInfinity,
  OpenEndpoint,
  PersistenceBar,
  PositiveInfinity
}

import math.Ordering.Implicits.sortedSetOrdering

/** Package for the Scala library TDA4j
  */
package object tda4j {
  class TDAContext[VertexT: Ordering, CoefficientT: Fractional]
      extends ChainOps[AbstractSimplex[VertexT], CoefficientT],
        SimplexContext[VertexT] {
<<<<<<< HEAD

    given Conversion[Simplex, MapChain[Simplex, CoefficientT]] = MapChain.apply
    
=======
    given Conversion[Simplex, ChainElement[Simplex, CoefficientT]] =
      ChainElement.apply
>>>>>>> 38ae15d5743564e6a676dcf2456f6a7f88f6a460
  }
}
