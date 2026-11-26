package org.appliedtopology.tda4j

import BlockParallelReductionSpec.{grid, integerMetric, octagon}
import ParallelPackedCohomology.{blockOf, coboundaryOf, cocycle, persistence, BlockOrder, EdgeTies, Result, Space}

import org.specs2.mutable.Specification
import org.specs2.execute.Result as TestResult

/** The order of simplices of one block in `ParallelPackedCohomology` (`BlockOrder`): the order itself, hand-derived;
  * the apparent pairs the engine finds against the definition (the oldest cofacet of a column, whose youngest facet is
  * the column) evaluated on the engine's own keys by brute force, so that a rule derived for one direction and mirrored
  * wrongly for the other is caught; the pairs of every degree against a plain reduction of the coboundary matrix in the
  * same order (degree 0, which the engine pairs by union-find, included); and, as for `EdgeTies`, the bars (which no
  * order may change) and each combination against itself (parallel run = sequential run, apparent pairs from the
  * block's edge = Ripser's way, two-word keys, sparse neighbour lists, cocycles with the coboundary they claim).
  */
class ParallelBlockOrderSpec extends Specification:
  private val orders = BlockOrder.values.toSeq
  private val ties = EdgeTies.values.toSeq
  private val combinations = for t <- ties; o <- orders yield (t, o)

  private def equilateral(n: Int): ExplicitMetricSpace =
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else 1.0))

  // The lattice {0..side-1}^d, Euclidean: ties everywhere.
  private def lattice(side: Int, d: Int = 2): EuclideanMetricSpace =
    EuclideanMetricSpace(
      Array.tabulate(math.pow(side, d).round.toInt)(i =>
        Array.tabulate(d)(k => ((i / math.pow(side, k).round) % side).toDouble)
      )
    )

  // The same distances with the vertex labels reversed.
  private def reversed(metric: FiniteMetricSpace[Int]): ExplicitMetricSpace =
    val n = metric.size
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => metric.distance(n - 1 - i, n - 1 - j)))

  // Apparent pairs, pivots cleared by the degree below and columns left to reduce, per degree from 1.
  private def work(r: Result): Seq[(Int, Int, Int)] =
    r.degrees.drop(1).map(_.stats).map(s => (s.apparent, s.cleared, s.columns - s.cleared - s.apparent))

  private def space(
    metricSpace: FiniteMetricSpace[Int],
    edgeTies: EdgeTies,
    blockOrder: BlockOrder,
    maxDim: Int = 2,
    threshold: Double = Double.PositiveInfinity,
    p: Int = 17,
    ripserApparent: Boolean = false,
    wide: Boolean = false,
    sparse: Boolean = false,
    streams: Boolean = true
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
      edgeTies = edgeTies,
      blockOrder = blockOrder,
      streams = streams
    )

  private def reduce(sp: Space, threads: Int = 1, unitsPerThread: Int = 1): Result =
    Parallel.withThreads(threads)(persistence(sp, threads, unitsPerThread, cocycles = true, keepPairs = true))

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

  // Every simplex of `size` vertices with all its edges within the threshold: (vertices, key).
  private def simplices(sp: Space, size: Int): Seq[(Array[Int], Long)] =
    (0 until sp.n).combinations(size).map(_.toArray).toSeq.collect {
      case vs if vs.indices.forall(i => (i + 1 until size).forall(j => sp.rankOf(vs(i), vs(j)) >= 0)) =>
        (vs, sp.key(blockOf(sp, vs, size), sp.indexOf(vs, size)))
    }

  private def keyOf(sp: Space, vs: Array[Int]): Long = sp.key(blockOf(sp, vs, vs.length), sp.indexOf(vs, vs.length))

  private def facetsOf(vs: Array[Int]): Seq[Array[Int]] = vs.indices.map(i => vs.patch(i, Nil, 1))

  // The pairs of degree `d` by a plain reduction of the coboundary matrix, columns youngest first, pivot the oldest row,
  // the columns that are the pivots of the degree below skipped: (column key, row key or -1) per degree.
  private def plainPairs(sp: Space, maxDim: Int): Seq[Set[(Long, Long)]] =
    val p = sp.p
    var cleared = Set.empty[Long]
    (0 to maxDim).map { d =>
      val k = d + 1
      val owners = scala.collection.mutable.Map.empty[Long, Map[Long, Int]]
      val pairs = Set.newBuilder[(Long, Long)]
      for (_, column) <- simplices(sp, k).sortBy(_._2).reverse if !cleared(column) do
        var col = coboundaryOf(sp, k, Map(column -> 1))
        var row = -1L
        while row < 0 && col.nonEmpty do
          val pivot = col.keys.min
          owners.get(pivot) match
            case None        => row = pivot
            case Some(owner) =>
              val f = (p - col(pivot)) * sp.inverse(owner(pivot)) % p
              col = (col.keySet ++ owner.keySet).iterator
                .map(key => key -> ((col.getOrElse(key, 0) + f * owner.getOrElse(key, 0)) % p))
                .filter(_._2 != 0)
                .toMap
        if row >= 0 then owners(row) = col
        pairs += ((column, row))
      val result = pairs.result()
      cleared = result.collect { case (_, row) if row >= 0 => row }
      result
    }

  // The apparent pairs of degree `d >= 1` by their definition on the engine's keys: the column's oldest cofacet, if it
  // is in the column's block and the column is its youngest facet; the columns that are pivots of the degree below
  // are not tried (clearing).
  private def apparentPairs(sp: Space, d: Int, cleared: Set[Long]): Set[(Long, Long)] =
    val k = d + 1
    simplices(sp, k).flatMap { (vs, column) =>
      val cofacets = coboundaryOf(sp, k, Map(column -> 1)).keys
      Option
        .when(!cleared(column) && cofacets.nonEmpty) {
          val oldest = cofacets.min
          val taus = vertices(sp, oldest, k + 1)
          val youngestFacet = facetsOf(taus).map(keyOf(sp, _)).max
          Option.when(sp.block(oldest) == sp.block(column) && youngestFacet == column)((column, oldest))
        }
        .flatten
    }.toSet

  private def vertices(sp: Space, key: Long, size: Int): Array[Int] =
    val vs = new Array[Int](size)
    sp.decode(sp.index(key), size, vs)
    vs

  // The inputs where ties are everywhere, a few of them small enough for a brute-force reference.
  private val smallInputs = Seq(
    ("octagon", octagon, 2, Double.PositiveInfinity),
    ("grid", grid, 2, Double.PositiveInfinity),
    ("grid, cut", grid, 2, 1.5),
    ("integer distances", integerMetric(8, 7L), 2, Double.PositiveInfinity),
    ("lattice 4x4", lattice(4), 2, 2.1),
    ("equilateral", equilateral(6), 3, Double.PositiveInfinity),
    // Columns of four and five vertices with several vertices outside the block's edge, and many that are not cleared.
    ("equilateral 8, degree 3", equilateral(8), 3, Double.PositiveInfinity),
    ("equilateral 7, degree 4", equilateral(7), 4, Double.PositiveInfinity),
    ("integer distances, degree 3", integerMetric(9, 7L), 3, Double.PositiveInfinity),
    ("lattice 4x4, degree 3", lattice(4), 3, 2.1)
  )

  "BlockOrder" should {
    // Five equidistant points, edges ranked by increasing combinatorial index (Colexicographic): {x < y < z} is in the
    // block of its largest edge {y, z}, and within it by its combinatorial index C(z,3) + C(y,2) + x, so by x.
    //   block of {1,2} {1,3} {2,3}     {1,4}  {2,4}     {3,4}
    "order the simplices of one block by their combinatorial index, in the direction chosen" in {
      val expected = Map(
        BlockOrder.SmallerIndexFirst -> List(
          List(0, 1, 2),
          List(0, 1, 3),
          List(0, 2, 3),
          List(1, 2, 3),
          List(0, 1, 4),
          List(0, 2, 4),
          List(1, 2, 4),
          List(0, 3, 4),
          List(1, 3, 4),
          List(2, 3, 4)
        ),
        BlockOrder.LargerIndexFirst -> List(
          List(0, 1, 2),
          List(0, 1, 3),
          List(1, 2, 3),
          List(0, 2, 3),
          List(0, 1, 4),
          List(1, 2, 4),
          List(0, 2, 4),
          List(2, 3, 4),
          List(1, 3, 4),
          List(0, 3, 4)
        )
      )
      TestResult.foreach(expected.toSeq) { (order, triangles) =>
        TestResult.foreach(Seq(false, true)) { wide =>
          val sp = space(equilateral(5), EdgeTies.Colexicographic, order, wide = wide)
          // Two-word keys name the simplex within its dimension and carry the block beside them.
          val ordered = simplices(sp, 3)
            .sortBy((vs, key) => (blockOf(sp, vs, 3), key))
            .map(_._1.toList)
          (ordered must beEqualTo(triangles)).and(sp.wide must beEqualTo(wide))
        }
      }
    }

    "order the vertices, which share block 0, by index in the direction chosen" in
      TestResult.foreach(Seq(false, true)) { wide =>
        val larger = space(equilateral(5), EdgeTies.Lexicographic, BlockOrder.LargerIndexFirst, wide = wide)
        val smaller = space(equilateral(5), EdgeTies.Lexicographic, BlockOrder.SmallerIndexFirst, wide = wide)
        def order(sp: Space) = simplices(sp, 1).sortBy(_._2).map(_._1.head)
        (order(larger) must beEqualTo(Seq(4, 3, 2, 1, 0))).and(order(smaller) must beEqualTo(Seq(0, 1, 2, 3, 4)))
      }

    "have a key that gives back its index, and index order that is the direction chosen" in
      TestResult.foreach(orders) { order =>
        val sp = space(equilateral(7), EdgeTies.Colexicographic, order)
        val ks = (0 until 30).map(i => sp.key(3, i.toLong))
        (ks.map(sp.index) must beEqualTo((0 until 30).map(_.toLong)))
          .and(ks.sorted == ks must beEqualTo(order == BlockOrder.SmallerIndexFirst))
          .and(ks.sorted.reverse == ks must beEqualTo(order == BlockOrder.LargerIndexFirst))
      }

    // Any order is a refinement of the diameter filtration: the barcode is the packed Ripser engine's.
    "give the packed Ripser engine's bars in every combination with an edge order, over F_17 and F_2" in {
      val inputs = Seq(
        ("octagon", octagon, 2, Double.PositiveInfinity),
        ("grid", grid, 2, Double.PositiveInfinity),
        ("integer distances", integerMetric(9, 7L), 2, Double.PositiveInfinity),
        ("lattice 5x5", lattice(5), 2, 2.3),
        ("equilateral", equilateral(8), 3, Double.PositiveInfinity)
      )
      TestResult.foreach(Seq(17, 2)) { p =>
        TestResult.foreach(inputs) { (name, metric, maxDim, threshold) =>
          val expected = BlockParallelReduction.packedBars(metric, maxDim, threshold, p)
          TestResult.foreach(combinations) { (t, o) =>
            val bars = reduce(space(metric, t, o, maxDim, threshold, p), 3, 4).bars
            firstDifference(bars, expected).aka(s"$name, $t, $o, p = $p") must beNone
          }
        }
      }
    }

    // The definition of an apparent pair, evaluated by brute force on the keys, against the engine's count and pairs.
    "find the apparent pairs the definition gives, in every combination" in
      TestResult.foreach(smallInputs) { (name, metric, maxDim, threshold) =>
        TestResult.foreach(combinations) { (t, o) =>
          val sp = space(metric, t, o, maxDim, threshold)
          val r = reduce(sp)
          val rowsOf = r.degrees.map(_.pairs.collect { case (_, row) if row >= 0 => row }.toSet)
          TestResult.foreach(1 to maxDim) { d =>
            val expected = apparentPairs(sp, d, rowsOf(d - 1))
            val found = r.degrees(d).pairs.toSet
            (r.degrees(d).stats.apparent.aka(s"$name, $t, $o, degree $d: apparent columns") must
              beEqualTo(expected.size))
              .and((expected -- found).take(3).aka("apparent pairs that are not pairs") must beEmpty)
          }
        }
      }

    // A reduction that knows nothing about blocks, apparent pairs, units or union-find: the pairs are the matrix's.
    "pair every degree as a plain reduction of the coboundary matrix does, in every combination" in
      TestResult.foreach(smallInputs) { (name, metric, maxDim, threshold) =>
        TestResult.foreach(combinations) { (t, o) =>
          val sp = space(metric, t, o, maxDim, threshold)
          val expected = plainPairs(sp, maxDim)
          val r = reduce(sp, 3, 4)
          TestResult.foreach(0 to maxDim) { d =>
            val found = r.degrees(d).pairs.toSet
            ((expected(d) -- found).take(3).aka(s"$name, $t, $o, degree $d: missing pairs") must beEmpty)
              .and((found -- expected(d)).take(3).aka(s"$name, $t, $o, degree $d: extra pairs") must beEmpty)
          }
        }
      }

    "reduce in parallel as it does sequentially, find apparent pairs the same two ways, and with two-word keys" in {
      val inputs = Seq(octagon, grid, integerMetric(9, 7L), lattice(4))
      TestResult.foreach(combinations) { (t, o) =>
        TestResult.foreach(inputs) { metric =>
          val sp = space(metric, t, o)
          val (pairs, logs, bars) = byIndex(sp, reduce(sp))
          val parallel = byIndex(sp, reduce(sp, 3, 4))
          val ripserWay = byIndex(sp, reduce(space(metric, t, o, ripserApparent = true)))
          val wide = space(metric, t, o, wide = true)
          val wideRun = byIndex(wide, reduce(wide, 3, 4))
          val sparseRanks = space(metric, t, o, sparse = true)
          val sparse = byIndex(sparseRanks, reduce(sparseRanks, 3, 4))
          val sparseRipser = byIndex(sparseRanks, reduce(space(metric, t, o, ripserApparent = true, sparse = true)))
          (firstDifference(parallel._1, pairs).aka(s"$t, $o: parallel pairs") must beNone)
            .and(firstDifference(parallel._2, logs) must beNone)
            .and(firstDifference(ripserWay._1, pairs).aka(s"$t, $o: Ripser's way") must beNone)
            .and(firstDifference(wideRun._1, pairs) must beNone)
            .and(firstDifference(wideRun._2, logs) must beNone)
            .and(firstDifference(wideRun._3, bars) must beNone)
            .and(wide.wide must beTrue)
            .and(firstDifference(sparse._1, pairs).aka(s"$t, $o: sparse ranks") must beNone)
            .and(firstDifference(sparse._3, bars) must beNone)
            .and(firstDifference(sparseRipser._1, pairs).aka(s"$t, $o: sparse ranks, Ripser's way") must beNone)
        }
      }
    }

    "give cocycles whose coboundary is the reduced column in every order" in
      TestResult.foreach(Seq(17, 3)) { p =>
        TestResult.foreach(orders) { o =>
          val sp = space(grid, EdgeTies.Colexicographic, o, p = p)
          val r = reduce(sp, 3, 1000)
          // the reduced columns, stored by the run that keeps every entry of them (cofacet streams store only V)
          val stored = reduce(space(grid, EdgeTies.Colexicographic, o, p = p, streams = false), 3, 1000)
          TestResult.foreach(r.degrees) { d =>
            val essential = d.essential.toSet
            val wrong = d.logs.keys.toSeq.filterNot { c =>
              val expected =
                if essential(c) then Map.empty[Long, Int]
                else
                  val owner = stored.degrees(d.degree).reduced(c)
                  owner.keys.zip(owner.coefs).toMap
              coboundaryOf(sp, d.degree + 1, cocycle(d, p, c)) == expected
            }
            wrong.take(3) must beEmpty
          }
        }
      }

    // The two directions are two orders of the same simplices, not one order under two names: the same bars, other
    // pairs (where a block holds more than one simplex of a dimension).
    // ubauer/ripser-dev (857c9d0) `ripser-forest`, built with -DCOUNT_WORK, run with `--dim 2 --threshold 10` on the lower
    // triangle of the squared distances of the 12 x 12 lattice, and `--dim 3 --threshold 2` on the 4 x 4 x 4 lattice,
    // reports its `columns` (the columns it reduces: not apparent, not cleared) per --ties; its order is the edge order
    // `--ties` names with the smaller index older inside a block. (Its `random` is another hash than `Shuffled`'s.)
    //     12 x 12: increasing 121, decreasing 221, lexicographic 121     4 x 4 x 4: 108, 162, 108
    "reduce as many columns as ripser-forest does in the orders it shares, with the smaller index older" in {
      val forest = Seq(
        (
          "12 x 12",
          lattice(12),
          2,
          math.sqrt(10.0),
          Seq(EdgeTies.Colexicographic -> 121, EdgeTies.ReverseColexicographic -> 221, EdgeTies.Lexicographic -> 121)
        ),
        (
          "4 x 4 x 4",
          lattice(4, 3),
          3,
          math.sqrt(2.0),
          Seq(EdgeTies.Colexicographic -> 108, EdgeTies.ReverseColexicographic -> 162, EdgeTies.Lexicographic -> 108)
        )
      )
      TestResult.foreach(forest) { (name, metric, maxDim, threshold, expected) =>
        TestResult.foreach(expected) { (t, columns) =>
          val r = reduce(space(metric, t, BlockOrder.SmallerIndexFirst, maxDim, threshold))
          work(r).map(_._3).sum.aka(s"$name, $t: columns left to reduce") must beEqualTo(columns)
        }
      }
    }

    // Bauer's translation (ORDER_CONVENTIONS.md): with the labels reversed, his hybrid's lexicographic order is Ripser's
    // edge-lexicographic order (ripser.cpp's EDGE_LEXICOGRAPHIC, `ReverseColexicographic` with the larger index older),
    // up to the last refinement, which changes neither apparent pairs nor columns reduced.
    "have the lexicographic order with the smaller index older be Ripser's edge-lexicographic order on reversed labels" in {
      val inputs = Seq(
        ("octagon", octagon, 2),
        ("grid", grid, 2),
        ("integer distances", integerMetric(9, 7L), 3),
        ("lattice 6x6", lattice(6), 2),
        ("lattice 3x3x3", lattice(3, 3), 3)
      )
      TestResult.foreach(inputs) { (name, metric, maxDim) =>
        val hybrid = work(reduce(space(metric, EdgeTies.Lexicographic, BlockOrder.SmallerIndexFirst, maxDim)))
        val ripser = work(
          reduce(space(reversed(metric), EdgeTies.ReverseColexicographic, BlockOrder.LargerIndexFirst, maxDim))
        )
        hybrid.aka(name) must beEqualTo(ripser)
      }
    }

    "pair simplices differently in the two directions where blocks hold several simplices" in {
      val sp = (o: BlockOrder) => space(equilateral(7), EdgeTies.Colexicographic, o, maxDim = 2)
      val larger = reduce(sp(BlockOrder.LargerIndexFirst))
      val smaller = reduce(sp(BlockOrder.SmallerIndexFirst))
      def pairsOf(s: Space, r: Result) = r.degrees(2).pairs.map((a, b) => (s.index(a), if b < 0 then b else s.index(b)))
      (larger.bars must beEqualTo(smaller.bars))
        .and(
          pairsOf(sp(BlockOrder.LargerIndexFirst), larger).toSet must
            not(beEqualTo(pairsOf(sp(BlockOrder.SmallerIndexFirst), smaller).toSet))
        )
    }
  }
