package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{coboundaryOf, cocycle, persistence, Degree, Log, Result, Space, Stats}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result as TestResult

/** The parallel packed experiment against itself run sequentially (every pair and every column's reduction log, in
  * every degree) and against `PackedRipserCohomologyEngine` (bar for bar), on points in general position and on
  * tie-heavy metrics; its cocycles against their coboundaries over F_17 and F_3, where a wrong sign shows. With
  * two-word keys (forced, and chosen on its own where one Long is too small): the one-word run's pairs and logs, by
  * combinatorial index, and its bars.
  */
class ParallelPackedCohomologySpec extends Specification with ScalaCheck:
  // (threads, units per thread): one unit per thread, several, and so many that most units are one block.
  private val parallel = Seq((3, 1), (3, 4), (3, 1000))

  private def space(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int,
    threshold: Double,
    p: Int,
    ripserApparent: Boolean = false,
    wide: Boolean = false
  ) =
    Space(metricSpace.size, metricSpace.distance(_, _), threshold, maxDim, p, ripserApparent, forceWide = wide)

  private def reduce(sp: Space, threads: Int, unitsPerThread: Int): Result =
    Parallel.withThreads(threads)(persistence(sp, threads, unitsPerThread, cocycles = true, keepPairs = true))

  // The first place two sequences differ, if any (a `must beEqualTo` on whole arrays renders both into its message).
  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  private def logs(r: Result) =
    r.degrees.map(d => d.logs.toSeq.map((c, log) => (c, log.columns.toSeq, log.multipliers.toSeq)).sortBy(_._1))

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

  // The pairs, the logs (by combinatorial index: a two-word key is not the one-word key) and the bars.
  private def byIndex(sp: Space, r: Result) =
    def i(key: Long) = if key < 0 then key else sp.index(key)
    (
      r.degrees.map(_.pairs.map((a, b) => (i(a), i(b))).sorted.toSeq),
      r.degrees.map(d =>
        d.logs.toSeq.map((c, log) => (i(c), log.columns.toSeq.map(i), log.multipliers.toSeq)).sortBy(_._1)
      ),
      r.bars
    )

  private def sameWide(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int = 2,
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17
  ): TestResult =
    val narrow = space(metricSpace, maxDim, threshold, p)
    val (pairs, logs, bars) = byIndex(narrow, reduce(narrow, 1, 1))
    val wide = space(metricSpace, maxDim, threshold, p, wide = true)
    (wide.wide must beTrue)
      .and(narrow.wide must beFalse)
      .and(TestResult.foreach(Seq((1, 1), (3, 4), (3, 1000))) { (t, u) =>
        val (wPairs, wLogs, wBars) = byIndex(wide, reduce(wide, t, u))
        (firstDifference(wPairs, pairs) must beNone)
          .and(firstDifference(wLogs, logs) must beNone)
          .and(firstDifference(wBars, bars) must beNone)
      })

  // delta(V) = R for every column the reduction changed: R its stored reduced column, or zero for an essential one.
  private def cocyclesValid(metricSpace: FiniteMetricSpace[Int], p: Int, wide: Boolean = false): TestResult =
    val sp = space(metricSpace, 2, Double.PositiveInfinity, p, wide = wide)
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

    "pair and log the same with two-word keys on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(6, 12))) { pts =>
        val metricSpace = EuclideanMetricSpace(pts)
        sameWide(metricSpace).and(sameWide(metricSpace, threshold = 0.6 * metricSpace.minimumEnclosingRadius))
      }.set(minTestsOk = TestPlatform.propertyCases(20))

    "pair and log the same with two-word keys on tie-heavy metrics, over F_17 and F_2" in
      TestResult.foreach(Seq(17, 2)) { p =>
        sameWide(octagon, p = p).and(sameWide(grid, p = p)).and(sameWide(integerMetric(9, 7L), p = p))
      }

    // 100 points to H12: C(100, 14) needs 56 bits, and the edges within the threshold more than 7.
    "choose two-word keys when one Long cannot hold them, and still give the packed engine's bars" in {
      val rng = scala.util.Random(11)
      val metricSpace = EuclideanMetricSpace(Array.fill(100)(Array(rng.nextDouble(), rng.nextDouble())))
      val sp = space(metricSpace, 12, 0.12, 17)
      val bars = reduce(sp, 3, 4).bars
      (sp.wide must beTrue)
        .and(space(metricSpace, 2, 0.12, 17).wide must beFalse)
        .and(firstDifference(bars, BlockParallelReduction.packedBars(metricSpace, 12, 0.12, 17)) must beNone)
        .and(bars.count(_._1 == 1) must beGreaterThan(0))
    }

    "keep cocycles whose coboundary is the reduced column" in
      TestResult.foreach(Seq(17, 3)) { p =>
        cocyclesValid(grid, p)
          .and(cocyclesValid(grid, p, wide = true))
          .and(cocyclesValid(integerMetric(9, 7L), p, wide = true))
          .and(cocyclesValid(integerMetric(9, 7L), p))
          .and(
            cocyclesValid(
              EuclideanMetricSpace(Array.tabulate(10)(i => Array(math.cos(i * 0.7), math.sin(i * 1.3), i * 0.1))),
              p
            )
          )
      }

    "expand a deep cocycle log without using the call stack, and reject a cycle" in {
      val depth = 30000
      val degree = Degree(0, Stats(0, depth + 1, 0))
      var column = 1L
      while column <= depth do
        val log = Log()
        log.add(column - 1, 0)
        degree.logs(column) = log
        column += 1
      val expanded = cocycle(degree, 17, depth.toLong)

      val cyclic = Degree(0, Stats(0, 1, 0))
      val cycle = Log()
      cycle.add(0L, 1)
      cyclic.logs(0L) = cycle
      (expanded must beEqualTo(Map(depth.toLong -> 1))) and
        (cocycle(cyclic, 17, 0L) must throwA[IllegalStateException])
    }

    // Otherwise the examples above would pass with every column in phase 2b, or none deferred. In general position a
    // pair inside a block has length zero, so phase 1 pairs only those; most are apparent pairs.
    "pair columns inside their units and defer others on 40 points in the plane" in {
      val rng = scala.util.Random(3)
      val sp = space(EuclideanMetricSpace(Array.fill(40)(Array(rng.nextDouble(), rng.nextDouble()))), 2, 0.5, 17)
      val stats = reduce(sp, 3, 4).degrees(1).stats
      (stats.apparent + stats.claimed1 must beGreaterThan(0)).and(stats.deferred must beGreaterThan(0))
    }

    // The edge pass reads (larger, smaller), the lower triangle, as ripser.cpp and ExplicitMetricSpace do (and along a
    // row of a cached matrix): on a function that is not symmetric the lengths are the lower entries.
    "read the lower triangle of a distance function that is not symmetric" in {
      val lower = (i: Int, j: Int) => 1.0 + 0.01 * i + 0.001 * j
      val sp = Space(5, (i, j) => if i > j then lower(i, j) else 100.0 + lower(j, i), 10.0, 1, 17)
      val edges = sp.edgeA.indices.map(e => (sp.edgeA(e), sp.edgeB(e), sp.edgeLength(e)))
      (edges.size must beEqualTo(10)).and(TestResult.foreach(edges)((a, b, l) => l must beEqualTo(lower(b, a))))
    }
  }
