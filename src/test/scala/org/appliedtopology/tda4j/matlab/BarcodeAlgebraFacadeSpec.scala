package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.productBars`, `homBars`, `homDimension` and `dualBars`: rows in and out. The product is checked against a
  * computation the barcode algebra has no part in: the Vietoris-Rips complex of the product of two point clouds with
  * the maximum of the two distances is homotopy equivalent to the product of their Vietoris-Rips complexes at every
  * scale, so its barcode is the one of the product.
  */
class BarcodeAlgebraFacadeSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def distance(a: Array[Double], b: Array[Double]) = math.hypot(a(0) - b(0), a(1) - b(1))

  private val everything = Array("minPersistence", "0", "maxDimension", "2", "maxFiltrationValue", "100")

  private def allBars(result: PersistenceResult): List[(Int, Double, Double)] =
    result.toArrayUnfiltered().map(r => (r(0).toInt, r(1), r(2))).toList

  private def rounded(bars: Seq[(Int, Double, Double)]) =
    def r(x: Double) = if x.isInfinite then x else math.round(x * 1e9) / 1e9
    bars.map((k, b, d) => (k, r(b), r(d))).sorted

  private def rows(bars: List[(Int, Double, Double)]) = bars.map((k, b, d) => Array(k.toDouble, b, d)).toArray

  "productBars" should {
    "be the barcode of the Vietoris-Rips complex of the product with the maximum metric, in degrees 0 to 2" in {
      val failures = for
        seed <- 0L until 3L
        (x, y) = (cloud(6, seed), cloud(5, seed + 50))
        pairs = for a <- x.indices; b <- y.indices yield (a, b)
        distances = Array.tabulate(pairs.size, pairs.size) { (i, j) =>
          math.max(distance(x(pairs(i)._1), x(pairs(j)._1)), distance(y(pairs(i)._2), y(pairs(j)._2)))
        }
        predicted = TDA4j.productBars(
          rows(allBars(TDA4j.computeFromPoints(x, everything))),
          rows(allBars(TDA4j.computeFromPoints(y, everything))),
          "max"
        )
        found = allBars(TDA4j.computeFromDistanceMatrix(distances, everything))
        if rounded(found) != rounded(predicted.filter(_(0) <= 2).map(r => (r(0).toInt, r(1), r(2))).toList)
      yield s"seed $seed: found ${rounded(found)}"
      failures.take(1) must beEmpty
    }

    "default to the maximum, take the sum, and refuse another rule or a row that is not a bar" in {
      val segment = Array(Array(0.0, 0.0, 1.0), Array(0.0, 0.0, Double.PositiveInfinity))
      val sum = TDA4j.productBars(segment, segment, "sum").map(_.toSeq).toSeq
      (TDA4j.productBars(segment, segment).length must beEqualTo(4)) and
        (sum must contain(Seq(1.0, 1.0, 2.0))) and
        (TDA4j.productBars(segment, segment, "mean") must throwAn[IllegalArgumentException]) and
        (TDA4j.productBars(Array(Array(0.5, 0.0, 1.0)), segment) must throwAn[IllegalArgumentException]) and
        (TDA4j.productBars(Array(Array(0.0, 1.0)), segment) must throwAn[IllegalArgumentException])
    }
  }

  "homBars, homDimension and dualBars" should {
    val a = Array(Array(0.0, 0.0, 3.0))
    val b = Array(Array(0.0, 1.0, 5.0))
    "give the worked example [0, 3) to [1, 5): the bar [2, 5), no map without a shift, one map the other way; maps keep the degree" in {
      (TDA4j.homBars(a, b).map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 2.0, 5.0)))) and
        (TDA4j.homDimension(a, b) must beEqualTo(0)) and
        (TDA4j.homDimension(b, a) must beEqualTo(1)) and
        (TDA4j.homDimension(Array(Array(0.0, 1.0, 4.0)), Array(Array(1.0, 0.0, 2.0))) must beEqualTo(0))
    }
    "reflect the bars: [1, 4) becomes (-4, -1], a bar that never dies starts at -Infinity" in {
      TDA4j
        .dualBars(Array(Array(1.0, 1.0, 4.0), Array(0.0, 2.0, Double.PositiveInfinity)))
        .map(_.toSeq)
        .toSet must beEqualTo(Set(Seq(1.0, -4.0, -1.0), Seq(0.0, Double.NegativeInfinity, -2.0)))
    }
  }
end BarcodeAlgebraFacadeSpec
