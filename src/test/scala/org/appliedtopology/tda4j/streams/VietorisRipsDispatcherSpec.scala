package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.homology.SimplicialHomologyEngine
import org.specs2.mutable.Specification

/** The `VietorisRips` dispatcher: `maxDimension` is a homological degree and every implementation agrees. */
class VietorisRipsDispatcherSpec extends Specification:
  private val circle = EuclideanMetricSpace(
    (0 until 12).map(i => Seq(math.cos(2 * math.Pi * i / 12), math.sin(2 * math.Pi * i / 12)))
  )

  private def barsOf(impl: VietorisRips.Implementation, maxDim: Int): List[(Int, Double, Double)] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val engine = SimplicialHomologyEngine[Int, Double, Double]()
    engine
      .persistentHomology(VietorisRips(circle, maxDim, implementation = impl))
      .diagramAt(Double.PositiveInfinity)
      .filter((dim, b, d) => dim <= maxDim && d > b + 1e-9)
      .map((dim, b, d) => (dim, BigDecimal(b).setScale(9, BigDecimal.RoundingMode.HALF_UP).toDouble, if d.isInfinity then d else BigDecimal(d).setScale(9, BigDecimal.RoundingMode.HALF_UP).toDouble))
      .sortBy(t => (t._1, t._2, t._3))

  "VietorisRips" should {
    "give the circle exactly one loop with maxDimension = 1 (a homological degree)" in {
      val bars = barsOf(VietorisRips.Implementation.Enumerating, 1)
      bars.count((dim, _, _) => dim == 1) must beEqualTo(1)
    }
    "agree across implementations" in {
      val reference = barsOf(VietorisRips.Implementation.Enumerating, 1)
      VietorisRips.Implementation.values.toList.map(i => i -> barsOf(i, 1)).filter(_._2 != reference) must beEmpty
    }
  }
