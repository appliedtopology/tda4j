package org.appliedtopology.tda4j

import Persistence.Engine

import SpecThrows.throwWith

import org.specs2.mutable.Specification

/** Persistent homology over the rational numbers. The oracles: the projective plane, whose `Z/2` class is torsion and
  * so is not there over `Q` (nor over `Z/3`); and the bars of random clouds, which over `Q` are those over a large
  * prime where no torsion of the complex has that prime as a factor, for every engine; the representatives are closed
  * exactly.
  */
class RationalPersistenceSpec extends Specification:

  private val rationals = Coefficients.Rationals

  /** The 6-vertex triangulation of the real projective plane: vertices at 0, edges at 1, triangles at 2. */
  private def projectivePlane =
    val triangles = List(
      List(1, 2, 3),
      List(1, 3, 4),
      List(1, 4, 5),
      List(1, 5, 6),
      List(1, 6, 2),
      List(2, 3, 5),
      List(3, 4, 6),
      List(4, 5, 2),
      List(5, 6, 3),
      List(6, 2, 4)
    )
    val edges = triangles.flatMap(t => t.combinations(2).toList).map(_.sorted).distinct
    val builder = ExplicitStreamBuilder[Int, Double]()
    (1 to 6).foreach(v => builder += (0.0 -> Simplex(v)))
    edges.foreach(e => builder += (1.0 -> Simplex.from(e)))
    triangles.foreach(t => builder += (2.0 -> Simplex.from(t)))
    builder.result()

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()))

  private val engines = List(Engine.Cohomology, Engine.Naive, Engine.Chunks)

  "persistent homology over the rationals" should {
    "not see the torsion class of the projective plane that Z/2 sees" in {
      val plane = projectivePlane
      val failures = for
        engine <- engines
        (name, diagram) <- List(
          "Z/2" -> Persistence(plane, maxDimension = 2, characteristic = 2, engine = engine),
          "Z/3" -> Persistence(plane, maxDimension = 2, characteristic = 3, engine = engine),
          "Q" -> Persistence(plane, maxDimension = 2, coefficients = rationals, engine = engine)
        )
        expected = if name == "Z/2" then Vector(1, 1, 1) else Vector(1, 0, 0)
        if diagram.bettiNumbers.toVector != expected
      yield s"$engine over $name: ${diagram.bettiNumbers}"
      failures must beEmpty
    }

    "have the bars of a large prime where there is no torsion, for every engine, either kind of representative" in {
      val failures = for
        seed <- List(1L, 2L)
        points = cloud(14, seed)
        engine <- List(Engine.Ripser, Engine.Cohomology, Engine.Naive, Engine.Chunks)
        kind <- List(Representatives.Cycles, Representatives.Cocycles)
        exact = Persistence(points, maxDimension = 2, coefficients = rationals, engine = engine, representatives = kind)
        modular = Persistence(points, maxDimension = 2, characteristic = 10007, engine = engine, representatives = kind)
        if exact.triples.sorted != modular.triples.sorted
      yield s"seed $seed $engine/$kind"
      failures must beEmpty
    }

    "give cycles that are closed exactly, and cells of the pairing" in {
      val points = cloud(14, 3L)
      val diagram = Persistence(points, maxDimension = 2, coefficients = rationals)
      import diagram.given
      val closed = diagram.bars.forall(bar => Chain.from(bar.representative.boundary).isZero())
      // 1/2 is 0.5 in the diagram's field: not a residue (the default characteristic would make it 9 = -8 mod 17)
      val half = diagram.coefficientField.divide(
        diagram.coefficientField.one,
        diagram.coefficientField.plus(diagram.coefficientField.one, diagram.coefficientField.one)
      )
      (diagram.bars.size must beGreaterThan(10)) and (closed must beTrue) and
        (diagram.coefficientField.toDouble(half) must beEqualTo(0.5)) and
        (diagram.bars.forall(_.cells.isDefined) must beTrue)
    }

    "refuse a characteristic that disagrees with the coefficients it is given alongside" in {
      Persistence(cloud(5, 1L), characteristic = 3, coefficients = rationals) must throwWith[IllegalArgumentException](
        "disagree"
      )
    }

    "refuse a map between a diagram over the rationals and one over the reals" in {
      val points = cloud(6, 4L)
      def diagram(coefficients: Optional[Coefficients]) =
        Persistence(
          points,
          maxDimension = 1,
          coefficients = coefficients,
          includeZeroLength = true,
          maxFiltrationValue = 5.0
        )
      PersistenceMap
        .simplicial(diagram(rationals), diagram(Coefficients(0)))(identity) must throwWith[IllegalArgumentException](
        "different fields of characteristic 0"
      )
    }

    "carry maps: the inclusion of a subsample, over the rationals" in {
      val points = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
      def diagram(ps: Array[Array[Double]]) =
        Persistence(ps, maxDimension = 1, maxFiltrationValue = 2.5, coefficients = rationals, includeZeroLength = true)
      val map = PersistenceMap.simplicial(
        diagram(points.grouped(1).zipWithIndex.collect { case (p, i) if i % 4 == 0 => p.head }.toArray),
        diagram(points)
      )(i => 4 * i)
      (map.image(1).size must beEqualTo(1)) and (map.entries(1).map(_._3.toString).distinct must beEqualTo(List("1"))
        .or(
          beEqualTo(List("-1"))
        ))
    }
  }
