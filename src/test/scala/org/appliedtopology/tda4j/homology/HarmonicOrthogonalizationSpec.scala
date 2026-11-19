package org.appliedtopology.tda4j

import BruteForceComplexes.boundary as simplexBoundary

import org.specs2.mutable.Specification

import scala.util.Random

/** The two ways to make the chain of a positive cell orthogonal to the older cycles (`Orthogonalization`, #305): the
  * stored orthogonal basis (Gram-Schmidt) and the solve `(∂ ∂ᵀ) y = -∂σ` (`z = e_σ + ∂ᵀ y`) that keeps only the
  * boundaries. They are two computations of one chain, so over the rationals the diagrams must be equal bar for bar and
  * chain for chain, and over the reals equal to the field's tolerance; the Gram-Schmidt run is the one every other
  * harmonic spec judges against the paper's definitions.
  */
class HarmonicOrthogonalizationSpec extends Specification:
  sequential

  private type S = Simplex[Int]
  private type Q = Coefficients.Rational

  import HarmonicChainBarcode.Orthogonalization.{GramSchmidt, LaplacianSolve}

  private def universe(n: Int, size: Int): Vector[S] =
    (1 to size).flatMap(k => (0 until n).toList.combinations(k).map(Simplex.from(_))).toVector

  private def faces(s: S): Seq[S] = simplexBoundary(s).map(_._1)

  private def randomOrder(cells: Vector[S], length: Int, seed: Long): Vector[S] =
    val rng = new Random(seed)
    val inserted = scala.collection.mutable.LinkedHashSet.empty[S]
    while inserted.size < length do
      val ready = cells.filter(c => !inserted(c) && faces(c).forall(inserted))
      inserted += ready(rng.nextInt(ready.size))
    inserted.toVector

  private def indexed(order: Vector[S]): Seq[(S, Double)] = order.zipWithIndex.map((s, i) => (s, i + 1.0))

  private def exact(order: Vector[S], degree: Int, how: HarmonicChainBarcode.Orthogonalization) =
    HarmonicChainBarcode
      .ofOrder(indexed(order), degree, Coefficients.Rationals, includeZeroLength = true, orthogonalization = how)
      .asInstanceOf[PersistenceDiagram.Of[S, Q]]

  private def listing(d: PersistenceDiagram.Of[S, Q]) =
    d.bars.map(b => (b.dim, b.lower.toString, b.upper.toString, b.representative, b.cells))

  "A new cycle by the Laplacian solve" should {
    "be the Gram-Schmidt chain, bar for bar and term for term over the rationals, on random orders" in {
      // five vertices up to 4-vertex simplices: degrees 0 to 2, with cycles in degrees 1 and 2
      val cells = universe(6, 4)
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 24L, js = 4L)
        length = 14 + (seed % 17).toInt * 3
        order = randomOrder(cells, math.min(length, cells.size), seed)
        degree <- Seq(1, 2)
        if listing(exact(order, degree, GramSchmidt)) != listing(exact(order, degree, LaplacianSolve))
      yield s"seed $seed, degree $degree"
      failures.take(3) must beEmpty
    }

    "be the Gram-Schmidt chain in a tie-heavy Vietoris-Rips filtration, with the cells in the stream's order" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 5L, js = 1L)
        rng = new Random(seed)
        // integer coordinates: many equal distances
        points = Array.fill(8)(Array(rng.nextInt(4).toDouble, rng.nextInt(4).toDouble + 0.5 * rng.nextInt(2)))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        gs = HarmonicChainBarcode(
          rips,
          1,
          Coefficients.Rationals,
          includeZeroLength = true,
          orthogonalization = GramSchmidt
        )
        ls = HarmonicChainBarcode(
          rips,
          1,
          Coefficients.Rationals,
          includeZeroLength = true,
          orthogonalization = LaplacianSolve
        )
        if listing(gs.asInstanceOf[PersistenceDiagram.Of[S, Q]]) != listing(
          ls.asInstanceOf[PersistenceDiagram.Of[S, Q]]
        )
      yield s"seed $seed"
      failures.take(3) must beEmpty
    }

    "be the Gram-Schmidt chain over the reals, to the field's tolerance" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 8L, js = 2L)
        rng = new Random(seed)
        points = Array.fill(12)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        gs = HarmonicChainBarcode(rips, 1, includeZeroLength = true, orthogonalization = GramSchmidt)
          .asInstanceOf[PersistenceDiagram.Of[S, Double]]
        ls = HarmonicChainBarcode(rips, 1, includeZeroLength = true, orthogonalization = LaplacianSolve)
          .asInstanceOf[PersistenceDiagram.Of[S, Double]]
        wrong = gs.bars.zip(ls.bars).filter { (a, b) =>
          def entries(bar: PersistenceBar[Double, Chain[S, Double]]) =
            bar.representative.terms.map((c, x) => c.toString -> x).toMap
          val (x, y) = (entries(a), entries(b))
          a.dim != b.dim || a.lower != b.lower || a.upper != b.upper || (x.keySet ++ y.keySet)
            .exists(k => math.abs(x.getOrElse(k, 0.0) - y.getOrElse(k, 0.0)) > 1e-7)
        }
        if gs.bars.size != ls.bars.size || wrong.nonEmpty
      yield s"seed $seed: ${gs.bars.size} bars against ${ls.bars.size}, ${wrong.size} differ"
      failures.take(3) must beEmpty
    }

    "give the Basu-Cox representatives and the harmonic chains at a value of the Gram-Schmidt run" in {
      val rng = new Random(77)
      val points = Array.fill(8)(Array(rng.nextDouble(), rng.nextDouble()))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
      val gs = HarmonicRepresentatives(rips, 1, Coefficients.Rationals, orthogonalization = GramSchmidt)
        .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val ls =
        HarmonicRepresentatives(rips, 1, Coefficients.Rationals, orthogonalization = LaplacianSolve)
          .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val value = HarmonicChainBarcode.insertionOrder(rips, 1).map(_._2).distinct.apply(10)
      val chainsGs = HarmonicChainBarcode.harmonicChains(rips, 1, value, Coefficients.Rationals, GramSchmidt)
      val chainsLs = HarmonicChainBarcode.harmonicChains(rips, 1, value, Coefficients.Rationals, LaplacianSolve)
      (listing(gs) == listing(ls)) must beTrue
      (chainsGs == chainsLs && chainsGs.nonEmpty) must beTrue
    }
  }
