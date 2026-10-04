package org.appliedtopology.tda4j

import org.specs2.mutable

/** The frontier walk picks each facet's Delaunay cofacet as the light point with the smallest centre parameter (one
  * pass over the points), checks that its circumsphere is empty, and falls back to the old candidate scan only if that
  * check fails. These examples pin that the fast path is the one taken, and that the results are triangulations:
  *   - random clouds in 2-D, 3-D and 4-D: no fallback, no facet with more than two cofaces, no interior void;
  *   - exact grids, where whole cubes are cospherical: a walk that tiled cospherical clusters is checked and repaired
  *     without being asked, so the default result tiles the grid's bounding box (Euler characteristic 1, total volume
  *     of the box, every facet in at most two top simplices).
  */
class HelixWalkSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  "On random clouds the walk never falls back, and returns a valid triangulation" >> {
    val problems = for
      (dim, n, count) <- Seq((2, 40, 40), (3, 40, 20), (4, 25, 10))
      seed <- 0 until count
      rng = new scala.util.Random(100L * dim + seed)
      helix = HelixDelaunay(Array.fill(n)(Array.fill(dim)(rng.nextDouble())))
      problem <- Seq(
        Option.when(helix.sphereScanFallbacks > 0)(s"${helix.sphereScanFallbacks} fallbacks"),
        Option.when(HelixDelaunay.badFacetsOf(helix.validated).nonEmpty)("a facet with three cofaces"),
        Option.when(HelixDelaunay.interiorVoidVertices(helix.validated, dim).nonEmpty)("an interior void")
      ).flatten
    yield s"dim $dim seed $seed: $problem"
    problems must beEmpty
  }

  def determinant(rows: Seq[Seq[Double]]): Double =
    if rows.size == 1 then rows.head.head
    else
      rows.head.indices.map { j =>
        val minor = rows.tail.map(r => r.patch(j, Nil, 1))
        (if j % 2 == 0 then 1 else -1) * rows.head(j) * determinant(minor)
      }.sum

  /** (Euler characteristic, total top volume, facets in more than two top simplices) of the default triangulation. */
  def gridCheck(side: Int, dim: Int): (Int, Double, Int) =
    val coords: IndexedSeq[IndexedSeq[Double]] =
      (0 until math.pow(side, dim).toInt).map(k => (0 until dim).map(a => ((k / math.pow(side, a).toInt) % side).toDouble))
    val helix = HelixDelaunay(coords.map(_.toArray).toArray)
    val simplices = helix.simplices().toSeq
    val euler = simplices.map(s => if s.toSeq.size % 2 == 1 then 1 else -1).sum
    val tops = simplices.filter(_.toSeq.size == dim + 1)
    val factorial = (1 to dim).product.toDouble
    val volume = tops.map { t =>
      val vs = t.toSeq.map(coords)
      math.abs(determinant(vs.tail.map(v => v.indices.map(i => v(i) - vs.head(i))))) / factorial
    }.sum
    val facetCounts = tops.flatMap(t => t.toSeq.map(v => t - v)).groupBy(identity).view.mapValues(_.size)
    (euler, volume, facetCounts.count(_._2 > 2))

  "An exact 6x6 grid is triangulated validly by default" >> {
    val (euler, volume, bad) = gridCheck(6, 2)
    (euler must be_==(1)) and (volume must beCloseTo(25.0, 1e-9)) and (bad must be_==(0))
  }

  "An exact 4x4x4 grid is triangulated validly by default" >> {
    val (euler, volume, bad) = gridCheck(4, 3)
    (euler must be_==(1)) and (volume must beCloseTo(27.0, 1e-9)) and (bad must be_==(0))
  }
