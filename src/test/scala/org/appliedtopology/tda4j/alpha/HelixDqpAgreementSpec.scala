package org.appliedtopology.tda4j

import org.specs2.mutable

/** For points in general position, the alpha filtration is unique, so the two backends must give the same barcode:
  * Helix (a Delaunay triangulation, then alpha values top-down by the Gabriel rule) and DQP (each simplex's value from
  * its own dual quadratic program, no triangulation). They share no construction code, which makes this an independent
  * check. A Helix that dropped the Gabriel rule above dimension 1 lost most `H_2` classes in 3-D while this was only a
  * diagnostic (`.claude/WORKLOG-helix-alpha-values.md`).
  *
  * Random uniform clouds in 2-D, 3-D and 4-D; bars shorter than `1e-7` are ignored (floating-point near-ties can split
  * a zero-length pair into a tiny bar in one backend and not the other), the rest must match within `1e-9`.
  */
class HelixDqpAgreementSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def bars(stream: LevelwiseSimplexStream[Int, Double], top: Int): List[(Int, Double, Double)] =
    Persistence(Truncated(stream, top), characteristic = 3, engine = Persistence.Engine.Cohomology).triples
      .filter((_, b, d) => d - b > 1e-7)
      .sorted

  def agree(a: List[(Int, Double, Double)], b: List[(Int, Double, Double)]): Boolean =
    a.size == b.size && a.zip(b).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
      d1 == d2 && math.abs(b1 - b2) <= 1e-9 && (e1 == e2 || math.abs(e1 - e2) <= 1e-9)
    }

  def check(dim: Int, seeds: Range, sizes: Range): Seq[(Long, Int, Boolean)] =
    for seed <- seeds; n = sizes(seed % sizes.size)
    yield
      val rng = new scala.util.Random(1000L * dim + seed)
      val pts = Array.fill(n)(Array.fill(dim)(rng.nextDouble()))
      val helix = bars(AlphaShapes(pts, AlphaBackend.Helix), dim - 1)
      val dqp = bars(AlphaShapes(pts, AlphaBackend.DQP), dim - 1)
      (seed.toLong, n, agree(helix, dqp))

  "Helix and DQP give the same alpha barcode in the plane" >> {
    check(2, 0 until 20, 8 to 40).filterNot(_._3) must beEmpty
  }

  "Helix and DQP give the same alpha barcode in 3-D, H_2 included" >> {
    check(3, 0 until 20, 8 to 40).filterNot(_._3) must beEmpty
  }

  "Helix and DQP give the same alpha barcode in 4-D" >> {
    check(4, 0 until 8, 8 to 16).filterNot(_._3) must beEmpty
  }

  def cellsUpTo(delaunay: HelixDelaunay, radius: Double): Set[Simplex[Int]] =
    (for (_, cells) <- delaunay.simplicesMap.toSeq; cell <- cells if delaunay.filtrationValue(cell) <= radius
    yield cell).toSet

  // Simplices of large circumradius are the ill-conditioned ones: a pivot under `rankTolerance` used to rule them out,
  // and with them every coface, so a high-degree barcode lost its last classes. 40 points in R^7 up to radius 200 (a
  // hundred times the spacing) lost 27 to 106 cells to this, from the second dimension up.
  "Helix and DQP give the same simplices in 7-D, with the same values, up to a radius far above the spacing" >> {
    val radius = 200.0
    val problems =
      for seed <- TestPlatform.perPlatform(jvm = Seq(0, 1, 2), js = Seq.empty[Int])
      yield
        val rng = new scala.util.Random(31L * 7 + seed)
        val pts = Array.fill(40)(Array.fill(7)(rng.nextDouble()))
        val helix = HelixDelaunay(pts)
        val expected = cellsUpTo(helix, radius)
        val dqp = AlphaComplexDQP.euclidean(pts, radius, 7)
        val found: Set[Simplex[Int]] = (for k <- 0 to 7; cell <- dqp.cellsOfDimension(k) yield cell).toSet
        val worst = expected
          .intersect(found)
          .iterator
          .map(s => math.abs(dqp.radiusOf(s) - helix.filtrationValue(s)) / (1.0 + helix.filtrationValue(s)))
          .maxOption
          .getOrElse(0.0)
        (seed, expected.diff(found).size, found.diff(expected).size, worst > 1e-6)
    problems.filter { case (_, missing, extra, valuesOff) => missing + extra > 0 || valuesOff } must beEmpty
  }
