package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*

import org.appliedtopology.tda4j.*
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
    "match RipserCohomologyEngine[Fp(2)] driven directly" in {
      // RipserCohomologyEngine's own maxDimension now means "top homological degree reported," fixed at its
      // own source (see .claude/WORKLOG-maxdim-semantics-fix.md) -- so the facade's engine=ripser path
      // (TDA4j.computeGeneric) is now a fully transparent passthrough of requestedMaxDimension, with no
      // +1-and-filter workaround on either side of this comparison anymore.
      val viaFacade = triples(FullBarcode.computeFromPoints(points).toArray())

      val ff = new FiniteField(2)
      import ff.given
      val metricSpace = EuclideanMetricSpace(points)

      def toDouble(e: BarcodeEndpoint[Double]): Double = e match
        case NegativeInfinity() => Double.NegativeInfinity
        case PositiveInfinity() => Double.PositiveInfinity
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v

      val direct = RipserCohomologyEngine[ff.Fp](metricSpace, 2)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

      viaFacade must containTheSameElementsAs(direct)
    }
  }

  "RipserCohomologyEngine's maxDimension semantics fix" should {
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

      val direct = RipserCohomologyEngine[ff.Fp](metricSpace, 2)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

      val viaOneHigherFiltered = RipserCohomologyEngine[ff.Fp](metricSpace, 3)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))
        .filter(_._1 <= 2)

      direct must containTheSameElementsAs(viaOneHigherFiltered)
    }
  }

  "engine=naive, through the facade" should {
    "agree with the default engine=ripser, through the facade" in {
      val ripser = triples(FullBarcode.computeFromPoints(points).toArray())
      val naive = triples(FullBarcode.computeFromPoints(points, Array("engine", "naive")).toArray())
      naive must containTheSameElementsAs(ripser)
    }
  }

  "engine=cohomology, through the facade" should {
    // CellularCohomologyEngine -- .claude/DESIGN-generic-cohomology.md. Bar VALUES should agree with
    // engine=ripser on complex=vr (both compute persistent cohomology of the same Vietoris-Rips complex,
    // just one generic/materialized and one VR-specialized) -- not representative CONTENT, which
    // `CohomologySpec`'s own comment explains isn't a sound cross-engine claim on VR input (dimension 0 is
    // always fully tied, and the two engines' tie-breaks genuinely differ).
    "agree with the default engine=ripser, through the facade" in {
      val ripser = triples(FullBarcode.computeFromPoints(points).toArray())
      val cohomology = triples(FullBarcode.computeFromPoints(points, Array("engine", "cohomology")).toArray())
      cohomology must containTheSameElementsAs(ripser)
    }

    // Every bar carries a real annotation (this engine never resolves a bar via a shortcut that skips
    // recording one, unlike engine=ripser's apparent-pairs case) -- mirrors the equivalent engine=chunks
    // check below.
    "be the default for every complex but vr (ripser) and images (fast-cubical): same bars and representatives" in {
      def same(default: PersistenceResult, explicit: PersistenceResult) =
        default.size() == explicit.size() &&
          triples(default.toArray()) == triples(explicit.toArray()) &&
          (0 until default.size()).forall(i =>
            default.cycleVertices(i).map(_.toList).toList == explicit.cycleVertices(i).map(_.toList).toList
          )
      val cech = Array("complex", "cech", "maxDimension", "1")
      val pixels = Array(Array(0.0, 1.0, 0.0), Array(1.0, 2.0, 1.0), Array(0.0, 1.0, 0.0))
      val relation = Array(Array(0.0, 1.0, 2.0), Array(1.0, 0.0, 1.0), Array(2.0, 1.0, 0.0))
      same(
        FullBarcode.computeFromPoints(points, cech),
        FullBarcode.computeFromPoints(points, cech ++ Array("engine", "cohomology"))
      )
        .must(beTrue)
        .and(
          same(
            FullBarcode.computeFromImage(pixels),
            FullBarcode.computeFromImage(pixels, Array("engine", "fast-cubical"))
          ) must beTrue
        )
        .and(
          same(
            TDA4j.computeFromRelation(relation),
            TDA4j.computeFromRelation(relation, Array("engine", "cohomology"))
          ) must beTrue
        )
    }

    "honour representativeType: cocycles from cohomology for an image, refused by homology engines and bad values" in {
      val pixels = Array(Array(0.0, 1.0, 0.0), Array(1.0, 2.0, 1.0), Array(0.0, 1.0, 0.0))
      val cocycles = FullBarcode.computeFromImage(pixels, Array("representativeType", "cocycles"))
      val explicit =
        FullBarcode.computeFromImage(pixels, Array("engine", "cohomology", "representativeType", "cocycles"))
      (triples(cocycles.toArray()) must beEqualTo(triples(explicit.toArray())))
        .and(
          (0 until cocycles.size()).forall(i =>
            cocycles.cycleVertices(i).map(_.toList).toList == explicit.cycleVertices(i).map(_.toList).toList
          ) must beTrue
        )
        .and(
          TDA4j.computeFromImage(pixels, Array("engine", "fast-cubical", "representativeType", "cocycles")) must
            throwAn[IllegalArgumentException](message = "for cocycles use engine=cohomology")
        )
        .and(
          triples(
            FullBarcode.computeFromPoints(points, Array("engine", "chunks", "representativeType", "cocycles")).toArray()
          ).sorted
            must beEqualTo(triples(FullBarcode.computeFromPoints(points, Array("engine", "chunks")).toArray()).sorted)
        )
        .and(
          TDA4j.computeFromPoints(points, Array("representativeType", "both")) must
            throwAn[IllegalArgumentException](message = "must be cycles or cocycles")
        )
    }

    "have representative chains readable for every bar, with matching vertex/coefficient array lengths" in {
      val result = FullBarcode.computeFromPoints(points, Array("engine", "cohomology"))
      result.size() must be_>(0)
      (0 until result.size()).forall { i =>
        result.cycleVertices(i).length == result.cycleCoefficients(i).length
      } must beTrue
    }
  }

  "complex=vr, edgeCollapse option validation" should {
    "reject edgeCollapse=true combined with a non-vr complex" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "alpha", "edgeCollapse", "true")
      ) must throwA[IllegalArgumentException]
    }
    "reject a non-boolean edgeCollapse value" in {
      FullBarcode.computeFromPoints(points, Array("edgeCollapse", "yes")) must throwA[IllegalArgumentException]
    }
  }

  "complex=vr with edgeCollapse=true, through the facade" should {
    // The real oracle (EdgeCollapse's own worklog): edge collapse preserves persistent homology exactly,
    // so the collapsed complex's own barcode must match plain complex=vr's, bar for bar -- not just "doesn't
    // throw." A dropped edgeCollapse option, or one silently ignored inside computeGeneric, would still pass
    // every other test in this file (nothing else here ever asks for it) but would fail this one immediately if
    // it somehow changed the answer -- it should NOT change the answer at all, only how it's computed.
    "agree exactly with edgeCollapse=false (the default), across every engine" in {
      // Zero-persistence (birth == death) bars are dropped before comparing: edge collapse specifically
      // eliminates exactly this kind of momentary flicker (see EdgeCollapse's own worklog), so the
      // uncollapsed baseline can have MORE of them while still agreeing with the collapsed result on every bar
      // that represents a genuine feature -- the same filter EdgeCollapseStreamSpec's own barcode comparisons
      // already need, for the identical reason.
      def realBars(triples: List[(Int, Double, Double)]) = triples.filterNot((_, b, d) => b == d)
      val baseline = realBars(triples(FullBarcode.computeFromPoints(points).toArray()))
      forall(Seq("ripser", "naive", "chunks", "cohomology")) { engine =>
        val collapsed =
          realBars(
            triples(FullBarcode.computeFromPoints(points, Array("edgeCollapse", "true", "engine", engine)).toArray())
          )
        collapsed must containTheSameElementsAs(baseline)
      }
    }

    "still expose real representative chains for every bar (the collapsed stream still satisfies the ordering " +
      "contract, not just that bar VALUES happen to survive)" in {
        val result = FullBarcode.computeFromPoints(points, Array("edgeCollapse", "true"))
        result.size() must be_>(0)
        (0 until result.size()).forall { i =>
          result.cycleVertices(i).length == result.cycleCoefficients(i).length && result.cycleVertices(i).length > 0
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
        triples(FullBarcode.computeFromPoints(points, Array("complex", "alpha")).toArray()).sortBy(t =>
          (t._1, t._2, t._3)
        )
      val cohomology = triples(
        FullBarcode.computeFromPoints(points, Array("complex", "alpha", "engine", "cohomology")).toArray()
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
      FullBarcode.computeFromPoints(points, Array("complex", "alpha", "engine", "bogus")) must throwA[
        IllegalArgumentException
      ]
    }

    "engine=fast-alpha agrees with the default engine=naive, up to floating-point tolerance" in {
      val naive =
        triples(FullBarcode.computeFromPoints(points, Array("complex", "alpha")).toArray()).sortBy(t =>
          (t._1, t._2, t._3)
        )
      val fastAlpha = triples(
        FullBarcode.computeFromPoints(points, Array("complex", "alpha", "engine", "fast-alpha")).toArray()
      ).sortBy(t => (t._1, t._2, t._3))

      naive.length must be_==(fastAlpha.length)
      val agree = naive.zip(fastAlpha).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
        d1 == d2 &&
        math.abs(b1 - b2) < 1e-9 &&
        (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
      }
      agree must beTrue
    }

    "reject engine=fast-alpha combined with alphaBackend=DQP" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "alpha", "engine", "fast-alpha", "alphaBackend", "DQP")
      ) must throwA[IllegalArgumentException]
    }

    // Was "reject engine=fast-alpha for a 3D point cloud" -- true of the OLD ambient-dimension-2-only engine, no
    // longer true since the hybrid-with-chunks extension to d >= 3
    // (.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md): a 3D point cloud is now a real, supported case,
    // not a rejection, so this became a positive agreement test instead (mirroring the 2D test above), reusing
    // FastAlphaHomologySpec's own pinned 8-point d3Fixture (already confirmed there to trigger neither known
    // HelixDelaunay limitation).
    "engine=fast-alpha agrees with the default engine=naive on a 3D point cloud, up to floating-point tolerance" in {
      val points3d = Array(
        Array(0.4613980841200842, 0.49833920626726624, -0.30338059393748606),
        Array(0.7945542854842094, 0.4163543155535945, -0.2961704447073863),
        Array(-0.7585278972189831, 0.6998262016945451, -0.833560565510757),
        Array(0.8574961456450381, 0.2832300995593273, 0.5695196611305218),
        Array(0.17793559124047031, -0.05405078558279208, -0.49115647079559355),
        Array(-0.2347321324300118, 0.5413935071566336, -0.5601041700478047),
        Array(-0.37397256964256, 0.7333269621250991, -0.7932032354418583),
        Array(-0.11756472947596674, 0.04518546446771521, -0.7937972268406879)
      )
      val naive =
        triples(FullBarcode.computeFromPoints(points3d, Array("complex", "alpha")).toArray())
          .sortBy(t => (t._1, t._2, t._3))
      val fastAlpha = triples(
        FullBarcode.computeFromPoints(points3d, Array("complex", "alpha", "engine", "fast-alpha")).toArray()
      ).sortBy(t => (t._1, t._2, t._3))

      naive.length must be_==(fastAlpha.length)
      val agree = naive.zip(fastAlpha).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
        d1 == d2 &&
        math.abs(b1 - b2) < 1e-9 &&
        (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
      }
      agree must beTrue
    }

    "engine=fast-alpha computes the point set whose triangulation once broke it, and agrees with engine=naive" in {
      // The 12-point set FastAlphaHomologySpec pins: Helix's earlier candidate search gave one facet three cofaces
      // here, and the engine threw FastAlphaTriangulationException. The facade has no catch on this path; the
      // exception's message is tested on the engine's own check (FastAlphaHomologySpec).
      val degeneratePoints = Array(
        Array(0.25695462472920483, 0.05056259919533401),
        Array(0.16861543461245865, 0.6584119575973783),
        Array(0.04467548898740192, 0.34594140416504626),
        Array(0.4001206924759393, 0.7492099413470164),
        Array(0.9883782492738798, 0.31376350981292744),
        Array(0.9160887469534176, 0.952687093337434),
        Array(0.19808274564375272, 0.2756763438426806),
        Array(0.6337671470530175, 0.4977740447848821),
        Array(0.6906131750679769, 0.9538206186545584),
        Array(0.4693304070850357, 0.4362857418234436),
        Array(0.5483329515783447, 0.7788827446454716),
        Array(0.8916378524720998, 0.4724706741593929)
      )
      val naive = triples(FullBarcode.computeFromPoints(degeneratePoints, Array("complex", "alpha")).toArray())
        .sortBy(t => (t._1, t._2, t._3))
      val fastAlpha = triples(
        FullBarcode.computeFromPoints(degeneratePoints, Array("complex", "alpha", "engine", "fast-alpha")).toArray()
      ).sortBy(t => (t._1, t._2, t._3))
      (naive.length must be_==(fastAlpha.length)) and
        naive
          .zip(fastAlpha)
          .forall { case ((d1, b1, e1), (d2, b2, e2)) =>
            d1 == d2 && math.abs(b1 - b2) < 1e-9 &&
            (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
          }
          .must(beTrue)
    }

    // requireValidTriangulation (.claude/DESIGN-helix-triangulation-repair.md), threaded through the facade.
    // Reuses the SAME pinned 12-point facet-multiplicity-violation fixture as the exception test above.
    val degeneratePoints = Array(
      Array(0.25695462472920483, 0.05056259919533401),
      Array(0.16861543461245865, 0.6584119575973783),
      Array(0.04467548898740192, 0.34594140416504626),
      Array(0.4001206924759393, 0.7492099413470164),
      Array(0.9883782492738798, 0.31376350981292744),
      Array(0.9160887469534176, 0.952687093337434),
      Array(0.19808274564375272, 0.2756763438426806),
      Array(0.6337671470530175, 0.4977740447848821),
      Array(0.6906131750679769, 0.9538206186545584),
      Array(0.4693304070850357, 0.4362857418234436),
      Array(0.5483329515783447, 0.7788827446454716),
      Array(0.8916378524720998, 0.4724706741593929)
    )

    "requireValidTriangulation=true fixes the pinned facet-multiplicity violation: no exception, and " +
      "engine=fast-alpha agrees with engine=naive on the SAME (deterministically seeded) repaired stream" in {
        val naive = triples(
          FullBarcode
            .computeFromPoints(degeneratePoints, Array("complex", "alpha", "requireValidTriangulation", "true"))
            .toArray()
        ).sortBy(t => (t._1, t._2, t._3))
        val fastAlpha = triples(
          FullBarcode
            .computeFromPoints(
              degeneratePoints,
              Array("complex", "alpha", "engine", "fast-alpha", "requireValidTriangulation", "true")
            )
            .toArray()
        ).sortBy(t => (t._1, t._2, t._3))

        naive.length must be_==(fastAlpha.length)
        val agree = naive.zip(fastAlpha).forall { case ((d1, b1, e1), (d2, b2, e2)) =>
          d1 == d2 &&
          math.abs(b1 - b2) < 1e-9 &&
          (e1.isInfinite == e2.isInfinite) && (e1.isInfinite || math.abs(e1 - e2) < 1e-9)
        }
        agree must beTrue
      }

    "reject requireValidTriangulation combined with a non-alpha complex" in {
      FullBarcode.computeFromPoints(
        points,
        Array("requireValidTriangulation", "true")
      ) must throwA[IllegalArgumentException]
    }

    "reject requireValidTriangulation=true combined with alphaBackend=DQP" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "alpha", "alphaBackend", "DQP", "requireValidTriangulation", "true")
      ) must throwA[IllegalArgumentException]
    }

    "reject a non-boolean requireValidTriangulation value" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "alpha", "requireValidTriangulation", "yes")
      ) must throwA[IllegalArgumentException]
    }
  }

  "computeFromDistanceMatrix" should {
    "agree with computeFromPoints given the same cloud's own Euclidean distances" in {
      val fromDist = triples(FullBarcode.computeFromDistanceMatrix(euclideanDistanceMatrix(points)).toArray())
      val fromPoints = triples(FullBarcode.computeFromPoints(points).toArray())
      fromDist must containTheSameElementsAs(fromPoints)
    }
  }

  "field=R" should {
    "agree (up to floating-point tolerance) with the default field=Z, through the facade" in {
      val zResult = triples(FullBarcode.computeFromPoints(points).toArray()).sortBy(t => (t._1, t._2, t._3))
      val rResult =
        triples(FullBarcode.computeFromPoints(points, Array("field", "R")).toArray()).sortBy(t => (t._1, t._2, t._3))

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
      FullBarcode.computeFromPoints(points, Array("engine")) must throwA[IllegalArgumentException]
    }
    "reject an unrecognized option key" in {
      FullBarcode.computeFromPoints(points, Array("bogus", "value")) must throwA[IllegalArgumentException]
    }
    "reject engine=ripser combined with complex=alpha" in {
      FullBarcode
        .computeFromPoints(points, Array("complex", "alpha", "engine", "ripser")) must throwA[IllegalArgumentException]
    }
    "reject engine=chunks combined with complex=alpha" in {
      FullBarcode
        .computeFromPoints(points, Array("complex", "alpha", "engine", "chunks")) must throwA[IllegalArgumentException]
    }
    "reject engine=fast-cubical combined with complex=vr" in {
      FullBarcode.computeFromPoints(points, Array("engine", "fast-cubical")) must throwA[IllegalArgumentException]
    }
    "reject engine=fast-alpha combined with complex=vr" in {
      FullBarcode.computeFromPoints(points, Array("engine", "fast-alpha")) must throwA[IllegalArgumentException]
    }
    "reject complex=alpha via computeFromDistanceMatrix (alpha needs coordinates)" in {
      FullBarcode.computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "alpha")) must throwA[
        IllegalArgumentException
      ]
    }
    "reject engine=ripser combined with complex=cech" in {
      FullBarcode
        .computeFromPoints(points, Array("complex", "cech", "engine", "ripser")) must throwA[IllegalArgumentException]
    }
    "reject complex=cech via computeFromDistanceMatrix (cech needs coordinates)" in {
      FullBarcode.computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "cech")) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "complex=cech, through the facade" should {
    "default to engine=naive and agree with an explicit engine=chunks call" in {
      // Cross-validated directly against the naive engine on random Cech streams in CechStreamSpec -- this is a
      // conversion-layer check (does the facade's own dispatch wire engine=chunks up correctly for complex=cech),
      // not a re-proof that chunks is correct on Cech streams in general.
      val naive = triples(FullBarcode.computeFromPoints(points, Array("complex", "cech")).toArray())
      val chunks =
        triples(FullBarcode.computeFromPoints(points, Array("complex", "cech", "engine", "chunks")).toArray())
      naive must containTheSameElementsAs(chunks)
    }

    "produce a genuinely different barcode than complex=vr on the same points (Cech uses radius, VR uses diameter)" in {
      // Not just "doesn't crash" -- Cech and VR are different filtrations on the same point set, so their own
      // birth/death VALUES should differ (same discriminator CechStreamSpec's own unit-equilateral-triangle
      // fixture uses), even though both should reveal the same underlying topology at some threshold.
      val vrBars = triples(FullBarcode.computeFromPoints(points).toArray())
      val cechBars = triples(FullBarcode.computeFromPoints(points, Array("complex", "cech")).toArray())
      val vrBirths = vrBars.map(_._2).toSet.toSeq
      val cechBirths = cechBars.map(_._2).toSet.toSeq
      vrBirths must not(containTheSameElementsAs(cechBirths))
    }

    "have representative chains readable for engine=naive and engine=chunks alike" in {
      val naiveResult = FullBarcode.computeFromPoints(points, Array("complex", "cech"))
      val chunksResult = FullBarcode.computeFromPoints(points, Array("complex", "cech", "engine", "chunks"))
      def allReadable(r: PersistenceResult): Boolean =
        (0 until r.size()).forall(i => r.cycleVertices(i).length == r.cycleCoefficients(i).length)
      allReadable(naiveResult) must beTrue
      allReadable(chunksResult) must beTrue
    }

    "engine=cohomology agrees with the default engine=naive, with readable representative chains" in {
      val naive = triples(FullBarcode.computeFromPoints(points, Array("complex", "cech")).toArray())
      val cohomologyResult = FullBarcode.computeFromPoints(points, Array("complex", "cech", "engine", "cohomology"))
      val cohomology = triples(cohomologyResult.toArray())
      val allReadable =
        (0 until cohomologyResult.size()).forall { i =>
          cohomologyResult.cycleVertices(i).length == cohomologyResult.cycleCoefficients(i).length
        }
      (cohomology must containTheSameElementsAs(naive)) and (allReadable must beTrue)
    }
  }

  "complex=dtm-rips/complex=dtm-alpha, option validation" should {
    "require dtmK" in {
      FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips")) must throwA[IllegalArgumentException]
      FullBarcode.computeFromPoints(points, Array("complex", "dtm-alpha")) must throwA[IllegalArgumentException]
    }
    "reject engine=ripser combined with complex=dtm-rips" in {
      FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "2", "engine", "ripser")) must throwA[
        IllegalArgumentException
      ]
    }
    "reject engine=ripser combined with complex=dtm-alpha" in {
      FullBarcode.computeFromPoints(points, Array("complex", "dtm-alpha", "dtmK", "2", "engine", "ripser")) must throwA[
        IllegalArgumentException
      ]
    }
    "reject engine=chunks combined with complex=dtm-alpha" in {
      FullBarcode.computeFromPoints(points, Array("complex", "dtm-alpha", "dtmK", "2", "engine", "chunks")) must throwA[
        IllegalArgumentException
      ]
    }
    "reject complex=dtm-alpha via computeFromDistanceMatrix (dtm-alpha needs coordinates)" in {
      FullBarcode.computeFromDistanceMatrix(
        euclideanDistanceMatrix(points),
        Array("complex", "dtm-alpha", "dtmK", "2")
      ) must
        throwA[IllegalArgumentException]
    }
  }

  "complex=dtm-rips, through the facade" should {
    "match DtmRipsSimplexStream/SimplicialHomologyEngine driven directly" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val viaFacade =
        triples(
          FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "3", "field", "R")).toArray()
        )
      val metricSpace = EuclideanMetricSpace(points)
      val f = DistanceToMeasure(metricSpace, 3)
      val stream = LimitedCofaceSimplexStream(DtmRipsSimplexStream(metricSpace, f), 3)
      val direct = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
        .filter(_._1 <= 2)
        .map { case (d, b, dd) => (d, b, if dd.isPosInfinity then Double.PositiveInfinity else dd) }
        .toList
      viaFacade must containTheSameElementsAs(direct)
    }

    "works from a distance matrix too (DistanceToMeasure needs no coordinates, unlike complex=cech/alpha)" in {
      val viaPoints =
        triples(FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "3")).toArray())
      val viaDistances = triples(
        FullBarcode
          .computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "dtm-rips", "dtmK", "3"))
          .toArray()
      )
      viaPoints must containTheSameElementsAs(viaDistances)
    }

    "default to engine=naive and agree with an explicit engine=chunks call" in {
      val naive = triples(FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "3")).toArray())
      val chunks =
        triples(
          FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "3", "engine", "chunks")).toArray()
        )
      naive must containTheSameElementsAs(chunks)
    }

    "k=1 (dtmK=1) reproduces plain complex=vr exactly, filtration values included" in {
      val vr = triples(FullBarcode.computeFromPoints(points).toArray())
      val dtmRips1 = triples(FullBarcode.computeFromPoints(points, Array("complex", "dtm-rips", "dtmK", "1")).toArray())
      vr must containTheSameElementsAs(dtmRips1)
    }
  }

  "complex=sparse-rips, option validation" should {
    "say so when the pre-0.5.0 name sheehy-rips is used" in {
      FullBarcode.computeFromPoints(points, Array("complex", "sheehy-rips", "sparseEpsilon", "0.5")) must throwA[
        IllegalArgumentException
      ](
        "renamed 'sparse-rips'"
      )
    }
    "require sparseEpsilon" in {
      FullBarcode.computeFromPoints(points, Array("complex", "sparse-rips")) must throwA[IllegalArgumentException]
    }
    "reject engine=ripser combined with complex=sparse-rips" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "engine", "ripser")
      ) must
        throwA[IllegalArgumentException]
    }
  }

  "complex=sparse-rips, through the facade" should {
    "match SheehyRipsSimplexStream/SimplicialHomologyEngine driven directly" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val viaFacade = triples(
        FullBarcode
          .computeFromPoints(points, Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "field", "R"))
          .toArray()
      )
      val metricSpace = EuclideanMetricSpace(points)
      val stream = LimitedCofaceSimplexStream(SheehyRipsSimplexStream(metricSpace, epsilon = 0.5), 3)
      val direct = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
        .filter(_._1 <= 2)
        .map { case (d, b, dd) => (d, b, if dd.isPosInfinity then Double.PositiveInfinity else dd) }
        .toList
      viaFacade must containTheSameElementsAs(direct)
    }

    "works from a distance matrix too (SheehyRipsSimplexStream needs no coordinates, unlike complex=cech/alpha)" in {
      val viaPoints =
        triples(
          FullBarcode.computeFromPoints(points, Array("complex", "sparse-rips", "sparseEpsilon", "0.5")).toArray()
        )
      val viaDistances = triples(
        FullBarcode
          .computeFromDistanceMatrix(
            euclideanDistanceMatrix(points),
            Array("complex", "sparse-rips", "sparseEpsilon", "0.5")
          )
          .toArray()
      )
      viaPoints must containTheSameElementsAs(viaDistances)
    }

    "default to engine=naive and agree with an explicit engine=chunks call" in {
      val naive =
        triples(
          FullBarcode.computeFromPoints(points, Array("complex", "sparse-rips", "sparseEpsilon", "0.5")).toArray()
        )
      val chunks = triples(
        FullBarcode
          .computeFromPoints(points, Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "engine", "chunks"))
          .toArray()
      )
      naive must containTheSameElementsAs(chunks)
    }

    // The 6-point `points` fixture above almost certainly doesn't sparsify at all (SheehyRipsStreamSpec found
    // this needs either a wide scale spread or many more points) -- a dispatch bug that silently routed
    // complex=sparse-rips to plain VR, or dropped sparseEpsilon entirely, could still pass every test above. The
    // three-cluster fixture (same construction, same seeds, as SheehyRipsStreamSpec's own deterministic
    // sparsification fixture -- 105 -> 26 edges at epsilon=0.5) is reused here specifically to close that gap.
    def clusterPoints: Array[Array[Double]] =
      def cluster(cx: Double, cy: Double, seed: Int): Array[Array[Double]] =
        val rng = new scala.util.Random(seed)
        Array.fill(5)(Array(cx + (rng.nextDouble() - 0.5) * 0.5, cy + (rng.nextDouble() - 0.5) * 0.5))
      cluster(0.0, 0.0, 1) ++ cluster(50.0, 0.0, 2) ++ cluster(25.0, 50.0, 3)

    "produce a genuinely different (sparser) barcode than complex=vr, on a point cloud where sparsification fires" in {
      val sparse = triples(
        FullBarcode
          .computeFromPoints(
            clusterPoints,
            Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "maxDimension", "1")
          )
          .toArray()
      )
      val vr = triples(
        FullBarcode
          .computeFromPoints(clusterPoints, Array("maxFiltrationValue", "1000.0", "maxDimension", "1"))
          .toArray()
      )
      sparse must not(containTheSameElementsAs(vr))
    }

    "engine=cohomology agrees with the default engine=naive, on the same sparsifying point cloud" in {
      val naive =
        triples(
          FullBarcode
            .computeFromPoints(clusterPoints, Array("complex", "sparse-rips", "sparseEpsilon", "0.5"))
            .toArray()
        )
      val cohomology = triples(
        FullBarcode
          .computeFromPoints(
            clusterPoints,
            Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "engine", "cohomology")
          )
          .toArray()
      )
      naive must containTheSameElementsAs(cohomology)
    }
  }

  "complex=dtm-alpha, through the facade" should {
    "match AlphaComplexDQP.dtm driven directly, in radius (not squared-power) units" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val viaFacade =
        triples(
          FullBarcode.computeFromPoints(points, Array("complex", "dtm-alpha", "dtmK", "3", "field", "R")).toArray()
        )
      val ac = AlphaComplexDQP.dtm(points, 3, Double.PositiveInfinity, points.head.length)
      val direct = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(AlphaComplexDQPStream(points, ac))
        .diagramAt(Double.PositiveInfinity)
        .map { case (d, b, dd) => (d, b, if dd.isPosInfinity then Double.PositiveInfinity else dd) }
        .toList
      viaFacade must containTheSameElementsAs(direct)
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

    "computeFromCubicalImage's default (engine=naive) matches CubicalHomologyEngine driven directly" in {
      val viaFacade = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())

      val stream = CubicalImage.fromFlatArray(ringShape.toIndexedSeq, ringFlat.toIndexedSeq)
      given Double is Field = Field.DoubleApproximated(1e-9)
      val direct = CubicalHomologyEngine[Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
        .map { case (d, b, e) => (d, b, e) }

      viaFacade must containTheSameElementsAs(direct)
    }

    "engine=chunks agrees with the default engine=naive" in {
      val naive = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val chunks =
        triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "chunks")).toArray())
      naive must containTheSameElementsAs(chunks)
    }

    "engine=cohomology agrees with the default engine=naive" in {
      val naive = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val cohomology =
        triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "cohomology")).toArray())
      naive must containTheSameElementsAs(cohomology)
    }

    "engine=fast-cubical agrees with the default engine=naive" in {
      val naive = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val fastCubical =
        triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "fast-cubical")).toArray())
      naive must containTheSameElementsAs(fastCubical)
    }

    // Was "engine=fast-cubical is refused for a 3D image" -- true of the OLD ambient-dimension-2-only engine,
    // no longer true since the hybrid-with-chunks extension to d >= 3
    // (.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md): a 3D image is now a real, supported case, not
    // a rejection, so this became a positive agreement test instead (mirroring the 2D "agrees with the default
    // engine=naive" test just above) rather than being deleted outright.
    "engine=fast-cubical agrees with the default engine=naive on a 3D image" in {
      val cubeShape = Array(3, 3, 3)
      val cubeFlat = Array.fill(27)(0.0)
      cubeFlat(13) = 1.0 // flat index of (1,1,1), row-major/last-axis-fastest: 1*9 + 1*3 + 1 -- the single
      // elevated interior voxel, exactly FastCubicalHomologySpec's own hand-derived singleVoidFixture3D
      val naive = triples(FullBarcode.computeFromCubicalImage(cubeShape, cubeFlat).toArray())
      val fastCubical =
        triples(FullBarcode.computeFromCubicalImage(cubeShape, cubeFlat, Array("engine", "fast-cubical")).toArray())
      naive must containTheSameElementsAs(fastCubical)
    }

    "computeFromImage (the 2D double[][] convenience) matches computeFromCubicalImage on the same grid" in {
      val pixels = Array(
        Array(0.0, 0.0, 0.0),
        Array(0.0, Double.PositiveInfinity, 0.0),
        Array(0.0, 0.0, 0.0)
      )
      val viaImage = triples(FullBarcode.computeFromImage(pixels).toArray())
      val viaCubicalImage = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      viaImage must containTheSameElementsAs(viaCubicalImage)
    }

    "finds the ring's genuine essential H1 bar" in {
      val bars = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      bars.exists { case (dim, _, death) => dim == 1 && death.isPosInfinity } must beTrue
    }

    "reject engine=ripser" in {
      FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "ripser")) must throwA[
        IllegalArgumentException
      ]
    }

    "reject a flatValues length that doesn't match shape's product" in {
      FullBarcode.computeFromCubicalImage(Array(2, 2), Array(1.0, 2.0, 3.0)) must throwA[IllegalArgumentException]
    }

    "reject an unrecognized sublevel value" in {
      FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("sublevel", "sideways")) must throwA[
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
      val sublevelBars = triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat).toArray())
      val superlevelBars =
        triples(FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("sublevel", "false")).toArray())
      def hasEssentialH1(bars: List[(Int, Double, Double)]): Boolean =
        bars.exists { case (dim, _, death) => dim == 1 && death.isPosInfinity }
      (hasEssentialH1(sublevelBars) must beTrue) and (hasEssentialH1(superlevelBars) must beFalse)
    }

    "have representative chains readable for engine=naive and engine=chunks alike, as doubled-coordinate arrays" in {
      val naiveResult = FullBarcode.computeFromCubicalImage(ringShape, ringFlat)
      val chunksResult = FullBarcode.computeFromCubicalImage(ringShape, ringFlat, Array("engine", "chunks"))
      def allReadable(r: PersistenceResult): Boolean =
        (0 until r.size()).forall(i => r.cycleVertices(i).length == r.cycleCoefficients(i).length)
      allReadable(naiveResult) must beTrue
      allReadable(chunksResult) must beTrue
    }
  }

  "representative chains" should {
    "be readable for at least one engine=ripser bar, with matching vertex/coefficient array lengths" in {
      val result = FullBarcode.computeFromPoints(points)
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

    // CellularPersistenceInChunksEngine.barcodeAt now records a real representative for EVERY bar, at every
    // dimension (see .claude/CLAUDE.md's coefficients-and-representatives principle and
    // .claude/WORKLOG-chunks-representatives-incremental.md) -- the earlier "dimension-0 only" gap is closed,
    // so this checks every reported bar, not just dimension 0.
    "be readable for every engine=chunks bar, at every dimension, with matching vertex/coefficient array lengths" in {
      val chunksResult = FullBarcode.computeFromPoints(points, Array("engine", "chunks"))
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
  // local-to-ambient vertex remapping) -- WitnessStreamSpec already cross-validates the underlying
  // construction itself (brute-force oracle, general-vs-lazy agreement, Ripser-on-lazy, downward closure).
  // ---------------------------------------------------------------------------------------------------------

  "complex=witness, through the facade" should {
    "require numLandmarks" in {
      FullBarcode.computeFromPoints(points, Array("complex", "witness")) must throwA[IllegalArgumentException]
    }

    "reject an unrecognized landmarkSelector" in {
      FullBarcode.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", "4", "landmarkSelector", "bogus")
      ) must throwA[IllegalArgumentException]
    }

    "landmarkSelector=random with an explicit landmarkSeed matches PackedRipserCohomologyEngine driven " +
      "directly over the SAME LandmarkSelector.random(...) call" in {
        val numLandmarks = 4
        val seed = 7L
        val viaFacade = triples(
          FullBarcode
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

        val direct = PackedRipserCohomologyEngine[ff.Fp](wms, 2)
          .persistentCohomology()
          .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

        viaFacade must containTheSameElementsAs(direct)
      }

    "reject engine=ripser and engine=chunks combined with witnessVariant=general" in {
      (FullBarcode.computeFromPoints(
        points,
        Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general", "engine", "ripser")
      ) must throwA[IllegalArgumentException]) and
        (FullBarcode.computeFromPoints(
          points,
          Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general", "engine", "chunks")
        ) must throwA[IllegalArgumentException])
    }

    "default witnessVariant=lazy, engine=ripser: matches PackedRipserCohomologyEngine driven directly over " +
      "WitnessMetricSpace, via the SAME maxmin landmark selection" in {
        val numLandmarks = 4
        val viaFacade = triples(
          FullBarcode
            .computeFromPoints(points, Array("complex", "witness", "numLandmarks", numLandmarks.toString))
            .toArray()
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

        val direct = PackedRipserCohomologyEngine[ff.Fp](wms, 2)
          .persistentCohomology()
          .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))

        viaFacade must containTheSameElementsAs(direct)
      }

    "witnessVariant=general, engine=naive: matches WitnessCofaceSimplexStream driven directly, via the SAME " +
      "maxmin landmark selection" in {
        val numLandmarks = 4
        val viaFacade = triples(
          FullBarcode
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
          FullBarcode.computeFromPoints(points, Array("complex", "witness", "numLandmarks", numLandmarks.toString))
        val naiveResult = FullBarcode.computeFromPoints(
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
      val result = FullBarcode.computeFromPoints(
        points,
        // cocycles: they touch landmarks beyond the first numLandmarks point numbers, which the last check needs
        Array(
          "complex",
          "witness",
          "numLandmarks",
          numLandmarks.toString,
          "witnessVariant",
          "general",
          "representativeType",
          "cocycles"
        )
      )
      val vertices = (0 until result.size()).flatMap(i => result.cycleVertices(i).flatten).toSet
      (vertices.nonEmpty must beTrue) and
        (vertices.forall(landmarks.contains) must beTrue) and
        (vertices.subsetOf((0 until numLandmarks).toSet) must beFalse)
    }
  }

  // ---------------------------------------------------------------------------------------------------------
  // The two-step witness recipe: selectLandmarksFrom{Points,DistanceMatrix} (step 1) and
  // computeFrom{Points,DistanceMatrix}AndLandmarks (step 2), an alternative to the one-shot complex=witness path
  // above for callers who want the JavaPlex tutorial's own "pick landmarks, read R, pass 2R" recipe, or who want
  // to reuse/inspect/hand-edit a landmark set across more than one computation. The one-shot path above is
  // UNCHANGED by this refactor (confirmed by every test above still passing byte-for-byte); these tests cover
  // only the new entry points.
  // ---------------------------------------------------------------------------------------------------------

  "the two-step witness recipe, through the facade" should {
    "selectLandmarksFromPoints (maxmin, the default) matches LandmarkSelector.maxmin driven directly, for " +
      "both the landmarks and the covering radius" in {
        val numLandmarks = 4
        val result = TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", numLandmarks.toString))
        val direct = LandmarkSelector.maxmin(EuclideanMetricSpace(points), numLandmarks)
        (result.landmarks().toSeq must beEqualTo(direct.landmarks)) and
          (result.coveringRadius() must beEqualTo(direct.coveringRadius))
      }

    "selectLandmarksFromPoints (random) matches LandmarkSelector.random driven directly" in {
      val numLandmarks = 4
      val seed = 11L
      val result = TDA4j.selectLandmarksFromPoints(
        points,
        Array("numLandmarks", numLandmarks.toString, "landmarkSelector", "random", "landmarkSeed", seed.toString)
      )
      val direct = LandmarkSelector.random(EuclideanMetricSpace(points), numLandmarks, seed)
      (result.landmarks().toSeq must beEqualTo(direct.landmarks)) and
        (result.coveringRadius() must beEqualTo(direct.coveringRadius))
    }

    "selectLandmarksFromDistanceMatrix agrees exactly with selectLandmarksFromPoints on the same cloud's own " +
      "Euclidean distances" in {
        val numLandmarks = 4
        val distances = euclideanDistanceMatrix(points)
        val viaPoints = TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", numLandmarks.toString))
        val viaDistances =
          TDA4j.selectLandmarksFromDistanceMatrix(distances, Array("numLandmarks", numLandmarks.toString))
        (viaDistances.landmarks().toSeq must beEqualTo(viaPoints.landmarks().toSeq)) and
          (viaDistances.coveringRadius() must beEqualTo(viaPoints.coveringRadius()))
      }

    "the one-shot complex=witness path equals step 1 (selectLandmarksFromPoints) followed by step 2 " +
      "(computeFromPointsAndLandmarks) with the SAME landmarks, for both variants" in {
        val numLandmarks = 4
        def oneShot(variant: String): List[(Int, Double, Double)] =
          triples(
            FullBarcode
              .computeFromPoints(
                points,
                Array("complex", "witness", "numLandmarks", numLandmarks.toString, "witnessVariant", variant)
              )
              .toArray()
          ).sorted
        def twoStep(variant: String): List[(Int, Double, Double)] =
          val selection = TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", numLandmarks.toString))
          triples(
            FullBarcode
              .computeFromPointsAndLandmarks(points, selection.landmarks(), Array("witnessVariant", variant))
              .toArray()
          ).sorted
        (oneShot("lazy") must beEqualTo(twoStep("lazy"))) and (oneShot("general") must beEqualTo(twoStep("general")))
      }

    // THE discriminating test: {5,2,0} is deliberately NOT the set maxmin(numLandmarks=3) would choose from
    // this cloud ({0,4,5}, confirmed by hand -- points 4 and 5 tie at sqrt(4.25), 4 wins the tie). If
    // computeFromPointsAndLandmarks silently ignored its own `landmarks` argument and re-ran maxmin internally
    // instead, this would still "pass" a test built on maxmin's own output (as the one-shot-equals-two-step
    // test above necessarily is, by construction) -- it can only be caught by landmarks maxmin would not have
    // picked. Compared against PackedRipserCohomologyEngine driven directly over the SAME explicit, UNSORTED
    // array, as sorted lists (not `.toSet` -- see .claude/WORKLOG-witness-complex.md's own lesson about
    // multiplicity), plus a direct cycleVertices check.
    "computeFromPointsAndLandmarks uses the landmarks it is GIVEN, not a freshly-selected set" in {
      val landmarks = Array(5, 2, 0)
      // cocycles: they touch landmarks beyond the first landmarks.length point numbers, which the last check needs
      val result = FullBarcode.computeFromPointsAndLandmarks(points, landmarks, Array("representativeType", "cocycles"))
      val viaFacade = triples(result.toArray()).sorted

      val ff = new FiniteField(2)
      import ff.given
      val metricSpace = EuclideanMetricSpace(points)
      val wms = WitnessMetricSpace(WitnessGeometry(metricSpace, landmarks.toIndexedSeq), nu = 2)

      def toDouble(e: BarcodeEndpoint[Double]): Double = e match
        case NegativeInfinity() => Double.NegativeInfinity
        case PositiveInfinity() => Double.PositiveInfinity
        case ClosedEndpoint(v)  => v
        case OpenEndpoint(v)    => v

      val direct = PackedRipserCohomologyEngine[ff.Fp](wms, 2)
        .persistentCohomology()
        .map(bar => (bar.dim, toDouble(bar.lower), toDouble(bar.upper)))
        .sorted

      val allVertices = (0 until result.size()).flatMap(i => result.cycleVertices(i).flatten).toSet

      (viaFacade must beEqualTo(direct)) and
        (allVertices.forall(landmarks.contains) must beTrue) and
        (allVertices.subsetOf((0 until landmarks.length).toSet) must beFalse)
    }

    "reordering the SAME landmark set changes local indices/tie-breaks but not the resulting barcode" in {
      val barcodeA = triples(FullBarcode.computeFromPointsAndLandmarks(points, Array(0, 2, 5)).toArray()).sorted
      val barcodeB = triples(FullBarcode.computeFromPointsAndLandmarks(points, Array(5, 0, 2)).toArray()).sorted
      barcodeA must beEqualTo(barcodeB)
    }

    "computeFromDistanceMatrixAndLandmarks agrees exactly with computeFromPointsAndLandmarks on the same " +
      "cloud's own Euclidean distances, for the SAME hand-picked, unsorted landmark array used above" in {
        val landmarks = Array(5, 2, 0)
        val distances = euclideanDistanceMatrix(points)
        val viaDistances =
          triples(FullBarcode.computeFromDistanceMatrixAndLandmarks(distances, landmarks).toArray()).sorted
        val viaPoints = triples(FullBarcode.computeFromPointsAndLandmarks(points, landmarks).toArray()).sorted
        viaDistances must beEqualTo(viaPoints)
      }

    "computeFromPointsAndLandmarks rejects an invalid landmark array: empty, duplicate, negative, or out of " +
      "range (including exactly points.length, hinting at a 1-based-indexing mistake)" in {
        (FullBarcode.computeFromPointsAndLandmarks(points, Array.empty[Int]) must throwA[IllegalArgumentException]) and
          (FullBarcode.computeFromPointsAndLandmarks(points, Array(0, 1, 1)) must throwA[IllegalArgumentException]) and
          (FullBarcode.computeFromPointsAndLandmarks(points, Array(0, -1)) must throwA[IllegalArgumentException]) and
          (FullBarcode
            .computeFromPointsAndLandmarks(points, Array(0, points.length)) must throwA[IllegalArgumentException]) and
          (FullBarcode.computeFromPointsAndLandmarks(points, Array(0, points.length + 5)) must throwA[
            IllegalArgumentException
          ])
      }

    "computeFromPointsAndLandmarks rejects numLandmarks/landmarkSelector/landmarkSeed -- landmarks are given " +
      "directly here, not selected" in {
        val landmarks = Array(0, 2, 5)
        (FullBarcode.computeFromPointsAndLandmarks(points, landmarks, Array("numLandmarks", "3")) must throwA[
          IllegalArgumentException
        ]) and
          (FullBarcode.computeFromPointsAndLandmarks(
            points,
            landmarks,
            Array("landmarkSelector", "maxmin")
          ) must throwA[
            IllegalArgumentException
          ]) and
          (FullBarcode.computeFromPointsAndLandmarks(points, landmarks, Array("landmarkSeed", "0")) must throwA[
            IllegalArgumentException
          ])
      }

    "computeFromPointsAndLandmarks accepts complex=witness but rejects any other complex value" in {
      val landmarks = Array(0, 2, 5)
      (FullBarcode.computeFromPointsAndLandmarks(points, landmarks, Array("complex", "vr")) must throwA[
        IllegalArgumentException
      ]) and
        (FullBarcode.computeFromPointsAndLandmarks(points, landmarks, Array("complex", "witness")).size() must be_>=(0))
    }

    "selectLandmarksFromPoints rejects options meaningful only to step 2 (complex, witnessVariant, engine, ...)" in {
      (TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", "3", "complex", "witness")) must throwA[
        IllegalArgumentException
      ]) and
        (TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", "3", "witnessVariant", "lazy")) must throwA[
          IllegalArgumentException
        ])
    }

    "computeFromPointsAndLandmarks refuses engine=ripser/chunks with witnessVariant=general, exactly like the " +
      "one-shot path" in {
        val landmarks = Array(0, 2, 5)
        (FullBarcode.computeFromPointsAndLandmarks(
          points,
          landmarks,
          Array("witnessVariant", "general", "engine", "ripser")
        ) must throwA[IllegalArgumentException]) and
          (FullBarcode.computeFromPointsAndLandmarks(
            points,
            landmarks,
            Array("witnessVariant", "general", "engine", "chunks")
          ) must throwA[IllegalArgumentException]) and
          (FullBarcode
            .computeFromPointsAndLandmarks(points, landmarks, Array("witnessVariant", "general"))
            .size() must be_>=(0))
      }

    "coveringRadiusFromPoints matches LandmarkSelector.coveringRadius driven directly, for a hand-picked " +
      "landmark set" in {
        val landmarks = Array(0, 2, 5)
        val viaFacade = TDA4j.coveringRadiusFromPoints(points, landmarks)
        val direct = LandmarkSelector.coveringRadius(EuclideanMetricSpace(points), landmarks.toIndexedSeq)
        viaFacade must beEqualTo(direct)
      }

    "coveringRadiusFromDistanceMatrix agrees exactly with coveringRadiusFromPoints on the same cloud's own " +
      "Euclidean distances" in {
        val landmarks = Array(0, 2, 5)
        val distances = euclideanDistanceMatrix(points)
        TDA4j.coveringRadiusFromDistanceMatrix(distances, landmarks) must beEqualTo(
          TDA4j.coveringRadiusFromPoints(points, landmarks)
        )
      }
  }

  // ---------------------------------------------------------------------------------------------------------
  // computeFromRelation (Dowker complex) -- conversion-layer checks only, per this file's own stated purpose:
  // the underlying construction's own correctness (duality, monotonicity, the keptByThresholdAndCriterion
  // infinity fix) is already cross-validated in DowkerStreamSpec. A rectangular (numLeft != numWitnesses)
  // relation is used deliberately, the same shape DowkerStreamSpec's own duality property test needed to expose
  // a real bug during development -- see .claude/WORKLOG-dowker-complex.md.
  // ---------------------------------------------------------------------------------------------------------

  private val dowkerRelation: Array[Array[Double]] = Array(
    Array(0.0, 1.0, 2.0, 3.0),
    Array(1.0, 0.0, 1.0, 2.0),
    Array(2.0, 1.0, 0.0, 1.0)
  )

  "TDA4j.computeFromRelation" should {
    "default to engine=naive and match DowkerCofaceSimplexStream driven directly" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val direct = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(DowkerCofaceSimplexStream(dowkerRelation))
        .diagramAt(Double.PositiveInfinity)
      val facade = triples(FullBarcode.computeFromRelation(dowkerRelation).toArray())
      facade must containTheSameElementsAs(direct)
    }

    "engine=cohomology agrees exactly with the default engine=naive" in {
      val naive = triples(FullBarcode.computeFromRelation(dowkerRelation).toArray())
      val cohomology =
        triples(FullBarcode.computeFromRelation(dowkerRelation, Array("engine", "cohomology")).toArray())
      naive must containTheSameElementsAs(cohomology)
    }

    "reject engine=ripser and engine=chunks (the Dowker complex is not a flag complex in general)" in {
      (FullBarcode
        .computeFromRelation(dowkerRelation, Array("engine", "ripser")) must throwA[IllegalArgumentException]) and
        (FullBarcode
          .computeFromRelation(dowkerRelation, Array("engine", "chunks")) must throwA[IllegalArgumentException])
    }

    "reject a ragged or empty relation" in {
      (FullBarcode.computeFromRelation(Array(Array(0.0, 1.0), Array(0.0))) must throwA[IllegalArgumentException]) and
        (FullBarcode.computeFromRelation(Array.empty[Array[Double]]) must throwA[IllegalArgumentException])
    }

    "reject an unrecognized option (e.g. 'complex', which this entry point has no use for)" in {
      FullBarcode.computeFromRelation(dowkerRelation, Array("complex", "vr")) must throwA[IllegalArgumentException]
    }

    "dual=true matches DowkerCofaceSimplexStream(...).dual driven directly -- the functorial Dowker " +
      "duality theorem, exercised through the facade" in {
        given Double is Field = Field.DoubleApproximated(1e-9)
        val direct = SimplicialHomologyEngine[Int, Double, Double]()
          .persistentHomology(DowkerCofaceSimplexStream(dowkerRelation).dual)
          .diagramAt(Double.PositiveInfinity)
        val facade = triples(FullBarcode.computeFromRelation(dowkerRelation, Array("dual", "true")).toArray())
        facade must containTheSameElementsAs(direct)
      }

    "cycleVertices/cycleCoefficients are readable, same length, for engine=naive and engine=cohomology alike" in {
      val naiveResult = FullBarcode.computeFromRelation(dowkerRelation)
      val cohomologyResult = FullBarcode.computeFromRelation(dowkerRelation, Array("engine", "cohomology"))
      def allReadable(r: PersistenceResult): Boolean =
        (0 until r.size()).forall(i => r.cycleVertices(i).length == r.cycleCoefficients(i).length)
      allReadable(naiveResult) must beTrue
      allReadable(cohomologyResult) must beTrue
    }
  }
