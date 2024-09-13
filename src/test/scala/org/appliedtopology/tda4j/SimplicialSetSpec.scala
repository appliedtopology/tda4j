package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SimplicialSetExamples.sphere
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
  
  "homology of torus" >> {
    val T2 = Product(sphere(1), sphere(1))
    import T2.given
    given chc: CellularHomologyContext[SimplicialSetElement, Double, Double]()
    import chc.{*, given}
    val T2stream = normalizedCellStream[Double](T2, Some({ (sse:SimplicialSetElement) => sse.dim.toDouble }))
    
    val ph = persistentHomology(T2stream)
    val dgm = ph.diagramAt(5.0)

    dgm must containTheSameElementsAs(List(
      (0, 0.0, Double.PositiveInfinity), // essential 0-class
      (1, 1.0, 2.0),                     // transient 1-class, cancels with the first 2-cell
      (1, 1.0, Double.PositiveInfinity), // essential 1-class
      (1, 1.0, Double.PositiveInfinity), // essential 1-class
      (2, 2.0, Double.PositiveInfinity), // essential 2-class
    ))
  }
}
