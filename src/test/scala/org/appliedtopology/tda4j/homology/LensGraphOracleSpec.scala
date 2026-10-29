package org.appliedtopology.tda4j

import Persistence.Engine

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification

/** The lens-graph identities as an oracle for every Vietoris-Rips engine (#187). For an edge `e = ab` of length `α`,
  * its lens graph has the points closer than `α` to both `a` and `b` as vertices, joined when closer than `α` to each
  * other. With all distances distinct, the simplices of diameter `α` are `e` joined with the clique complex of that
  * graph, so `H_j(X_α, X_α⁻) ≅ H̃_{j−2}(Cl(G_e))` and two counts follow:
  *
  *   - positive-length `H_1` bars = (edges with an empty lens) − (`n` − components of the threshold graph);
  *   - (`H_1` deaths) + (`H_2` bars) = the sum over edges with a nonempty lens of (components of the lens graph − 1).
  *
  * The oracle counts components of small graphs and never computes persistence, so an engine that loses a bar or
  * invents one breaks a count whatever it shares with the other engines. The counts hold over every field: they are
  * ranks of `H̃_0` of a flag complex and of the relative group of one edge.
  */
class LensGraphOracleSpec extends Specification with PlatformScalaCheck:
  import LensGraphOracleSpec.*

  // Every engine that computes Vietoris-Rips persistence from points, each by its own reduction.
  private val engines = List(Engine.Chunks, Engine.Naive, Engine.Cohomology, Engine.Ripser, Engine.ParallelRipser)

  private def thresholds(metricSpace: EuclideanMetricSpace): List[Double] =
    val r = metricSpace.minimumEnclosingRadius
    List(Double.PositiveInfinity, r, 0.6 * r)

  // The engine's counts against the oracle's, as the first disagreement per engine (a failed `must beEqualTo` on a
  // diagram would render the whole diagram).
  private def disagreements(
    points: Array[Array[Double]],
    threshold: Double,
    p: Int,
    which: List[Engine]
  ): List[String] =
    val expected = counts(EuclideanMetricSpace(points), threshold)
    which.flatMap { engine =>
      val d = Persistence(points, maxDimension = 2, maxFiltrationValue = threshold, characteristic = p, engine = engine)
      val actual = Counts(
        d.dim(1).bars.size,
        d.dim(1).bars.count(!_.death.isPosInfinity) + d.dim(2).bars.size
      )
      Option.when(actual != expected)(
        s"$engine over F_$p, n = ${points.length}, threshold $threshold: engine $actual, lens graphs $expected"
      )
    }

  "the lens-graph counts" should {
    "match the bars of every engine on random clouds, over F_2, F_3 and F_17" in
      forAll(
        matrixGen(Gen.choose(0.0, 1.0), Gen.chooseNum(2, 4), Gen.chooseNum(6, 12)).suchThat(distinctDistances),
        Gen.oneOf(2, 3, 17)
      ) { (points, p) =>
        val failures = thresholds(EuclideanMetricSpace(points)).flatMap(t => disagreements(points, t, p, engines))
        failures.take(3) must beEmpty
      }.set(minTestsOk = TestPlatform.propertyCases(60))

    "match the fast engines on larger clouds (n = 40 to 60 in R^2 to R^4, as measured against Ripser)" in {
      val failures = for
        (n, dim, seed) <- TestPlatform.perPlatform(
          jvm = List(
            (40, 2, 1L),
            (50, 3, 2L),
            (60, 4, 3L),
            (45, 2, 4L),
            (55, 3, 5L),
            (40, 4, 6L),
            (60, 2, 7L),
            (50, 4, 8L)
          ),
          js = List((40, 2, 1L), (50, 3, 2L))
        )
        rng = scala.util.Random(seed)
        points = Array.fill(n)(Array.fill(dim)(rng.nextDouble()))
        t <- thresholds(EuclideanMetricSpace(points))
        failure <- disagreements(points, t, 17, List(Engine.Ripser, Engine.ParallelRipser))
      yield failure
      failures.take(3) must beEmpty
    }

    // Hand counts. A square, perturbed so that no two sides or diagonals are equal: the four sides have empty lenses,
    // three of them join the four points and the fourth closes a loop, so one H_1 bar; the shorter diagonal's lens is
    // the two other corners, which are farther apart than the diagonal itself, so two components and one H_1 death;
    // the longer diagonal's lens is the same two corners, now closer than it, one component.
    "count the loop of a perturbed square by hand" in {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.01, 1.02), Array(-0.01, 0.99))
      val space = EuclideanMetricSpace(square)
      distinctDistances(square) must beTrue
      counts(space, Double.PositiveInfinity) === Counts(h1Bars = 1, h1DeathsPlusH2Bars = 1)
      disagreements(square, Double.PositiveInfinity, 17, engines) must beEmpty
    }

    // A perturbed octahedron: the 12 short edges close up a 2-sphere, so one H_2 bar and no H_1 bar that survives the
    // fill; its lens sums are checked against the engines, the H_2 bar by hand.
    "count the sphere of a perturbed octahedron" in {
      val octahedron = Array(
        Array(1.0, 0.01, 0.0),
        Array(-1.0, 0.0, 0.02),
        Array(0.03, 1.0, 0.0),
        Array(0.0, -1.0, 0.015),
        Array(0.0, 0.025, 1.0),
        Array(0.012, 0.0, -1.0)
      )
      distinctDistances(octahedron) must beTrue
      val d = Persistence(octahedron, maxDimension = 2, maxFiltrationValue = Double.PositiveInfinity)
      d.dim(2).bars.size === 1
      disagreements(octahedron, Double.PositiveInfinity, 17, engines) must beEmpty
    }

    // The identities need distinct distances: on a grid the lens graph of an edge misses the simplices that its tied
    // edges share, so the oracle is only asked about clouds that pass this check.
    "refuse to call a tied cloud generic" in {
      val grid = Array.tabulate(9)(i => Array((i % 3).toDouble, (i / 3).toDouble))
      distinctDistances(grid) must beFalse
    }
  }

