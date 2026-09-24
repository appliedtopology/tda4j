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

  "engine=cohomology, through the facade" should {
    // CellularCohomologyContext -- .claude/DESIGN-generic-cohomology.md. Bar VALUES should agree with
    // engine=ripser on complex=vr (both compute persistent cohomology of the same Vietoris-Rips complex,
    // just one generic/materialized and one VR-specialized) -- not representative CONTENT, which
    // `CohomologySpec`'s own comment explains isn't a sound cross-engine claim on VR input (dimension 0 is
    // always fully tied, and the two engines' tie-breaks genuinely differ).
    "agree with the default engine=ripser, through the facade" in {
      val ripser = triples(TDA4j.computeFromPoints(points).toArray())
      val cohomology = triples(TDA4j.computeFromPoints(points, Array("engine", "cohomology")).toArray())
      cohomology must containTheSameElementsAs(ripser)
    }

    // Every bar carries a real annotation (this engine never resolves a bar via a shortcut that skips
    // recording one, unlike engine=ripser's apparent-pairs case) -- mirrors the equivalent engine=chunks
    // check below.
    "have representative chains readable for every bar, with matching vertex/coefficient array lengths" in {
      val result = TDA4j.computeFromPoints(points, Array("engine", "cohomology"))
      result.size() must be_>(0)
      (0 until result.size()).forall { i =>
        result.cycleVertices(i).length == result.cycleCoefficients(i).length
      } must beTrue
    }
  }

  "complex=alpha, through the facade" should {
    // Tolerance-based, not exact `containTheSameElementsAs` -- each facade call independently reconstructs its
    // own `AlphaShapes(pts, alphaBackend)`, and HelixDelaunay's own filtration-value computation touches a
    // `mutable.Set` whose iteration order (hence floating-point summation order) isn't guaranteed identical
    // between two independent constructions of "the same" complex -- the exact construction-nondeterminism
    // class `AlphaComplexSpec`'s own comment documents (last-ULP-level differences, not a reduction bug).
    "engine=cohomology agrees with the default engine=naive, up to floating-point tolerance" in {
      val naive =
        triples(TDA4j.computeFromPoints(points, Array("complex", "alpha")).toArray()).sortBy(t => (t._1, t._2, t._3))
      val cohomology = triples(
        TDA4j.computeFromPoints(points, Array("complex", "alpha", "engine", "cohomology")).toArray()
      ).sortBy(t => (t._1, t._2, t._3))

      naive.length must be_==(cohomology.length)
      val agree = naive.zip(cohomology).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
        d1 == d2 &&
        math.abs(b1 - b2) < 1e-9 &&
        (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
      }
      agree must beTrue
    }

    "reject an unrecognized engine value" in {
      TDA4j.computeFromPoints(points, Array("complex", "alpha", "engine", "bogus")) must throwA[
        IllegalArgumentException
      ]
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

    "engine=cohomology agrees with the default engine=naive, with readable representative chains" in {
      val naive = triples(TDA4j.computeFromPoints(points, Array("complex", "cech")).toArray())
      val cohomologyResult = TDA4j.computeFromPoints(points, Array("complex", "cech", "engine", "cohomology"))
      val cohomology = triples(cohomologyResult.toArray())
      val allReadable =
        (0 until cohomologyResult.size()).forall { i =>
          cohomologyResult.cycleVertices(i).length == cohomologyResult.cycleCoefficients(i).length
        }
      (cohomology must containTheSameElementsAs(naive)) and (allReadable must beTrue)
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

    "engine=cohomology agrees with the default engine=naive" in {
      val naive = triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val cohomology =
        triples(TDA4j.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "cohomology")).toArray())
      naive must containTheSameElementsAs(cohomology)
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

  // ---------------------------------------------------------------------------------------------------------
  // complex=witness: only the facade's own conversion layer (option parsing, landmark-selector dispatch,
  // local-to-ambient vertex remapping) -- streams.WitnessStreamSpec already cross-validates the underlying
  // construction itself (brute-force oracle, general-vs-lazy agreement, Ripser-on-lazy, downward closure).
  // ---------------------------------------------------------------------------------------------------------

  "complex=witness, through the facade" should {
    "require numLandmarks" in {
      TDA4j.computeFromPoints(points, Array("complex", "witness")) must throwA[IllegalArgumentException]
    }

    "reject an unrecognized landmarkSelector" in {
      TDA4j.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", "4", "landmarkSelector", "bogus")
      ) must throwA[IllegalArgumentException]
    }

    "landmarkSelector=random with an explicit landmarkSeed matches PackedRipserCohomologyContext driven " +
      "directly over the SAME LandmarkSelector.random(...) call" in {
        val numLandmarks = 4
        val seed = 7L
        val viaFacade = triples(
          TDA4j
            .computeFromPoints(
              points,
              Array(
                "complex",
                "witness",
                "numLandmarks",
                numLandmarks.toString,
                "landmarkSelector",
                "random",
                "landmarkSeed",
                seed.toString
              )
            )
            .toArray()
        )

        val ff = new FiniteField(2)
        import ff.given
        val metricSpace = EuclideanMetricSpace(points)
        val landmarks = LandmarkSelector.random(metricSpace, numLandmarks, seed).landmarks
        val wms = WitnessMetricSpace(WitnessGeometry(metricSpace, landmarks), nu = 2)

        def toDouble(e: BarcodeEndpoint[Double]): Double = e match
          case NegativeInfinity() => Double.NegativeInfinity
          case PositiveInfinity() => Double.PositiveInfinity
          case ClosedEndpoint(v)  => v
          case OpenEndpoint(v)    => v

        val direct = PackedRipserCohomologyContext[ff.Fp](wms, 2)
          .persistentCohomology()
          .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

        viaFacade must containTheSameElementsAs(direct)
      }

    "reject engine=ripser and engine=chunks combined with witnessVariant=general" in {
      (TDA4j.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general", "engine", "ripser")
      ) must throwA[IllegalArgumentException]) and
        (TDA4j.computeFromPoints(
          points,
          Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general", "engine", "chunks")
        ) must throwA[IllegalArgumentException])
    }

    "default witnessVariant=lazy, engine=ripser: matches PackedRipserCohomologyContext driven directly over " +
      "streams.WitnessMetricSpace, via the SAME maxmin landmark selection" in {
        val numLandmarks = 4
        val viaFacade = triples(
          TDA4j.computeFromPoints(points, Array("complex", "witness", "numLandmarks", numLandmarks.toString)).toArray()
        )

        val ff = new FiniteField(2)
        import ff.given
        val metricSpace = EuclideanMetricSpace(points)
        val landmarks = LandmarkSelector.maxmin(metricSpace, numLandmarks).landmarks
        val wms = WitnessMetricSpace(WitnessGeometry(metricSpace, landmarks), nu = 2)

        def toDouble(e: BarcodeEndpoint[Double]): Double = e match
          case NegativeInfinity() => Double.NegativeInfinity
          case PositiveInfinity() => Double.PositiveInfinity
          case ClosedEndpoint(v)  => v
          case OpenEndpoint(v)    => v

        val direct = PackedRipserCohomologyContext[ff.Fp](wms, 2)
          .persistentCohomology()
          .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

        viaFacade must containTheSameElementsAs(direct)
      }

    "witnessVariant=general, engine=naive: matches WitnessCofaceSimplexStream driven directly, via the SAME " +
      "maxmin landmark selection" in {
        val numLandmarks = 4
        val viaFacade = triples(
          TDA4j
            .computeFromPoints(
              points,
              Array("complex", "witness", "numLandmarks", numLandmarks.toString, "witnessVariant", "general")
            )
            .toArray()
        )

        val ff = new FiniteField(2)
        import ff.given
        val metricSpace = EuclideanMetricSpace(points)
        val geometry = WitnessGeometry(metricSpace, LandmarkSelector.maxmin(metricSpace, numLandmarks).landmarks)
        val stream = LimitedCofaceSimplexStream(WitnessCofaceSimplexStream(geometry), 3)

        def toDouble(e: BarcodeEndpoint[Double]): Double = e match
          case NegativeInfinity() => Double.NegativeInfinity
          case PositiveInfinity() => Double.PositiveInfinity
          case ClosedEndpoint(v)  => v
          case OpenEndpoint(v)    => v

        val direct = PersistenceEngine
          .naive[Simplex[Int], ff.Fp]
          .barcode(stream)
          .filter(_.dim <= 2)
          .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

        viaFacade must containTheSameElementsAs(direct)
      }

    // NOTE on why "every vertex is in [0, points.length)" is NOT a sufficient check, and was wrong in an
    // earlier version of this test: with numLandmarks=3 and 6 points, an UNMAPPED local landmark index (0, 1,
    // or 2) is ALSO a perfectly valid AMBIENT index in [0, 6) -- so that check passes whether or not the
    // facade actually maps through witnessLandmarks(i). The real discriminator is comparing against the
    // SPECIFIC landmark set maxmin actually chose (computed independently here, not hardcoded) and confirming
    // it is NOT a subset of the local range {0, ..., numLandmarks-1} -- which only holds if the reported
    // vertices are genuinely ambient, not local, indices. On this fixture maxmin from firstLandmark=0 picks
    // {0, 4, 5} (points 4 and 5 tie exactly at sqrt(4.25); 4 wins the tie by lower index), so a local-index bug
    // would show only {0,1,2}, never 4 or 5.
    "cycleVertices reports AMBIENT point-cloud indices, not local 0-until-numLandmarks landmark indices, for " +
      "both the default engine=ripser and engine=naive" in {
        val numLandmarks = 3
        val landmarks = LandmarkSelector.maxmin(EuclideanMetricSpace(points), numLandmarks).landmarks.toSet
        def allVertices(result: PersistenceResult): Set[Int] =
          (0 until result.size()).flatMap(i => result.cycleVertices(i).flatten).toSet
        val ripserResult =
          TDA4j.computeFromPoints(points, Array("complex", "witness", "numLandmarks", numLandmarks.toString))
        val naiveResult = TDA4j.computeFromPoints(
          points,
          Array("complex", "witness", "numLandmarks", numLandmarks.toString, "engine", "naive")
        )
        val ripserVertices = allVertices(ripserResult)
        val naiveVertices = allVertices(naiveResult)
        (ripserVertices.nonEmpty must beTrue) and (naiveVertices.nonEmpty must beTrue) and
          (ripserVertices.forall(landmarks.contains) must beTrue) and
          (naiveVertices.forall(landmarks.contains) must beTrue) and
          (ripserVertices.subsetOf((0 until numLandmarks).toSet) must beFalse) and
          (naiveVertices.subsetOf((0 until numLandmarks).toSet) must beFalse)
      }

    "cycleVertices reports AMBIENT indices for witnessVariant=general too" in {
      val numLandmarks = 3
      val landmarks = LandmarkSelector.maxmin(EuclideanMetricSpace(points), numLandmarks).landmarks.toSet
      val result = TDA4j.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", numLandmarks.toString, "witnessVariant", "general")
      )
      val vertices = (0 until result.size()).flatMap(i => result.cycleVertices(i).flatten).toSet
      (vertices.nonEmpty must beTrue) and
        (vertices.forall(landmarks.contains) must beTrue) and
        (vertices.subsetOf((0 until numLandmarks).toSet) must beFalse)
    }
  }
