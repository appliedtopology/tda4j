package org.appliedtopology.tda4j

import org.specs2.mutable

/** With a radius, `AlphaShapes(points, Default, maxRadius = r)` picks a triangulation (BowyerWatson up to 4 dimensions,
  * Helix above; the whole triangulation, then the simplices with value at most `r`) or DQP (built only up to `r`),
  * whichever it expects to be faster (`AlphaShapes.prefersDQP`). The choice must never change the result: on random
  * clouds in 2-D, 3-D and 4-D, every backend gives the same simplices with the same values, and the verb with
  * `maxFiltrationValue` gives the same bars.
  */
class AlphaDispatchSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def cloud(dim: Int, n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  def contents(stream: AlphaShapes, topDimension: Int): Map[Simplex[Int], Double] =
    (0 to topDimension)
      .filter(stream.iterateDimension.isDefinedAt)
      .flatMap(d => stream.iterateDimension(d))
      .map(s => s -> stream.filtrationValue(s))
      .toMap

  "BowyerWatson, Helix and DQP give the same radius-limited alpha complex" >> {
    val problems = for
      (dim, n, seeds) <- Seq((2, 60, 6), (3, 50, 5), (4, 30, 3))
      seed <- 0 until seeds
      r <- Seq(0.08, 0.15, 0.25)
      pts = cloud(dim, n, 1000L * dim + seed)
      helix = contents(AlphaShapes(pts, AlphaBackend.Helix, maxRadius = r), dim)
      dqp = contents(AlphaShapes(pts, AlphaBackend.DQP, maxRadius = r), dim)
      bw = contents(AlphaShapes(pts, AlphaBackend.BowyerWatson, maxRadius = r), dim)
      problem <- Seq(
        Option.when(bw.keySet != dqp.keySet)(s"BowyerWatson: different simplices (${bw.size} vs ${dqp.size})"),
        Option
          .when(bw.keySet == dqp.keySet && bw.exists((s, v) => math.abs(v - dqp(s)) > 1e-9))(
            "BowyerWatson: different values"
          ),
        Option.when(helix.keySet != dqp.keySet)(s"different simplices (${helix.size} vs ${dqp.size})"),
        Option
          .when(helix.keySet == dqp.keySet && helix.exists((s, v) => math.abs(v - dqp(s)) > 1e-9))("different values")
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
        Persistence(
          Truncated(AlphaShapes(pts, backend, maxRadius = r, maxDimension = dim - 1), dim - 1),
          characteristic = 3
        ).triples
          .filter((_, b, d) => d - b > 1e-7)
          .sorted
      val viaVerb = Persistence(
        pts,
        complex = AlphaShapes,
        maxDimension = dim - 1,
        maxFiltrationValue = r,
        characteristic = 3
      ).triples
        .filter((_, b, d) => d - b > 1e-7)
        .sorted
      val (h, q, b) = (bars(AlphaBackend.Helix), bars(AlphaBackend.DQP), bars(AlphaBackend.BowyerWatson))
      def same(a: List[(Int, Double, Double)], b: List[(Int, Double, Double)]) =
        a.size == b.size && a.zip(b).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
          d1 == d2 && math.abs(b1 - b2) <= 1e-9 && (e1 == e2 || math.abs(e1 - e2) <= 1e-9)
        }
      Option.when(!same(h, q) || !same(h, b) || !same(h, viaVerb))(
        s"dim $dim seed $seed r $r: ${h.size} / ${q.size} / ${b.size} / ${viaVerb.size} bars"
      )
    problems.flatten must beEmpty
  }

  "The default picks DQP for a small radius, else BowyerWatson up to 4 dimensions and Helix above" >> {
    val pts = cloud(3, 1000, 5)
    (AlphaShapes.prefersDQP(pts, 0.02) must beTrue) and
      (AlphaShapes.prefersDQP(pts, 0.25) must beFalse) and
      (AlphaShapes(pts.take(100), maxRadius = 0.01).isInstanceOf[AlphaComplexDQPStream] must beTrue) and
      (AlphaShapes(pts.take(100)).isInstanceOf[BowyerWatsonDelaunay] must beTrue) and
      (AlphaShapes(cloud(4, 30, 6)).isInstanceOf[BowyerWatsonDelaunay] must beTrue) and
      (AlphaShapes(cloud(5, 20, 7)).isInstanceOf[HelixDelaunay] must beTrue) and
      (AlphaBackend.parse("Bowyer-Watson") must be_==(AlphaBackend.BowyerWatson))
  }

  "Without a radius the default builds the complex up to the enclosing radius, with DQP when the degree cap is low in high dimension" >> {
    def dqp(dim: Int, n: Int, cap: Optional[Int]) =
      AlphaShapes.upToEnclosingRadius(cloud(dim, n, 40L + dim), maxDimension = cap).isInstanceOf[AlphaComplexDQPStream]
    // Measured (n = 40 and 60, cap 4): DQP is 3 to 28 times faster from 8 dimensions, ties at 6 and loses at 5.
    (dqp(8, 14, 2) must beTrue) and
      (dqp(8, 14, 6) must beTrue).setMessage("the cap d - 2 is still DQP") and
      (dqp(8, 14, 7) must beFalse).setMessage("the top degree d - 1 stays on the triangulation") and
      (dqp(9, 14, 2) must beTrue) and
      (dqp(7, 14, 2) must beFalse).setMessage("7 dimensions stay on the triangulation") and
      (dqp(8, 14, Optional.empty) must beFalse).setMessage("no cap is the whole complex") and
      (AlphaShapes
        .upToEnclosingRadius(cloud(8, 14, 3), AlphaBackend.Helix, maxDimension = 2)
        .isInstanceOf[AlphaComplexDQPStream] must beFalse).setMessage("an explicit backend is kept") and
      (AlphaShapes
        .upToEnclosingRadius(cloud(8, 14, 3), maxRadius = 5.0, maxDimension = 2)
        .isInstanceOf[AlphaComplexDQPStream] must beTrue).setMessage("a given radius goes through prefersDQP as before")
  }

  "The verb in 8 dimensions with a low degree cap gives the same bars as the triangulation" >> {
    val problems = for
      seed <- 0 until 4
      cap <- Seq(1, 2, 4)
      pts = cloud(8, 16, 900L + seed)
    yield
      def clean(bars: List[(Int, Double, Double)]) = bars.filter((_, b, d) => d - b > 1e-7).sorted
      val viaVerb = clean(Persistence(pts, maxDimension = cap, complex = AlphaShapes, characteristic = 3).triples)
      val viaHelix = clean(
        Persistence(
          Truncated(AlphaShapes.upToEnclosingRadius(pts, AlphaBackend.Helix, maxDimension = cap), cap),
          characteristic = 3
        ).triples
      )
      Option.when(
        viaVerb.size != viaHelix.size || viaVerb.zip(viaHelix).exists { case ((d1, b1, e1), (d2, b2, e2)) =>
          d1 != d2 || math.abs(b1 - b2) > 1e-9 || !(e1 == e2 || math.abs(e1 - e2) <= 1e-9)
        }
      )(s"seed $seed cap $cap: ${viaVerb.size} vs ${viaHelix.size} bars")
    problems.flatten must beEmpty
  }
