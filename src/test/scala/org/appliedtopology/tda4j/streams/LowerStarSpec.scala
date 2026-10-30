package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class LowerStarSpec extends Specification:

  private def bars(stream: ExplicitStream[Int, Double], maxDimension: Int) =
    Persistence(stream, maxDimension = maxDimension).triples.sorted

  "LowerStar" should {
    "give each simplex the largest value of its vertices" >> {
      val stream = LowerStar(Seq(Simplex(0, 1, 2)), Vector(5.0, 1.0, 3.0))
      (stream.filtrationValue(Simplex(0)) must beEqualTo(5.0)) and (stream.filtrationValue(
        Simplex(1, 2)
      ) must beEqualTo(3.0)) and
        (stream.filtrationValue(Simplex(0, 1, 2)) must beEqualTo(5.0)) and
        (stream.filtrationValue(Simplex(1)) must beEqualTo(1.0))
    }

    "find the sublevel-set components of a function on a path" >> {
      // heights 0 3 1 4 2 on the path 0-1-2-3-4: minima at 0, 2 and 4 (heights 0, 1, 2); the minimum at 2 joins the
      // one at 0 over the peak of height 3, the one at 4 joins over the peak of height 4.
      val heights = Vector(0.0, 3.0, 1.0, 4.0, 2.0)
      val stream = LowerStar.ofGraph(5, Seq((0, 1), (1, 2), (2, 3), (3, 4)), heights)
      bars(stream, 0) must beEqualTo(List((0, 0.0, Double.PositiveInfinity), (0, 1.0, 3.0), (0, 2.0, 4.0)))
    }

    "reverse the order for superlevel sets, in negated units" >> {
      val heights = Vector(0.0, 3.0, 1.0, 4.0, 2.0)
      val stream = LowerStar.ofGraph(5, Seq((0, 1), (1, 2), (2, 3), (3, 4)), heights, superlevel = true)
      bars(stream, 0) must beEqualTo(List((0, -4.0, Double.PositiveInfinity), (0, -3.0, -1.0)))
    }

    "close a cycle of a graph when its last vertex enters" >> {
      // the square 0-1-2-3 with heights 0 1 2 3: both edges of vertex 3 enter at 3, and so does the loop
      val stream = LowerStar.ofGraph(4, Seq((0, 1), (1, 2), (2, 3), (3, 0)), Vector(0.0, 1.0, 2.0, 3.0))
      bars(stream, 1) must beEqualTo(List((0, 0.0, Double.PositiveInfinity), (1, 3.0, Double.PositiveInfinity)))
    }

    "equal the persistence of a mesh with a height function, on a filled triangle: nothing but the component" >> {
      val stream = LowerStar(Seq(Simplex(0, 1, 2)), Vector(0.0, 1.0, 2.0))
      bars(stream, 2) must beEqualTo(List((0, 0.0, Double.PositiveInfinity)))
    }

    "keep a vertex no edge touches" >> {
      val stream = LowerStar.ofGraph(3, Seq((0, 1)), Vector(0.0, 1.0, 5.0))
      bars(stream, 0).filter(_._3.isInfinite).map(_._2) must beEqualTo(List(0.0, 5.0))
    }

    "reject a NaN value and an edge outside the graph" >> {
      (LowerStar(Seq(Simplex(0, 1)), Vector(0.0, Double.NaN)) must throwAn[IllegalArgumentException]) and
        (LowerStar.ofGraph(2, Seq((0, 2)), Vector(0.0, 1.0)) must throwAn[IllegalArgumentException])
    }
  }
