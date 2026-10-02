package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/networks-and-relations.md` and asserts every number the page quotes. */
class NetworksSpec extends Specification:
  sequential

  private def page(): (List[(Int, Double, Double)], List[(Int, Double, Double)], List[(Int, Double, Double)], List[(Int, Double, Double)]) =
    val lab = TDAlab(2)
    import lab.{*, given}

    val never = Double.PositiveInfinity
    val relation: Array[Array[Double]] = Array.tabulate(6, 7) { (person, club) =>
      if club == 6 then 5.0
      else if club == person then 1.0
      else if club == (person + 1) % 6 then 2.0
      else never
    }

    val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()
    def barcode(dual: Boolean) =
      engine.persistentHomology(streams.Dowker(relation, maxDimension = 1, dual = dual)).diagramAt(Double.PositiveInfinity).filter(_._1 <= 1)
    def withoutZeroLength(bars: List[(Int, Double, Double)]) =
      bars.filter((_, birth, death) => death.isInfinite || death > birth).sortBy(bar => (bar._1, bar._2, bar._3))

    val people = barcode(dual = false)
    val clubs = barcode(dual = true)
    (people, clubs, withoutZeroLength(people), withoutZeroLength(clubs))

  "networks-and-relations.md" should {
    lazy val r = page()
    "have 16 bars on the people side and 13 on the clubs side, until zero-length bars are dropped" in {
      println(r)
      (r._1.size must beEqualTo(16)).and(r._2.size must beEqualTo(13))
    }
    "agree exactly afterwards: five merges, one component, one loop born at 2 and killed at 5" in {
      val expected = List(
        (0, 1.0, 2.0), (0, 1.0, 2.0), (0, 1.0, 2.0), (0, 1.0, 2.0), (0, 1.0, 2.0), (0, 1.0, Double.PositiveInfinity), (1, 2.0, 5.0)
      )
      (r._3 must beEqualTo(expected)).and(r._4 must beEqualTo(expected))
    }
  }
