package org.appliedtopology.tda4j

import org.specs2.mutable

/** The naive engine capped at a homological degree (`persistentHomology(stream, k)`, #51): the bars and representatives
  * of degrees `0 .. k` are the uncapped engine's, and a cell of dimension `k + 1` is reduced only while a class of
  * degree `k` is open and keeps only the pairing.
  */
class NaiveCappedSpec extends mutable.Specification:
  val inf = Double.PositiveInfinity

  def cloud(seed: Int, n: Int, ambient: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(seed.toLong)
    Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))

  // A lattice: every edge length and most triangle values tie.
  val grid: Array[Array[Double]] = Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))

  // The unit square: four sides at 1, two diagonals and four triangles at sqrt 2. The sides leave one class of degree 1
  // open, the diagonals two more, and the triangles close three of them: the fourth triangle finds none open.
  val square: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "A naive engine capped at degree k" >> {
    "has the uncapped engine's bars, pairs and representatives up to degree k" >> {
      val results =
        for
          p <- Seq(2, 3, 17)
          (name, pts) <- Seq("cloud 12x3" -> cloud(1, 12, 3), "cloud 16x2" -> cloud(2, 16, 2), "grid" -> grid)
          k <- Seq(0, 1, 2)
          built <- Seq(k, k + 1) // a stream built to exactly the cap, and one dimension higher
        yield
          val field = FiniteField(p)
          import field.given
          val vr = VietorisRips(EuclideanMetricSpace(pts), built)
          val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()
          val full = engine.persistentHomology(vr)
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
      (results.filterNot(_._2).map(_._1) must beEmpty) and (results.map(_._3).sum must beGreaterThan(1000))
    }

    "reduces a top-dimension cell only while a class of degree k is open" >> {
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
      val engine = CellularHomologyEngine[Simplex[Int], field.Fp, Double]()
      val cells = (0 to 2).map(d => vr.iterateDimension(d).size).sum
      val full = engine.persistentHomology(vr)
      full.advanceAll()
      val callsFull = boundaryCalls
      boundaryCalls = 0
      val capped = engine.persistentHomology(vr, 1)
      capped.advanceAll()
      val callsCapped = boundaryCalls
      val bars = capped.diagramAt(inf, includeZeroLength = true).sorted
      (cells must beEqualTo(14)) and
        (callsFull must beEqualTo(14)) and
        (callsCapped must beEqualTo(13)) and // the fourth triangle is not reduced
        (bars must beEqualTo(full.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted)) and
        (bars.filter(_._1 == 1) must beEqualTo(
          List((1, 1.0, math.sqrt(2.0)), (1, math.sqrt(2.0), math.sqrt(2.0)), (1, math.sqrt(2.0), math.sqrt(2.0)))
        ))
    }

    "does less field arithmetic than the uncapped engine" >> {
      val ff = FiniteField(17)
      var operations = 0L
      given counting: (ff.Fp is Field) = new (ff.Fp is Field):
        private val base = ff.fpField
        def plus(x: ff.Fp, y: ff.Fp) =
          operations += 1; base.plus(x, y)
        def minus(x: ff.Fp, y: ff.Fp) =
          operations += 1; base.minus(x, y)
        def times(x: ff.Fp, y: ff.Fp) =
          operations += 1; base.times(x, y)
        def divide(x: ff.Fp, y: ff.Fp) =
          operations += 1; base.divide(x, y)
        def isEqual(x: ff.Fp, y: ff.Fp) = base.isEqual(x, y)
        def negate(x: ff.Fp) = base.negate(x)
        def invert(x: ff.Fp) = base.invert(x)
        def zero = base.zero
        def one = base.one
        def showForSelf = base.showForSelf
      val vr = VietorisRips(EuclideanMetricSpace(cloud(3, 18, 3)), 1)
      val engine = SimplicialHomologyEngine[Int, ff.Fp, Double]()
      engine.persistentHomology(vr).advanceAll()
      val full = operations
      operations = 0
      engine.persistentHomology(vr, 1).advanceAll()
      (full must beGreaterThan(1000L)) and (operations must beLessThan(full))
    }
  }
