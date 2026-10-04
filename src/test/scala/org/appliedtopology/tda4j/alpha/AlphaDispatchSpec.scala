package org.appliedtopology.tda4j

import org.specs2.mutable

/** With a radius, `AlphaShapes(points, Default, maxRadius = r)` picks Helix (whole triangulation, then the simplices with
  * value at most `r`) or DQP (built only up to `r`), whichever it expects to be faster (`AlphaShapes.prefersDQP`).
  * The choice must never change the result: on random clouds in 2-D, 3-D and 4-D, both backends give the same simplices
  * with the same values, and the verb with `maxFiltrationValue` gives the same bars.
  */
class AlphaDispatchSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def cloud(dim: Int, n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  def contents(stream: AlphaShapes, topDimension: Int): Map[Simplex[Int], Double] =
    (0 to topDimension).filter(stream.iterateDimension.isDefinedAt).flatMap(d => stream.iterateDimension(d)).map(s => s -> stream.filtrationValue(s)).toMap

  "Helix and DQP give the same radius-limited alpha complex" >> {
    val problems = for
      (dim, n, seeds) <- Seq((2, 60, 6), (3, 50, 5), (4, 30, 3))
      seed <- 0 until seeds
      r <- Seq(0.08, 0.15, 0.25)
      pts = cloud(dim, n, 1000L * dim + seed)
      helix = contents(AlphaShapes(pts, AlphaBackend.Helix, maxRadius = r), dim)
      dqp = contents(AlphaShapes(pts, AlphaBackend.DQP, maxRadius = r), dim)
      problem <- Seq(
        Option.when(helix.keySet != dqp.keySet)(s"different simplices (${helix.size} vs ${dqp.size})"),
        Option.when(helix.keySet == dqp.keySet && helix.exists((s, v) => math.abs(v - dqp(s)) > 1e-9))("different values")
      ).flatten
    yield s"dim $dim seed $seed r $r: $problem"
    problems must beEmpty
  }

  "The verb with maxFiltrationValue gives the same bars from either backend" >> {
    val problems = for
      dim <- Seq(2, 3)
      seed <- 0 until 4
      r <- Seq(0.1, 0.2)
      pts = cloud(dim, 50, 77L * dim + seed)
    yield
      def bars(backend: AlphaBackend) =
        Persistence(Truncated(AlphaShapes(pts, backend, maxRadius = r, maxDimension = dim - 1), dim - 1), characteristic = 3).triples
          .filter((_, b, d) => d - b > 1e-7).sorted
      val viaVerb = Persistence(pts, complex = AlphaShapes, maxDimension = dim - 1, maxFiltrationValue = r, characteristic = 3).triples
        .filter((_, b, d) => d - b > 1e-7).sorted
      val (h, q) = (bars(AlphaBackend.Helix), bars(AlphaBackend.DQP))
      def same(a: List[(Int, Double, Double)], b: List[(Int, Double, Double)]) =
        a.size == b.size && a.zip(b).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
          d1 == d2 && math.abs(b1 - b2) <= 1e-9 && (e1 == e2 || math.abs(e1 - e2) <= 1e-9)
        }
      Option.when(!same(h, q) || !same(h, viaVerb))(s"dim $dim seed $seed r $r: ${h.size} / ${q.size} / ${viaVerb.size} bars")
    problems.flatten must beEmpty
  }

  "The default picks DQP for a small radius and Helix without one or for a large one" >> {
    val pts = cloud(3, 1000, 5)
    (AlphaShapes.prefersDQP(pts, 0.05) must beTrue) and
      (AlphaShapes.prefersDQP(pts, 0.25) must beFalse) and
      (AlphaShapes(pts.take(100), maxRadius = 0.02).isInstanceOf[AlphaComplexDQPStream] must beTrue) and
      (AlphaShapes(pts.take(100)).isInstanceOf[HelixDelaunay] must beTrue)
  }
