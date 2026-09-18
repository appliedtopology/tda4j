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
  * extensive cross-validation elsewhere -- see CLAUDE.md). Specifically: that `Tda4j`'s string-option parsing wires up
  * to the same engine calls a direct Scala caller would make, and that `PersistenceResult`'s double/Inf/dimension
  * conversion doesn't scramble anything relative to calling the engine directly. See WORKLOG-matlab-api.md for the
  * design this checks against, and note (per that worklog and CLAUDE.md) that MATLAB's own Java marshalling of
  * `double[][]`/`String[]` across the bridge is NOT covered by anything in this file -- unverified from Scala, flagged
  * explicitly rather than implied.
  */
class Tda4jSpec extends mutable.Specification:
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

  "Tda4j.computeFromPoints with default options (complex=vr, engine=ripser, field=Z, prime=2, maxDimension=2)" should {
    "match RipserCohomologyContext[Fp(2)] driven directly" in {
      // RipserCohomologyContext's own maxDimension now means "top homological degree reported," fixed at its
      // own source (see .claude/WORKLOG-maxdim-semantics-fix.md) -- so the facade's engine=ripser path
      // (Tda4j.computeGeneric) is now a fully transparent passthrough of requestedMaxDimension, with no
      // +1-and-filter workaround on either side of this comparison anymore.
      val viaFacade = triples(Tda4j.computeFromPoints(points).toArray())

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
      val ripser = triples(Tda4j.computeFromPoints(points).toArray())
      val naive = triples(Tda4j.computeFromPoints(points, Array("engine", "naive")).toArray())
      naive must containTheSameElementsAs(ripser)
    }
  }

  "computeFromDistanceMatrix" should {
    "agree with computeFromPoints given the same cloud's own Euclidean distances" in {
      val fromDist = triples(Tda4j.computeFromDistanceMatrix(euclideanDistanceMatrix(points)).toArray())
      val fromPoints = triples(Tda4j.computeFromPoints(points).toArray())
      fromDist must containTheSameElementsAs(fromPoints)
    }
  }

  "field=R" should {
    "agree (up to floating-point tolerance) with the default field=Z, through the facade" in {
      val zResult = triples(Tda4j.computeFromPoints(points).toArray()).sortBy(t => (t._1, t._2, t._3))
      val rResult =
        triples(Tda4j.computeFromPoints(points, Array("field", "R")).toArray()).sortBy(t => (t._1, t._2, t._3))

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
      Tda4j.computeFromPoints(points, Array("engine")) must throwA[IllegalArgumentException]
    }
    "reject an unrecognized option key" in {
      Tda4j.computeFromPoints(points, Array("bogus", "value")) must throwA[IllegalArgumentException]
    }
    "reject engine=ripser combined with complex=alpha" in {
      Tda4j
        .computeFromPoints(points, Array("complex", "alpha", "engine", "ripser")) must throwA[IllegalArgumentException]
    }
    "reject engine=chunks combined with complex=alpha" in {
      Tda4j
        .computeFromPoints(points, Array("complex", "alpha", "engine", "chunks")) must throwA[IllegalArgumentException]
    }
    "reject complex=alpha via computeFromDistanceMatrix (alpha needs coordinates)" in {
      Tda4j.computeFromDistanceMatrix(euclideanDistanceMatrix(points), Array("complex", "alpha")) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "representative chains" should {
    "be readable for at least one engine=ripser bar, with matching vertex/coefficient array lengths" in {
      val result = Tda4j.computeFromPoints(points)
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

    "explicitly refuse (not silently return empty) for engine=chunks" in {
      val chunksResult = Tda4j.computeFromPoints(points, Array("engine", "chunks"))
      chunksResult.cycleVertices(0) must throwA[UnsupportedOperationException]
    }
  }
