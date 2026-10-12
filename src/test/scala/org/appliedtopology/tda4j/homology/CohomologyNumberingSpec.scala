package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.sset.*
import org.specs2.mutable.Specification

/** `CellularCohomologyEngine` reduces over dense cell numbers; it must give exactly what reducing over the cells
  * themselves gives (`CellKeyedCohomologyReference`): the same bars in the same order, the same pairs, and the same
  * representatives term for term, with the same leading cell. Inputs are tie-heavy (where the tie-break decides the
  * pivots), generic, non-`Double`-filtered, and simplicial sets.
  */
class CohomologyNumberingSpec extends Specification:

  private def same[CellT: OrderedCell, C: Field, F: Ordering](stream: CellStream[CellT, F]): Boolean =
    val (dense, denseOrder) = CellularCohomologyEngine[CellT, C, F]().pairedCohomology(stream)
    val (keyed, _) = CellKeyedCohomologyReference[CellT, C, F]().pairedCohomology(stream)
    given Ordering[CellT] = denseOrder
    def key(entry: (PersistenceBar[F, Chain[CellT, C]], Involution.Pair[CellT])) =
      val (bar, pair) = entry
      val rep = bar.representative
      (bar.dim, bar.lower, bar.upper, pair, rep.terms.toSet, rep.leadingCell)
    // Cycles: the engine's involution runs on cell numbers; the oracle runs it on the cells.
    val engineCycles = CellularCohomologyEngine[CellT, C, F]().persistentHomology(stream, includeZeroLength = true)
    val keyedCycles = Involution
      .cycles[CellT, C](keyed.map(_._2).toIndexedSeq, denseOrder.reverse, (cell, _) => cell.boundary[C])
      .map(_._1)
    val limit = stream match
      case s: StratifiedCellStream[?, ?] => s.homologyDegreeLimit.getOrElse(Int.MaxValue)
      case _                             => Int.MaxValue
    val expectedCycles = keyed.zip(keyedCycles).collect {
      case ((bar, pair), z) if pair.dim <= limit =>
        (bar.dim, bar.lower, bar.upper, z.terms.toSet, z.leadingCell)
    }
    val actualCycles = engineCycles.map { bar =>
      val z = bar.representative
      (bar.dim, bar.lower, bar.upper, z.terms.toSet, z.leadingCell)
    }
    dense.nonEmpty && dense.map(key) == keyed.map(key) && actualCycles == expectedCycles

  private val ff = FiniteField(17)
  import ff.given

  "CellularCohomologyEngine's dense numbering" should {
    "match the cell-keyed reduction on a Vietoris-Rips complex of grid points (many ties)" in {
      val grid = for i <- 0 until 4; j <- 0 until 4 yield Array(i.toDouble, j.toDouble)
      same[Simplex[Int], ff.Fp, Double](
        VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 2, maxFiltrationValue = 2.5)
      ) must beTrue
    }
    "match it on the all-zero torus" in {
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      same[Simplex[Int], ff.Fp, Double](ExplicitStreamBuilder.fromFacets(triangles)) must beTrue
    }
    "match it on a random point cloud, on Cech and on an image" in {
      val rnd = new scala.util.Random(11)
      val cloud = Array.fill(25)(Array(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()))
      val pixels = Array.tabulate(9, 9)((i, j) => ((i * 7 + j * 3) % 5).toDouble)
      (same[Simplex[Int], ff.Fp, Double](VietorisRips(EuclideanMetricSpace(cloud), maxDimension = 2)) must beTrue)
        .and(
          same[Simplex[Int], ff.Fp, Double](Cech(EuclideanMetricSpace(cloud.take(12)), maxDimension = 2)) must beTrue
        )
        .and(
          same[Cube, ff.Fp, Double](
            CubicalImage.fromFlatArray(IndexedSeq(9, 9), pixels.flatten.toIndexedSeq)
          ) must beTrue
        )
    }
    "match it on simplicial sets with Int filtration values, over F3 (RP^2 and RP^4 have 2-torsion)" in {
      val f3 = FiniteField(3)
      import f3.given
      def rp(n: Int): Boolean =
        val space = SimplicialSet.realProjectiveSpace(n)
        given (RealProjectiveGenerator is OrderedCell) = space.cellInstance
        same[RealProjectiveGenerator, f3.Fp, Int](SimplicialSetStream(space))
      (rp(2) must beTrue).and(rp(4) must beTrue)
    }
    "match it over the reals" in {
      given (Double is Field) = Field.DoubleApproximated(1e-9)
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      same[Simplex[Int], Double, Double](ExplicitStreamBuilder.fromFacets(triangles)) must beTrue
    }
  }
