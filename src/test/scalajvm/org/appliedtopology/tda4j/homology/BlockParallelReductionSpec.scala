package org.appliedtopology.tda4j

import BlockParallelReduction.{coboundary, complex, packedBars, persistence, Coboundary, Method}
import BlockParallelReductionSpec.{grid, integerMetric, octagon}

import java.util.concurrent.Executors
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result

/** The edge-block reduction experiment against one sequential reduction of the same matrices (pair for pair, every
  * degree) and against `PackedRipserCohomologyEngine` (bar for bar), on points in general position and on tie-heavy
  * metrics, where blocks of equal length split by edge; its cocycles against their coboundaries over F_17, where a
  * wrong sign shows.
  */
class BlockParallelReductionSpec extends Specification with ScalaCheck:
  private val pool = Executors.newFixedThreadPool(3)
  private val parallel = Seq(Method.Blocks(1), Method.Blocks(3), Method.Blocks(3, 5))

  private def build(metricSpace: FiniteMetricSpace[Int], maxDim: Int, threshold: Double, p: Int) =
    val n = metricSpace.size
    val cells = complex(n, metricSpace.distance(_, _), threshold, maxDim + 1)
    (cells, (1 to maxDim).map(d => coboundary(n, cells(d), cells(d + 1), p)).toVector)

  // The first place two sequences differ, if any. A `must beEqualTo` on whole arrays renders both into its message, and
  // thousands of those kept by `Result.foreach` exhaust the shared test JVM's heap.
  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  private def agrees(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int = 2,
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17
  ): Result =
    val (cells, cobs) = build(metricSpace, maxDim, threshold, p)
    val sequential = persistence(cells, cobs, p, Method.Sequential, cocycles = false, pool)
    Result
      .foreach(parallel) { m =>
        val r = persistence(cells, cobs, p, m, cocycles = false, pool)
        Result.foreach(r.degrees.zip(sequential.degrees)) { (a, b) =>
          firstDifference(a.owner.toSeq, b.owner.toSeq) must beNone
        }
      }
      .and(firstDifference(sequential.bars, packedBars(metricSpace, maxDim, threshold, p)) must beNone)

  // delta(V_j) = R_j for every column reduced, and R_j's pivot is the row it owns.
  private def cocyclesValid(metricSpace: FiniteMetricSpace[Int], p: Int): Result =
    val (cells, cobs) = build(metricSpace, 2, Double.PositiveInfinity, p)
    Result.foreach(Seq(Method.Sequential, Method.Blocks(3), Method.Blocks(3, 5))) { m =>
      val r = persistence(cells, cobs, p, m, cocycles = true, pool)
      Result.foreach(r.degrees) { d =>
        val cob = d.cob
        val wrongCocycles = (0 until cob.columns.size).filterNot(d.cleared).filterNot { j =>
          val (vR, vC) = d.store.cocycle(j)
          val sum = scala.collection.mutable.Map.empty[Int, Int].withDefaultValue(0)
          for (i, c) <- vR.zip(vC); e <- cob.start(i) until cob.start(i + 1) do
            sum(cob.entryRow(e)) = (sum(cob.entryRow(e)) + c * cob.entryCoeff(e)) % p
          val (rR, rC) = d.store.reduced(j)
          sum.filter(_._2 != 0).toMap == rR.zip(rC).toMap
        }
        val wrongPivots = (0 until cob.rows.size).filter(r => d.owner(r) >= 0 && d.store.pivot(d.owner(r)) != r)
        (wrongCocycles.take(3) must beEmpty).and(wrongPivots.take(3) must beEmpty)
      }
    }

  private def zeroLengthPairs(cob: Coboundary, owner: Array[Int]): Int =
    (0 until cob.rows.size).count(r => owner(r) >= 0 && cob.rows.value(r) == cob.columns.value(owner(r)))

  "BlockParallelReduction" should {
    "pair like one sequential reduction and give the packed engine's bars on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(6, 12))) { pts =>
        val metricSpace = EuclideanMetricSpace(pts)
        agrees(metricSpace).and(agrees(metricSpace, threshold = 0.6 * metricSpace.minimumEnclosingRadius))
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "pair like one sequential reduction on tie-heavy metrics, over F_17 and F_2" in
      Result.foreach(Seq(17, 2)) { p =>
        agrees(octagon, p = p).and(agrees(grid, p = p)).and(agrees(grid, threshold = 1.5, p = p))
      }

    "pair like one sequential reduction on random integer distances" in
      forAll(Gen.chooseNum(5, 10), Gen.long) { (n, seed) =>
        agrees(integerMetric(n, seed))
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "keep cocycles whose coboundary is the reduced column" in
      Result.foreach(Seq(17, 3)) { p =>
        cocyclesValid(grid, p)
          .and(cocyclesValid(integerMetric(9, 7L), p))
          .and(
            cocyclesValid(
              EuclideanMetricSpace(Array.tabulate(10)(i => Array(math.cos(i * 0.7), math.sin(i * 1.3), i * 0.1))),
              p
            )
          )
      }

    // In general position a block is one filtration value, so a pair inside a block has length zero; and a zero-length
    // pair's pivot never leaves the block on the way, so phase 1 makes all of them.
    "make exactly the zero-length pairs in phase 1 in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(8, 12))) { pts =>
        val (cells, cobs) = build(EuclideanMetricSpace(pts), 2, Double.PositiveInfinity, 17)
        val sequential = persistence(cells, cobs, 17, Method.Sequential, cocycles = false, pool)
        val blocks = persistence(cells, cobs, 17, Method.Blocks(3), cocycles = false, pool)
        Result.foreach(blocks.degrees.zip(sequential.degrees)) { (b, s) =>
          b.stats.pairs1 must beEqualTo(zeroLengthPairs(s.cob, s.owner))
        }
      }.set(minTestsOk = TestPlatform.propertyCases(30))
  }

  step(pool.shutdown())

/** Tie-heavy metrics for the block experiments: equal edge lengths split a filtration value into several blocks. */
object BlockParallelReductionSpec:
  val octagon: EuclideanMetricSpace = EuclideanMetricSpace(
    Array.tabulate(8)(i => Array(math.cos(i * math.Pi / 4), math.sin(i * math.Pi / 4)))
  )
  val grid: EuclideanMetricSpace = EuclideanMetricSpace(
    (for x <- 0 to 2; y <- 0 to 2; z <- 0 to 1 yield Array(x.toDouble, y.toDouble, z.toDouble)).toArray
  )
  def integerMetric(n: Int, seed: Long): ExplicitMetricSpace =
    val rng = scala.util.Random(seed)
    val values = Array.fill(n, n)(1.0 + rng.nextInt(3))
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else values(i max j)(i min j)))