object LensGraphOracleSpec:

  /** What the two identities predict: positive-length `H_1` bars, and `H_1` deaths plus `H_2` bars. */
  final case class Counts(h1Bars: Int, h1DeathsPlusH2Bars: Int)

  /** Whether all `n (n - 1) / 2` distances are different, the general position the identities assume. */
  def distinctDistances(points: Array[Array[Double]]): Boolean =
    val space = EuclideanMetricSpace(points)
    val ds = for b <- 1 until points.length; a <- 0 until b yield space.distance(a, b)
    ds.distinct.size == ds.size

  // The number of connected components of the graph on `vertices` with the edges `adjacent` accepts.
  private def components(vertices: Seq[Int], adjacent: (Int, Int) => Boolean): Int =
    val parent = scala.collection.mutable.Map.from(vertices.map(v => v -> v))
    def root(x: Int): Int = if parent(x) == x then x else root(parent(x))
    var count = vertices.size
    for
      x <- vertices
      y <- vertices if x < y && adjacent(x, y) && root(x) != root(y)
    do
      parent(root(x)) = root(y)
      count -= 1
    count

  /** The counts the lens graphs of the Vietoris-Rips complex of `metricSpace` up to `threshold` predict. */
  def counts(metricSpace: FiniteMetricSpace[Int], threshold: Double): Counts =
    val n = metricSpace.size
    val d = (x: Int, y: Int) => metricSpace.distance(x, y)
    val edges = for b <- 1 until n; a <- 0 until b if d(a, b) <= threshold yield (a, b)
    var emptyLenses = 0
    var lensComponents = 0
    for (a, b) <- edges do
      val alpha = d(a, b)
      val lens = (0 until n).filter(w => w != a && w != b && d(w, a) < alpha && d(w, b) < alpha)
      if lens.isEmpty then emptyLenses += 1
      else lensComponents += components(lens, (x, y) => d(x, y) < alpha) - 1
    Counts(emptyLenses - (n - components(0 until n, (x, y) => d(x, y) <= threshold)), lensComponents)
