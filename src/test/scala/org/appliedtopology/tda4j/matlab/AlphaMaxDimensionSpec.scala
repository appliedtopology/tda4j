package org.appliedtopology.tda4j.matlab

import org.specs2.mutable

/** `maxDimension` is the top homological degree for the alpha complexes through the facade, as for every other complex
  * and as in the `Persistence` verb: the complex is built to one dimension above it and no bar of a higher degree is
  * reported. The facade used to ignore it for `alpha` and `dtm-alpha` and build and reduce the whole complex, which for
  * points in 10 dimensions is the difference between seconds and an hour (and made Scala.js look hundreds of times
  * slower than the JVM, whose benchmark driver does honor it).
  *
  * Each example compares a capped run with the complete one: the bars of the degrees up to `k` must be the complete
  * run's, which fails if the cap cuts the scaffolding (the cells of dimension `k + 1`) short or reports degree `k + 1`,
  * whose bars are incomplete.
  */
class AlphaMaxDimensionSpec extends mutable.Specification:

  private def cloud(n: Int, d: Int, seed: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(d)(rng.nextDouble()))

  private def bars(result: PersistenceResult): Seq[(Int, Double, Double)] =
    result.toArray().toSeq.map(row => (row(0).toInt, row(1), row(2))).sortBy(t => (t._1, t._2, t._3))

  private def agree(left: Seq[(Int, Double, Double)], right: Seq[(Int, Double, Double)]): Boolean =
    left.length == right.length && left.zip(right).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
      d1 == d2 && math.abs(b1 - b2) < 1e-9 && (e1.isInfinite == e2.isInfinite) &&
      (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
    }

  /** Cells of a result's boundary matrix by dimension. */
  private def cellsByDimension(result: PersistenceResult): Map[Int, Int] =
    (0 until result.numCells()).groupBy(result.columnDimension).view.mapValues(_.size).toMap

  private val points = cloud(16, 5, 1)

  private def capped(extra: Array[String], k: Int): PersistenceResult =
    FullBarcode.computeFromPoints(points, extra ++ Array("maxDimension", k.toString))

  private def check(label: String, extra: Array[String]) =
    val complete = capped(extra, 5)
    val completeBars = bars(complete)
    val problems = for
      k <- 0 to 2
      result = capped(extra, k)
      got = bars(result)
      wanted = completeBars.filter(_._1 <= k)
      message <-
        if got.exists(_._1 > k) then Some(s"$label, maxDimension $k: reports degree ${got.map(_._1).max}")
        else if !agree(got, wanted) then
          Some(s"$label, maxDimension $k: ${got.size} bars, the complete run's are ${wanted.size}")
        else None
    yield message
    problems must beEmpty

  "complex=alpha reports the degrees up to maxDimension of the complete complex" >> {
    check("naive", Array("complex", "alpha", "engine", "naive")) and
      check("cohomology", Array("complex", "alpha", "engine", "cohomology")) and
      check("fast-alpha", Array("complex", "alpha", "engine", "fast-alpha"))
  }

  "complex=alpha with the DQP backend builds and reports the same" >> {
    check("DQP naive", Array("complex", "alpha", "alphaBackend", "DQP", "engine", "naive")) and
      check("DQP cohomology", Array("complex", "alpha", "alphaBackend", "DQP", "engine", "cohomology"))
  }

  "complex=dtm-alpha reports the degrees up to maxDimension of the complete complex" >> {
    check("dtm-alpha naive", Array("complex", "dtm-alpha", "dtmK", "3", "engine", "naive")) and
      check("dtm-alpha cohomology", Array("complex", "dtm-alpha", "dtmK", "3", "engine", "cohomology"))
  }

  "the complex is built to one dimension above maxDimension and no further (the boundary matrix shows the cells)" >> {
    val complete = cellsByDimension(capped(Array("complex", "alpha", "engine", "naive"), 5))
    val problems = for
      engine <- Seq("naive", "cohomology")
      backend <- Seq("helix", "DQP")
      k <- 0 to 2
      cells = cellsByDimension(capped(Array("complex", "alpha", "alphaBackend", backend, "engine", engine), k))
      if cells != complete.filter(_._1 <= k + 1)
    yield s"$engine/$backend, maxDimension $k: cells by dimension $cells, the complex has ${complete.filter(_._1 <= k + 1)}"
    problems must beEmpty
  }
