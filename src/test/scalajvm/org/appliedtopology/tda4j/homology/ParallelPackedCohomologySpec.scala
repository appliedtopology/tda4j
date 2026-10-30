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
    }

  // Degree 0 is not reduced (union-find over the edges in order), so it is checked against a plain column reduction of
  // the vertices' coboundaries in the same order of edges: youngest vertex first (the smaller id), a column's pivot its
  // oldest edge. A pair is (vertex, rank of the death edge or -1), and a cocycle the V-column, by vertex.
  private def degreeZeroByReduction(sp: Space, p: Int): Seq[(Int, Int, Seq[(Int, Int)])] =
    def inverse(a: Int): Int = (1 until p).find(b => a * b % p == 1).get
    val incident = Array.fill(sp.n)(Seq.newBuilder[(Int, Int)])
    for r <- sp.edgeA.indices do
      // The faces of the edge {a < b}: removing a leaves b with sign +, removing b leaves a with sign -.
      incident(sp.edgeB(r)) += ((r, 1))
      incident(sp.edgeA(r)) += ((r, p - 1))
    val owner = scala.collection.mutable.Map.empty[Int, (Map[Int, Int], Map[Int, Int])]
    def plus(a: Map[Int, Int], b: Map[Int, Int], f: Int): Map[Int, Int] =
      (a.keySet ++ b.keySet).iterator
        .map(k => k -> (a.getOrElse(k, 0) + f * b.getOrElse(k, 0)) % p)
        .filter(_._2 != 0)
        .toMap
    (0 until sp.n).map { v =>
      var column = incident(v).result().toMap
      var cocycle = Map(v -> 1)
      var done = false
      var pair = -1
      while !done do
        if column.isEmpty then done = true
        else
          val pivot = column.keys.min
          owner.get(pivot) match
            case None =>
              owner(pivot) = (column, cocycle)
              pair = pivot
              done = true
            case Some((c, z)) =>
              val f = (p - column(pivot) * inverse(c(pivot)) % p) % p
              column = plus(column, c, f)
              cocycle = plus(cocycle, z, f)
      (v, pair, cocycle.toSeq.sorted)
    }

  private def degreeZeroByEngine(sp: Space, p: Int): Seq[(Int, Int, Seq[(Int, Int)])] =
    val zero = reduce(sp, 3, 4).degrees(0)
    zero.pairs.toSeq
      .map { (column, row) =>
        val cocycle = ParallelPackedCohomology.cocycle(zero, p, column).toSeq.map((k, c) => (sp.index(k).toInt, c))
        (sp.index(column).toInt, if row < 0 then -1 else sp.block(row) - 1, cocycle.sorted)
      }
      .sortBy(_._1)

  private def degreeZeroAgrees(
    metricSpace: FiniteMetricSpace[Int],
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17
  ): TestResult =
    val sp = space(metricSpace, 1, threshold, p)
    firstDifference(degreeZeroByEngine(sp, p), degreeZeroByReduction(sp, p)) must beNone

  // With no ties the order of edges is the reference engine's too: its degree 0 is the same, bar for bar.
  private def degreeZeroMatchesReference(metricSpace: FiniteMetricSpace[Int], threshold: Double, p: Int): TestResult =
    val field = FiniteField(p)
    import field.given
    val sp = space(metricSpace, 1, threshold, p)
    val zero = reduce(sp, 3, 4).degrees(0)
    val mine = zero.bars.indices
      .map { i =>
        val vertices = ParallelPackedCohomology.cocycle(zero, p, zero.barColumns(i)).keys.map(sp.index(_).toInt).toSeq
        (zero.bars(i)._2, zero.bars(i)._3, vertices.sorted)
      }
      .sortBy(b => (b._2, b._3.toString))
    val reference = RipserCohomologyEngine[field.Fp](metricSpace, 0, true, Some(threshold))
      .persistentCohomology()
      .filter(_.dim == 0)
      .map { b =>
        val vertices = b.representative.rawEntries.map(_._1.head).sorted
        (BlockParallelReduction.endpoint(b.lower), BlockParallelReduction.endpoint(b.upper), vertices)
      }
      .sortBy(b => (b._2, b._3.toString))
    firstDifference(mine, reference) must beNone

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

    "give degree 0 by union-find the column reduction's pairs and cocycles on points in general position" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(6, 14))) { pts =>
        val metricSpace = EuclideanMetricSpace(pts)
        val tight = 0.4 * metricSpace.minimumEnclosingRadius
        degreeZeroAgrees(metricSpace)
          .and(degreeZeroAgrees(metricSpace, tight))
          .and(degreeZeroMatchesReference(metricSpace, metricSpace.minimumEnclosingRadius, 17))
          .and(degreeZeroMatchesReference(metricSpace, tight, 3))
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "give degree 0 by union-find the column reduction's pairs and cocycles on tie-heavy metrics, over F_17 and F_2" in
      TestResult.foreach(Seq(17, 2)) { p =>
        degreeZeroAgrees(octagon, p = p)
          .and(degreeZeroAgrees(grid, p = p))
          .and(degreeZeroAgrees(grid, threshold = 1.5, p = p))
          .and(degreeZeroAgrees(integerMetric(9, 7L), p = p))
      }

    "give degree 0 by union-find the column reduction's pairs and cocycles on random integer distances" in
      forAll(Gen.chooseNum(5, 12), Gen.long) { (n, seed) =>
        degreeZeroAgrees(integerMetric(n, seed))
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

    // The cocycle check above reads the reduction logs of degrees 1 and 2 (degree 0 has none): some must exist.
    "log reductions in degrees 1 and 2 on 40 points in the plane" in {
      val rng = scala.util.Random(3)
      val cloud = EuclideanMetricSpace(Array.fill(40)(Array(rng.nextDouble(), rng.nextDouble())))
      reduce(space(cloud, 2, 0.5, 17), 3, 4).degrees.map(_.logs.size).sum must beGreaterThan(0)
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
