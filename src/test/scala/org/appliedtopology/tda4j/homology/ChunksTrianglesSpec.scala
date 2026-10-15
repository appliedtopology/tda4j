package org.appliedtopology.tda4j

import org.specs2.mutable

/** The chunks engine at `maxDimension = 1`, once union-find has settled degrees 0 and 1 (#51): the triangles are
  * reduced in filtration order and a triangle that enters while no class of degree 1 is open is not reduced at all.
  */
class ChunksTrianglesSpec extends mutable.Specification:
  val inf = Double.PositiveInfinity

  def cloud(seed: Int, n: Int, ambient: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(seed.toLong)
    Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))

  val grid: Array[Array[Double]] = Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))
  val ring: Array[Array[Double]] = Array.tabulate(16)(i => Array(math.cos(i * math.Pi / 8), math.sin(i * math.Pi / 8)))
  // Four sides at 1, two diagonals and four triangles at sqrt 2: three triangles close the three open classes, the fourth
  // finds none open.
  val square: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "The chunks engine's triangles at maxDimension 1" >> {
    "have the naive engine's pairs, bars and representatives" >> {
      val results =
        for
          p <- Seq(2, 3, 17)
          (name, pts, threshold) <- Seq(
            ("cloud 12x3", cloud(1, 12, 3), None),
            ("cloud 16x2", cloud(2, 16, 2), None),
            ("grid", grid, None),
            ("ring, a class left open", ring, Some(0.9)),
            ("square", square, Some(10.0))
          )
        yield
          val field = FiniteField(p)
          import field.given
          val vr =
            threshold.fold(VietorisRips(EuclideanMetricSpace(pts), 1))(t =>
              VietorisRips(EuclideanMetricSpace(pts), 1, t)
            )
          val chunks = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(vr)
          val naive = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(vr)
          val samePairs = chunks.pairing.filter(_.dim <= 1).toSet == naive.pairing.filter(_.dim <= 1).toSet
          val sameBars = chunks.diagramAt(inf, includeZeroLength = true).sorted ==
            naive.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted
          val bars = chunks.barcodeAt(inf, includeZeroLength = true).filter(_.dim == 1)
          val repsAreCycles = bars.forall(bar =>
            bar.annotation.exists(rep =>
              !rep.isZero() && Chain.from(rep.boundary).isZero() && rep.cells.forall(s =>
                vr.filtrationValue(s) <= bar.birth
              )
            )
          )
          (s"$name F_$p", samePairs && sameBars && repsAreCycles, bars.size)
      (results.filterNot(_._2).map(_._1) must beEmpty) and (results.map(_._3).sum must beGreaterThan(200))
    }

    "are not reduced once no class of degree 1 is open" >> {
      // The square's fourth triangle finds no open class. A stream that lacks it costs exactly as many `boundary` calls, so it
      // is not reduced (and costs one more call if the engine reduces it).
      val field = FiniteField(17)
      import field.given
      var boundaryCalls = 0
      val base = simplexIsOrderedCell[Int]()
      def baseBoundary[F: Field](spx: Simplex[Int]): Seq[(Simplex[Int], F)] =
        import base.*
        spx.boundary[F]
      given counting: (Simplex[Int] is OrderedCell) = new (Simplex[Int] is OrderedCell):
        override lazy val ordering = base.ordering
        extension (spx: Simplex[Int])
          override def dim = base.dim(spx)
          override def boundary[CoefficientT: Field as fr]: Seq[(Simplex[Int], CoefficientT)] =
            boundaryCalls += 1
            baseBoundary[CoefficientT](spx)
      val vr = VietorisRips(EuclideanMetricSpace(square), 1, 10.0)
      val triangles = vr.iterateDimension(2).toVector
      val last = triangles.last
      val withoutLast = new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = vr.filtrationValue
        def filtrationOrdering = vr.filtrationOrdering
        val smallest = vr.smallest
        var largest = vr.largest
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case 2                                       => vr.iterateDimension(2).filter(_ != last)
          case d if vr.iterateDimension.isDefinedAt(d) => vr.iterateDimension(d)
        }
      val engine = CellularPersistenceInChunksEngine[Simplex[Int], field.Fp](1)
      def calls(stream: StratifiedCellStream[Simplex[Int], Double]): Int =
        boundaryCalls = 0
        engine.persistentHomology(stream).advanceAll()
        boundaryCalls
      val whole = calls(vr)
      val short = calls(withoutLast)
      (triangles.size must beEqualTo(4)) and (short must beGreaterThan(0)) and (whole must beEqualTo(short))
    }

    "are left to the chunk windows when a cell has no filtration value" >> {
      // The in-order pass compares the values of triangles and edges; a triangle with no value gives it nothing to compare, so
      // the ordinary clear-and-compress path runs. (The naive engine puts a cell with no value first and refuses its faces,
      // so it is no oracle here; degree 0 of this stream is known: three vertices at 0, the edges at 1 join them.)
      val field = FiniteField(17)
      import field.given
      val stream = new ExplicitStream[Int, Double](
        Map(∆(0) -> 0.0, ∆(1) -> 0.0, ∆(2) -> 0.0, ∆(0, 1) -> 1.0, ∆(1, 2) -> 1.0, ∆(0, 2) -> 1.0),
        Seq(∆(0), ∆(1), ∆(2), ∆(0, 1), ∆(1, 2), ∆(0, 2), ∆(0, 1, 2))
      )
      val chunks = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(stream)
      chunks.diagramAt(inf, includeZeroLength = true).filter(_._1 == 0).sorted must beEqualTo(
        List((0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, inf))
      )
    }

    "are reduced against a class that is born at the triangle's own value" >> {
      // The diagonals (positive edges, born at sqrt 2) enter at the value of the triangles. A triangle must see the classes born
      // at its own value as open: counting only those strictly before it reduces too few triangles.
      val field = FiniteField(17)
      import field.given
      val vr = VietorisRips(EuclideanMetricSpace(square), 1, 10.0)
      val chunks = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(vr)
      val bars = chunks.diagramAt(inf, includeZeroLength = true).filter(_._1 == 1).sorted
      val r2 = math.sqrt(2.0)
      bars must beEqualTo(List((1, 1.0, r2), (1, r2, r2), (1, r2, r2)))
    }
  }
