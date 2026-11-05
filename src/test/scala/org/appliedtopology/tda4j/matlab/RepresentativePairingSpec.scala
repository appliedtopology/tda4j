package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `cycleVertices(i)` and `cycleCoefficients(i)` are two reads of one bar's chain and must stay parallel. The invariant
  * that catches a coefficient paired with another cell's vertices is the one a representative exists for: the chain of
  * a bar of degree 1 is a cycle, so its boundary is zero at every vertex (mod the prime, or to rounding over the
  * reals). A heap's order after collapsing is not the same from one read to the next, so reading the entries of a chain
  * twice is not reading them in one order.
  */
class RepresentativePairingSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  // the boundary of the chain at each vertex: an edge [a, b] has boundary b - a
  private def boundary(result: PersistenceResult, i: Int): Map[Int, Double] =
    result
      .cycleVertices(i)
      .zip(result.cycleCoefficients(i))
      .flatMap((edge, c) => Seq(edge(1) -> c, edge(0) -> -c))
      .groupMapReduce(_._1)(_._2)(_ + _)

  private def badChains(result: PersistenceResult, modulus: Option[Int]): Seq[Int] =
    (0 until result.size()).filter(result.dimension(_) == 1).filter { i =>
      boundary(result, i).values
        .exists(sum => modulus.fold(math.abs(sum) > 1e-8)(p => (math.rint(sum).toLong % p) != 0L))
    }

  "the representatives of a result" should {
    "be cycles whose vertices and coefficients are parallel, whichever way they are read, over Z/p and the reals" in {
      val failures = for
        seed <- 1L to 6L
        (name, options, modulus) <- List(
          ("ripser", Array("engine", "ripser"), Some(17)),
          ("cohomology", Array("engine", "cohomology"), Some(17)),
          ("naive reals", Array("engine", "naive", "field", "R"), None),
          ("harmonic barcode", Array("field", "R"), None)
        )
        points = cloud(14, seed)
        result =
          if name == "harmonic barcode" then
            TDA4j.harmonicChainBarcodeFromPoints(points, options ++ Array("minPersistence", "0"))
          else TDA4j.computeFromPoints(points, options ++ Array("minPersistence", "0"))
        if badChains(result, modulus).nonEmpty
      yield s"$name, seed $seed"
      failures must beEmpty
    }
  }
