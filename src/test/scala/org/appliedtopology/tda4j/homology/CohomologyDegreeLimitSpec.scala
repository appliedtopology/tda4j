package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** A stream built for degrees `0..k` (its `homologyDegreeLimit`) carries cells of degree `k + 1` only to kill classes
  * of degree `k`. The cells that kill nothing are cocycles of the truncated complex, but they are not its homology.
  * `CellularCohomologyEngine.persistentCohomology` leaves them out, as `persistentHomology` does, on both its paths
  * (flat arrays and cells): a wrong implementation reports one degree-`k + 1` bar for each of them, and builds a
  * representative for each, which on a Vietoris-Rips complex of a few hundred points is millions of chains.
  */
class CohomologyDegreeLimitSpec extends Specification:

  private val ff = FiniteField(17)
  import ff.given

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  private def triples[A](bars: List[PersistenceBar[Double, A]]): List[(Int, Double, Double)] =
    bars.map(_.toTriple).sorted

  private val points = EuclideanMetricSpace(cloud(16, 3, 5L))

  // The same cloud built for degrees 0..1 (limit 1) and 0..2 (limit 2); the first has triangles that kill no class.
  private def truncated = VietorisRips(points, maxDimension = 1)
  private def whole = VietorisRips(points, maxDimension = 2)

  for (path, pack) <- Seq("flat arrays" -> true, "cells" -> false) do
    s"CellularCohomologyEngine.persistentCohomology on $path" should {
      def engine = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double](packSimplices = pack)

      "report no bar above the stream's degree limit" in {
        (engine.reducesPacked(truncated) must beEqualTo(pack)) and
          (truncated.homologyDegreeLimit must beSome(1)) and
          (engine.persistentCohomology(truncated).map(_.dim).max must beEqualTo(1)) and
          (engine.persistentCohomology(truncated, includeZeroLength = true).map(_.dim).max must beEqualTo(1))
      }

      "report the bars of the lower degrees that the longer stream reports, and those of persistentHomology" in {
        val low = triples(engine.persistentCohomology(whole)).filter(_._1 <= 1)
        (triples(engine.persistentCohomology(truncated)) must beEqualTo(low)) and
          (triples(engine.persistentCohomology(truncated)) must
            beEqualTo(triples(engine.persistentHomology(truncated))))
      }

      "leave a stream with no limit whole" in {
        val unlimited = new CellStream[Simplex[Int], Double] with DoubleFiltration[Simplex[Int]]:
          def filtrationValue = whole.filtrationValue
          def filtrationOrdering = whole.filtrationOrdering
          def iterator = whole.iterator
        engine.persistentCohomology(unlimited).map(_.dim).max must beEqualTo(3)
      }
    }
