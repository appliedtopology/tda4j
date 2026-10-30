package org.appliedtopology.tda4j

import org.specs2.mutable

/** What `AlphaComplexDQP` hands to the engines, checked on the complex itself: the cells of a dimension come in the
  * stream's order (ascending value, ties by descending `simplexOrdering`), a face is in the complex and no larger than
  * its coface, the cells are those of a Delaunay triangulation, and threads change nothing.
  */
class AlphaComplexDQPOrderSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  private def cloud(n: Int, d: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(d)(rng.nextDouble()))

  private def grid(side: Int, d: Int): Array[Array[Double]] =
    Array.tabulate(math.pow(side, d).toInt)(k =>
      Array.tabulate(d)(a => ((k / math.pow(side, a).toInt) % side).toDouble)
    )

  private def complexOf(points: Array[Array[Double]], top: Int, parallel: Boolean = true): AlphaComplexDQP =
    AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, top, AlphaDQPSettings(parallel = parallel))

  private def cellsOf(c: AlphaComplexDQP, dim: Int): List[List[Int]] =
    c.cellsOfDimension(dim).toList.map(_.toList)

  "the cells of every dimension are in ascending value order, ties by descending simplexOrdering" >> {
    val problems = for
      (points, top) <- Seq((cloud(14, 3, 1L), 3), (cloud(12, 4, 2L), 4), (cloud(12, 5, 3L), 4), (grid(3, 3), 3))
      c = complexOf(points, top)
      k <- 0 to top
      cells = c.cellsOfDimension(k)
      order = Ordering
        .by[Simplex[Int], Double](c.filtrationValue)
        .orElse(simplexOrdering[Int].reverse)
      if cells != cells.sorted(using order)
    yield (points.length, points.head.length, k)
    problems must beEmpty
  }

  "every facet of a cell is in the complex, with a value no larger than the cell's" >> {
    val problems = for
      (points, top) <- Seq((cloud(14, 3, 4L), 3), (cloud(12, 4, 5L), 4), (grid(3, 3), 3))
      c = complexOf(points, top)
      k <- 1 to top
      cell <- c.cellsOfDimension(k)
      v <- cell.toSeq
      facet = cell - v
      if !c.contains(facet) || c.filtrationValue(facet) > c.filtrationValue(cell)
    yield (points.length, points.head.length, cell)
    problems must beEmpty
  }

  "in general position the cells are those of the Bowyer-Watson triangulation, with its values" >> {
    val problems = for
      (points, d) <- Seq((cloud(16, 2, 6L), 2), (cloud(14, 3, 7L), 3), (cloud(12, 4, 8L), 4))
      dqp = complexOf(points, d)
      delaunay = BowyerWatsonDelaunay(points)
      k <- 0 to d
      mine = dqp.cellsOfDimension(k).toSet
      theirs = delaunay.iterateDimension(k).toSet
      if mine != theirs ||
        mine.exists(cell => math.abs(dqp.radiusOf(cell) - delaunay.filtrationValue(cell)) > 1e-6)
    yield (d, k)
    problems must beEmpty
  }

  "threads change nothing: the same cells in the same order, with the same values" >> {
    // 60 points in R^6 give more than one chunk of candidates, so the merge of the chunks is exercised.
    val points = cloud(60, 6, 9L)
    val together = complexOf(points, 4)
    val alone = complexOf(points, 4, parallel = false)
    val problems = for
      k <- 0 to 4
      if cellsOf(together, k) != cellsOf(alone, k) ||
        together.cellsOfDimension(k).exists(c => together.filtrationValue(c) != alone.filtrationValue(c))
    yield k
    (problems must beEmpty) and (together.sizeByDimension must beEqualTo(alone.sizeByDimension))
  }
