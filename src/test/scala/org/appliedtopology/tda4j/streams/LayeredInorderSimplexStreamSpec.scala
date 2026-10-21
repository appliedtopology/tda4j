package org.appliedtopology.tda4j

import LayeredInorderSimplexStream.{Candidates, Generator, Layer}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result

/** `LayeredInorderSimplexStream` against `EnumeratingCofaceSimplexStream`, cell for cell, value for value and in the
  * same order, on clouds in general position and on tie-heavy metrics (a grid, a regular octagon, a metric with three
  * distance values), where Theorem 3's strict and non-strict inequalities matter.
  */
class LayeredInorderSimplexStreamSpec extends Specification with ScalaCheck:
  private val topDimension = 4

  private def listing(s: LevelwiseSimplexStream[Int, Double], d: Int): Vector[(Simplex[Int], Double)] =
    s.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toVector.map(c => (c, s.filtrationValue(c)))

  // The first place two listings differ, if any. A `must beEqualTo` on whole listings renders both into its message, and
  // thousands of those kept by `Result.foreach` exhaust the shared test JVM's heap.
  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  private def agrees(metricSpace: FiniteMetricSpace[Int], threshold: Option[Double], candidates: Candidates): Result =
    val layered = LayeredInorderSimplexStream(metricSpace, threshold, candidates)
    val enumerating = EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = threshold)
    Result.foreach(0 to math.min(topDimension, metricSpace.size - 1)) { d =>
      firstDifference(listing(layered, d), listing(enumerating, d)) must beNone
    }

  private def allAgree(metricSpace: FiniteMetricSpace[Int]): Result =
    Result.foreach(Seq(None, Some(Double.PositiveInfinity), Some(0.6 * metricSpace.minimumEnclosingRadius))) { t =>
      Result.foreach(Candidates.values.toSeq)(c => agrees(metricSpace, t, c))
    }

  // The paper's worked example: 8 points evenly on the unit circle, every distance repeated 8 times.
  private val octagon = EuclideanMetricSpace(
    Array.tabulate(8)(i => Array(math.cos(i * math.Pi / 4), math.sin(i * math.Pi / 4)))
  )
  private val grid = EuclideanMetricSpace(
    (for x <- 0 to 2; y <- 0 to 2; z <- 0 to 1 yield Array(x.toDouble, y.toDouble, z.toDouble)).toArray
  )
  // Three distance values on 9 points (lower triangle read, as ExplicitMetricSpace does), not a metric: VR does not
  // need the triangle inequality, and this is about as tied as a filtration gets.
  private val threeValues = ExplicitMetricSpace(
    Seq.tabulate(9, 9)((i, j) => if i == j then 0.0 else 1.0 + ((i * 7 + j * 7 + i * j) % 3))
  )

  "LayeredInorderSimplexStream" should {
    "list the same cells, values and order as EnumeratingCofaceSimplexStream on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(6, 13))) { pts =>
        allAgree(EuclideanMetricSpace(pts))
      }.set(minTestsOk = TestPlatform.propertyCases(40))

    "list the same cells, values and order on the paper's octagon" in allAgree(octagon)
    "list the same cells, values and order on a grid" in allAgree(grid)
    "list the same cells, values and order on a metric with three distance values" in allAgree(threeValues)
    "list the same cells on random integer distances (heavy ties)" in
      forAll(Gen.chooseNum(5, 10), Gen.long) { (n, seed) =>
        val rng = scala.util.Random(seed)
        val values = Array.fill(n, n)(1.0 + rng.nextInt(3))
        allAgree(ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else values(i max j)(i min j))))
      }.set(minTestsOk = TestPlatform.propertyCases(40))

    "keep the stream ordering contract" in {
      HomologyFixtures.respectsOrderingContract(LimitedCofaceSimplexStream(LayeredInorderSimplexStream(grid), 3)) must
        beTrue
      HomologyFixtures.respectsOrderingContract(
        LimitedCofaceSimplexStream(LayeredInorderSimplexStream(octagon), 3)
      ) must
        beTrue
    }

    // The claim that nothing above the edges is sorted: with no two distances equal, every tie is one first longest edge's
    // cofaces, and emitting candidates largest first lists them colexicographically descending.
    "need no sorting at all on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(8, 16))) { pts =>
        Result.foreach(Candidates.values.toSeq) { c =>
          val g = Generator(pts.length, EuclideanMetricSpace(pts).distance(_, _), Double.PositiveInfinity, c)
          Iterator.iterate(g.edges)(g.cofacets).take(topDimension).foreach(_ => ())
          g.tieRunsSorted must beEqualTo(0L)
        }
      }.set(minTestsOk = TestPlatform.propertyCases(40))

    // Proposition 7: the edge a simplex inherits is its first full-length edge (largest smaller end, then largest
    // larger end, the mirror of the paper's lexicographically first), and its length is the diameter.
    "carry each simplex's first longest edge" in {
      def check(metricSpace: FiniteMetricSpace[Int]): Result =
        val g = Generator(metricSpace.size, metricSpace.distance(_, _), Double.PositiveInfinity)
        val layers = Iterator.iterate(g.edges)(g.cofacets).take(topDimension).toVector
        val wrong = for
          layer <- layers
          i <- 0 until layer.size
          vs = layer.simplex(i).toList
          pairs = for a <- vs; b <- vs if a < b yield (a, b)
          alpha = pairs.map(metricSpace.distance(_, _)).max
          first = pairs.filter(metricSpace.distance(_, _) == alpha).max
          r = layer.root(i)
          if (g.edgeA(r), g.edgeB(r)) != first || g.edgeLength(r) != alpha
        yield vs
        wrong.take(3) must beEmpty
      check(octagon).and(check(grid)).and(check(threeValues))
    }
  }
