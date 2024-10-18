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
  class TDAContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]
      extends SimplicialHomologyContext[VertexT, CoefficientT, FiltrationT]() {
    val chainIsRingModule : Chain[Simplex[VertexT],CoefficientT] is RingModule { type R=CoefficientT } =
      summon[Chain[Simplex[VertexT],CoefficientT] is RingModule { type R=CoefficientT}]
    export chainIsRingModule.*
    import scala.language.implicitConversions
    given [T: Ordering]: Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
      Chain.apply
  }
}
