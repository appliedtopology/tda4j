package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.ScalaCheck

class HomologySpec extends mutable.Specification with ScalaCheck {
  given shc : SimplicialHomologyContext[Int,Double]()
  import shc.{given,*}
  
  "Homology of a triangle" >> {
    val streamBuilder = ExplicitStreamBuilder[Int,Double]()
    streamBuilder.addAll(List(1,2,3).map{i => (0.0,s(i))})
    streamBuilder.addAll(List((1.0,s(1,2)),(2.0,s(1,3)),(3.0,s(2,3)),(4.0,s(1,2,3))))
    val stream = streamBuilder.result()
    val homology = persistentHomology(stream)
    homology.diagramAt(5.0) must containTheSameElementsAs(List(
      (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
      (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
      (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
      (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3.
    ))
  }
}
