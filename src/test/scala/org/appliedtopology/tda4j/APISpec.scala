package org.appliedtopology.tda4j

import org.specs2.mutable

class APISpec extends mutable.Specification {
  """Test case class for developing the non-Scala facing API functionality
    |and the non-expert API functionality""".stripMargin

  given ctx: TDAContext[Int, Double]()
  import ctx.{given, *}

  "we should be able to create and compute with chains" >> {
    1.0 ⊠ s(1, 2) - s(2, 3) should beEqualTo(
      Chain(Simplex(1, 2) -> 1.0, Simplex(2, 3) -> -1.0)
    )
  }

  "A full persistent homology computation" >> {
    val as = (1 to 50).map(_ => scala.util.Random.nextDouble * 2.0 * math.Pi)
    val xys = as.map(a => Seq(math.cos(a), math.sin(a)))

    val homology = persistentHomology(
      VietorisRips[Int](
        EuclideanMetricSpace(xys),
        1.5,
        4,
        ZomorodianIncremental[Int]()
      )
    )

    homology.advanceTo(0.15)
    homology.diagramAt(0.15) should not(beEmpty)
  }
}
