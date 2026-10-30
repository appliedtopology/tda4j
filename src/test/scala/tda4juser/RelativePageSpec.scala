package tda4juser

import org.specs2.mutable.Specification

/** The snippet of the user guide page "Pairs of spaces", from a user's side of the package boundary. */
class RelativePageSpec extends Specification:
  import org.appliedtopology.tda4j.*

  "the snippet of the circle of twelve points" should {
    "have one loop, three loops relative to three points, and five points off an arc" in {
      val points = Array.tabulate(12)(i => Array(math.cos(2 * math.Pi * i / 12), math.sin(2 * math.Pi * i / 12)))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 2.0)
      (Persistence(rips).dim(1).bars.size must beEqualTo(1))
        .and(
          Persistence(Relative.ofVertices(rips, Set(0, 4, 8))).dim(1).bars.size must beEqualTo(3)
        )
        .and(Persistence(Relative.ofVertices(rips, Set(0, 1, 2, 3, 4, 5, 6))).dim(0).size must beEqualTo(5))
    }
  }
end RelativePageSpec
