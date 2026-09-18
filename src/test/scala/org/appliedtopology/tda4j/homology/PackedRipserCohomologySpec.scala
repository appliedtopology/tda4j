package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.appliedtopology.tda4j.barcode.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

/** Cross-validates `PackedRipserCohomologyContext` (`PackedRipserCohomology.scala`) against the reference
  * `RipserCohomologyContext` (`Homology.scala`) -- NOT against hand-derived expected barcodes, since that would just
  * re-litigate `RipserCohomologySpec`'s own already-established correctness. The reference engine is itself extensively
  * cross-validated elsewhere (see CLAUDE.md); this spec's job is only to confirm the packed re-keying didn't change
  * behavior, on exactly the fixtures `RipserCohomologySpec` already uses (so a real behavioral difference here can't be
  * blamed on an unfamiliar input). See `.claude/WORKLOG-packed-ripser-engine.md` for the design and the performance
  * measurement this spec's passing status is a precondition for.
  */
class PackedRipserCohomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given Parameters = Parameters(minTestsOk = 200)

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def toTuple[A](bar: PersistenceBar[Double, A]): (Int, Double, Double) =
    (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))

  private def referenceBars(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int,
    maxFiltrationValue: Double = Double.NaN
  ): List[(Int, Double, Double)] =
    RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = maxFiltrationValue)
      .persistentCohomology()
      .map(toTuple)

  private def packedBars(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int,
    maxFiltrationValue: Double = Double.NaN
  ): List[(Int, Double, Double)] =
    PackedRipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = maxFiltrationValue)
      .persistentCohomology()
      .map(toTuple)

  // Same fixture RipserCohomologySpec uses (3 colinear points, distances 1/2/3 -- all distinct, so H_0's
  // finite deaths are unambiguous, not tie-broken guesses).
  private val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))

  // Same adversarial cloud RipserCohomologySpec pins for the apparent-pairs singleton-vs-full-chain
  // regression (WORKLOG-cohomology.md's "Apparent pairs: resolved" section) -- a genuine mutual apparent
  // pair whose sigma ALSO has a second, non-apparent-paired cofacet tied at the same value, the exact shape
  // that caught a real bug in the reference engine's own apparent-pairs shortcut. Worth reusing here since
  // the packed engine reimplements that exact shortcut independently.
  private val apparentPairCollisionCloud = EuclideanMetricSpace(
    Array(
      Array(0.6840899819795643, 0.8632191311777603),
      Array(0.7925816891415143, 0.5159681790435516),
      Array(0.28417731332368257, 0.4559405417686636),
      Array(0.35675429103128775, 0.4806622567817712),
      Array(0.9029384857840582, 0.692961159818487),
      Array(0.20118350789432415, 0.06900108902109969),
      Array(0.1158610163249012, 0.5096602272996563),
      Array(0.12795009537546453, 0.906743133696058),
      Array(0.11613774851681025, 0.024181302854615505),
      Array(0.8012044768533325, 0.5021171135882059),
      Array(0.8937233905719311, 0.16065156938011227),
      Array(0.5637341324741952, 0.8352605284654816)
    )
  )

  "Packed engine matches the reference exactly on the 3-point line at maxDimension=1 (essential H^1 -- no triangle-caused kill possible below H^1's own requested degree, but the killing triangle still resolves correctly at degree 1 itself)" >> {
    packedBars(threePointLine, 1, Double.PositiveInfinity) must containTheSameElementsAs(
      referenceBars(threePointLine, 1, Double.PositiveInfinity)
    )
  }

  "Packed engine matches the reference exactly on the filled triangle at maxDimension=2 (zero-persistence H^1)" >> {
    packedBars(threePointLine, 2, Double.PositiveInfinity) must containTheSameElementsAs(
      referenceBars(threePointLine, 2, Double.PositiveInfinity)
    )
  }

  "Packed engine matches the reference exactly on the apparent-pair collision regression cloud" >> {
    packedBars(apparentPairCollisionCloud, 2, Double.PositiveInfinity) must containTheSameElementsAs(
      referenceBars(apparentPairCollisionCloud, 2, Double.PositiveInfinity)
    )
  }

  "The packed engine's apparent-pair substitution actually fires on the collision regression example" >> {
    // Mirrors RipserCohomologySpec's own discriminating check: not just "the barcode is unchanged" (which a
    // fallback that never fires could also produce if the shortcut path alone happens to be sufficient), but
    // that the on-the-fly recomputation this engine independently reimplements is genuinely exercised.
    val ctx = PackedRipserCohomologyContext[Double](apparentPairCollisionCloud, 2)
    ctx.persistentCohomology()
    ctx.substitutionCount must be_>(0)
  }

  "The packed engine's substitution never fires when useApparentPairs is disabled" >> {
    val ctx = PackedRipserCohomologyContext[Double](apparentPairCollisionCloud, 2, useApparentPairs = false)
    ctx.persistentCohomology()
    ctx.substitutionCount must be_==(0)
  }

  "Packed engine matches the reference exactly on random Vietoris-Rips point clouds, unthresholded" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 15))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      packedBars(metricSpace, 2, Double.PositiveInfinity) must containTheSameElementsAs(
        referenceBars(metricSpace, 2, Double.PositiveInfinity)
      )
    }

  "Packed engine matches the reference exactly on random Vietoris-Rips point clouds, at the default (enclosing-radius) threshold" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 15))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      packedBars(metricSpace, 2) must containTheSameElementsAs(referenceBars(metricSpace, 2))
    }

  "Packed engine's totalSimplexCount matches the reference's exactly, per random cloud" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 15))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val refCtx = RipserCohomologyContext[Double](metricSpace, 2)
      refCtx.persistentCohomology()
      val packedCtx = PackedRipserCohomologyContext[Double](metricSpace, 2)
      packedCtx.persistentCohomology()
      packedCtx.totalSimplexCount must be_==(refCtx.totalSimplexCount)
    }

  "Packed engine matches the reference exactly at requested maxDimension=3, confirming the maxDim-semantics fix carried over correctly" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(3, 4), Gen.chooseNum(6, 10))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      packedBars(metricSpace, 3, Double.PositiveInfinity) must containTheSameElementsAs(
        referenceBars(metricSpace, 3, Double.PositiveInfinity)
      )
    }
