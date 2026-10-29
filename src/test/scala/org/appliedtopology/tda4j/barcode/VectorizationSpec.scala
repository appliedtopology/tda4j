package org.appliedtopology.tda4j

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

class VectorizationSpec extends Specification with ScalaCheck:
  private def bar(dim: Int, birth: Double, death: Double): PersistenceBar[Double, Nothing] =
    PersistenceBar[Double, Nothing](dim, ClosedEndpoint(birth), OpenEndpoint(death))
  private def essentialBar(dim: Int, birth: Double): PersistenceBar[Double, Nothing] =
    PersistenceBar[Double, Nothing](dim, ClosedEndpoint(birth), PositiveInfinity())

  private def trapezoidalIntegral(ys: Array[Double], step: Double): Double =
    step * (ys.head / 2.0 + ys.slice(1, ys.length - 1).sum + ys.last / 2.0)

  "landscape" >> {
    "closed-form check: summing every level's integral recovers sum((death-birth)^2 / 4) exactly (up to " +
      "discretization tolerance -- each bar's tent has triangular area persistence * (persistence/2) / 2, and " +
      "the per-t sum of order statistics is exactly the per-t sum of tent values, so integrating a fine-enough " +
      "grid trapezoidally should match tightly)" >> {
        val diagram = List(bar(0, 1.0, 3.0), bar(0, 0.5, 6.5), bar(0, 4.0, 4.4), bar(0, 7.0, 9.0))
        val tMin = 0.0
        val tMax = 10.0
        val resolution = 4001 // fine grid so any breakpoint/grid misalignment contributes negligible error
        val step = (tMax - tMin) / (resolution - 1)
        // numLevels must cover every bar, or the top-k truncation would drop some tents' contributions entirely
        // and break the identity -- see Vectorization.landscape's own doc for why.
        val levels = Vectorization.landscape(diagram, numLevels = diagram.size, tMin, tMax, resolution)

        val totalIntegral = levels.map(trapezoidalIntegral(_, step)).sum
        val expected = diagram.map(b => math.pow(DiagramPoint.of(b).persistence, 2) / 4.0).sum
        totalIntegral must beCloseTo(expected, 1e-3)
      }

    "a single bar only populates level 0, matching its tent function exactly" >> {
      val diagram = List(bar(0, 2.0, 6.0))
      val levels = Vectorization.landscape(diagram, numLevels = 2, tMin = 0.0, tMax = 10.0, resolution = 11)
      // grid step 1.0, points at t=0..10; tent(t) = max(0, min(t-2, 6-t))
      val expectedLevel0 = (0 to 10).map(t => math.max(0.0, math.min(t - 2.0, 6.0 - t)))
      levels(0).toSeq must beEqualTo(expectedLevel0)
      levels(1).toSeq must beEqualTo(Seq.fill(11)(0.0))
    }

    "an essential bar contributes an unbounded ramp t - birth, needing no special-casing" >> {
      val diagram = List(essentialBar(0, 2.0))
      val levels = Vectorization.landscape(diagram, numLevels = 1, tMin = 0.0, tMax = 10.0, resolution = 11)
      val expected = (0 to 10).map(t => math.max(0.0, t - 2.0))
      levels(0).toSeq must beEqualTo(expected)
    }

    "levels are pointwise non-increasing in k (each is an order statistic of the same multiset)" >> {
      val diagram = List(bar(0, 0.0, 4.0), bar(0, 1.0, 5.0), bar(0, 2.0, 3.0))
      val levels = Vectorization.landscape(diagram, numLevels = 3, tMin = 0.0, tMax = 5.0, resolution = 51)
      forall(0 until 51) { j =>
        forall(0 until 2) { k =>
          levels(k)(j) must beGreaterThanOrEqualTo(levels(k + 1)(j))
        }
      }
    }

    "rejects a degenerate or backwards grid" >> {
      val diagram = List(bar(0, 0.0, 1.0))
      Vectorization.landscape(diagram, 1, tMin = 5.0, tMax = 1.0, resolution = 10) must throwA[
        IllegalArgumentException
      ]
      Vectorization.landscape(diagram, 1, tMin = 0.0, tMax = 1.0, resolution = 1) must throwA[
        IllegalArgumentException
      ]
      Vectorization.landscape(diagram, 0, tMin = 0.0, tMax = 1.0, resolution = 10) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "persistenceImage" >> {
    "a single point's total mass over a wide-enough grid is its own weight (here, 1 -- persistence >= cap)" >> {
      val diagram = List(bar(0, 3.0, 5.0)) // birth-persistence coords (3.0, 2.0)
      val sigma = 0.2
      // +-10 sigma margin: erf(10/sqrt(2)) is 1 - (far below double precision), so no meaningful tail is cut off
      val image = Vectorization.persistenceImage(
        diagram,
        sigma,
        birthRange = (3.0 - 10 * sigma, 3.0 + 10 * sigma),
        persistenceRange = (2.0 - 10 * sigma, 2.0 + 10 * sigma),
        birthResolution = 40,
        persistenceResolution = 40,
        weightCap = Some(1.0) // persistence 2.0 >= cap 1.0 -> full weight 1.0
      )
      image.flatten.sum must beCloseTo(1.0, 1e-9)
    }

    "the piecewise-linear weight halves the total mass at persistence = cap / 2" >> {
      val diagram = List(bar(0, 0.0, 1.0)) // persistence 1.0
      val sigma = 0.1
      val image = Vectorization.persistenceImage(
        diagram,
        sigma,
        birthRange = (0.0 - 10 * sigma, 0.0 + 10 * sigma),
        persistenceRange = (1.0 - 10 * sigma, 1.0 + 10 * sigma),
        birthResolution = 40,
        persistenceResolution = 40,
        weightCap = Some(2.0) // persistence 1.0 is exactly half of cap 2.0 -> weight 0.5
      )
      image.flatten.sum must beCloseTo(0.5, 1e-9)
    }

    "drops essential bars entirely rather than letting them underflow silently" >> {
      val diagram = List(essentialBar(0, 1.0), bar(0, 0.0, 0.0)) // second bar has persistence 0 -> weight 0 too
      val image = Vectorization.persistenceImage(
        diagram,
        sigma = 1.0,
        birthRange = (-5.0, 5.0),
        persistenceRange = (-5.0, 5.0),
        birthResolution = 10,
        persistenceResolution = 10
      )
      image.flatten.forall(_ == 0.0) must beTrue
    }

    "every pixel is non-negative" >>
      forAll(
        Gen
          .choose(0, 4)
          .flatMap(n =>
            Gen.listOfN(
              n,
              for
                b <- Gen.choose(-2.0, 2.0)
                p <- Gen.choose(0.01, 3.0)
              yield bar(0, b, b + p)
            )
          )
      ) { diagram =>
        val image = Vectorization.persistenceImage(
          diagram,
          sigma = 0.5,
          birthRange = (-5.0, 5.0),
          persistenceRange = (-5.0, 5.0),
          birthResolution = 8,
          persistenceResolution = 8
        )
        image.flatten.forall(_ >= 0.0) must beTrue
      }

    "matches independent Riemann-sum numerical integration of the raw weighted Gaussian density" >> {
      // Independent oracle: evaluates the actual 2D Gaussian PDF (not the CDF-difference trick) on a fine
      // sub-grid within one pixel and sums, rather than reusing any part of Vectorization's own integration path.
      val diagram = List(bar(0, 0.3, 1.1)) // birth-persistence coords (0.3, 0.8)
      val sigma = 0.7
      val birthRange = (-2.0, 2.0)
      val persistenceRange = (-2.0, 2.0)
      val birthResolution = 6
      val persistenceResolution = 6
      val image = Vectorization.persistenceImage(
        diagram,
        sigma,
        birthRange,
        persistenceRange,
        birthResolution,
        persistenceResolution,
        weightCap = Some(0.8) // persistence == cap -> weight 1.0, so the raw Gaussian is directly comparable
      )

      def gaussianPdf(x: Double, y: Double): Double =
        val dx = x - 0.3
        val dy = y - 0.8
        math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)) / (2 * math.Pi * sigma * sigma)

      val bStep = (birthRange._2 - birthRange._1) / birthResolution
      val pStep = (persistenceRange._2 - persistenceRange._1) / persistenceResolution
      val subSamples = 200 // fine sub-grid per pixel for the Riemann sum
      def riemannPixel(r: Int, c: Int): Double =
        val bLo = birthRange._1 + r * bStep
        val pLo = persistenceRange._1 + c * pStep
        var total = 0.0
        for i <- 0 until subSamples; j <- 0 until subSamples do
          val x = bLo + (i + 0.5) * bStep / subSamples
          val y = pLo + (j + 0.5) * pStep / subSamples
          total += gaussianPdf(x, y)
        total * (bStep / subSamples) * (pStep / subSamples)

      // Only check a couple of pixels near the point (where mass is concentrated) -- the Riemann sum above is
      // O(resolution^2 * subSamples^2) and not worth spending on every low-mass corner pixel.
      val nearPixels = for
        r <- 0 until birthResolution
        c <- 0 until persistenceResolution
        if riemannPixel(r, c) > 1e-4
      yield (r, c)
      nearPixels must not(beEmpty)
      forall(nearPixels) { (r, c) =>
        image(r)(c) must beCloseTo(riemannPixel(r, c), 1e-3)
      }
    }

    "rejects non-positive sigma or a degenerate grid" >> {
      val diagram = List(bar(0, 0.0, 1.0))
      Vectorization.persistenceImage(diagram, 0.0, (0.0, 1.0), (0.0, 1.0), 5, 5) must throwA[IllegalArgumentException]
      Vectorization.persistenceImage(diagram, 1.0, (1.0, 0.0), (0.0, 1.0), 5, 5) must throwA[IllegalArgumentException]
      Vectorization.persistenceImage(diagram, 1.0, (0.0, 1.0), (0.0, 1.0), 0, 5) must throwA[IllegalArgumentException]
    }
  }

  "bettiCurve" >> {
    "counts the bars alive at each sample, a bar ending exactly at a sample already gone there" >> {
      // [0,1), [0.5,2), [1,inf) sampled at 0, 0.5, 1, 1.5, 2: the first bar is gone at t = 1 (half-open), the second
      // is gone at t = 2, the essential one stays.
      val diagram = List(bar(1, 0.0, 1.0), bar(1, 0.5, 2.0), essentialBar(1, 1.0))
      Vectorization.bettiCurve(diagram, 0.0, 2.0, 5).toSeq must beEqualTo(Seq(1, 2, 2, 2, 1))
    }

    "integrates to the total persistence of the finite bars (a computed circle)" >> {
      val circle = (0 until 12).map(i => Array(math.cos(i * math.Pi / 6), math.sin(i * math.Pi / 6))).toArray
      val bars = Persistence(circle, maxDimension = 1).bars.filter(!_.death.isPosInfinity)
      val top = bars.map(_.death).max * 1.01
      val grid = 20001
      val curve = Vectorization.bettiCurve(bars, 0.0, top, grid)
      val step = top / (grid - 1)
      val integral = step * curve.map(_.toDouble).sum
      // a piecewise-constant curve sampled on a grid is off by less than one step per jump (two per bar)
      integral must beCloseTo(bars.map(_.persistence).sum, 2 * bars.size * step)
    }

    "rejects a degenerate grid" >> {
      Vectorization.bettiCurve(Nil, 1.0, 0.0, 5) must throwA[IllegalArgumentException]
      Vectorization.bettiCurve(Nil, 0.0, 1.0, 1) must throwA[IllegalArgumentException]
    }
  }

  "silhouette" >> {
    "is the weighted average of the tents: weights are persistence to the power" >> {
      // bars [0,2) and [0,4): tents rise to 1 at t = 1 and to 2 at t = 2 (the longer bar), weights 2 and 4.
      val diagram = List(bar(0, 0.0, 2.0), bar(0, 0.0, 4.0))
      val s = Vectorization.silhouette(diagram, 0.0, 4.0, 5) // t = 0, 1, 2, 3, 4
      // t = 1: (2*1 + 4*1)/6 = 1;  t = 2: (2*0 + 4*2)/6;  t = 3: (2*0 + 4*1)/6
      s(1) must beCloseTo(1.0, 1e-12)
      s(2) must beCloseTo(8.0 / 6.0, 1e-12)
      s(3) must beCloseTo(4.0 / 6.0, 1e-12)
    }

    "power 2 weights by squared persistence, so it differs from power 1" >> {
      val diagram = List(bar(0, 0.0, 2.0), bar(0, 0.0, 4.0))
      val s = Vectorization.silhouette(diagram, 0.0, 4.0, 5, power = 2.0)
      s(2) must beCloseTo((4.0 * 0.0 + 16.0 * 2.0) / 20.0, 1e-12)
    }

    "drops essential bars and is zero for an empty diagram" >> {
      Vectorization.silhouette(List(essentialBar(0, 1.0)), 0.0, 4.0, 5).toSeq must beEqualTo(Seq.fill(5)(0.0))
      Vectorization.silhouette(Nil, 0.0, 4.0, 5).toSeq must beEqualTo(Seq.fill(5)(0.0))
    }

    "rejects a non-positive power" >> {
      Vectorization.silhouette(List(bar(0, 0.0, 1.0)), 0.0, 1.0, 5, power = 0.0) must throwA[IllegalArgumentException]
    }
  }

  "persistenceEntropy" >> {
    "is ln n for n bars of equal length and 0 for one bar" >> {
      val equal = List.tabulate(5)(i => bar(1, i.toDouble, i + 2.0))
      Vectorization.persistenceEntropy(equal) must beCloseTo(math.log(5.0), 1e-12)
      Vectorization.persistenceEntropy(equal, normalized = true) must beCloseTo(1.0, 1e-12)
      Vectorization.persistenceEntropy(List(bar(1, 0.0, 3.0))) must beEqualTo(0.0)
    }

    "matches the hand-computed value for lengths 1, 1, 2 and ignores essential bars" >> {
      val diagram = List(bar(1, 0.0, 1.0), bar(1, 0.0, 1.0), bar(1, 0.0, 2.0), essentialBar(1, 0.0))
      val expected = -(2 * 0.25 * math.log(0.25) + 0.5 * math.log(0.5))
      Vectorization.persistenceEntropy(diagram) must beCloseTo(expected, 1e-12)
    }
  }
