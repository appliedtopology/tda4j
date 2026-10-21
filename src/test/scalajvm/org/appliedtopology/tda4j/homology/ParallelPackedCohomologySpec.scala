package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{coboundaryOf, cocycle, persistence, Result, Space}

import java.util.concurrent.Executors
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result as TestResult

/** The parallel packed experiment against itself run sequentially (every pair and every column's reduction log, in
  * every degree) and against `PackedRipserCohomologyEngine` (bar for bar), on points in general position and on
  * tie-heavy metrics; its cocycles against their coboundaries over F_17 and F_3, where a wrong sign shows.
  */
class ParallelPackedCohomologySpec extends Specification with ScalaCheck:
  private val pool = Executors.newFixedThreadPool(3)
  // (threads, units per thread): one unit per thread, several, and so many that most units are one block.
  private val parallel = Seq((3, 1), (3, 4), (3, 1000))

  private def space(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int,
    threshold: Double,
    p: Int,
    ripserApparent: Boolean = false
  ) =
    Space(metricSpace.size, metricSpace.distance(_, _), threshold, maxDim, p, ripserApparent)

  private def reduce(sp: Space, threads: Int, unitsPerThread: Int): Result =
    persistence(sp, threads, unitsPerThread, cocycles = true, if threads > 1 then pool else null, keepPairs = true)

  // The first place two sequences differ, if any (a `must beEqualTo` on whole arrays renders both into its message).
  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  private def logs(r: Result) = r.degrees.map(d =>
    d.logs.toSeq.map((c, log) => (c, log.added.result().toSeq, log.factors.result().toSeq)).sortBy(_._1)
  )

  private def agrees(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int = 2,
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17
  ): TestResult =
    val sp = space(metricSpace, maxDim, threshold, p)
    val sequential = reduce(sp, 1, 1)
    TestResult
      .foreach(parallel) { (t, u) =>
        val r = reduce(sp, t, u)
        TestResult.foreach(r.degrees.zip(sequential.degrees)) { (a, b) =>
          firstDifference(a.pairs.sorted.toSeq, b.pairs.sorted.toSeq) must beNone
        } and (firstDifference(logs(r), logs(sequential)) must beNone)
      }
      .and(
        firstDifference(sequential.bars, BlockParallelReduction.packedBars(metricSpace, maxDim, threshold, p)) must
          beNone
      )
      .and {
        // Apparent pairs found Ripser's way, from every facet's pairs: the same pairs.
        val ripserWay = reduce(space(metricSpace, maxDim, threshold, p, ripserApparent = true), 1, 1)
        TestResult.foreach(ripserWay.degrees.zip(sequential.degrees)) { (a, b) =>
          firstDifference(a.pairs.sorted.toSeq, b.pairs.sorted.toSeq) must beNone
        }
      }

  // delta(V) = R for every column the reduction changed: R its stored reduced column, or zero for an essential one.
  private def cocyclesValid(metricSpace: FiniteMetricSpace[Int], p: Int): TestResult =
    val sp = space(metricSpace, 2, Double.PositiveInfinity, p)
    TestResult.foreach(Seq((1, 1), (3, 1000))) { (t, u) =>
      val r = reduce(sp, t, u)
      TestResult
        .foreach(r.degrees) { d =>
          val essential = d.essential.toSet
          val wrong = d.logs.keys.toSeq.filterNot { c =>
            val expected =
              if essential(c) then Map.empty[Long, Int]
              else
                val o = d.reduced(c)
                o.keys.zip(o.coefs).toMap
            coboundaryOf(sp, d.degree + 1, cocycle(d, p, c)) == expected
          }
          wrong.take(3) must beEmpty
        }
        .and(r.degrees.map(_.logs.size).sum must beGreaterThan(0))
    }

  "ParallelPackedCohomology" should {
    "pair like its sequential run and give the packed engine's bars on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(6, 12))) { pts =>
        val metricSpace = EuclideanMetricSpace(pts)
        agrees(metricSpace).and(agrees(metricSpace, threshold = 0.6 * metricSpace.minimumEnclosingRadius))
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "pair like its sequential run on tie-heavy metrics, over F_17 and F_2" in
      TestResult.foreach(Seq(17, 2)) { p =>
        agrees(octagon, p = p).and(agrees(grid, p = p)).and(agrees(grid, threshold = 1.5, p = p))
      }

    "pair like its sequential run on random integer distances" in
      forAll(Gen.chooseNum(5, 10), Gen.long) { (n, seed) =>
        agrees(integerMetric(n, seed))
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "keep cocycles whose coboundary is the reduced column" in
      TestResult.foreach(Seq(17, 3)) { p =>
        cocyclesValid(grid, p)
          .and(cocyclesValid(integerMetric(9, 7L), p))
          .and(
            cocyclesValid(
              EuclideanMetricSpace(Array.tabulate(10)(i => Array(math.cos(i * 0.7), math.sin(i * 1.3), i * 0.1))),
              p
            )
          )
      }

    // Otherwise the examples above would pass with every column in phase 2b, or none deferred. In general position a
    // pair inside a block has length zero, so phase 1 pairs only those; most are apparent pairs.
    "pair columns inside their units and defer others on 40 points in the plane" in {
      val rng = scala.util.Random(3)
      val sp = space(EuclideanMetricSpace(Array.fill(40)(Array(rng.nextDouble(), rng.nextDouble()))), 2, 0.5, 17)
      val stats = reduce(sp, 3, 4).degrees(1).stats
      (stats.apparent + stats.claimed1 must beGreaterThan(0)).and(stats.deferred must beGreaterThan(0))
    }
  }

  step(pool.shutdown())
