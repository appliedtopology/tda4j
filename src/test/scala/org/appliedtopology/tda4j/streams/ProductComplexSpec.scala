package org.appliedtopology.tda4j

import Persistence.Engine
import ProductComplex.Combine

import org.specs2.mutable.Specification

import scala.collection.mutable

/** The product of two filtered complexes. Oracles: `∂∂ = 0` over a field with signs, the square and the torus with
  * known answers, and the persistent Künneth theorem: the bars of the product, computed by an engine on the product
  * cells, are the ones `Barcode.kunneth` predicts from the bars of the factors (which share no code with the stream).
  */
class ProductComplexSpec extends Specification:

  private val infinity = Double.PositiveInfinity
  private type Cell = ProductCell[Simplex[Int], Simplex[Int]]

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def rips(points: Array[Array[Double]], maxDimension: Int = 2) =
    VietorisRips(EuclideanMetricSpace(points), maxDimension = maxDimension, maxFiltrationValue = 100.0)

  /** The complex with the given simplices entering at the given values. */
  private def explicit(entries: (Double, Simplex[Int])*): StratifiedCellStream[Simplex[Int], Double] =
    val builder = ExplicitStreamBuilder[Int, Double]()
    entries.foreach(builder += _)
    builder.result()

  // two points joined by an edge that enters at 1, and the cycle of four edges on a square all entering at 1
  private val segment = explicit(
    (0.0, Simplex(0)),
    (0.0, Simplex(1)),
    (1.0, Simplex(0, 1))
  )
  private val circle = explicit(
    (0.0, Simplex(0)),
    (0.0, Simplex(1)),
    (0.0, Simplex(2)),
    (0.0, Simplex(3)),
    (1.0, Simplex(0, 1)),
    (1.0, Simplex(1, 2)),
    (1.0, Simplex(2, 3)),
    (1.0, Simplex(0, 3))
  )

  private def triples(d: PersistenceDiagram[?]) = d.triples.sorted

  /** A bar's numbers rounded, so that equal sums of the same values compare equal whatever the order of addition. */
  private def rounded(bars: Seq[(Int, Double, Double)]) =
    def r(x: Double) = if x.isInfinite then x else math.round(x * 1e9) / 1e9
    bars.map((k, b, d) => (k, r(b), r(d))).sorted

  "the cells of a product" should {
    "have the boundary of a product of two edges: four edges with the signs of the Leibniz rule" in {
      val coefficients = Coefficients.Rationals
      import coefficients.given
      val cells = summon[Cell is OrderedCell]
      val fr = summon[coefficients.C is Field]
      val context = ProductCell.Context(summon[Simplex[Int] is OrderedCell], summon[Simplex[Int] is OrderedCell])
      val (e, v0, v1) = (Simplex(0, 1), Simplex(0), Simplex(1))
      val boundary = cells.boundary(ProductCell(e, e)(context))[coefficients.C]
      // ∂(e × f) = ∂e × f - e × ∂f, with ∂e = (1) - (0)
      val expected = Map[Cell, Int](
        ProductCell(v1, e)(context) -> 1,
        ProductCell(v0, e)(context) -> -1,
        ProductCell(e, v1)(context) -> -1,
        ProductCell(e, v0)(context) -> 1
      )
      (boundary.size must beEqualTo(4)) and
        (boundary.forall((face, k) => fr.isEqual(k, coefficients.fromInt(expected(face)))) must beTrue)
    }

    "square to zero over Z/3, where a missing sign of the Leibniz rule shows" in {
      val coefficients = Coefficients(3)
      import coefficients.given
      val cells = summon[Cell is OrderedCell]
      val fr = summon[coefficients.C is Field]
      val product = ProductComplex(rips(cloud(6, 1L)), rips(cloud(5, 2L)))
      val failures = product.iterator.flatMap { cell =>
        val twice = mutable.HashMap.empty[Cell, coefficients.C]
        for
          (face, k) <- cells.boundary(cell)[coefficients.C]
          (faceOfFace, k2) <- cells.boundary(face)[coefficients.C]
        do twice(faceOfFace) = fr.plus(twice.getOrElse(faceOfFace, fr.zero), fr.times(k, k2))
        Option.when(twice.values.exists(v => !fr.isEqual(v, fr.zero)))(cell)
      }.toList
      failures must beEmpty
    }

    "enter no earlier than their faces, with the combined value, in the stream's own order" in {
      val coefficients = Coefficients(17)
      import coefficients.given
      val cells = summon[Cell is OrderedCell]
      val (x, y) = (rips(cloud(6, 3L)), rips(cloud(5, 4L)))
      val failures = for
        combine <- Combine.values.toList
        product = ProductComplex(x, y, combine)
        value = product.filtrationValue
        cell <- product.iterator.toList
        problem <- List(
          Option.when(
            value(cell) != (combine match
              case Combine.Max => math.max(x.filtrationValue(cell.left), y.filtrationValue(cell.right))
              case Combine.Sum => x.filtrationValue(cell.left) + y.filtrationValue(cell.right))
          )("the value"),
          Option.when(
            cells.boundary(cell)[coefficients.C].exists((face, _) => value(face) > value(cell))
          )("a face enters later")
        ).flatten
      yield s"$combine $cell: $problem"
      val byDimension = ProductComplex(x, y).iterateDimension
      val inOrder = (0 to 3).forall { d =>
        val product = ProductComplex(x, y)
        val layer = product.iterateDimension(d).toList
        layer == layer.sorted(using product.filtrationOrdering.reverse)
      }
      (failures must beEmpty) and (inOrder must beTrue) and (byDimension.isDefinedAt(4) must beFalse)
    }

    "be cut off at the smaller degree limit of the factors, one dimension higher" in {
      val product = ProductComplex(rips(cloud(6, 3L), 2), rips(cloud(5, 4L), 1))
      (product.homologyDegreeLimit must beSome(1)) and (product.iterator.map(_.dim).max must beEqualTo(2))
    }
  }

  "the product of the segment with itself" should {
    "be a square: for Max everything enters at 1, for Sum the square enters at 2 and fills the cycle of its sides" in {
      val failures = for
        p <- List(2, 3, 17)
        (combine, expected) <- List(
          Combine.Max -> List((0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, infinity)),
          Combine.Sum -> List((0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, 1.0), (0, 0.0, infinity), (1, 1.0, 2.0))
        )
        found = triples(Persistence(ProductComplex(segment, segment, combine), maxDimension = 2, characteristic = p))
        if rounded(found) != rounded(expected)
      yield s"p=$p $combine: $found"
      failures must beEmpty
    }
  }

  "the product of two circles" should {
    "be the torus: Betti numbers 1, 2, 1 over every field, the second class entering at 1 for Max and at 2 for Sum" in {
      val failures = for
        p <- List(2, 3, 17)
        engine <- List(Engine.Cohomology, Engine.Naive)
        (combine, secondDegree) <- List(Combine.Max -> 1.0, Combine.Sum -> 2.0)
        found = triples(
          Persistence(ProductComplex(circle, circle, combine), maxDimension = 2, characteristic = p, engine = engine)
        )
        // The 16 vertices give one component and 15 finite bars of degree 0; the circle contributes 2 + 2 degree 1 bars.
        essential = found.filter(_._3.isInfinite)
        if rounded(essential) != rounded(
          List((0, 0.0, infinity), (1, 1.0, infinity), (1, 1.0, infinity), (2, secondDegree, infinity))
        )
      yield s"p=$p $combine $engine: $found"
      failures must beEmpty
    }
  }

  "the persistent Künneth theorem" should {
    def bars(stream: StratifiedCellStream[Simplex[Int], Double], p: Int) =
      Persistence(stream, maxDimension = 2, characteristic = p).bars

    "give the bars of the product of two Vietoris-Rips complexes, for Max and for Sum, over Z/2, Z/3 and Z/5" in {
      val failures = for
        seed <- 0L until 4L
        combine <- Combine.values.toList
        p <- List(2, 3, 5)
        (x, y) = (rips(cloud(6, seed)), rips(cloud(5, seed + 100)))
        predicted = Barcode.kunneth(bars(x, p), bars(y, p), combine).filter(_.dim <= 2)
        found = Persistence(ProductComplex(x, y, combine), maxDimension = 2, characteristic = p)
        if rounded(found.triples) != rounded(predicted.map(_.toTriple))
      yield s"seed $seed $combine p=$p: found ${rounded(found.triples)}, predicted ${rounded(predicted.map(_.toTriple))}"
      failures.take(1) must beEmpty
    }

    "see the Tor term of the Sum product: a degree 1 class that no pair of classes of the factors makes" in {
      val factor = Barcode.tor(bars(segment, 17), bars(segment, 17))
      val predicted = Barcode.kunneth(bars(segment, 17), bars(segment, 17), Combine.Sum)
      (rounded(factor.map(_.toTriple)) must beEqualTo(List((1, 1.0, 2.0)))) and
        (rounded(predicted.map(_.toTriple)) must contain(allOf((1, 1.0, 2.0))))
    }
  }
end ProductComplexSpec
