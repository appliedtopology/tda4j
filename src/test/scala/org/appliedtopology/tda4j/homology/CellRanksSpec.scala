package org.appliedtopology.tda4j

import org.specs2.mutable

/** `CellRanks`, the cells of a stream ranked once by the stream's ordering (#51), and what rests on it (#88). */
class CellRanksSpec extends mutable.Specification:
  val inf = Double.PositiveInfinity

  // A lattice: most edge lengths and most triangle values tie, so the tie-break decides most comparisons.
  val grid: Array[Array[Double]] = Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))

  // The reviewers' stream for #88: the cell ∆1 is in the stream with no filtration value. The ordering then puts it by
  // dimension and tie-break alone, which makes the relation cyclic: ∆0 < ∆1 < ∆(0,1) < ∆2 < ∆0 (smaller is younger).
  def stream88: ExplicitStream[Int, Double] =
    new ExplicitStream[Int, Double](
      Map(∆(0) -> 0.0, ∆(2) -> 1.0, ∆(0, 1) -> 2.0),
      Seq(∆(0), ∆(1), ∆(2), ∆(0, 1))
    )

  // `Simplex` is opaque, so `eq` needs the cells as objects.
  def same(a: Simplex[Int], b: Simplex[Int]): Boolean = a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]

  def ranksOf(stream: StratifiedCellStream[Simplex[Int], Double]): (Vector[Simplex[Int]], CellRanks[Simplex[Int]]) =
    val cells = stream.iterator.toVector
    (cells, CellRanks(cells, cells.zipWithIndex.toMap, stream.filtrationOrdering, stream.filtrationValue.isDefinedAt))

  "CellRanks" >> {
    "orders every pair of cells as the stream's ordering does, the stream's own objects and fresh equal ones alike" >> {
      val vr = VietorisRips(EuclideanMetricSpace(grid), 2)
      val (cells, ranks) = ranksOf(vr)
      val order = vr.filtrationOrdering
      def fresh(c: Simplex[Int]): Simplex[Int] = ∆(c.toSeq*)
      val sample = cells.zipWithIndex.collect { case (c, i) if i % 17 == 0 => c }
      val disagreements =
        for
          x <- sample
          y <- sample
          (fx, fy) <- Seq((x, y), (fresh(x), y), (x, fresh(y)), (fresh(x), fresh(y)))
          if Integer.signum(ranks.ordering.compare(fx, fy)) != Integer.signum(order.compare(fx, fy))
        yield (fx, fy)
      (ranks.ranked must beTrue) and (sample.size must beGreaterThan(40)) and (disagreements must beEmpty)
    }

    "gives the stream's own object for a cell equal to it" >> {
      val vr = VietorisRips(EuclideanMetricSpace(grid), 1)
      val (cells, ranks) = ranksOf(vr)
      val c = cells(cells.size / 2)
      val copy = ∆(c.toSeq*)
      (same(copy, c) must beFalse) and (same(ranks.canonical(copy), c) must beTrue) and
        (ranks.canonical(∆(99, 100)) must beEqualTo(∆(99, 100)))
    }

    "does not rank cells when one has no filtration value, and then is the stream's ordering" >> {
      val stream = stream88
      val (cells, ranks) = ranksOf(stream)
      (stream.filtrationValue.isDefinedAt(∆(1)) must beFalse) and
        (ranks.ranked must beFalse) and
        (ranks.ordering must beTheSameAs(stream.filtrationOrdering)) and
        (same(ranks.canonical(cells.head), cells.head) must beTrue)
    }

    "runs the chunks and naive engines to the end on that stream, and they agree" >> {
      // Neither ranks the cells here (above), so both use the stream's ordering as it is. The example does not tell ranks from
      // no ranks (the engines share `CellRanks`, so they cannot disagree about it): the example above does. It checks that a
      // stream whose ordering is not transitive still runs, capped and uncapped.
      val field = FiniteField(17)
      import field.given
      val stream = stream88
      val chunks = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(stream)
      val naive = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(stream)
      val naiveCapped = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(stream, 1)
      (chunks.pairing.filter(_.dim <= 1).toSet must beEqualTo(naive.pairing.filter(_.dim <= 1).toSet)) and
        (naiveCapped.pairing.toSet must beEqualTo(naive.pairing.filter(_.dim <= 1).toSet)) and
        (chunks.diagramAt(inf, includeZeroLength = true).sorted must beEqualTo(
          naive.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted
        ))
    }
  }
