package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{blockOf, coboundaryOf, cocycle, persistence, BlockOrder, EdgeTies, Result, Space}

import org.specs2.mutable.Specification
import org.specs2.execute.Result as TestResult

/** The columns `ForestColumns` finds in place of listing every simplex of a degree (`Space.forestColumns`): the set
  * itself against the definition evaluated by brute force on the engine's keys (every simplex that is neither the facet
  * of an apparent pair nor the cofacet of one in the degree below), and a whole run against the run that lists the
  * simplices: bars, cocycles, essential columns, additions and columns left to reduce, in every combination of edge
  * order and direction in a block, over F_17, F_3 and F_2, on one thread and on several.
  */
class ParallelForestColumnsSpec extends Specification:
  private val orders = BlockOrder.values.toSeq
  private val ties = EdgeTies.values.toSeq
  private val combinations = for t <- ties; o <- orders yield (t, o)

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
    p: Int = 17,
    forest: Boolean = true
  ) =
    Space(
      metricSpace.size,
      metricSpace.distance(_, _),
      threshold,
      maxDim,
      p,
      edgeTies = edgeTies,
      blockOrder = blockOrder,
      forestColumns = forest
    )

  private def run(sp: Space, threads: Int, unitsPerThread: Int): Result =
    Parallel.withThreads(threads)(persistence(sp, threads, unitsPerThread, cocycles = true))

  // Every simplex of `size` vertices with all its edges within the threshold: (vertices, key).
  private def simplices(sp: Space, size: Int): Seq[(Array[Int], Long)] =
    (0 until sp.n).combinations(size).map(_.toArray).toSeq.collect {
      case vs if vs.indices.forall(i => (i + 1 until size).forall(j => sp.rankOf(vs(i), vs(j)) >= 0)) =>
        (vs, sp.key(blockOf(sp, vs, size), sp.indexOf(vs, size)))
    }

  private def keyOf(sp: Space, vs: Array[Int]): Long = sp.key(blockOf(sp, vs, vs.length), sp.indexOf(vs, vs.length))

  private def vertices(sp: Space, key: Long, size: Int): Array[Int] =
    val vs = new Array[Int](size)
    sp.decode(sp.index(key), size, vs)
    vs

  // The apparent pairs of degree `d >= 1` by their definition on the engine's keys: the oldest cofacet of the column, if
  // it is in the column's block and the column is its youngest facet. Clearing is not applied.
  private def apparentPairs(sp: Space, d: Int): Set[(Long, Long)] =
    val k = d + 1
    simplices(sp, k).flatMap { (_, column) =>
      val cofacets = coboundaryOf(sp, k, Map(column -> 1)).keys
      Option
        .when(cofacets.nonEmpty) {
          val oldest = cofacets.min
          val tau = vertices(sp, oldest, k + 1)
          val youngestFacet = tau.indices.map(i => keyOf(sp, tau.patch(i, Nil, 1))).max
          Option.when(sp.block(oldest) == sp.block(column) && youngestFacet == column)((column, oldest))
        }
        .flatten
    }.toSet

  private def same[A](a: A, b: A): Option[String] = Option.when(a != b)("differ")

  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  // Ties everywhere, and a generic cloud; small enough for the brute-force definition.
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

  "ForestColumns" should {
    "find the simplices that are neither apparent facets nor the minimal child of an apparent pair" in
      TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
        TestResult.foreach(combinations) { (t, o) =>
          val sp = space(metric, t, o, maxDim, threshold)
          val found = ForestColumns.compute(sp, 1)
          TestResult.foreach(1 to maxDim) { d =>
            val below = if d == 1 then Set.empty[Long] else apparentPairs(sp, d - 1).map(_._2)
            val apparent = apparentPairs(sp, d).map(_._1)
            val expected = simplices(sp, d + 1).map(_._2).filterNot(c => apparent(c) || below(c)).sorted
            firstDifference(found(d).toSeq, expected).aka(s"$name, $t, $o, degree $d") must beNone
          }
        }
      }

    "find the same columns in tasks over ranges of edges" in
      TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
        TestResult.foreach(combinations) { (t, o) =>
          val sp = space(metric, t, o, maxDim, threshold)
          val whole = ForestColumns.compute(sp, 1)
          val split = ForestColumns.compute(sp, 3, splitAt = 1)
          TestResult.foreach(1 to maxDim) { d =>
            firstDifference(split(d).toSeq, whole(d).toSeq).aka(s"$name, $t, $o, degree $d") must beNone
          }
        }
      }
  }

  "A run by the forest" should {
    // The bars with their cocycles, the essential columns, the additions and the columns left to reduce.
    def summary(sp: Space, r: Result) =
      (
        r.degrees.flatMap(d => d.bars.indices.map(i => (d.bars(i), cocycle(d, sp.p, d.barColumns(i))))).toSet,
        r.degrees.map(_.essential.toSet),
        r.degrees.map(d => d.stats.adds1 + d.stats.adds2a + d.stats.adds2b),
        r.degrees.drop(1).map(s => s.stats.columns - s.stats.cleared - s.stats.apparent)
      )

    "equal the run that lists the simplices, in every combination, over F_17, F_3 and F_2, on one thread and several" in
      TestResult.foreach(Seq(17, 3, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          TestResult.foreach(combinations) { (t, o) =>
            val listed = space(metric, t, o, maxDim, threshold, p, forest = false)
            val forest = space(metric, t, o, maxDim, threshold, p)
            val expected = summary(listed, run(listed, 1, 1))
            val one = summary(forest, run(forest, 1, 1))
            val several = summary(forest, run(forest, 3, 4))
            val label = s"$name, $t, $o, p = $p"
            (same(one._1, expected._1).aka(s"$label: bars and cocycles") must beNone)
              .and(same(one._2, expected._2).aka(s"$label: essential") must beNone)
              .and(same(one._3, expected._3).aka(s"$label: additions") must beNone)
              .and(same(one._4, expected._4).aka(s"$label: columns left") must beNone)
              .and(same(several._1, expected._1).aka(s"$label: bars and cocycles, 3 threads") must beNone)
              .and(same(several._2, expected._2).aka(s"$label: essential, 3 threads") must beNone)
              .and(same(several._4, expected._4).aka(s"$label: columns left, 3 threads") must beNone)
          }
        }
      }

    "not list a level: the apparent columns are never visited" in {
      val sp = space(lattice(6), EdgeTies.Colexicographic, BlockOrder.SmallerIndexFirst, 3, 5.1)
      val r = run(sp, 1, 1)
      (r.degrees.drop(1).map(_.stats.apparent) must beEqualTo(Seq(0, 0, 0)))
        .and(r.degrees(3).stats.columns must beLessThan(100))
    }
  }
