package org.appliedtopology.tda4j

import org.specs2.mutable

/** The witness map Phi of a DQP complex (the point of a simplex's power face where its value is attained) is stored in
  * rows beside the face tables and read back by simplex; `keepWitnesses = false` drops it and changes nothing else.
  */
class AlphaComplexDQPWitnessSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-9)

  // A right triangle: every edge is Gabriel, the hypotenuse's midpoint is the circumcentre.
  private val triangle = Array(Array(0.0, 0.0), Array(4.0, 0.0), Array(0.0, 3.0))

  private def cell(vertices: Int*): Simplex[Int] = Simplex(vertices*)

  "the witness of a vertex is its point, of an edge its midpoint, of the triangle its circumcentre" >> {
    val complex = AlphaComplexDQP.euclidean(triangle, Double.PositiveInfinity, 2)
    val expected = Seq(
      cell(0) -> Seq(0.0, 0.0),
      cell(1) -> Seq(4.0, 0.0),
      cell(2) -> Seq(0.0, 3.0),
      cell(0, 1) -> Seq(2.0, 0.0),
      cell(0, 2) -> Seq(0.0, 1.5),
      cell(1, 2) -> Seq(2.0, 1.5),
      cell(0, 1, 2) -> Seq(2.0, 1.5)
    )
    val wrong = expected.filterNot((c, point) =>
      complex.witness(c).exists(w => w.length == 2 && w.zip(point).forall((a, b) => math.abs(a - b) < 1e-9))
    )
    wrong.map(_._1) must beEmpty
  }

  "witnesses of a cloud are the points where the power is attained" >> {
    val rng = new scala.util.Random(3L)
    val points = Array.fill(30)(Array.fill(4)(rng.nextDouble()))
    val complex = AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, 4)
    // The power of the witness to the simplex's own vertices is the filtration value: all vertices are at the same
    // squared distance from it, and that is w(sigma).
    val problems = for
      k <- 1 to 4
      c <- complex.cellsOfDimension(k)
      witness = complex.witness(c).get
      v <- c.toSeq
      power = points(v).zip(witness).map((a, b) => (a - b) * (a - b)).sum
      if math.abs(power - complex.filtrationValue(c)) > 1e-8 * (1.0 + complex.filtrationValue(c))
    yield c
    problems must beEmpty
  }

  "without witnesses the complex and its values are the same and witness is empty" >> {
    val rng = new scala.util.Random(5L)
    val points = Array.fill(25)(Array.fill(4)(rng.nextDouble()))
    val with_ = AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, 4)
    val without = AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, 4, AlphaDQPSettings(keepWitnesses = false))
    val differ = for
      k <- 0 to 4
      c <- with_.cellsOfDimension(k)
      if without.cellsOfDimension(k) != with_.cellsOfDimension(k) ||
        without.filtrationValue(c) != with_.filtrationValue(c) || without.witness(c).isDefined
    yield c
    (differ must beEmpty) and (without.sizeByDimension must beEqualTo(with_.sizeByDimension))
  }
