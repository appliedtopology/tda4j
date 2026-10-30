package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `PersistenceResult.birthCell` and `deathCell`: the cells that open and close each bar, as the facade reports a cell
  * (`cycleVertices`' format). The oracles are the cells themselves: a bar's birth cell has the bar's dimension and, as
  * a simplex over the points, the bar's birth as its diameter; the death cell is one dimension up and has the death as
  * its diameter; an essential bar has none. A packed (Ripser) cell decoded with the wrong size, or a witness cell left
  * in landmark numbers, fails these.
  */
class BarCellsFacadeSpec extends Specification:

  private val points: Array[Array[Double]] =
    val rng = new scala.util.Random(5L)
    Array.fill(14)(Array(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()))

  private def distance(a: Int, b: Int): Double =
    math.sqrt(points(a).zip(points(b)).map((x, y) => (x - y) * (x - y)).sum)

  private def diameter(cell: Array[Int]): Double =
    if cell.length < 2 then 0.0 else cell.toSeq.combinations(2).map(p => distance(p(0), p(1))).max

  private def every(options: String*): Array[String] = (options ++ Seq("includeZeroLength", "true")).toArray

  /** What is wrong with the cells of the bars of `result`, as a list of `bar: problem`. */
  private def problems(result: PersistenceResult): Seq[String] =
    for
      i <- 0 until result.size()
      (born, died) = (result.birthCell(i), result.deathCell(i))
      dim = result.dimension(i)
      problem <- List(
        Option.when(born.isEmpty)("no birth cell"),
        Option.when(born.nonEmpty && born.length != dim + 1)("birth cell of the wrong dimension"),
        Option.when(born.nonEmpty && math.abs(diameter(born) - result.birth(i)) > 1e-12)("birth cell valued otherwise"),
        Option.when(died.nonEmpty && died.length != dim + 2)("death cell of the wrong dimension"),
        Option.when(died.nonEmpty && math.abs(diameter(died) - result.death(i)) > 1e-12)("death cell valued otherwise"),
        Option.when(died.isEmpty != result.death(i).isPosInfinity)("an essential bar iff there is no death cell")
      ).flatten
    yield s"bar $i (H$dim [${result.birth(i)}, ${result.death(i)})): $problem"

  private def pairing(result: PersistenceResult): Set[(Int, Seq[Int], Seq[Int])] =
    (0 until result.size()).map(i => (result.dimension(i), result.birthCell(i).toSeq, result.deathCell(i).toSeq)).toSet

  "the cells of a bar through the facade" should {
    "have the dimension and the value of the bar's endpoints, for every engine and kind of representative" in {
      val failures = for
        engine <- List("ripser", "naive", "chunks", "cohomology")
        kind <- List("cycles", "cocycles")
        result = FullBarcode.computeFromPoints(points, every("engine", engine, "representativeType", kind))
        problem <- problems(result)
      yield s"$engine/$kind $problem"
      failures.take(3) must beEmpty
    }

    "be the cells the verb finds, for each engine and kind of representative" in {
      // The facade and the verb are two routes to one engine; engines break ties differently, so each against its own.
      val engines = List(
        "ripser" -> Persistence.Engine.Ripser,
        "naive" -> Persistence.Engine.Naive,
        "chunks" -> Persistence.Engine.Chunks,
        "cohomology" -> Persistence.Engine.Cohomology
      )
      val failures = for
        (name, engine) <- engines
        (kind, representatives) <- List("cycles" -> Representatives.Cycles, "cocycles" -> Representatives.Cocycles)
        found = pairing(FullBarcode.computeFromPoints(points, Array("engine", name, "representativeType", kind)))
        diagram = Persistence(points, engine = engine, representatives = representatives)
        expected = diagram.bars
          .map(b => (b.dim, b.birthCell.get.toList, b.deathCell.fold(List.empty[Int])(_.toList)))
          .toSet
        if found.map((d, born, died) => (d, born.toList, died.toList)) != expected
      yield s"$name/$kind"
      failures must beEmpty
    }

    "survive the threshold and the removal of zero-length bars with the bars they belong to" in {
      val all = FullBarcode.computeFromPoints(points, every("maxDimension", "2"))
      val trimmed = TDA4j.computeFromPoints(points, Array("maxDimension", "2", "minPersistenceFraction", "0.1"))
      (trimmed.size() must be_<(all.size())) and (problems(trimmed) must beEmpty) and
        (pairing(trimmed).subsetOf(pairing(all)) must beTrue)
    }

    "name the cells by point numbers for a witness complex, not by landmark numbers" in {
      val landmarks = Array(2, 3, 5, 7, 8, 11, 13)
      val result = FullBarcode.computeFromPointsAndLandmarks(points, landmarks, every("maxDimension", "1"))
      val cells = (0 until result.size()).flatMap(i => result.birthCell(i).toSeq ++ result.deathCell(i).toSeq)
      (cells must not(beEmpty)) and (cells.forall(landmarks.contains) must beTrue)
    }

    "be cubes in doubled coordinates for an image: the middle pixel closes the loop around it" in {
      // a ring of 0 around a pixel of 3, on a background of 3: one loop, born with the ring and filled in at 3
      val image = Array(
        Array(3.0, 3.0, 3.0, 3.0, 3.0),
        Array(3.0, 0.0, 0.0, 0.0, 3.0),
        Array(3.0, 0.0, 3.0, 0.0, 3.0),
        Array(3.0, 0.0, 0.0, 0.0, 3.0),
        Array(3.0, 3.0, 3.0, 3.0, 3.0)
      )
      val result = FullBarcode.computeFromImage(image, Array("engine", "cohomology"))
      val loops = (0 until result.size()).filter(result.dimension(_) == 1)
      // The loop is the boundary of the middle pixel, the cube (5, 5) of the doubled grid: that pixel closes it, and the
      // edge that opens it is one of the pixel's four edges.
      val edgesOfMiddlePixel = Set(Seq(4, 5), Seq(6, 5), Seq(5, 4), Seq(5, 6))
      (loops.size must beEqualTo(1)) and
        (result.deathCell(loops.head).toSeq must beEqualTo(Seq(5, 5))) and
        (edgesOfMiddlePixel must contain(result.birthCell(loops.head).toSeq))
    }
  }
