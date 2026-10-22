package org.appliedtopology.tda4j

import org.specs2.mutable.Specification
import scala.language.experimental.modularity

import SpecThrows.throwWith

/** Ripser's `sparse` format: `i j d` triples, a pair not listed has no edge. */
class RipserSparseSpec extends Specification:

  private def rows(m: Array[Array[Double]]): Seq[Seq[Double]] = m.toSeq.map(_.toSeq)
  private val inf = Double.PositiveInfinity

  "Ripser.parseSparseDistanceMatrix" should {
    "read i j d, with the number of points the largest index plus one, and +Infinity for a pair not listed" >> {
      val m = Ripser.parseSparseDistanceMatrix("0 1 0.5\n2,1,1.5\n\n3 3 9\n")
      // 3 3 9 is skipped (i == j) and does not make a fourth point
      rows(m) must beEqualTo(Seq(Seq(0.0, 0.5, inf), Seq(0.5, 0.0, 1.5), Seq(inf, 1.5, 0.0)))
    }
    "treat the first number as the row: (0, 2) is not (2, 0) of a different pair" >> {
      val m = Ripser.parseSparseDistanceMatrix("0 2 7\n")
      (m.length must beEqualTo(3)) and (m(0)(2) must beEqualTo(7.0)) and (m(2)(0) must beEqualTo(7.0)) and
        (m(0)(1) must beEqualTo(inf))
    }
    "refuse a pair listed twice, a malformed line and a negative vertex" >> {
      (Ripser.parseSparseDistanceMatrix("0 1 1\n1 0 1\n", "g.txt") must throwWith[IllegalArgumentException](
        "g.txt, line 2: the pair .1, 0. is listed twice"
      )) and
        (Ripser.parseSparseDistanceMatrix("0 1\n") must throwWith[IllegalArgumentException](
          "expected 'i j distance'"
        )) and
        (Ripser.parseSparseDistanceMatrix("-1 1 2\n") must throwWith[IllegalArgumentException]("not a vertex number"))
    }
    "format the finite pairs above the diagonal and read them back" >> {
      val m = Ripser.parseSparseDistanceMatrix("0 1 0.5\n1 2 1.5\n")
      (Ripser.formatSparseDistanceMatrix(m) must beEqualTo(s"0 1 ${0.5}\n1 2 ${1.5}\n")) and
        (rows(Ripser.parseSparseDistanceMatrix(Ripser.formatSparseDistanceMatrix(m))) must beEqualTo(rows(m))) and
        (Ripser.maxFiniteDistance(m) must beEqualTo(1.5))
    }
  }

  "A sparse matrix as a metric space" should {
    import scala.language.experimental.modularity
    "be the flag complex of the listed edges: a 4-cycle without diagonals has an essential loop" >> {
      val text = "0 1 1\n1 2 1\n2 3 1\n3 0 1\n"
      val m = Ripser.parseSparseDistanceMatrix(text)
      val space = ExplicitMetricSpace(m.map(_.toIndexedSeq).toIndexedSeq)
      val diagram =
        Persistence(space, maxDimension = 1, maxFiltrationValue = Ripser.maxFiniteDistance(m), characteristic = 2)
      val loops = diagram.dim(1).bars
      (loops.size must beEqualTo(1)) and (loops.head.lower must beEqualTo(ClosedEndpoint(1.0))) and
        (loops.head.upper must beEqualTo(PositiveInfinity[Double]()))
    }
    "default to the largest listed distance when no threshold is given" >> {
      val m = Ripser.parseSparseDistanceMatrix("0 1 1\n1 2 1\n2 3 1\n3 0 1\n")
      val space = ExplicitMetricSpace(m.map(_.toIndexedSeq).toIndexedSeq)
      val diagram = Persistence(space, maxDimension = 1, characteristic = 2)
      (space.minimumEnclosingRadius must beEqualTo(1.0)) and (diagram.dim(1).bars.map(_.upper) must beEqualTo(
        Seq(PositiveInfinity[Double]())
      ))
    }
    "close the loop once the diagonals are listed" >> {
      val text = "0 1 1\n1 2 1\n2 3 1\n3 0 1\n0 2 2\n1 3 2\n"
      val m = Ripser.parseSparseDistanceMatrix(text)
      val space = ExplicitMetricSpace(m.map(_.toIndexedSeq).toIndexedSeq)
      val diagram =
        Persistence(space, maxDimension = 1, maxFiltrationValue = Ripser.maxFiniteDistance(m), characteristic = 2)
      val loops = diagram.dim(1).bars
      (loops.size must beEqualTo(1)) and (loops.head.upper must beEqualTo(OpenEndpoint(2.0)))
    }
  }
