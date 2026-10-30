package org.appliedtopology.tda4j
package matlab

import SpecThrows.throwWith

import org.specs2.mutable.Specification

/** `field=Q` in the facade: exact rational coefficients, with the bars of a large prime where there is no torsion. */
class RationalFacadeSpec extends Specification:

  private val points =
    val rng = new scala.util.Random(8L)
    Array.fill(12)(Array(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()))

  private def triples(r: PersistenceResult) =
    (0 until r.size()).map(i => (r.dimension(i), r.birth(i), r.death(i))).sorted

  "field=Q" should {
    "give the bars of a large prime for every engine and either kind of representative" in {
      val failures = for
        engine <- List("ripser", "cohomology", "naive", "chunks")
        kind <- List("cycles", "cocycles")
        exact = FullBarcode.computeFromPoints(
          points,
          Array("field", "Q", "engine", engine, "representativeType", kind)
        )
        modular = FullBarcode.computeFromPoints(
          points,
          Array("prime", "10007", "engine", engine, "representativeType", kind)
        )
        if triples(exact) != triples(modular)
      yield s"$engine/$kind"
      failures must beEmpty
    }

    "report loops that close up exactly: the signed edges of a cycle cancel at every vertex" in {
      val exact = FullBarcode.computeFromPoints(points, Array("field", "Q", "maxDimension", "1"))
      val loops = (0 until exact.size()).filter(exact.dimension(_) == 1)
      def residue(i: Int): Double =
        val net = scala.collection.mutable.Map.empty[Int, Double].withDefaultValue(0.0)
        for (edge, c) <- exact.cycleVertices(i).zip(exact.cycleCoefficients(i)) do
          net(edge(0)) -= c
          net(edge(1)) += c
        net.values.map(math.abs).maxOption.getOrElse(0.0)
      (loops must not(beEmpty)) and (loops.map(residue).max must beLessThan(1e-12))
    }

    "work for a relative pair, an image, and say which fields there are" in {
      val relative = TDA4j.computeRelativeFromPoints(points, Array(0), Array("field", "Q", "minPersistence", "0"))
      val image = Array(Array(3.0, 3.0, 3.0), Array(3.0, 0.0, 3.0), Array(3.0, 3.0, 3.0))
      val exactImage = FullBarcode.computeFromImage(image, Array("field", "Q"))
      val modularImage = FullBarcode.computeFromImage(image, Array("prime", "10007"))
      (relative.toArray().count(b => b(0) == 0.0 && b(2).isInfinite) must beEqualTo(0)) and
        (triples(exactImage) must beEqualTo(triples(modularImage))) and
        (TDA4j.computeFromPoints(points, Array("field", "C")) must throwWith[IllegalArgumentException](
          "'Z', 'R' or 'Q'"
        ))
    }
  }
