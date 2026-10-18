package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/images.md` quotes, on the values its "whole script" fence defines (see
  * `build.sbt`).
  */
class ImagesSpec extends Specification:
  sequential

  private val page = ImagesScript

  private def rounded(bars: List[(Int, Double, Double)]) =
    bars.map((dim, birth, death) => (dim, math.round(birth * 1e9), math.round(death * 1e9))).sorted
  private def r3(x: Double) = if x.isInfinite then x else math.round(x * 1000) / 1000.0
  private def shown(d: PersistenceDiagram[Cube]) =
    d.longerThan(0.3).triples.map((k, b, e) => (k, r3(b), r3(e))).sortBy(t => (t._1, -t._3))

  "images.md" should {
    "count the cells and bars" in
      (page.stream.iterator.size must beEqualTo(57 * 57))
        .and(page.dark.size must beEqualTo(221))
        .and(page.dark.significant().size must beEqualTo(170))
    "agree between Persistence and the fast cubical engine, bar for bar" in {
      rounded(page.dark.triples) must beEqualTo(rounded(page.fastBars.map(_.toTriple)))
    }
    "find in the dark: a background, an enclosed dark disc, and two holes (the ring and the blob)" in {
      shown(page.dark) must beEqualTo(
        List((0, 0.0, Double.PositiveInfinity), (0, 0.001, 1.002), (1, 0.062, 1.099), (1, 0.064, 0.9))
      )
    }
    "find in the bright: the ring's component, the blob's component, and the ring's one loop" in {
      shown(page.bright) must beEqualTo(
        List((0, -1.099, Double.PositiveInfinity), (0, -0.9, -0.063), (1, -1.004, -0.001))
      )
    }
  }
