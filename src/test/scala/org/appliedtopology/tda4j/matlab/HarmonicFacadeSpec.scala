package org.appliedtopology.tda4j
package matlab

import SpecThrows.throwWith

import org.specs2.mutable.Specification

/** `TDA4j.harmonicChainBarcodeFromPoints` and `harmonicRepresentativesFromPoints` (and their distance-matrix twins):
  * the Vietoris-Rips complex's harmonic chain barcode and the ordinary barcode with Basu-Cox representatives. The
  * oracle for each is the typed route, `HarmonicChainBarcode(VietorisRips(...))` and `HarmonicRepresentatives(...)`,
  * which `HarmonicChainBarcodeSpec` and `HarmonicRepresentativesSpec` check against the definitions; here the facade
  * must be that, bar for bar with cells and chains, in both fields. One cloud is also worked by hand: the unit square.
  */
class HarmonicFacadeSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
  private val diagonal = math.sqrt(2.0)

  private type Row = (Int, Double, Double, Set[Int], Set[Int], Map[Set[Int], Double])

  private def facadeRows(r: PersistenceResult): Set[Row] =
    (0 until r.size()).map { i =>
      (
        r.dimension(i),
        r.birth(i),
        r.death(i),
        r.birthCell(i).toSet,
        r.deathCell(i).toSet,
        r.cycleVertices(i).map(_.toSet).zip(r.cycleCoefficients(i)).toMap
      )
    }.toSet

  private def typedRows(d: PersistenceDiagram[Simplex[Int]]): Set[Row] =
    import d.given
    d.bars.map { b =>
      (
        b.dim,
        b.birth,
        b.death,
        b.birthCell.fold(Set.empty[Int])(_.underlying.toSet),
        b.deathCell.fold(Set.empty[Int])(_.underlying.toSet),
        b.representative.terms
          .map((cell, c) => cell.underlying.toSet -> summon[d.Coefficient is Field].toDouble(c))
          .toMap
      )
    }.toSet

  private def rips(points: Array[Array[Double]], maxDimension: Int, radius: Double) =
    VietorisRips(EuclideanMetricSpace(points), maxDimension = maxDimension, maxFiltrationValue = radius)

  private def all(extra: String*) = Array("minPersistence", "0", "maxFiltrationValue", "1.5") ++ extra

  private def degree(r: PersistenceResult, k: Int) = (0 until r.size()).count(r.dimension(_) == k)

  "the harmonic chain barcode facade" should {
    "be the typed barcode, with its cells and chains, over the reals and over the rationals" in {
      val points = cloud(8, 11L)
      val real = TDA4j.harmonicChainBarcodeFromPoints(points, all("maxDimension", "2"))
      val exact = TDA4j.harmonicChainBarcodeFromPoints(points, all("maxDimension", "2", "field", "Q"))
      val typed = HarmonicChainBarcode(rips(points, 2, 1.5), maxDimension = 2)
      val typedExact =
        HarmonicChainBarcode(rips(points, 2, 1.5), maxDimension = 2, coefficients = Coefficients.Rationals)
      (facadeRows(real) must not(beEmpty)) and
        (facadeRows(real) must beEqualTo(typedRows(typed))) and
        (facadeRows(exact) must beEqualTo(typedRows(typedExact)))
    }

    "give the loop of the unit square from the cell that closes it, as the cycle of its four sides" in {
      val result = TDA4j.harmonicChainBarcodeFromPoints(square, all("maxDimension", "1"))
      val loops = (0 until result.size()).filter(result.dimension(_) == 1)
      val sides = Map(Set(0, 1) -> 1.0, Set(1, 2) -> 1.0, Set(2, 3) -> 1.0, Set(0, 3) -> -1.0)
      val i = loops.head
      val found = result.cycleVertices(i).map(_.toSet).zip(result.cycleCoefficients(i)).toMap
      (loops.size must beEqualTo(1)) and
        (result.birth(i) must beEqualTo(1.0)) and
        (result.death(i) must beCloseTo(diagonal, 1e-12)) and
        // the unit coefficient is on the cell that closes the loop, so the chain is the cycle or its negative
        ((found == sides || found == sides.view.mapValues(-_).toMap) must beTrue) and
        (result.birthCell(i).length must beEqualTo(2)) and
        (result.deathCell(i).length must beEqualTo(3)) and
        // three bars in degree 0 end where the loop begins, and one is essential
        (degree(result, 0) must beEqualTo(4)) and
        (result.toArray().count(b => b(0) == 0.0 && b(2).isInfinite) must beEqualTo(1))
    }

    "leave out the bars that begin and end at one value unless asked, and then have them" in {
      val without = TDA4j.harmonicChainBarcodeFromPoints(square, all("maxDimension", "1"))
      val with0 = TDA4j.harmonicChainBarcodeFromPoints(square, all("maxDimension", "1", "includeZeroLength", "true"))
      // both diagonals enter at the value where the four triangles do: two new loops, each gone at once
      (degree(without, 1) must beEqualTo(1)) and (degree(with0, 1) must beEqualTo(3)) and
        ((0 until with0.size()).filter(i => with0.birth(i) == with0.death(i)).size must beEqualTo(2))
    }

    "take a distance matrix and give the same bars as the points" in {
      val points = cloud(7, 5L)
      val distances = Array.tabulate(points.length, points.length)((i, j) =>
        math.sqrt(points(i).zip(points(j)).map((a, b) => (a - b) * (a - b)).sum)
      )
      facadeRows(TDA4j.harmonicChainBarcodeFromDistanceMatrix(distances, all())) must
        beEqualTo(facadeRows(TDA4j.harmonicChainBarcodeFromPoints(points, all())))
    }

    "report by default what the other entry points report: the essential bars and the bars longer than 1% of the scale" in {
      val points = cloud(9, 3L)
      val everything = TDA4j.harmonicChainBarcodeFromPoints(points, Array("minPersistence", "0"))
      val byDefault = TDA4j.harmonicChainBarcodeFromPoints(points)
      val scale = EuclideanMetricSpace(points).minimumEnclosingRadius
      val kept = (0 until everything.size()).filter(i =>
        everything.death(i).isInfinite || everything.death(i) - everything.birth(i) >= 0.01 * scale
      )
      (byDefault.size() must beEqualTo(kept.size)) and (byDefault.size() must beLessThan(everything.size()))
    }

    "refuse a finite field, an option that is not its own, and a degree that is not one, before computing anything" in {
      def run(options: String*) = TDA4j.harmonicChainBarcodeFromPoints(square, options.toArray)
      (run("field", "Z") must throwWith[IllegalArgumentException]("inner product")) and
        (run("prime", "5") must throwWith[IllegalArgumentException]("unrecognized option")) and
        (run("engine", "ripser") must throwWith[IllegalArgumentException]("unrecognized option")) and
        (run("maxDimension", "-1") must throwWith[IllegalArgumentException]("0 or more")) and
        (run("field", "C") must throwWith[IllegalArgumentException]("unrecognized field")) and
        (TDA4j.harmonicChainBarcodeFromPoints(Array.empty[Array[Double]]) must
          throwWith[IllegalArgumentException]("at least one row"))
    }
  }

  "the harmonic representatives facade" should {
    "be the typed diagram with its cells and Basu-Cox chains, over the reals and over the rationals" in {
      val points = cloud(8, 17L)
      val real = TDA4j.harmonicRepresentativesFromPoints(points, all("maxDimension", "2"))
      val exact = TDA4j.harmonicRepresentativesFromPoints(points, all("maxDimension", "2", "field", "Q"))
      val typed = HarmonicRepresentatives(rips(points, 2, 1.5), maxDimension = 2)
      val typedExact =
        HarmonicRepresentatives(rips(points, 2, 1.5), maxDimension = 2, coefficients = Coefficients.Rationals)
      (facadeRows(real) must not(beEmpty)) and
        (facadeRows(real) must beEqualTo(typedRows(typed))) and
        (facadeRows(exact) must beEqualTo(typedRows(typedExact)))
    }

    "have the bars of the ordinary barcode, and the harmonic cycle of the square as the loop's chain" in {
      val result = TDA4j.harmonicRepresentativesFromPoints(square, all("maxDimension", "1"))
      val ordinary = TDA4j.computeFromPoints(square, all("maxDimension", "1"))
      def triples(r: PersistenceResult) =
        (0 until r.size()).map(i => (r.dimension(i), r.birth(i), r.death(i))).sorted
      val loop = (0 until result.size()).find(result.dimension(_) == 1).get
      val found = result.cycleVertices(loop).map(_.toSet).zip(result.cycleCoefficients(loop)).toMap
      (triples(result) must beEqualTo(triples(ordinary))) and
        // the harmonic chain of a one-dimensional space is unique up to scale, here scaled to 1 at its first cell
        (found must beEqualTo(Map(Set(0, 1) -> 1.0, Set(1, 2) -> 1.0, Set(2, 3) -> 1.0, Set(0, 3) -> -1.0)))
    }

    "take a distance matrix and give the same bars as the points" in {
      val points = cloud(7, 9L)
      val distances = Array.tabulate(points.length, points.length)((i, j) =>
        math.sqrt(points(i).zip(points(j)).map((a, b) => (a - b) * (a - b)).sum)
      )
      facadeRows(TDA4j.harmonicRepresentativesFromDistanceMatrix(distances, all())) must
        beEqualTo(facadeRows(TDA4j.harmonicRepresentativesFromPoints(points, all())))
    }

    "refuse a finite field, a zero-length bar request (it has no Basu-Cox chain), and the options of other entry points" in {
      def run(options: String*) = TDA4j.harmonicRepresentativesFromPoints(square, options.toArray)
      (run("field", "Z") must throwWith[IllegalArgumentException]("inner product")) and
        (run("includeZeroLength", "true") must throwWith[IllegalArgumentException]("unrecognized option")) and
        (run("representativeType", "cocycles") must throwWith[IllegalArgumentException]("unrecognized option"))
    }
  }
