package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** Zero-length bars `[v, v)` -- a cell paired with one entering at the same filtration value -- are left out of every
  * engine's output by default, and come back with `includeZeroLength = true`.
  *
  * Two things a wrong implementation gets wrong, each pinned here:
  *   - filtering AFTER truncation: a class born at exactly `f` and alive there is reported as `(dim, f, f)` by
  *     `diagramAt(f)`, which looks zero-length but is a real class (`diagramAt(0.0)` on `n` points must still give `n`
  *     classes in degree 0);
  *   - filtering a different set of bars in different engines: the default output must be exactly the full output
  *     minus its zero-length bars, engine by engine.
  */
class ZeroLengthBarsSpec extends Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  // Unit square: edges at 1, both diagonals at sqrt(2), together with all four triangles. In degrees <= 1 the only
  // zero-length bars are the two H1 classes the diagonals create and two triangles kill, all at sqrt(2).
  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
  private val squareSpace = EuclideanMetricSpace(square)
  private val r2 = math.sqrt(2.0)
  private def vr = VietorisRips(squareSpace, 1, 3.0)

  private def triples[A](bars: List[PersistenceBar[Double, A]]): List[(Int, Double, Double)] =
    bars.map(_.toTriple).sorted
  private def lowDegrees(ts: List[(Int, Double, Double)]) = ts.filter(_._1 <= 1).sorted

  private val squareDefault = List((0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, Double.PositiveInfinity))
    .appended((1, 1.0, r2))
    .sorted
  private val squareAll = (squareDefault ++ List((1, r2, r2), (1, r2, r2))).sorted

  /** The default barcode is the full one minus its zero-length bars, and the full one has some. */
  private def consistent[A](default: List[PersistenceBar[Double, A]], all: List[PersistenceBar[Double, A]]) =
    (default.exists(_.isZeroLength) must beFalse) and
      (all.exists(_.isZeroLength) must beTrue) and
      (triples(default) must beEqualTo(triples(all.filterNot(_.isZeroLength))))

  "the naive engine" should {
    "leave zero-length bars out by default and return them on request" in {
      val s = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(vr)
      (lowDegrees(s.diagramAt(Double.PositiveInfinity)) must beEqualTo(squareDefault)) and
        (lowDegrees(s.diagramAt(Double.PositiveInfinity, includeZeroLength = true)) must beEqualTo(squareAll))
    }
    "keep a class born at exactly f in diagramAt(f): n points give n classes at 0" in {
      val s = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(vr)
      (s.diagramAt(0.0).count(_._1 == 0) must beEqualTo(4)) and
        (s.barcodeAt(0.0).count(b => b.dim == 0 && !b.isZeroLength) must beEqualTo(4))
    }
    "report a class alive at f as closed at f, a finished bar as open" in {
      val bars = SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(vr).barcodeAt(1.0)
      (bars.count(_.upper == ClosedEndpoint(1.0)) must beEqualTo(2)) and // the surviving H0 class and the new H1 class
        (bars.count(_.upper == OpenEndpoint(1.0)) must beEqualTo(3)) //    the three H0 classes merged at 1
    }
  }

  "the chunks engine" should {
    "leave zero-length bars out by default and return them on request" in {
      val s = CellularPersistenceInChunksEngine[Simplex[Int], Double](1).persistentHomology(vr)
      (s.diagramAt(Double.PositiveInfinity).sorted must beEqualTo(squareDefault)) and
        (s.diagramAt(Double.PositiveInfinity, includeZeroLength = true).sorted must beEqualTo(squareAll)) and
        consistent(s.barcodeAt(Double.PositiveInfinity), s.barcodeAt(Double.PositiveInfinity, includeZeroLength = true))
    }
    "keep a class born at exactly f in diagramAt(f)" in {
      val s = CellularPersistenceInChunksEngine[Simplex[Int], Double](1).persistentHomology(vr)
      (s.diagramAt(0.0).count(_._1 == 0) must beEqualTo(4)) and
        (s.barcodeAt(0.0).count(b => b.dim == 0 && !b.isZeroLength) must beEqualTo(4))
    }
    "drop a zero-length H0 bar from duplicate points (the union-find path)" in {
      val dup = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(0.0, 0.0), Array(1.0, 0.0)))
      val s = CellularPersistenceInChunksEngine[Simplex[Int], Double](1).persistentHomology(VietorisRips(dup, 1, 3.0))
      (s.diagramAt(Double.PositiveInfinity).filter(_._1 == 0).sorted must beEqualTo(
        List((0, 0.0, 1.0), (0, 0.0, Double.PositiveInfinity))
      )) and
        (s.diagramAt(Double.PositiveInfinity, includeZeroLength = true).count(_ == (0, 0.0, 0.0)) must beEqualTo(1))
    }
  }

  "the cohomology and Ripser engines" should {
    "leave zero-length bars out by default and return them on request" in {
      val coh = CellularCohomologyEngine[Simplex[Int], Double, Double]()
      def limited = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(squareSpace, maxFiltrationValue = Some(3.0)), 2)
      val ripser = RipserCohomologyEngine[Double](squareSpace, 1, maxFiltrationValue = Some(3.0))
      val packed = PackedRipserCohomologyEngine[Double](squareSpace, 1, maxFiltrationValue = Some(3.0))
      consistent(coh.persistentCohomology(limited), coh.persistentCohomology(limited, includeZeroLength = true)) and
        (lowDegrees(triples(coh.persistentCohomology(limited))) must beEqualTo(squareDefault)) and
        consistent(ripser.persistentCohomology(), ripser.persistentCohomology(includeZeroLength = true)) and
        (triples(ripser.persistentCohomology()) must beEqualTo(squareDefault)) and
        consistent(packed.persistentCohomology(), packed.persistentCohomology(includeZeroLength = true)) and
        (triples(packed.persistentCohomology()) must beEqualTo(squareDefault))
    }
  }

  "the fast cubical engine" should {
    "drop a plateau's zero-length bars by default" in {
      val plateau = CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0)
      val engine = FastCubicalHomologyEngine[Double]()
      consistent(engine.persistentHomology(plateau), engine.persistentHomology(plateau, includeZeroLength = true)) and
        (triples(engine.persistentHomology(plateau)) must beEqualTo(
          List((0, 0.0, Double.PositiveInfinity), (1, 0.0, 1.0))
        ))
    }
  }

  "constructions whose face and coface values come from different computations" should {
    "give exactly equal values where they are mathematically equal (no bars of length 1e-16)" in {
      def nearZero(stream: StratifiedCellStream[Simplex[Int], Double]) =
        SimplicialHomologyEngine[Int, Double, Double]()
          .persistentHomology(stream)
          .barcodeAt(Double.PositiveInfinity)
          .count(b => b.persistence > 0 && b.persistence < 1e-9)
      (1 to 5).map { seed =>
        val rnd = new scala.util.Random(seed)
        val points = Array.fill(25)(Array(rnd.nextDouble(), rnd.nextDouble()))
        (nearZero(Cech(EuclideanMetricSpace(points), 1)) must beEqualTo(0)) and
          (nearZero(AlphaShapes(points, AlphaBackend.DQP)) must beEqualTo(0))
      }.reduce(_ and _)
    }
  }

  "the Persistence verb" should {
    "leave zero-length bars out by default, return them on request, and keep classes alive at f in at(f)" in {
      val d = Persistence(square, maxDimension = 1, maxFiltrationValue = 3.0)
      val all = Persistence(square, maxDimension = 1, maxFiltrationValue = 3.0, includeZeroLength = true)
      (d.triples.sorted must beEqualTo(squareDefault)) and
        (all.triples.sorted must beEqualTo(squareAll)) and
        (d.at(0.0).dim(0).size must beEqualTo(4))
    }
  }

  "the MATLAB/CLI facade" should {
    "leave zero-length bars out of the result (not merely hidden) unless includeZeroLength is set" in {
      val options = Array("maxDimension", "1", "maxFiltrationValue", "3.0", "minPersistence", "0")
      val default = matlab.TDA4j.computeFromPoints(square, options)
      val all = matlab.TDA4j.computeFromPoints(square, options ++ Array("includeZeroLength", "true"))
      (default.toArray().map(_.toSeq).toList.sortBy(r => (r(0), r(1), r(2))) must beEqualTo(
        squareDefault.map((d, b, e) => Seq(d.toDouble, b, e))
      )) and
        (default.hiddenCount() must beEqualTo(0)) and
        (all.size() must beEqualTo(squareAll.size)) and
        (matlab.TDA4j.computeFromPoints(square, Array("maxDimension", "1", "maxFiltrationValue", "3.0")).hiddenCount()
          must beEqualTo(0))
    }
  }

  "short-bar filtering" should {
    "be one call on a diagram and on an engine's bar list, keeping essential bars" in {
      val d = Persistence(square, maxDimension = 1, maxFiltrationValue = 3.0)
      val bars = CellularPersistenceInChunksEngine[Simplex[Int], Double](1)
        .persistentHomology(vr)
        .barcodeAt(Double.PositiveInfinity)
      (d.longerThan(0.5).triples.sorted must beEqualTo(
        List((0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, Double.PositiveInfinity))
      )) and
        (bars.longerThan(0.5).map(_.toTriple).sorted must beEqualTo(d.longerThan(0.5).triples.sorted)) and
        (d.longerThan(10.0).triples must beEqualTo(List((0, 0.0, Double.PositiveInfinity))))
    }
  }
