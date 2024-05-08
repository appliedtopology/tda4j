package org.appliedtopology.tda4j

import org.specs2.ScalaCheck
import org.specs2.mutable

class CubicalSpec extends mutable.Specification with ScalaCheck {
  "Testing our Cubical Set implementation" >> {
    prop { (lst: List[(Int, Boolean)]) =>
      val cube = ElementaryCube(lst.map((j, b) => if (b) FullInterval(j) else DegenerateInterval(j)))
      cube.boundary[Double] must beAnInstanceOf[Chain[ElementaryCube, Double]]
    }

    val simpleCube = ElementaryCube(List(FullInterval(0), FullInterval(0)))
    simpleCube.boundary[Double] must be_==(
      Chain[ElementaryCube, Double](
        ElementaryCube(List(DegenerateInterval(1), FullInterval(0))) -> -1.0,
        ElementaryCube(List(DegenerateInterval(0), FullInterval(0))) -> 1.0,
        ElementaryCube(List(FullInterval(0), DegenerateInterval(1))) -> 1.0,
        ElementaryCube(List(FullInterval(0), DegenerateInterval(0))) -> -1.0
      )
    )
  }
}
