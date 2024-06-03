package org.appliedtopology.tda4j

import org.specs2.mutable

class SimplicialSetSpec extends mutable.Specification {

  "Homology of 4-sphere" >> {
    val sphere4 = sphere(4)
    import sphere4.given
    given chc: CellularHomologyContext[SimplicialSetElement, Double, Double]()
    import chc.{*, given}

    val sphere4stream = new CellStream[SimplicialSetElement, Double] {
      override def filtrationValue = _ => 0.0

      override def iterator = sphere4.generators.iterator

      override def filtrationOrdering = sseOrdering

      override def smallest = Double.NegativeInfinity

      override def largest = Double.PositiveInfinity
    }

    val ph = persistentHomology(sphere4stream)
    val dgm = ph.diagramAt(1.0)

    dgm must containTheSameElementsAs(List(
      (0, 0.0, Double.PositiveInfinity),
      (4, 0.0, Double.PositiveInfinity)
    ))
  }
}
