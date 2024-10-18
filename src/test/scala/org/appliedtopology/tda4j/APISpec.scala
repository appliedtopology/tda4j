package org.appliedtopology.tda4j

import org.specs2.mutable

import scala.math.Numeric.DoubleIsFractional

class APISpec extends mutable.Specification {
  """Test case class for developing the non-Scala facing API functionality
    |and the non-expert API functionality""".stripMargin

  given (Double is Field) = Field.DoubleApproximated(1e-25)
  given ctx: TDAContext[Int, Double, Double]()
  import ctx.{given, *}

  "we should be able to create and compute with chains" >> {
    1.0 ⊠ ∆(1, 2) - ∆(2, 3) must beEqualTo(
      Chain(Simplex(1, 2) -> 1.0, Simplex(2, 3) -> -1.0)
    )
  }

  "A full persistent homology computation" >> {
    val as = (1 to 50).map(_ => scala.util.Random.nextDouble * 2.0 * math.Pi)
    val xys = as.toSeq.map(a => Seq(math.cos(a), math.sin(a)))

    val homology = persistentHomology(
      VietorisRips[Int](
        EuclideanMetricSpace(xys),
        1.5,
        4,
        ZomorodianIncremental[Int]()
      )
    )

    val metricSpace = EuclideanMetricSpace(xys)
    val lazyHomology = persistentHomology(
      SimplexStream.from(
        LazyVietorisRips(
          metricSpace,
          1.5,
          4
        ),
        metricSpace
      )
    )

    homology.advanceTo(0.15)
    homology.diagramAt(0.15) should not(beEmpty)

    lazyHomology.advanceTo(0.15)
    lazyHomology.diagramAt(0.15) should not(beEmpty)

    lazyHomology.diagramAt(0.15) should containTheSameElementsAs(homology.diagramAt(0.15))
  }
}
