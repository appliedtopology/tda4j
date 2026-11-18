package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{coboundaryOf, cocycle, persistence, EdgeTies, Result, Space}

import org.specs2.mutable.Specification
import org.specs2.execute.Result as TestResult

/** How `ParallelPackedCohomology` ranks edges of one length (`EdgeTies`): the order itself, hand-derived; the bars,
  * which no order may change; each order against itself (parallel run = sequential run, apparent pairs from the block's
  * edge = Ripser's way, two-word keys, cocycles with the coboundary they claim); and the work counts of
  * `ReverseColexicographic` against `ripser.cpp` built with `EDGE_LEXICOGRAPHIC -DCOUNT_WORK` (ubauer/ripser-dev,
  * 857c9d0), which is the same order: a wrong rank or a wrong block changes the number of apparent pairs and nothing
  * else, so only that count can say the order is Bauer's.
  */
class ParallelEdgeTiesSpec extends Specification:
  private val all = EdgeTies.values.toSeq

  private def equilateral(n: Int): ExplicitMetricSpace =
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else 1.0))

  // The lattice {0..side-1}^2, Euclidean: ties everywhere (distances sqrt of a sum of two squares).
  private def lattice(side: Int): EuclideanMetricSpace =
    EuclideanMetricSpace((for x <- 0 until side; y <- 0 until side yield Array(x.toDouble, y.toDouble)).toArray)

  private def space(
    metricSpace: FiniteMetricSpace[Int],
    ties: EdgeTies,
    maxDim: Int = 2,
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17,
    ripserApparent: Boolean = false,
    wide: Boolean = false,
    sparse: Boolean = false
  ) =
    Space(
      metricSpace.size,
      metricSpace.distance(_, _),
      threshold,
      maxDim,
      p,
      ripserApparent,
      forceWide = wide,
      forceSparseRanks = sparse,
      edgeTies = ties
    )

  private def reduce(sp: Space, threads: Int = 1, unitsPerThread: Int = 1): Result =
    Parallel.withThreads(threads)(persistence(sp, threads, unitsPerThread, cocycles = true, keepPairs = true))

  private def edges(sp: Space): List[(Int, Int)] = sp.edgeA.indices.map(r => (sp.edgeA(r), sp.edgeB(r))).toList

  private def firstDifference[A](a: Seq[A], b: Seq[A]): Option[String] =
    Option.when(a != b) {
      val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
      s"sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
    }

  // The pairs, the logs and the bars of a run, by combinatorial index so that two-word keys compare.
  private def byIndex(sp: Space, r: Result) =
    def i(key: Long) = if key < 0 then key else sp.index(key)
    (
      r.degrees.map(_.pairs.map((a, b) => (i(a), i(b))).sorted.toSeq),
      r.degrees.map(d =>
        d.logs.toSeq.map((c, log) => (i(c), log.columns.toSeq.map(i), log.multipliers.toSeq)).sortBy(_._1)
      ),
      r.bars
    )

  // Apparent pairs, and columns left for the reduction, per degree: (apparent, cleared, left to reduce).
  private def work(r: Result): Seq[(Int, Int, Int)] =
    r.degrees.map(_.stats).map(s => (s.apparent, s.cleared, s.columns - s.cleared - s.apparent))

  "EdgeTies" should {
    // Six edges of length 1 on four points. An edge {a < b} has combinatorial index C(b, 2) + a: 01 = 0, 02 = 1, 12 = 2,
    // 03 = 3, 13 = 4, 23 = 5, so colexicographic is that order and the reverse is its reverse; lexicographic compares
    // the smaller end first.
    "rank edges of one length by the chosen order" in {
      val expected = Map(
        EdgeTies.Lexicographic -> List((0, 1), (0, 2), (0, 3), (1, 2), (1, 3), (2, 3)),
        EdgeTies.ReverseLexicographic -> List((2, 3), (1, 3), (1, 2), (0, 3), (0, 2), (0, 1)),
        EdgeTies.Colexicographic -> List((0, 1), (0, 2), (1, 2), (0, 3), (1, 3), (2, 3)),
        EdgeTies.ReverseColexicographic -> List((2, 3), (1, 3), (0, 3), (1, 2), (0, 2), (0, 1))
      )
      TestResult.foreach(expected.toSeq) { (ties, order) =>
        edges(space(equilateral(4), ties)) must beEqualTo(order)
      }
    }

    "rank by length first whatever the order, and keep every edge once" in {
      // Lengths 1, 2 and 3 in a pattern that no order of the ends can reproduce.
      val metric = integerMetric(7, 5L)
      TestResult.foreach(all) { ties =>
        val sp = space(metric, ties)
        val lengths = sp.edgeLength.toSeq
        (lengths must beEqualTo(lengths.sorted))
          .and(edges(sp).toSet.size must beEqualTo(21))
          .and(edges(sp).map((a, b) => metric.distance(b, a)) must beEqualTo(lengths))
      }
    }

    "rank the edges of one length by a fixed hash for Shuffled, unlike every other order" in {
      val sp = space(equilateral(9), EdgeTies.Shuffled)
      val others = all.filter(_ != EdgeTies.Shuffled).map(t => edges(space(equilateral(9), t)))
      (edges(sp) must beEqualTo(edges(space(equilateral(9), EdgeTies.Shuffled))))
        .and(edges(sp).toSet.size must beEqualTo(36))
        .and(others.contains(edges(sp)) must beFalse)
    }

    // Any order is a refinement of the diameter filtration: the barcode is the packed Ripser engine's.
    "give the packed Ripser engine's bars in every order, over F_17 and F_2" in {
      val inputs = Seq(
        ("octagon", octagon, 2, Double.PositiveInfinity),
        ("grid", grid, 2, Double.PositiveInfinity),
        ("grid, cut", grid, 2, 1.5),
        ("integer distances", integerMetric(9, 7L), 2, Double.PositiveInfinity),
        ("lattice 5x5", lattice(5), 2, 2.3),
        ("equilateral", equilateral(8), 3, Double.PositiveInfinity)
      )
      TestResult.foreach(Seq(17, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          val expected = BlockParallelReduction.packedBars(metric, maxDim, threshold, p)
          TestResult.foreach(all) { ties =>
            val bars = reduce(space(metric, ties, maxDim, threshold, p), 3, 4).bars
            firstDifference(bars, expected).aka(s"$name, $ties, p = $p") must beNone
          }
        }
      }
    }

    // The checks of ParallelPackedCohomologySpec, once per order on the tie-heavy inputs.
    "reduce in parallel as it does sequentially, find apparent pairs the same two ways, and with two-word keys, in every order" in {
      val inputs = Seq(octagon, grid, integerMetric(9, 7L), lattice(4))
      TestResult.foreach(all) { ties =>
        TestResult.foreach(inputs) { metric =>
          val sp = space(metric, ties)
          val sequential = byIndex(sp, reduce(sp))
          val (pairs, logs, bars) = sequential
          val parallel = byIndex(sp, reduce(sp, 3, 4))
          val ripserWay = byIndex(sp, reduce(space(metric, ties, ripserApparent = true)))
          val wide = space(metric, ties, wide = true)
          val wideRun = byIndex(wide, reduce(wide, 3, 4))
          val sparseRanks = space(metric, ties, sparse = true)
          val sparse = byIndex(sparseRanks, reduce(sparseRanks, 3, 4))
          (firstDifference(parallel._1, pairs) must beNone)
            .and(firstDifference(parallel._2, logs) must beNone)
            .and(firstDifference(ripserWay._1, pairs) must beNone)
            .and(firstDifference(wideRun._1, pairs) must beNone)
            .and(firstDifference(wideRun._2, logs) must beNone)
            .and(firstDifference(wideRun._3, bars) must beNone)
            .and(wide.wide must beTrue)
            .and(firstDifference(sparse._1, pairs) must beNone)
            .and(firstDifference(sparse._3, bars) must beNone)
        }
      }
    }

    "give cocycles whose coboundary is the reduced column in every order" in
      TestResult.foreach(Seq(17, 3)) { p =>
        TestResult.foreach(all) { ties =>
          val sp = space(grid, ties, p = p)
          val r = reduce(sp, 3, 1000)
          TestResult.foreach(r.degrees) { d =>
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
      }

    // Degree 0 is the elder rule over the edges in rank order: whatever the order, its bars are the distinct-length ones.
    "pair degree 0 in an order that need not be the lexicographic one" in {
      val metric = integerMetric(10, 3L)
      val expected = reduce(space(metric, EdgeTies.Lexicographic), 1, 1).degrees(0).bars.map(b => (b._2, b._3)).sorted
      TestResult.foreach(all) { ties =>
        reduce(space(metric, ties), 3, 4).degrees(0).bars.map(b => (b._2, b._3)).sorted must beEqualTo(expected)
      }
    }

    // The sequential packed engine on the ranks of the edges is the same order as the block-parallel engine: the same
    // bars (in lengths) and the same number of apparent pairs, which an order that ranked differently, or a block that
    // disagreed with the rank, would change. (Their counts of column additions are counted differently: not compared.)
    "have the sequential Ripser engine on edge ranks find the parallel engine's apparent pairs and bars, in every order" in {
      val field = FiniteField(17)
      import field.given
      val inputs = Seq(octagon, grid, integerMetric(9, 7L), lattice(6))
      TestResult.foreach(all) { ties =>
        TestResult.foreach(inputs) { metric =>
          val parallel = reduce(space(metric, ties))
          val ranked = ParallelPackedCohomology.EdgeRankedMetricSpace(metric, Double.PositiveInfinity, ties)
          val engine = PackedRipserCohomologyEngine[field.Fp](ranked, 2, maxFiltrationValue = Some(ranked.limit))
          val bars = engine
            .persistentCohomology()
            .map(b =>
              (
                b.dim,
                ranked.lengthOf(BlockParallelReduction.endpoint(b.lower)),
                ranked.lengthOf(BlockParallelReduction.endpoint(b.upper))
              )
            )
            .filter(b => b._2 != b._3)
            .sorted
          (firstDifference(bars, parallel.bars) must beNone)
            .and(
              engine.apparentPairCount.aka(s"apparent pairs, $ties, ${metric.size} points") must
                beEqualTo(parallel.degrees.drop(1).map(_.stats.apparent).sum)
            )
        }
      }
    }

    // ripser.cpp (-DEDGE_LEXICOGRAPHIC -DCOUNT_WORK) on the lower-triangular matrix of squared distances of the 12 x 12
    // lattice, `--dim 2 --threshold 10`, prints per dimension
    //   1: simplices 1998 apparent 1734 cleared 0 columns 121      2: simplices 11568 apparent 11447 cleared 121 columns 0
    // Its "apparent" counts the simplices that are in an apparent pair either way round, and its "cleared" only the
    // pivots that are not: in degree 2 the 11447 are the 9713 columns with an apparent cofacet plus the 1734 triangles
    // that are the apparent cofacet of an edge, and the 121 are the other pivots of degree 1 (here, apparent pairs and
    // pivots are counted as columns of their own degree, and every pivot of the degree below is cleared: 143 spanning
    // tree edges, and 1734 + 121 triangles). Upstream Ripser's order, the diameter and then the index, has 10876 simplices
    // in apparent pairs and 571 columns in degree 2.
    "have ReverseColexicographic apparent pairs and columns as ripser.cpp's edge-lexicographic order has" in {
      val sp = space(lattice(12), EdgeTies.ReverseColexicographic, maxDim = 2, threshold = math.sqrt(10.0))
      val r = reduce(sp)
      (r.degrees.map(_.stats.columns) must beEqualTo(Seq(144, 1998, 11568)))
        .and(work(r).drop(1) must beEqualTo(Seq((1734, 143, 121), (9713, 1734 + 121, 0))))
    }

    // The other orders find fewer, on the same lattice: they are not the same order under another name. (Measured with
    // this build; pinned so that an option that is ignored shows.)
    "find different numbers of apparent pairs in different orders on the 12 x 12 lattice" in {
      val counts = all.map { ties =>
        ties -> work(reduce(space(lattice(12), ties, maxDim = 2, threshold = math.sqrt(10.0))))(2)._1
      }.toMap
      (counts(EdgeTies.ReverseColexicographic) must beEqualTo(9713))
        .and(counts.values.toSet.size must beGreaterThan(1))
    }
  }
