package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The dense-numbering experiment must be the general cohomology engine's algorithm exactly: the same bars and the same
  * cocycles, term for term, on tie-heavy and generic inputs.
  */
class DenseCohomologySpec extends Specification:
  private val ff = FiniteField(17)
  import ff.given

  private def same[CellT: OrderedCell](stream: StratifiedCellStream[CellT, Double]): Boolean =
    val general =
      CellularCohomologyEngine[CellT, ff.Fp, Double]().persistentCohomology(stream, includeZeroLength = true)
    val dense = DenseCohomologyEngine[CellT, ff.Fp]().persistentCohomology(stream, includeZeroLength = true)
    def key(b: PersistenceBar[Double, Chain[CellT, ff.Fp]]) = (b.toTriple, b.representative.terms.toSet)
    general.size == dense.size && general.map(key).toSet == dense.map(key).toSet

  "DenseCohomologyEngine" should {
    "match the general engine on a Vietoris-Rips complex of grid points (many ties)" in {
      val grid = for i <- 0 until 4; j <- 0 until 4 yield Array(i.toDouble, j.toDouble)
      same(VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 2, maxFiltrationValue = 2.5)) must beTrue
    }
    "match it on the all-zero torus" in {
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      same(ExplicitStreamBuilder.fromFacets(triangles)) must beTrue
    }
    "match it on a random point cloud and on an image" in {
      val rnd = new scala.util.Random(11)
      val cloud = Array.fill(25)(Array(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()))
      val pixels = Array.tabulate(9, 9)((i, j) => ((i * 7 + j * 3) % 5).toDouble)
      (same(VietorisRips(EuclideanMetricSpace(cloud), maxDimension = 2)) must beTrue)
        .and(same(CubicalImage.fromFlatArray(IndexedSeq(9, 9), pixels.flatten.toIndexedSeq)) must beTrue)
    }
  }
