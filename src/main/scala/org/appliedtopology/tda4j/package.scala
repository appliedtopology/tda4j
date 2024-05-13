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
  class TDAContext[VertexT: Ordering, CoefficientT: Fractional, FiltrationT : Ordering]
      extends SimplicialHomologyContext[VertexT, CoefficientT, FiltrationT]() {
    import scala.language.implicitConversions
    given Conversion[Simplex, Chain[Simplex, CoefficientT]] =
      Chain.apply
  }
}
