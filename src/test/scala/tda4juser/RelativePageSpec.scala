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

  "the snippet of the line from edge to edge" should {
    "be no loop alone and a cycle of the pair relative to the boundary" in {
      val pixels = Array.tabulate(7, 7)((row, column) => if row == 3 then 0.0 else 1.0)
      val grid = CubicalImage.fromGrayscale2D(pixels)
      (Persistence(Image(pixels), maxDimension = 1).dim(1).size must beEqualTo(0))
        .and(Persistence(Relative.ofBoundary(grid), maxDimension = 2).dim(1).size must beEqualTo(1))
    }
  }
end RelativePageSpec
