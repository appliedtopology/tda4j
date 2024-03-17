package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.collection.immutable.Seq

class BarcodeSpec extends Specification {
  "0-persistence output" >> {
    given sc: SimplexContext[Int]()
    import sc.*
    val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(
      Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
    )
    val rs = RipserStream(ms, ms.minimumEnclosingRadius, 5)
    
    rs.zeroPersistence() must containTheSameElementsAs(List(
      PersistenceBar[Double,Chain[Simplex,Double]](0, Some(0.0), Some(1.0)), 
      PersistenceBar[Double,Chain[Simplex,Double]](0, Some(0.0), Some(2.0))
    ))
  }
}
