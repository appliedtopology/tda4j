package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/images.md` and asserts every number the page quotes. */
class ImagesSpec extends Specification:
  sequential

  private case class Result(
    picture: List[String],
    cells: Int,
    bars: Int,
    enginesAgree: Boolean,
    sublevel: List[(Int, Double, Double)],
    superlevel: List[(Int, Double, Double)],
    keptByOnePercentOfRange: Int
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    // A 28 x 28 "photograph": a bright ring (brightness about 1), a dimmer solid blob (about 0.8), a dark background, and noise.
    val random = new java.util.Random(5L)
    val size = 28
    val pixels = Array.tabulate(size, size) { (row, column) =>
      val distanceToRingCentre = math.hypot(row - 12.0, column - 12.0)
      val distanceToBlobCentre = math.hypot(row - 23.0, column - 23.0)
      val signal =
        if distanceToRingCentre >= 6.0 && distanceToRingCentre <= 8.0 then 1.0
        else if distanceToBlobCentre <= 2.5 then 0.8
        else 0.0
      signal + 0.1 * random.nextDouble()
    }
    val picture = pixels.map(_.map(v => if v > 0.5 then "#" else ".").mkString).toList

    def significant(bars: List[(Int, Double, Double)]) =
      bars.filter((_, birth, death) => death.isInfinite || death - birth > 0.3).sortBy(bar => (bar._1, bar._2))

    // Sublevel: dark things first. Superlevel (sublevel = false): bright things first; the image is negated, so values are -brightness.
    val sublevelStream = streams.CubicalImage.fromGrayscale2D(pixels, sublevel = true)
    val superlevelStream = streams.CubicalImage.fromGrayscale2D(pixels, sublevel = false)

    val engine = homology.CubicalHomologyEngine[CoefficientT, Double]()
    val sublevelBars = engine.persistentHomology(sublevelStream).diagramAt(Double.PositiveInfinity)
    val superlevelBars = engine.persistentHomology(superlevelStream).diagramAt(Double.PositiveInfinity)

    val range = pixels.flatten.max - pixels.flatten.min
    val keptByOnePercentOfRange =
      sublevelBars.count((_, birth, death) => death.isInfinite || death - birth > 0.01 * range)

    val fast = homology.FastCubicalHomologyEngine[CoefficientT]()
    def rounded(bars: List[(Int, Double, Double)]) =
      bars.map((dim, birth, death) => (dim, math.round(birth * 1e9), math.round(death * 1e9))).sorted
    val fastBars = fast.persistentHomology(sublevelStream).map(_.toTriple)

    Result(
      picture,
      sublevelStream.iterator.size,
      sublevelBars.size,
      rounded(sublevelBars) == rounded(fastBars),
      significant(sublevelBars),
      significant(superlevelBars),
      keptByOnePercentOfRange
    )

  "images.md" should {
    lazy val r = page()
    "print the picture and count the cells" in {
      r.picture.foreach(println)
      (r.cells must beEqualTo(57 * 57)).and(r.bars must beEqualTo(1625))
    }
    "keep far more bars at the 1%-of-range default than the noise-based cut-off does" in {
      println("kept by 1% of range: " + r.keptByOnePercentOfRange)
      r.keptByOnePercentOfRange must beGreaterThan(100)
    }
    "agree between the generic and the fast cubical engine, bar for bar" in { r.enginesAgree must beTrue }
    "find in the dark: a background, an enclosed dark disc, and two holes (the ring and the blob)" in {
      println(r.sublevel)
      (r.sublevel.map(_._1) must beEqualTo(List(0, 0, 1, 1)))
        .and(r.sublevel(0)._3.isInfinite must beTrue)
        .and(r.sublevel(1)._3 must beCloseTo(1.0, 0.1))
        .and(r.sublevel(2)._3 must beCloseTo(1.1, 0.1))
        .and(r.sublevel(3)._3 must beCloseTo(0.9, 0.1))
    }
    "find in the bright: the ring's component, the blob's component, and the ring's one loop" in {
      println(r.superlevel)
      (r.superlevel.map(_._1) must beEqualTo(List(0, 0, 1)))
        .and(r.superlevel(0)._2 must beCloseTo(-1.1, 0.1))
        .and(r.superlevel(1)._2 must beCloseTo(-0.9, 0.1))
        .and(r.superlevel(2)._2 must beCloseTo(-1.0, 0.1))
    }
  }
