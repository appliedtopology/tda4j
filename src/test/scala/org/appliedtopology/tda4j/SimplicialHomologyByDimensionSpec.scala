package org.appliedtopology.tda4j

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck

/** Cross-validation for `SimplicialHomologyByDimensionContext` -- the MST/Kruskal-based engine (see `Homology.scala`'s
  * class doc and CLAUDE.md's "Persistent homology" section). Confirmed non-functional before this session:
  * `HomologyState`'s constructor threw `NoSuchElementException` unconditionally on any complex with an MST edge (an
  * unguarded `barcode(0)` read on a map initialized empty), and separately lacked the
  * `given Ordering[Simplex[VertexT]] = stream.filtrationOrdering` every other engine needs (see
  * `CellularHomologyContext`'s own class doc) -- without it, chain arithmetic silently falls back to the generic
  * lexicographic `Simplex is OrderedCell` ordering instead of filtration order. Both are now fixed; this spec is the
  * regression suite that class never had, checking the fixed engine against hand-verified fixtures AND against
  * `SimplicialHomologyContext` (the already-cross-validated reference engine) on random Vietoris-Rips complexes -- not
  * just "does it run without crashing."
  */
class SimplicialHomologyByDimensionSpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given Ordering[Int] = Ordering.Int

  private def stratifiedStream(cells: Seq[(Double, Simplex[Int])]): StratifiedSimplexStream[Int, Double] =
    val fvMap: Map[Simplex[Int], Double] = cells.map((f, s) => s -> f).toMap
    new StratifiedSimplexStream[Int, Double]:
      val smallest = Double.NegativeInfinity
      val largest = Double.PositiveInfinity
      def filtrationValue: PartialFunction[Simplex[Int], Double] = fvMap
      override def filtrationOrdering: Ordering[Simplex[Int]] =
        FilteredSimplexOrdering[Int, Double](this)(using summon[Ordering[Int]])(using summon[Ordering[Double]].reverse)
      // Every dimension's bucket MUST be sorted by filtrationOrdering.reverse, not just by the fixture's own
      // listed order -- on a fixture with genuinely distinct filtration values the two happen to coincide (an
      // author naturally lists cells in increasing fv order), which is exactly why this went unnoticed until
      // a fully-tied fixture (every cell at fv 0.0) exposed it: with no fv differences to fall back on, the
      // bucket order is then driven entirely by tie-break direction, and the fixture's own (human-friendly,
      // ascending-lex) listing order does not match filtrationOrdering.reverse's tie-break direction. See
      // EnumeratingCofaceSimplexStream's own filtrationOrdering doc and CLAUDE.md's "Bug found while
      // cross-validating" section for why iteration order and filtrationOrdering must be the exact same total
      // order (one the consistent .reverse of the other), not just each independently a valid total order.
      private val byDim: Map[Int, Vector[Simplex[Int]]] =
        cells.map(_._2).toVector.groupBy(_.dim).view.mapValues(_.sorted(using filtrationOrdering.reverse)).toMap
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if byDim.contains(d) => byDim(d).iterator
      }

  /** Drives a `SimplicialHomologyByDimensionContext#HomologyState` to completion and reads off the full diagram
    * (finished bars from `barcode`, essential/still-open bars from whatever remains in `cycles`) -- there is no
    * `diagramAt`-style accessor on this engine (unlike `CellularHomologyContext`), so this reconstructs the same shape
    * `SimplicialHomologyContext.diagramAt` returns directly from the two maps `advanceTo` leaves behind.
    */
  private def fullDiagram(
    shc: SimplicialHomologyByDimensionContext[Int, Double]
  )(hs: shc.HomologyState, maxDim: Int): List[(Int, Double, Double)] =
    hs.advanceTo(maxDim, Double.PositiveInfinity)
    val finished: List[(Int, Double, Double)] =
      hs.barcode.toList.flatMap { case (dim, q) => q.toList.map { case (lo, hi, _) => (dim, lo, hi) } }
    val essential: List[(Int, Double, Double)] =
      hs.cycles.toList.map { case (cell, _) =>
        (
          cell.dim,
          hs.stream.filtrationValue.applyOrElse(hs.cyclesBornBy(cell), (_: Simplex[Int]) => Double.NegativeInfinity),
          Double.PositiveInfinity
        )
      }
    finished ++ essential

  "SimplicialHomologyByDimensionContext reproduces hand-verified fixtures" >> {
    def check(cells: Seq[(Double, Simplex[Int])], expected: List[(Int, Double, Double)], maxDim: Int) =
      val shc = SimplicialHomologyByDimensionContext[Int, Double]()
      val hs = shc.persistentHomology(stratifiedStream(cells))
      fullDiagram(shc)(hs, maxDim) must containTheSameElementsAs(expected)

    check(HomologyFixtures.triangleCells, HomologyFixtures.triangleExpected, 2)
    check(HomologyFixtures.tetrahedronCells, HomologyFixtures.tetrahedronExpected, 3)
    check(HomologyFixtures.torusCells, HomologyFixtures.torusExpected, 2)
    check(HomologyFixtures.elderRuleCells, HomologyFixtures.elderRuleExpected, 1)
    check(
      HomologyFixtures.tetrahedronBoundaryDegenerateCells,
      HomologyFixtures.tetrahedronBoundaryDegenerateExpected,
      2
    )
  }

  "SimplicialHomologyByDimensionContext satisfies the bars-account-for-cells invariant" >> {
    val cases = List(
      (HomologyFixtures.triangleCells, 2),
      (HomologyFixtures.tetrahedronCells, 3),
      (HomologyFixtures.torusCells, 2),
      (HomologyFixtures.elderRuleCells, 1)
    )
    forall(cases) { (cells, maxDim) =>
      val shc = SimplicialHomologyByDimensionContext[Int, Double]()
      val hs = shc.persistentHomology(stratifiedStream(cells))
      val dgm = fullDiagram(shc)(hs, maxDim)
      HomologyFixtures.totalBarsAccountForAllCells(dgm, cells.size) must beTrue
    }
  }

  "SimplicialHomologyByDimensionContext agrees with SimplicialHomologyContext on random Vietoris-Rips complexes" >> {
    val boundedMaxDim = 3
    "agreement" ==> forAll(matrixGen[Double](Gen.double, Gen.chooseNum(1, 4), Gen.chooseNum(4, 12))) {
      (points: Array[Array[Double]]) =>
        val metricSpace = EuclideanMetricSpace(points)
        val naiveStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), boundedMaxDim)
        val naive = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(naiveStream)
          .diagramAt(Double.PositiveInfinity)

        val mstStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), boundedMaxDim)
        val shc = SimplicialHomologyByDimensionContext[Int, Double]()
        val hs = shc.persistentHomology(mstStream)
        val viaMST = fullDiagram(shc)(hs, boundedMaxDim)

        "MST-based and naive engines agree" ==> (viaMST must containTheSameElementsAs(naive))
    }
  }
