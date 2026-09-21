package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.barcode.*
import org.specs2.mutable

/** Verifies the MATLAB-facing facade's *conversion layer*, not the underlying engines (those already have their own
  * extensive cross-validation elsewhere -- see CLAUDE.md). Specifically: that `TDA4j`'s string-option parsing wires up
  * to the same engine calls a direct Scala caller would make, and that `PersistenceResult`'s double/Inf/dimension
  * conversion doesn't scramble anything relative to calling the engine directly. See WORKLOG-matlab-api.md for the
  * design this checks against, and note (per that worklog and CLAUDE.md) that MATLAB's own Java marshalling of
  * `double[][]`/`String[]` across the bridge is NOT covered by anything in this file -- unverified from Scala, flagged
  * explicitly rather than implied.
  */
class TDA4jSpec extends mutable.Specification:
  private val points: Array[Array[Double]] = Array(
    Array(0.0, 0.0),
    Array(1.0, 0.0),
    Array(1.0, 1.0),
    Array(0.0, 1.0),
    Array(0.5, 2.0),
    Array(2.0, 0.5)
  )

  private def triples(m: Array[Array[Double]]): List[(Int, Double, Double)] =
    m.toList.map(row => (row(0).toInt, row(1), row(2)))

  private def euclideanDistanceMatrix(pts: Array[Array[Double]]): Array[Array[Double]] =
    val n = pts.length
    Array.tabulate(n, n) { (i, j) =>
      math.sqrt(pts(i).zip(pts(j)).map((a, b) => (a - b) * (a - b)).sum)
    }

  "TDA4j.computeFromPoints with default options (complex=vr, engine=ripser, field=Z, prime=2, maxDimension=2)" should {
    "match RipserCohomologyContext[Fp(2)] driven directly" in {
      // RipserCohomologyContext's own maxDimension now means "top homological degree reported," fixed at its
      // own source (see .claude/WORKLOG-maxdim-semantics-fix.md) -- so the facade's engine=ripser path
      // (TDA4j.computeGeneric) is now a fully transparent passthrough of requestedMaxDimension, with no
      // +1-and-filter workaround on either side of this comparison anymore.
      val viaFacade = triples(TDA4j.computeFromPoints(points).toArray())

      val ff = new FiniteField(2)
      import ff.given
      val metricSpace = EuclideanMetricSpace(points)

      def toDouble(e: BarcodeEndpoint[Double]): Double = e match
        case NegativeInfinity() => Double.NegativeInfinity
        case PositiveInfinity() => Double.PositiveInfinity
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v

      val direct = RipserCohomologyContext[ff.Fp](metricSpace, 2)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

      viaFacade must containTheSameElementsAs(direct)
    }
  }

  "RipserCohomologyContext's maxDimension semantics fix" should {
    "resolve the same barcode whether asked for degree k directly or degree k+1 with the extra dimension filtered" in {
      // Regression pin for the fix itself (.claude/WORKLOG-maxdim-semantics-fix.md): before the fix, calling
      // directly at maxDimension=2 produced spurious essential dim-2 bars that calling at maxDimension=3 and
      // filtering to dim<=2 did not -- exactly the discriminating check that originally caught the bug (see
      // .claude/WORKLOG-ripser-comparison.md). If the fix ever regresses, these two calls disagree again.
      val ff = new FiniteField(2)
      import ff.given
      val metricSpace = EuclideanMetricSpace(points)

      def toDouble(e: BarcodeEndpoint[Double]): Double = e match
        case NegativeInfinity() => Double.NegativeInfinity
        case PositiveInfinity() => Double.PositiveInfinity
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v

      val direct = RipserCohomologyContext[ff.Fp](metricSpace, 2)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

      val viaOneHigherFiltered = RipserCohomologyContext[ff.Fp](metricSpace, 3)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))
        .filter(_._1 <= 2)

      direct must containTheSameElementsAs(viaOneHigherFiltered)
    }
  }

  "engine=naive, through the facade" should {
    "agree with the default engine=ripser, through the facade" in {
      val ripser = triples(TDA4j.computeFromPoints(points).toArray())
      val naive = triples(TDA4j.computeFromPoints(points, Array("engine", "naive")).toArray())
      naive must containTheSameElementsAs(ripser)
    }
  }

  "computeFromDistanceMatrix" should {
    "agree with computeFromPoints given the same cloud's own Euclidean distances" in {
      val fromDist = triples(TDA4j.computeFromDistanceMatrix(euclideanDistanceMatrix(points)).toArray())
      val fromPoints = triples(TDA4j.computeFromPoints(points).toArray())
      fromDist must containTheSameElementsAs(fromPoints)
    }
  }

  "field=R" should {
    "agree (up to floating-point tolerance) with the default field=Z, through the facade" in {
      val zResult = triples(TDA4j.computeFromPoints(points).toArray()).sortBy(t => (t._1, t._2, t._3))
      val rResult =
        triples(TDA4j.computeFromPoints(points, Array("field", "R")).toArray()).sortBy(t => (t._1, t._2, t._3))

      zResult.length must be_==(rResult.length)
      val agree = zResult.zip(rResult).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
        d1 == d2 &&
        math.abs(b1 - b2) < 1e-9 &&
        (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
      }
      agree must beTrue
    }
  }

  "option parsing" should {
    "reject an odd-length options array" in {
      TDA4j.computeFromPoints(points, Array("engine")) must throwA[IllegalArgumentException]
    }
    "reject an unrecognized option key" in {
      TDA4j.computeFromPoints(points, Array("bogus", "value")) must throwA[IllegalArgumentException]
    }
    "reject engine=ripser combined with complex=alpha" in {
      TDA4j
        .computeFromPoints(points, Array("complex", "alpha", "engine", "ripser")) must throwA[IllegalArgumentException]
    }
    "reject engine=chunks combined with complex=alpha" in {
      TDA4j
        .computeFromPoints(points, Array("complex", "alpha", "engine", "chunks")) must throwA[IllegalArgumentException]
    }
    "reject complex=alpha via computeFromDistanceMatrix (alpha needs coordinates)" in {
      TDA4j.computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "alpha")) must throwA[
        IllegalArgumentException
      ]
    }
    "reject engine=ripser combined with complex=cech" in {
      TDA4j
        .computeFromPoints(points, Array("complex", "cech", "engine", "ripser")) must throwA[IllegalArgumentException]
    }
    "reject complex=cech via computeFromDistanceMatrix (cech needs coordinates)" in {
      TDA4j.computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "cech")) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "complex=cech, through the facade" should {
    "default to engine=naive and agree with an explicit engine=chunks call" in {
      // Cross-validated directly against the naive engine on random Cech streams in CechStreamSpec -- this is a
      // conversion-layer check (does the facade's own dispatch wire engine=chunks up correctly for complex=cech),
      // not a re-proof that chunks is correct on Cech streams in general.
      val naive = triples(TDA4j.computeFromPoints(points, Array("complex", "cech")).toArray())
      val chunks =
        triples(TDA4j.computeFromPoints(points, Array("complex", "cech", "engine", "chunks")).toArray())
      naive must containTheSameElementsAs(chunks)
    }

    "produce a genuinely different barcode than complex=vr on the same points (Cech uses radius, VR uses diameter)" in {
      // Not just "doesn't crash" -- Cech and VR are different filtrations on the same point set, so their own
      // birth/death VALUES should differ (same discriminator CechStreamSpec's own unit-equilateral-triangle
      // fixture uses), even though both should reveal the same underlying topology at some threshold.
      val vrBars = triples(TDA4j.computeFromPoints(points).toArray())
      val cechBars = triples(TDA4j.computeFromPoints(points, Array("complex", "cech")).toArray())
      val vrBirths = vrBars.map(_._2).toSet.toSeq
      val cechBirths = cechBars.map(_._2).toSet.toSeq
      vrBirths must not(containTheSameElementsAs(cechBirths))
    }

    "have representative chains readable for engine=naive and engine=chunks alike" in {
      val naiveResult = TDA4j.computeFromPoints(points, Array("complex", "cech"))
      val chunksResult = TDA4j.computeFromPoints(points, Array("complex", "cech", "engine", "chunks"))
      def allReadable(r: PersistenceResult): Boolean =
        (0 until r.size()).forall(i => r.cycleVertices(i).length == r.cycleCoefficients(i).length)
      allReadable(naiveResult) must beTrue
      allReadable(chunksResult) must beTrue
    }
  }

  "complex=cubical, through the facade" should {
    // A simple 3x3 image with a single-pixel hole in the middle -- the standard "ring" cubical fixture this
    // codebase already uses elsewhere (CLAUDE.md's Perseus missing-pixel test): topologically an 8-pixel ring,
    // homotopy equivalent to S^1, so it has a genuine essential H1 bar. Cheap enough to run at every engine/field
    // combination below without needing property-test-scale generation.
    // The center is PERMANENTLY missing (+Infinity), not just a later finite value -- matching io's own
    // established Perseus missing-pixel fixture (PerseusSpec.scala): only +Infinity guarantees the center's own
    // 2-cell never enters the filtration at any finite threshold, which is what makes the ring's H1 class
    // essential rather than eventually filled in.
    val ringShape = Array(3, 3)
    val ringFlat = Array(
      0.0,
      0.0,
      0.0,
      0.0,
      Double.PositiveInfinity,
      0.0,
      0.0,
      0.0,
      0.0
    )

    "computeFromCubicalImage's default (engine=naive) matches CubicalHomologyContext driven directly" in {
      val viaFacade = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())

      val stream = CubicalImage.fromFlatArray(ringShape.toIndexedSeq, ringFlat.toIndexedSeq)
      given Double is Field = Field.DoubleApproximated(1e-9)
      val direct = CubicalHomologyContext[Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
        .map { case (d, b, e) => (d, b, e) }

      viaFacade must containTheSameElementsAs(direct)
    }

    "engine=chunks agrees with the default engine=naive" in {
      val naive = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val chunks = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "chunks")).toArray())
      naive must containTheSameElementsAs(chunks)
    }

    "computeFromImage (the 2D double[][] convenience) matches computeFromCubicalImage on the same grid" in {
      val pixels = Array(
        Array(0.0, 0.0, 0.0),
        Array(0.0, Double.PositiveInfinity, 0.0),
        Array(0.0, 0.0, 0.0)
      )
      val viaImage = triples(TDA4j.computeFromImage(pixels).toArray())
      val viaCubicalImage = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())
      viaImage must containTheSameElementsAs(viaCubicalImage)
    }

    "finds the ring's genuine essential H1 bar" in {
      val bars = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())
      bars.exists { case (dim, _, death) => dim == 1 && death.isPosInfinity } must beTrue
    }

    "reject engine=ripser" in {
      TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "ripser")) must throwA[
        IllegalArgumentException
      ]
    }

    "reject a flatValues length that doesn't match shape's product" in {
      TDA4j.computeFromCubicalImage(Array(2, 2), Array(1.0, 2.0, 3.0)) must throwA[IllegalArgumentException]
    }

    "reject an unrecognized sublevel value" in {
      TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("sublevel", "sideways")) must throwA[
        IllegalArgumentException
      ]
    }

    "sublevel=false genuinely changes the computed barcode (it's a different filtration ORDER, not a relabeling)" in {
      // sublevel=false negates every raw value before it ever reaches CubicalGridStream (CubicalImage.scala's own
      // "sublevel of -f is superlevel of f" doc), which reverses the ORDER cells enter the filtration in -- this
      // is a genuinely different complex-over-time, not simply the sublevel=true barcode with every birth/death
      // sign-flipped (that would only hold under a symmetry this fixture has no reason to have). So the only
      // fixture-independent claim to check here is that the option actually has an effect, via the simplest
      // observable evidence: with the center pixel permanently missing (+Infinity) under sublevel=true, negating
      // gives it -Infinity, meaning it is now permanently PRESENT from the very start instead -- flipping the
      // ring from "has a permanent hole" (an essential H1 bar) to "the disk is filled in from birth" (no
      // essential H1 bar at all).
      val sublevelBars = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val superlevelBars =
        triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("sublevel", "false")).toArray())
      def hasEssentialH1(bars: List[(Int, Double, Double)]): Boolean =
        bars.exists { case (dim, _, death) => dim == 1 && death.isPosInfinity }
      (hasEssentialH1(sublevelBars) must beTrue) and (hasEssentialH1(superlevelBars) must beFalse)
    }

    "have representative chains readable for engine=naive and engine=chunks alike, as doubled-coordinate arrays" in {
      val naiveResult = TDA4j.computeFromCubicalImage(ringShape, ringFlat)
      val chunksResult = TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "chunks"))
      def allReadable(r: PersistenceResult): Boolean =
        (0 until r.size()).forall(i => r.cycleVertices(i).length == r.cycleCoefficients(i).length)
      allReadable(naiveResult) must beTrue
      allReadable(chunksResult) must beTrue
    }
  }

  "representative chains" should {
    "be readable for at least one engine=ripser bar, with matching vertex/coefficient array lengths" in {
      val result = TDA4j.computeFromPoints(points)
      val readable = (0 until result.size()).flatMap { i =>
        try
          val verts = result.cycleVertices(i)
          val coeffs = result.cycleCoefficients(i)
          Some(verts.length == coeffs.length)
        catch case _: UnsupportedOperationException => None
      }
      readable must not(beEmpty)
      readable must contain(true).forall
    }

    // CellularPersistenceInChunksContext.barcodeAt now records a real representative for EVERY bar, at every
    // dimension (see .claude/CLAUDE.md's coefficients-and-representatives principle and
    // .claude/WORKLOG-chunks-representatives-incremental.md) -- the earlier "dimension-0 only" gap is closed,
    // so this checks every reported bar, not just dimension 0.
    "be readable for every engine=chunks bar, at every dimension, with matching vertex/coefficient array lengths" in {
      val chunksResult = TDA4j.computeFromPoints(points, Array("engine", "chunks"))
      chunksResult.size() must be_>(0)
      // at least one bar above dimension 0, or this test isn't exercising the gap that used to exist
      (0 until chunksResult.size()).exists(chunksResult.dimension(_) > 0) must beTrue
      (0 until chunksResult.size()).forall { i =>
        chunksResult.cycleVertices(i).length == chunksResult.cycleCoefficients(i).length
      } must beTrue
    }
  }
