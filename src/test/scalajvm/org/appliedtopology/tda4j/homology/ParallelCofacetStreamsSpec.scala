package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{coboundaryOf, cocycle, persistence, BlockOrder, EdgeTies, Result, Space}

import org.specs2.mutable.Specification
import org.specs2.execute.Result as TestResult

/** Working columns kept as lazily generated cofacet streams (`Space.streams`, `CofacetStream`, `StreamColumn`) against
  * the run that writes every entry of every added coboundary into a heap: the pairs, the essential columns, the
  * reduction logs (so every cocycle), the additions and the bars are the same, term for term, in every combination of
  * edge order and direction in a block, over F_17, F_3 and F_2, on one thread and on several; and the column the
  * streams do not store, `R = δV`, is the legacy run's stored one.
  */
class ParallelCofacetStreamsSpec extends Specification:
  private val combinations = for t <- EdgeTies.values.toSeq; o <- BlockOrder.values.toSeq yield (t, o)

  private def equilateral(n: Int): ExplicitMetricSpace =
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else 1.0))

  private def lattice(side: Int, d: Int = 2): EuclideanMetricSpace =
    EuclideanMetricSpace(
      Array.tabulate(math.pow(side, d).round.toInt)(i =>
        Array.tabulate(d)(k => ((i / math.pow(side, k).round) % side).toDouble)
      )
    )

  private def cloud(n: Int, dim: Int, seed: Long): EuclideanMetricSpace =
    val rng = scala.util.Random(seed)
    EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextDouble())))

  private def space(
    metricSpace: FiniteMetricSpace[Int],
    edgeTies: EdgeTies,
    blockOrder: BlockOrder,
    maxDim: Int,
    threshold: Double,
    p: Int,
    streams: Boolean,
    forest: Boolean = false
  ) =
    Space(
      metricSpace.size,
      metricSpace.distance(_, _),
      threshold,
      maxDim,
      p,
      edgeTies = edgeTies,
      blockOrder = blockOrder,
      forestColumns = forest,
      streams = streams
    )

  private def run(sp: Space, threads: Int, unitsPerThread: Int, keepPairs: Boolean = true): Result =
    Parallel.withThreads(threads)(persistence(sp, threads, unitsPerThread, cocycles = true, keepPairs = keepPairs))

  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  private def logs(r: Result) =
    r.degrees.map(d => d.logs.toSeq.map((c, log) => (c, log.columns.toSeq, log.multipliers.toSeq)).sortBy(_._1))

  // The phase an addition falls in depends on the units, their number does not.
  private def additions(r: Result) = r.degrees.map(d => d.stats.adds1 + d.stats.adds2a + d.stats.adds2b)

  // Everything a run reports, as comparable sequences: pairs, bars, essential columns, logs, additions, cocycles.
  private def facts(sp: Space, r: Result): Seq[(String, Seq[Any])] =
    Seq(
      "pairs" -> r.degrees.map(_.pairs.sorted.toSeq),
      "bars" -> r.bars,
      "essential" -> r.degrees.map(_.essential.sorted.toSeq),
      "logs" -> logs(r),
      "additions" -> additions(r),
      "cocycles" -> r.degrees.map(d => d.barColumns.toSeq.sorted.map(c => cocycle(d, sp.p, c).toSeq.sorted))
    )

  private def firstFactDifference(a: Seq[(String, Seq[Any])], b: Seq[(String, Seq[Any])]): Option[String] =
    a.zip(b).flatMap((x, y) => firstDifference(x._2, y._2).map(d => s"${x._1}: $d")).headOption

  private val inputs = Seq(
    ("octagon", octagon, 3, Double.PositiveInfinity),
    ("grid", grid, 3, Double.PositiveInfinity),
    ("grid, cut", grid, 3, 1.5),
    ("integer distances", integerMetric(9, 7L), 3, Double.PositiveInfinity),
    ("lattice 4x4", lattice(4), 3, 2.1),
    ("lattice 3x3x3, cut", lattice(3, 3), 3, 1.5),
    ("equilateral 8", equilateral(8), 4, Double.PositiveInfinity),
    ("points in the plane", cloud(13, 2, 5L), 3, 0.6),
    ("points in R^5", cloud(12, 5, 11L), 4, 1.1)
  )

  "A run on cofacet streams" should {
    "report what the run on heaps reports, in every combination, over F_17, F_3 and F_2, on one thread and several" in
      TestResult.foreach(Seq(17, 3, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          TestResult.foreach(combinations) { (t, o) =>
            val legacy = space(metric, t, o, maxDim, threshold, p, streams = false)
            val streamed = space(metric, t, o, maxDim, threshold, p, streams = true)
            val expected = facts(legacy, run(legacy, 1, 1))
            TestResult.foreach(Seq((1, 1), (3, 1), (3, 4), (3, 1000))) { (threads, units) =>
              firstFactDifference(facts(streamed, run(streamed, threads, units)), expected)
                .aka(s"$name, $t, $o, p = $p, $threads threads, $units units") must beNone
            }
          }
        }
      }

    "give a cocycle whose coboundary is the column the heaps store" in
      TestResult.foreach(Seq(17, 3, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          TestResult.foreach(combinations) { (t, o) =>
            val legacy = space(metric, t, o, maxDim, threshold, p, streams = false)
            val streamed = space(metric, t, o, maxDim, threshold, p, streams = true)
            val stored = run(legacy, 1, 1)
            val r = run(streamed, 3, 4)
            TestResult.foreach(r.degrees) { d =>
              val essential = d.essential.toSet
              val wrong = d.logs.keys.toSeq.filterNot { c =>
                val expected =
                  if essential(c) then Map.empty[Long, Int]
                  else
                    val owner = stored.degrees(d.degree).reduced(c)
                    owner.keys.zip(owner.coefs).toMap
                coboundaryOf(streamed, d.degree + 1, cocycle(d, p, c)) == expected
              }
              wrong.take(3).aka(s"$name, $t, $o, p = $p, degree ${d.degree}") must beEmpty
            }
          }
        }
      }

    "give the bars, the essential columns and the cocycles of the run on heaps that lists every simplex" in
      TestResult.foreach(Seq(17, 3, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          TestResult.foreach(combinations) { (t, o) =>
            val old = space(metric, t, o, maxDim, threshold, p, streams = false, forest = false)
            val current = space(metric, t, o, maxDim, threshold, p, streams = true, forest = true)
            val a = run(old, 1, 1, keepPairs = false)
            val b = run(current, 3, 4, keepPairs = false)
            val bars = firstDifference(b.bars, a.bars)
            val essential =
              firstDifference(b.degrees.map(_.essential.sorted.toSeq), a.degrees.map(_.essential.sorted.toSeq))
            val cocycles = firstDifference(
              b.degrees.map(d => d.barColumns.toSeq.sorted.map(c => cocycle(d, p, c).toSeq.sorted)),
              a.degrees.map(d => d.barColumns.toSeq.sorted.map(c => cocycle(d, p, c).toSeq.sorted))
            )
            (bars.aka(s"$name, $t, $o, p = $p: bars") must beNone)
              .and(essential.aka(s"$name, $t, $o, p = $p: essential") must beNone)
              .and(cocycles.aka(s"$name, $t, $o, p = $p: cocycles") must beNone)
          }
        }
      }

    "store the column V and not the reduced column" in {
      val sp = space(grid, EdgeTies.Lexicographic, BlockOrder.LargerIndexFirst, 2, Double.PositiveInfinity, 17, true)
      val r = run(sp, 1, 1)
      val stored = r.degrees.flatMap(_.reduced.values).filter(_.column != 0L)
      (stored must not(beEmpty)).and(stored.forall(_.keys == null) must beTrue)
    }
  }
