package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.morseReductionFromPoints` and `...FromDistanceMatrix`: the cells left after pairing off, and bars that are
  * those of the whole complex.
  */
class MorseReductionFacadeSpec extends Specification:

  private val grid = Array.tabulate(16)(i => Array((i / 4).toDouble, (i % 4).toDouble))

  private def distances(points: Array[Array[Double]]) =
    Array.tabulate(points.length, points.length)((i, j) =>
      math.hypot(points(i)(0) - points(j)(0), points(i)(1) - points(j)(1))
    )

  private def rounded(rows: Array[Array[Double]]): List[List[Double]] =
    def r(x: Double) = if x.isInfinite then x else math.round(x * 1e9) / 1e9
    rows.toList.map(_.toList.map(r)).sortBy(_.mkString(","))

  "morseReductionFromPoints" should {
    "leave fewer cells, with the bars of the whole complex, for both strategies" in {
      val whole = TDA4j.computeFromPoints(grid, Array("maxDimension", "2")).toArrayUnfiltered()
      val results = List("reduction", "coreduction").map(strategy =>
        TDA4j.morseReductionFromPoints(grid, Array("maxDimension", "2", "strategy", strategy))
      )
      results
        .map { reduced =>
          (reduced.criticalCounts().sum must beLessThan(reduced.cellCounts().sum / 2)) and
            (reduced.criticalCells().length must beEqualTo(reduced.criticalCounts().sum)) and
            (reduced.criticalCells().map(_.length - 1).groupBy(identity).view.mapValues(_.length).toMap must beEqualTo(
              reduced.criticalCounts().zipWithIndex.map((n, k) => k -> n).toMap
            )) and
            (rounded(reduced.bars()) must beEqualTo(rounded(whole)))
        }
        .reduce(_ and _)
    }

    "take a matrix of distances, and say what is wrong with the arguments" in {
      val byPoints = TDA4j.morseReductionFromPoints(grid)
      val byMatrix = TDA4j.morseReductionFromDistanceMatrix(distances(grid))
      (byMatrix.criticalCounts().toSeq must beEqualTo(byPoints.criticalCounts().toSeq)) and
        (byMatrix.cellCounts().toSeq must beEqualTo(byPoints.cellCounts().toSeq)) and
        (TDA4j.morseReductionFromPoints(grid, Array("strategy", "greedy")) must throwAn[IllegalArgumentException]) and
        (TDA4j.morseReductionFromPoints(grid, Array("dimension", "2")) must throwAn[IllegalArgumentException]) and
        (TDA4j.morseReductionFromPoints(grid, Array("maxDimension", "-1")) must throwAn[IllegalArgumentException]) and
        (TDA4j.morseReductionFromPoints(Array.empty[Array[Double]]) must throwAn[IllegalArgumentException])
    }

    "cancel cells in pairs, and keep the Euler characteristic, even of a generic cloud (a triangle enters with its longest edge)" in {
      val generic = Array.tabulate(7)(i => Array(math.sin(i * 1.3), math.cos(i * 2.9) * i))
      val reduced = TDA4j.morseReductionFromPoints(generic)
      def euler(counts: Array[Int]) = counts.zipWithIndex.map((n, k) => if k % 2 == 0 then n else -n).sum
      (reduced.pairCount() must beGreaterThan(0)) and
        (reduced.cellCounts().sum - reduced.criticalCounts().sum must beEqualTo(2 * reduced.pairCount())) and
        (euler(reduced.cellCounts()) must beEqualTo(euler(reduced.criticalCounts())))
    }
  }
end MorseReductionFacadeSpec
