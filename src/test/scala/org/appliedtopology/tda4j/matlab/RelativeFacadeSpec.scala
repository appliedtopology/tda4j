package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.computeRelativeFromPoints` and `computeRelativeFromDistanceMatrix`: the Vietoris-Rips pair modulo the
  * subcomplex on some points. The oracle is the typed route,
  * `Persistence(Relative.ofVertices(VietorisRips(...), ...))`, which `RelativeSpec` checks against the ranks of the
  * structure maps of the pair; here the facade must be that, bar for bar with its cells, for every engine and field,
  * and the reduced homology of a cloud modulo one point.
  */
class RelativeFacadeSpec extends Specification:

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private val points = cloud(9, 6L)
  private val subcomplex = Array(0, 3, 5)

  private def facadeTriples(r: PersistenceResult) =
    (0 until r.size()).map(i => (r.dimension(i), r.birth(i), r.death(i))).sorted

  private def facade(engine: String, p: Int, kind: String = "cycles") =
    TDA4j.computeRelativeFromPoints(
      points,
      subcomplex,
      Array(
        "engine",
        engine,
        "prime",
        p.toString,
        "representativeType",
        kind,
        "maxFiltrationValue",
        "100",
        "minPersistence",
        "0"
      )
    )

  private def typed(engine: Persistence.Engine, p: Int, kind: Representatives) =
    Persistence(
      Relative.ofVertices(
        VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 100.0),
        subcomplex.toSet
      ),
      maxDimension = 2,
      characteristic = p,
      engine = engine,
      representatives = kind
    )

  private val engines = List(
    "cohomology" -> Persistence.Engine.Cohomology,
    "naive" -> Persistence.Engine.Naive,
    "chunks" -> Persistence.Engine.Chunks
  )

  "the relative facade" should {
    "give the bars and the cells of the typed pair, for every engine, field and kind of representative" in {
      val failures = for
        (name, engine) <- engines
        p <- List(2, 3)
        (kind, representatives) <- List("cycles" -> Representatives.Cycles, "cocycles" -> Representatives.Cocycles)
        found = facade(name, p, kind)
        expected = typed(engine, p, representatives)
        if facadeTriples(found) != expected.triples.sorted ||
          (0 until found.size())
            .map(i =>
              (found.dimension(i), found.birth(i), found.death(i)) ->
                (found.birthCell(i).toSeq, found.deathCell(i).toSeq)
            )
            .toSet !=
          expected.bars
            .map(b => b.toTriple -> (b.birthCell.get.cell.toList, b.deathCell.fold(List.empty[Int])(_.cell.toList)))
            .toSet
      yield s"$name p=$p $kind"
      failures must beEmpty
    }

    "have no cell of the subcomplex among the cells of its bars and representatives" in {
      val result = facade("cohomology", 2)
      val inside = subcomplex.toSet
      val cells = (0 until result.size()).flatMap { i =>
        result.cycleVertices(i).toSeq ++ Seq(result.birthCell(i), result.deathCell(i)).filter(_.nonEmpty)
      }
      (cells must not(beEmpty)) and (cells.exists(_.forall(inside)) must beFalse)
    }

    "be the reduced homology modulo one point of a connected cloud: the essential bar of degree 0 is gone" in {
      val relative = TDA4j.computeRelativeFromPoints(points, Array(4), Array("minPersistence", "0"))
      val absolute = TDA4j.computeFromPoints(points, Array("minPersistence", "0"))
      def degree(r: PersistenceResult, k: Int) =
        facadeTriples(r).filter(_._1 == k).filter(_._3 != Double.PositiveInfinity)
      (degree(relative, 0) must beEqualTo(degree(absolute, 0))) and
        (relative.toArray().count(b => b(0) == 0.0 && b(2).isInfinite) must beEqualTo(0)) and
        (degree(relative, 1) must beEqualTo(degree(absolute, 1)))
    }

    "take a distance matrix and give the same bars as the points" in {
      val distances = Array.tabulate(points.length, points.length)((i, j) =>
        math.sqrt(points(i).zip(points(j)).map((a, b) => (a - b) * (a - b)).sum)
      )
      val byMatrix = TDA4j.computeRelativeFromDistanceMatrix(distances, subcomplex, Array("minPersistence", "0"))
      val byPoints = TDA4j.computeRelativeFromPoints(points, subcomplex, Array("minPersistence", "0"))
      facadeTriples(byMatrix) must beEqualTo(facadeTriples(byPoints))
    }

    "refuse a vertex that is not a point, an engine it does not have, and an option for another complex" in {
      def run(subset: Array[Int], options: String*) =
        TDA4j.computeRelativeFromPoints(points, subset, options.toArray)
      (run(Array(9)) must throwA[IllegalArgumentException](message = "1-based")) and
        (run(Array(-1)) must throwA[IllegalArgumentException](message = "out of range")) and
        (run(subcomplex, "engine", "ripser") must throwA[IllegalArgumentException](message = "relative")) and
        (run(subcomplex, "complex", "alpha") must throwA[IllegalArgumentException](message = "unrecognized option"))
    }
  }
