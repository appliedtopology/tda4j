package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable

import scala.math.Numeric.DoubleIsFractional

class APISpec extends mutable.Specification:
  """Test case class for developing the non-Scala facing API functionality
    |and the non-expert API functionality""".stripMargin

  given Double is Field = Field.DoubleApproximated(1e-25)
  given ctx: TDAContext[Int, Double, Double]()
  import ctx.{*, given}

  "we should be able to create and compute with chains" >> {
    1.0 ⊠ ∆(1, 2) - ∆(2, 3) must beEqualTo(
      Chain(Simplex(1, 2) -> 1.0, Simplex(2, 3) -> -1.0)
    )
  }

  "A full Vietoris-Rips persistence computation" >> {
    // #full-vr-computation
    given Double is Field = Field.DoubleApproximated(1e-9)
    given ctx: TDAContext[Int, Double, Double]()
    import ctx.{*, given}

    val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8))
    val metricSpace = EuclideanMetricSpace(points)
    val stream = EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(2.0))

    val state = ctx.persistentHomology(stream)
    state.barcodeAt(Double.PositiveInfinity).foreach(println)
    // #full-vr-computation

    state.barcodeAt(Double.PositiveInfinity) must not(beEmpty)
  }

  "A full persistent homology computation" >> {
    val as = (1 to 50).map(_ => scala.util.Random.nextDouble() * 2.0 * math.Pi)
    val xys = as.toSeq.map(a => Seq(math.cos(a), math.sin(a)))

    val metricSpace = EuclideanMetricSpace(xys)
    val homology = persistentHomology(LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), 4))

    homology.advanceTo(0.15)
    homology.diagramAt(0.15) should not(beEmpty)

  }
