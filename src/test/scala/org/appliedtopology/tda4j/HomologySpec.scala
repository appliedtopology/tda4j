package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.ScalaCheck

class HomologySpec extends mutable.Specification with ScalaCheck {
  given (Double is Field) = Field.DoubleApproximated(1e-25)

  "Homology of a triangle" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3))))
    val stream = streamBuilder.result()
    val homology = persistentHomology(stream)
    homology.diagramAt(5.0) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
        (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
        (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
        (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3.
      )
    )
  }
}
