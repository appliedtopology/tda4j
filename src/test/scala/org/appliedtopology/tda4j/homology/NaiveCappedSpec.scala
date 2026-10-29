package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.sset.*
import org.specs2.mutable

/** The naive engine capped at a homological degree (`persistentHomology(stream, k)`, #51): the bars and representatives
  * of degrees `0 .. k` are the uncapped engine's (the cursor on a stream with no declared degree limit), and a cell of
  * dimension `k + 1` is reduced only while a class of degree `k` is open and keeps only the pairing. A stream with a
  * declared limit is read up to it, whether the cap is given or not.
  */
class NaiveCappedSpec extends mutable.Specification:
  val inf = Double.PositiveInfinity

  def cloud(seed: Int, n: Int, ambient: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(seed.toLong)
    Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))

  // The same cells, order and values with no declared degree limit: what the whole-stream cursor reads to the top,
  // where `VietorisRips(.., k)` itself is read up to degree k.
  def unlimited(stream: StratifiedCellStream[Simplex[Int], Double]): StratifiedCellStream[Simplex[Int], Double] =
    new StratifiedCellStream[Simplex[Int], Double] with DoubleFiltration[Simplex[Int]]:
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = stream.iterateDimension
      def filtrationOrdering: Ordering[Simplex[Int]] = stream.filtrationOrdering
      def filtrationValue: PartialFunction[Simplex[Int], Double] = stream.filtrationValue

  // A lattice: every edge length and most triangle values tie.
  val grid: Array[Array[Double]] = Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))

  // The unit square: four sides at 1, two diagonals and four triangles at sqrt 2. The sides leave one class of degree 1
  // open, the diagonals two more, and the triangles close three of them: the fourth triangle finds none open.
  val square: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "A naive engine capped at degree k" >> {
    "has the uncapped engine's bars, pairs and representatives up to degree k" >> {
      // The three primes are three algebras (F_2 has no signs, F_3 tells orientations apart); the platform difference
      // shows over one, so Scala.js takes F_3.
      val primes = TestPlatform.perPlatform(jvm = Seq(2, 3, 17), js = Seq(3))
      val results =
        for
          p <- primes
          (name, pts) <- Seq("cloud 12x3" -> cloud(1, 12, 3), "cloud 16x2" -> cloud(2, 16, 2), "grid" -> grid)
          k <- Seq(0, 1, 2)
          built <- Seq(k, k + 1) // a stream built to exactly the cap, and one dimension higher
        yield
          val field = FiniteField(p)
          import field.given
          val vr = VietorisRips(EuclideanMetricSpace(pts), built)
          val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
          val full = engine.persistentHomology(unlimited(vr))
          full.advanceAll()
          val capped = engine.persistentHomology(vr, k)
          capped.advanceAll()
          val want = full.diagramWithGeneratorsAt(inf, includeZeroLength = true).filter(_._1 <= k)
          val got = capped.diagramWithGeneratorsAt(inf, includeZeroLength = true)
          var rest = got
          val sameBars = want.size == got.size && want.forall { case (d, l, u, rep) =>
            val i = rest.indexWhere { case (d2, l2, u2, rep2) => d2 == d && l2 == l && u2 == u && rep2 == rep }
            i >= 0 && { rest = rest.patch(i, Nil, 1); true }
          }
          val samePairs = capped.pairing.toSet == full.pairing.filter(_.dim <= k).toSet
          val ok = sameBars && samePairs && got.forall(_._1 <= k)
          (s"$name F_$p k=$k built=$built", ok, want.size)
      (results.filterNot(_._2).map(_._1) must beEmpty) and (results.map(_._3).sum must beGreaterThan(300 * primes.size))
    }

    "reduces a top-dimension cell only while a class of degree k is open" >> {
      // The square: after the sides and diagonals, three triangles close the three open classes of degree 1 and the
      // fourth finds none. Reducing it costs field operations; skipping it costs none.
      val field = FiniteField(17)
      var operations = 0L
      given counting: (field.Fp is Field) = countingField(field)(() => operations += 1)
      val vr = VietorisRips(EuclideanMetricSpace(square), 1, 10.0)
      val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
      def operationsOfLastCell(state: CellularHomologyEngine[Simplex[Int], field.Fp, Double]#HomologyState): Long =
        while state.processedCells < state.totalCells - 1 do state.advanceOne()
        val before = operations
        state.advanceOne()
        operations - before
      val full = engine.persistentHomology(unlimited(vr))
      val capped = engine.persistentHomology(vr, 1)
      val fullLast = operationsOfLastCell(full)
      val cappedLast = operationsOfLastCell(capped)
      val bars = capped.diagramAt(inf, includeZeroLength = true).sorted
      (full.totalCells must beEqualTo(14)) and
        (fullLast must beGreaterThan(0L)) and
        (cappedLast must beEqualTo(0L)) and
        (bars must beEqualTo(full.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted)) and
        (bars.filter(_._1 == 1) must beEqualTo(
          List((1, 1.0, math.sqrt(2.0)), (1, math.sqrt(2.0), math.sqrt(2.0)), (1, math.sqrt(2.0), math.sqrt(2.0)))
        ))
    }

    "does less field arithmetic than the uncapped engine" >> {
      val ff = FiniteField(17)
      var operations = 0L
      given counting: (ff.Fp is Field) = countingField(ff)(() => operations += 1)
      val vr = VietorisRips(EuclideanMetricSpace(cloud(3, 18, 3)), 1)
      val engine = SimplicialHomologyEngine[Int, ff.Fp, Double]()
      engine.persistentHomology(unlimited(vr)).advanceAll()
      val full = operations
      operations = 0
      engine.persistentHomology(vr, 1).advanceAll()
      (full must beGreaterThan(1000L)) and (operations must beLessThan(full))
    }
  }

  // A finite field that calls `tick` on every plus, minus, times and divide.
  def countingField(ff: FiniteField)(tick: () => Unit): ff.Fp is Field = new (ff.Fp is Field):
    private val base = ff.fpField
    def plus(x: ff.Fp, y: ff.Fp) =
      tick(); base.plus(x, y)
    def minus(x: ff.Fp, y: ff.Fp) =
      tick(); base.minus(x, y)
    def times(x: ff.Fp, y: ff.Fp) =
      tick(); base.times(x, y)
    def divide(x: ff.Fp, y: ff.Fp) =
      tick(); base.divide(x, y)
    def isEqual(x: ff.Fp, y: ff.Fp) = base.isEqual(x, y)
    def negate(x: ff.Fp) = base.negate(x)
    def invert(x: ff.Fp) = base.invert(x)
    def zero = base.zero
    def one = base.one
    def showForSelf = base.showForSelf

  // Both lists hold the same elements, as multisets (a Set would merge equal chains, and the counts must agree too).
  def sameMultiset[A](want: Seq[A], got: Seq[A]): Boolean =
    var rest = got
    want.size == got.size && want.forall { a =>
      val i = rest.indexWhere(_ == a)
      i >= 0 && { rest = rest.patch(i, Nil, 1); true }
    }

  "A naive engine capped at degree k, asked part-way and at the edges" >> {
    "answers a query at an intermediate value as the uncapped engine does, on a cursor that has not run to the end" >> {
      val results =
        for
          (name, pts, p) <- Seq(
            ("cloud 12x3", cloud(1, 12, 3), 17),
            ("cloud 16x2", cloud(2, 16, 2), 3),
            ("grid", grid, 2)
          )
          k <- Seq(0, 1)
        yield
          val field = FiniteField(p)
          import field.given
          val vr = VietorisRips(EuclideanMetricSpace(pts), k + 1)
          val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
          val values = engine
            .persistentHomology(vr)
            .diagramAt(inf, includeZeroLength = true)
            .flatMap((_, b, d) => Seq(b, d))
            .filter(_.isFinite)
            .distinct
            .sorted
          val queries = values.zipWithIndex.collect { case (v, i) if i % 4 == 0 => v } :+ (values.last + 1.0)
          val full = engine.persistentHomology(unlimited(vr))
          val capped = engine.persistentHomology(vr, k)
          val failing = queries.filterNot { f =>
            // The same two cursors, queried in increasing order: each query leaves them part-way.
            sameMultiset(
              full.diagramAt(f, includeZeroLength = true).filter(_._1 <= k),
              capped.diagramAt(f, includeZeroLength = true)
            ) && sameMultiset(
              full.diagramWithGeneratorsAt(f, includeZeroLength = true).filter(_._1 <= k),
              capped.diagramWithGeneratorsAt(f, includeZeroLength = true)
            )
          }
          (s"$name F_$p k=$k", failing, queries.size)
      (results.filter(_._2.nonEmpty).map(r => (r._1, r._2)) must beEmpty) and (results.map(_._3).sum must beGreaterThan(
        30
      ))
    }

    "lets a cell above the cap that enters last decide which classes are essential" >> {
      // A filled triangle: vertices at 0, edges at 1, the triangle at 5. Capped at degree 0 the triangle is not read, but
      // it is still the last cell the stream will ever produce, so a class alive at 3 is cut off there, not essential.
      val field = FiniteField(17)
      import field.given
      val stream = ExplicitStreamBuilder.fromFilteredFacets(
        Seq(0, 1, 2).map(v => (0.0, ∆(v))) ++
          Seq((1.0, ∆(0, 1)), (1.0, ∆(1, 2)), (1.0, ∆(0, 2)), (5.0, ∆(0, 1, 2)))
      )
      val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
      val full = engine.persistentHomology(stream)
      val capped = engine.persistentHomology(stream, 0)
      (capped.lastFiltrationValue must beEqualTo(Some(5.0))) and
        (capped.lastFiltrationValue must beEqualTo(full.lastFiltrationValue)) and
        (capped.diagramAt(3.0, includeZeroLength = true).sorted must beEqualTo(
          full.diagramAt(3.0, includeZeroLength = true).filter(_._1 <= 0).sorted
        )) and
        (capped.diagramAt(3.0).filter(_._3 == 3.0) must beEqualTo(List((0, 0.0, 3.0))))
    }

    "does not drop every cell for the largest cap" >> {
      val field = FiniteField(17)
      import field.given
      val vr = VietorisRips(EuclideanMetricSpace(square), 1, 10.0)
      val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
      val full = engine.persistentHomology(unlimited(vr))
      val capped = engine.persistentHomology(unlimited(vr), Int.MaxValue)
      (capped.totalCells must beEqualTo(14)) and
        (capped.diagramAt(inf, includeZeroLength = true).sorted must beEqualTo(
          full.diagramAt(inf, includeZeroLength = true).sorted
        ))
    }

    "still fails on a stream that lists a cell before one of its faces, as the uncapped engine does" >> {
      // Non-monotone, built by hand (`fromFilteredFacets` would refuse it): the triangle 012 at 2 before its edge 02 at 3.
      // No class of degree 1 is open when the triangle arrives, so the capped cursor does not reduce it; the cursor has to
      // notice that a face of it has not been seen, or it reports a class born at 3 that the triangle fills.
      val field = FiniteField(17)
      import field.given
      val builder = ExplicitStreamBuilder[Int, Double]
      builder.addAll(Seq(0, 1, 2).map(v => (0.0, ∆(v))))
      builder.addAll(Seq((1.0, ∆(0, 1)), (1.0, ∆(1, 2)), (2.0, ∆(0, 1, 2)), (3.0, ∆(0, 2))))
      val stream = builder.result()
      val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
      // A triangle of a simplicial COMPLEX whose edges are not in the stream: its boundary names cells that never come.
      val noEdges = ExplicitStreamBuilder[Int, Double]
      noEdges.addAll(Seq(0, 1, 2).map(v => (0.0, ∆(v))))
      noEdges.addOne((1.0, ∆(0, 1, 2)))
      val withoutEdges = noEdges.result()
      (engine.persistentHomology(stream).advanceAll() must throwAn[IllegalStateException]) and
        (engine.persistentHomology(stream, 1).advanceAll() must throwAn[IllegalStateException]) and
        (engine.persistentHomology(withoutEdges).advanceAll() must throwAn[IllegalStateException]) and
        (engine.persistentHomology(withoutEdges, 1).advanceAll() must throwAn[IllegalStateException])
    }

    "does not mistake a 2-simplex with no 1-simplices for a malformed stream when its faces are degenerate" >> {
      // The minimal 2-sphere, S^2 = Delta^2 / boundary: one vertex and one 2-simplex, all three edges degenerate, so the
      // 2-simplex has no faces to name. It is one of the first examples of a simplicial set, and it is a valid complex.
      val sphere = SimplicialSet.sphere(2)
      import sphere.given
      val field = FiniteField(17)
      import field.given
      val stream = sphere.filtered {
        case MinimalSphereGenerator.Vertex => 0.0
        case MinimalSphereGenerator.Top    => 1.0
      }
      val engine = CellularHomologyEngine[MinimalSphereGenerator, field.Fp, Double]()
      val full = engine.persistentHomology(stream)
      val capped1 = engine.persistentHomology(stream, 1) // the 2-simplex is a top cell, with no class of degree 1 open
      val capped2 = engine.persistentHomology(stream, 2) // the 2-simplex is a class of degree 2
      val capped0 = engine.persistentHomology(stream, 0) // the 2-simplex is not read at all
      (full.diagramAt(inf).sorted must beEqualTo(List((0, 0.0, inf), (2, 1.0, inf)))) and
        (capped1.diagramAt(inf).sorted must beEqualTo(List((0, 0.0, inf)))) and
        (capped2.diagramAt(inf).sorted must beEqualTo(List((0, 0.0, inf), (2, 1.0, inf)))) and
        (capped0.diagramAt(inf).sorted must beEqualTo(List((0, 0.0, inf))))
    }
  }

  "PersistenceEngine.naive (the MATLAB and command-line path)" >> {
    "is capped at the stream's homologyDegreeLimit and agrees with the uncapped cursor up to it" >> {
      val results =
        for
          (name, pts, p) <- Seq(
            ("cloud 12x3", cloud(1, 12, 3), 17),
            ("cloud 16x2", cloud(2, 16, 2), 3),
            ("grid", grid, 2)
          )
          k <- Seq(0, 1, 2)
        yield
          val field = FiniteField(p)
          import field.given
          val vr = VietorisRips(EuclideanMetricSpace(pts), k)
          val full = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(unlimited(vr))
          full.advanceAll()
          val cycles = sameMultiset(
            full.barcodeAt(inf, includeZeroLength = true).filter(_.dim <= k),
            PersistenceEngine.naive[Simplex[Int], field.Fp].barcode(vr, includeZeroLength = true)
          )
          val cocycles = sameMultiset(
            Involution.cocycleBars[Simplex[Int], field.Fp](vr, full.pairing, vr.filtrationOrdering.reverse, true),
            PersistenceEngine.naiveCocycles[Simplex[Int], field.Fp].barcode(vr, includeZeroLength = true)
          )
          val noneAbove = PersistenceEngine.naive[Simplex[Int], field.Fp].barcode(vr, true).forall(_.dim <= k)
          (s"$name F_$p k=$k", cycles && cocycles && noneAbove)
      results.filterNot(_._2).map(_._1) must beEmpty
    }

    "reads a complete complex whole, top-dimension classes included" >> {
      // The boundary of a tetrahedron, its triangles entering one by one: an essential class of degree 2 is born at 4.
      val field = FiniteField(17)
      import field.given
      val stream = ExplicitStreamBuilder.fromFilteredFacets(
        Seq((1.0, ∆(0, 1, 2)), (2.0, ∆(0, 1, 3)), (3.0, ∆(0, 2, 3)), (4.0, ∆(1, 2, 3)))
      )
      val full = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(stream)
      full.advanceAll()
      val bars = PersistenceEngine.naive[Simplex[Int], field.Fp].barcode(stream, includeZeroLength = true)
      (stream.homologyDegreeLimit must beNone) and
        (bars.exists(_.dim == 2) must beTrue) and
        (sameMultiset(full.barcodeAt(inf, includeZeroLength = true), bars) must beTrue)
    }
  }
