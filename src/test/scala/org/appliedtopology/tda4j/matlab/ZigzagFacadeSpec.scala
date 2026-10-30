package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.zigzagFromSteps` and `zigzagFromComplexes`: the library's zigzag barcode with arrays in and out. */
class ZigzagFacadeSpec extends Specification:

  private def v(i: Int) = Array(i)
  private def e(i: Int, j: Int) = Array(i, j)

  private def rows(result: ZigzagResult): List[(Int, Double, Double)] =
    result.toArray().toList.map(r => (r(0).toInt, r(1), r(2))).sortBy(r => (r._1, r._2, r._3))

  "zigzagFromSteps" should {
    "give the loop that is closed by an edge and opened by removing it, and a representative that is that loop" in {
      val simplices = Array(v(0), v(1), v(2), e(0, 1), e(1, 2), e(0, 2), e(0, 2))
      val additions = Array(true, true, true, true, true, true, false)
      val result = TDA4j.zigzagFromSteps(Array.empty[Array[Int]], simplices, additions)
      val loops = (0 until result.size()).filter(i => result.toArray()(i)(0) == 1.0)
      val representative = result.cycleVertices(loops.head).map(_.toList).toSet
      (rows(result) must beEqualTo(
        List((0, 1.0, Double.PositiveInfinity), (0, 2.0, 4.0), (0, 3.0, 5.0), (1, 6.0, 7.0))
      )) and (result.lastPosition() must beEqualTo(7)) and (loops.size must beEqualTo(1)) and
        (representative must beEqualTo(Set(List(0, 1), List(1, 2), List(0, 2)))) and
        (result.cycleCoefficients(loops.head).length must beEqualTo(3))
    }

    "start from a complex, and say what is wrong with the arguments" in {
      val filled = Array(v(0), v(1), v(2), e(0, 1), e(1, 2), e(0, 2), Array(0, 1, 2))
      val result = TDA4j.zigzagFromSteps(filled, Array(Array(0, 1, 2)), Array(false))
      (rows(result) must beEqualTo(List((0, 0.0, Double.PositiveInfinity), (1, 1.0, Double.PositiveInfinity)))) and
        (TDA4j.zigzagFromSteps(Array.empty[Array[Int]], Array(e(0, 1)), Array(true)) must throwAn[
          IllegalArgumentException
        ]) and
        (TDA4j.zigzagFromSteps(Array.empty[Array[Int]], Array(v(0)), Array(true, false)) must throwAn[
          IllegalArgumentException
        ]) and
        (TDA4j.zigzagFromSteps(Array.empty[Array[Int]], Array(v(0)), Array(true), Array("link", "union")) must throwAn[
          IllegalArgumentException
        ])
    }
  }

  "zigzagFromComplexes" should {
    "see the loop in the union of two paths only when asked to put the union between them" in {
      val first = Array(v(0), v(1), v(2), e(0, 1), e(1, 2))
      val second = Array(v(0), v(1), v(2), e(1, 2), e(0, 2))
      val joined = TDA4j.zigzagFromComplexes(Array(first, second), Array("link", "union"))
      (rows(joined).filter(_._1 == 1) must beEqualTo(List((1, 1.0, 2.0)))) and
        (joined.lastPosition() must beEqualTo(2)) and
        (TDA4j.zigzagFromComplexes(Array(first, second)) must throwAn[IllegalArgumentException]) and
        (TDA4j.zigzagFromComplexes(Array(first, second), Array("link", "glue")) must throwAn[IllegalArgumentException])
    }
  }
end ZigzagFacadeSpec
