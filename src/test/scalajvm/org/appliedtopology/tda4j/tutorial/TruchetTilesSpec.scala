package org.appliedtopology.tda4j
package tutorial

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/truchet-tiles.md` quotes, on the values its "whole script" fence defines (see
  * `build.sbt`), and the claims behind them on other patterns: the long bars of the pair are the curves that a trace
  * through the tiles finds, the cycles that reach the boundary are the curves that leave the picture, and the framed
  * image has the bars of the pair.
  */
class TruchetTilesSpec extends Specification:
  sequential

  private val page = TruchetTilesScript
  private val infinity = Double.PositiveInfinity
  private def r2(x: Double) = math.round(x * 100) / 100.0
  private def persistences(d: PersistenceDiagram[?]) = d.bars.map(_.persistence).sorted.reverse

  /** The curves of a trace, and the cutoff the page uses, for a pattern of `tiles` x `tiles` tiles. */
  private def long(side: Int) = 0.35 * side

  "truchet-tiles.md" should {
    "draw the pattern, one slash per arc" in {
      page.picture(page.flips) must beEqualTo(
        List(
          "/ /  \\/  \\/ ",
          " / /\\  /\\  /",
          "/ /  \\/ /  \\",
          " / /\\  / /\\ ",
          "/  \\ \\ \\/  \\",
          " /\\ \\ \\  /\\ ",
          "/ /  \\/ / / ",
          " / /\\  / / /",
          "/  \\/  \\/  \\",
          " /\\  /\\  /\\ ",
          " \\/ /  \\ \\/ ",
          "\\  / /\\ \\  /"
        ).mkString("\n")
      )
    }

    "see five loops in the curves alone, a gap below them, and none of the other curves" in
      (persistences(page.alone.dim(1)).take(7).map(r2) must beEqualTo(List(6.51, 6.51, 6.29, 6.29, 6.29, 4.90, 4.90)))
        .and(page.loopsAlone must beEqualTo(5))
        .and(page.alone.dim(1).bars.count(_.persistence > 1) must beEqualTo(25))

    "see 17 curves relative to the boundary, 5 pieces off the boundary, and the picture in degree 2" in
      (page.relative.dim(1).longerThan(page.long).size must beEqualTo(17))
        .and(persistences(page.relative.dim(1)).slice(15, 19).map(r2) must beEqualTo(List(6.29, 6.29, 4.90, 4.90)))
        .and(page.relative.dim(0).size must beEqualTo(5))
        .and(page.relative.dim(2).triples.map((k, b, d) => (k, r2(b), d)) must beEqualTo(List((2, 6.51, infinity))))
        .and(page.relative.dim(2).bars.head.birth must beEqualTo(page.pixels.flatten.max))

    "find 5 loops and 12 curves leaving the picture by tracing the tiles, as many as the long bars" in
      (page.closed must beEqualTo(5))
        .and(page.running must beEqualTo(12))
        .and(page.closed + page.running must beEqualTo(page.curves))
        .and(page.loopsAlone must beEqualTo(page.closed))

    "tell the loops from the other curves by the cycles that reach the boundary" in
      (page.reachingBoundary must beEqualTo(12))
        .and(page.reachingBoundary must beEqualTo(page.running))
        .and(
          page.relative.dim(1).longerThan(page.long).bars.map(_.persistence).map(r2).toSet must beEqualTo(
            Set(6.51, 6.29)
          )
        )

    "have the bars of the pair in the framed image, and 97 long bars in a 24 x 24 pattern" in
      (page.sameBars must beTrue)
        .and(page.bigCurves must beEqualTo(97))
        .and(page.bigClosed must beEqualTo(49))
        .and(page.bigRunning must beEqualTo(48))
        .and(page.bigCurves must beEqualTo(page.bigClosed + page.bigRunning))

    "have 31 bars of 4.90, 505 shorter than a pixel, and (tiles + 1)^2 - 1 longer than a pixel" in {
      val lengths = persistences(page.relative.dim(1))
      (lengths.count(l => l > 4.8 && l < 5.0) must beEqualTo(31))
        .and(lengths.count(_ < 1.0) must beEqualTo(505))
        .and(lengths.count(_ > 1.0) must beEqualTo(48))
    }

    "give, in black and white, exactly the bars [0, 1) of the curves, of the loops, and the picture" in {
      page.flat.triples.groupBy(identity).view.mapValues(_.size).toMap must beEqualTo(
        Map((0, 0.0, 1.0) -> 5, (1, 0.0, 1.0) -> 17, (2, 1.0, infinity) -> 1)
      )
    }
  }

  "other Truchet patterns" should {
    // The framed image is checked against the pair itself above; here it carries the claim about the long bars.
    "have as many long bars in the framed image as the trace has curves, with a gap below them, whatever the tiles" in {
      val failures = for
        tiles <- List(3, 5, 8, 12)
        seed <- 1L to 6L
        flips = page.tossTiles(tiles, seed)
        side = 16
        lengths = page
          .framed(page.distanceImage(flips, side))
          .dim(1)
          .bars
          .filter(_.birth > -1.0)
          .map(_.persistence)
          .sorted
          .reverse
        (closed, running) = page.trace(flips)
        problem <- List(
          Option.when(lengths.count(_ > long(side)) != closed + running)(
            s"${lengths.count(_ > long(side))} long bars, ${closed + running} curves"
          ),
          Option.when(running != 2 * tiles)(s"$running curves leave the picture"),
          Option.when(lengths.count(_ > 1.0) != (tiles + 1) * (tiles + 1) - 1)(
            s"${lengths.count(_ > 1.0)} bars over a pixel"
          )
        ).flatten
      yield s"$tiles tiles, seed $seed: $problem"
      failures must beEmpty
    }

    "have cycles that reach the boundary exactly for the curves that leave the picture, and a boundary that says so" in {
      val failures = (1L to 8L).flatMap(seed => disagreements(seed).map(problem => s"seed $seed: $problem"))
      failures must beEmpty
    }
  }

  /** What the page says about the curves of the 5 x 5 pattern of `seed`, checked on the pair itself: the long bars are
    * the traced curves, a cycle has a boundary in the picture for the curves that leave it and for no loop (the
    * boundary lies on the boundary, as it must for a cycle of the pair), and reaches the boundary exactly then.
    */
  private def disagreements(seed: Long): List[String] =
    val flips = page.tossTiles(5, seed)
    val grid = CubicalImage.fromGrayscale2D(page.distanceImage(flips, page.side))
    val relative = Persistence(Relative.ofBoundary(grid), maxDimension = 1)
    import relative.given
    val cubes = summon[Cube is OrderedCell]
    val zero = summon[relative.Coefficient is Field].zero
    val (closed, running) = page.trace(flips)
    val curves = relative.dim(1).longerThan(page.long).bars
    // The boundary of the cycle in the picture, not in the pair: a chain of points of the boundary.
    def endsOf(bar: PersistenceBar[Double, Chain[RelativeCell[Cube], relative.Coefficient]]) =
      val sums = scala.collection.mutable.Map.empty[Cube, relative.Coefficient]
      bar.representative.terms.foreach { (edge, a) =>
        cubes.boundary(edge.cell)[relative.Coefficient].foreach { (end, sign) =>
          sums(end) = sums.get(end).fold(a * sign)(_ + a * sign)
        }
      }
      sums.filter((_, c) => c != zero).keySet
    def reaches(bar: PersistenceBar[Double, Chain[RelativeCell[Cube], relative.Coefficient]]) =
      bar.representative.cells.exists(edge =>
        cubes.boundary(edge.cell)[relative.Coefficient].exists((end, _) => grid.onBoundary(end))
      )
    List(
      Option.when(curves.size != closed + running)(s"${curves.size} long bars, ${closed + running} curves"),
      Option.when(curves.count(b => endsOf(b).nonEmpty) != running)(
        s"${curves.count(b => endsOf(b).nonEmpty)} cycles with a boundary, $running curves leave"
      ),
      Option.when(curves.exists(b => endsOf(b).nonEmpty != reaches(b)))(
        "a cycle reaches the boundary without ending there"
      ),
      Option.when(curves.exists(b => !endsOf(b).forall(grid.onBoundary)))(
        "a representative has a boundary off the boundary"
      ),
      Option.when(relative.dim(0).size != closed)(s"${relative.dim(0).size} pieces off the boundary, $closed loops")
    ).flatten
end TruchetTilesSpec
