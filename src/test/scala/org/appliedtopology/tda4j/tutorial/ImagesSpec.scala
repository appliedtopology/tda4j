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

  "images.md" should {
    "count the cells and bars" in
      (page.sublevelStream.iterator.size must beEqualTo(57 * 57)).and(page.sublevelBars.size must beEqualTo(1625))
    "keep far more bars at the 1%-of-range default than the noise-based cut-off does" in {
      val flat = page.pixels.flatten
      val range = flat.max - flat.min
      page.sublevelBars.count((_, birth, death) => death.isInfinite || death - birth > 0.01 * range) must beGreaterThan(
        100
      )
    }
    "agree between the generic and the fast cubical engine, bar for bar" in {
      rounded(page.sublevelBars) must beEqualTo(rounded(page.fastBars))
    }
    "find in the dark: a background, an enclosed dark disc, and two holes (the ring and the blob)" in {
      val dark = page.dark
      (dark.map(_._1) must beEqualTo(List(0, 0, 1, 1)))
        .and(dark(0)._3.isInfinite must beTrue)
        .and(dark(1)._3 must beCloseTo(1.0, 0.1))
        .and(dark(2)._3 must beCloseTo(1.1, 0.1))
        .and(dark(3)._3 must beCloseTo(0.9, 0.1))
    }
    "find in the bright: the ring's component, the blob's component, and the ring's one loop" in {
      val bright = page.bright
      (bright.map(_._1) must beEqualTo(List(0, 0, 1)))
        .and(bright(0)._2 must beCloseTo(-1.1, 0.1))
        .and(bright(1)._2 must beCloseTo(-0.9, 0.1))
        .and(bright(2)._2 must beCloseTo(-1.0, 0.1))
    }
  }
