package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.specs2.mutable.Specification

/** Tests `CircularCoordinates.computeToroidal` (`.claude/WORKLOG-toroidal-coordinates.md`) -- the lattice-reduction
  * (LLL) extension of `CircularCoordinates.compute` combining SEVERAL simultaneously-alive H^1 classes into one
  * torus-valued map. `LatticeReductionSpec` already covers the reduction algorithm itself in isolation (Gram matrices
  * only, no geometry); this file is about the WIRING: class selection, the shared-component requirement, and that
  * `reduce=false` exactly reproduces calling `compute` per class.
  */
class ToroidalCoordinatesSpec extends Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  /** A wedge of two circles of DIFFERENT radii (deliberately unequal -- breaks any birth/death tie between the two
    * loops), touching near a single point so the whole point cloud is one connected component but the two loops stay
    * topologically independent (H^1 rank 2: a shared single join point doesn't add a cycle, same as a wedge sum). No
    * randomness anywhere -- circle A's own sample point 0 and circle B's own sample point 0 are placed to land almost
    * on top of each other by construction, and every other pair of A/B points is far apart by comparison, so exactly
    * one bridging edge appears over a wide range of filtration values, never a second one that would create a spurious
    * extra cycle around the outside.
    */
  private def wedgeOfTwoCircles(n: Int = 14): Array[Array[Double]] =
    val circleA = Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(math.cos(a), math.sin(a)) // center (0,0), radius 1.0
    }
    val circleB = Array.tabulate(n) { i =>
      val a = math.Pi + 2 * math.Pi * i / n // offset so i=0 lands at angle pi, i.e. facing circle A
      Array(1.55 + 0.6 * math.cos(a), 0.6 * math.sin(a)) // center (1.55,0), radius 0.6
    }
    circleA ++ circleB

  private def circlePoints(n: Int, center: (Double, Double) = (0.0, 0.0), radius: Double = 1.0): Array[Array[Double]] =
    Array.tabulate(n) { i =>
      val a = 2 * math.Pi * i / n
      Array(center._1 + radius * math.cos(a), center._2 + radius * math.sin(a))
    }

  /** A "theta graph": two poles at `(-1,0)`/`(1,0)` joined by three geometrically distinct arcs (top/bottom elliptical
    * bulges of DELIBERATELY different heights -- `1.0` vs `0.5`, breaking the top/bottom symmetry a plain top/bottom
    * semicircle pair would have, which ties the two natural cycles' birth values exactly and trips the
    * `matches.length == 1` tie-detection `computeToroidalGeneric` itself needs -- plus a straight middle path) -- 3
    * edges between 2 "vertices" gives H^1 rank 2, but UNLIKE `wedgeOfTwoCircles` above (whose two loops touch at a
    * single point and so have DISJOINT harmonic-cochain supports -- confirmed empirically, `originalGram` came back
    * diagonal there), any 2 independent cycles among only 3 arcs must share at least one arc (`top-middle` and
    * `bottom-middle` both use `middle`; so does any other integer basis of the same rank-2 lattice) -- so the resulting
    * Gram matrix has a genuine, nonzero off-diagonal entry REGARDLESS of which specific basis persistent cohomology
    * happens to return. This is the fixture that can actually demonstrate decorrelation, not just exercise the
    * identity/no-op path.
    */
  private def thetaGraph(pointsPerArc: Int = 9): Array[Array[Double]] =
    def arc(f: Double => Array[Double]): Array[Array[Double]] =
      Array.tabulate(pointsPerArc)(i => f((i + 1).toDouble / (pointsPerArc + 1)))
    val poles = Array(Array(-1.0, 0.0), Array(1.0, 0.0))
    val top = arc(t => Array(math.cos(math.Pi * (1 - t)), 1.0 * math.sin(math.Pi * (1 - t))))
    val bottom = arc(t => Array(math.cos(math.Pi * (1 - t)), -0.5 * math.sin(math.Pi * (1 - t))))
    val middle = arc(t => Array(-1.0 + 2.0 * t, 0.0))
    poles ++ top ++ bottom ++ middle

  private def det2(m: Array[Array[Double]]): Double = m(0)(0) * m(1)(1) - m(0)(1) * m(1)(0)

  /** Drives `CellularCohomologyContext` directly to recover the SAME integer cocycle `computeToroidalGeneric` itself
    * would compute for `cocycleIndex` at `r` -- same pattern `CircularCoordinatesSpec`'s own "essential
    * representatives" test already uses to check the engine independently of `CircularCoordinates`' own internals, not
    * a new production-code hook. Returns the integer cocycle (as an edge -> Int map) and the `K_r` stream it was
    * validated against, so a caller can combine several such cocycles and re-smooth once via the
    * ALREADY-package-private `harmonicSmoothOnComponent` -- the oracle the "combining cocycles first" test below needs.
    */
  private def integerCocycleAt(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int,
    prime: Int = 47
  ): (Map[Simplex[Int], Int], CellStream[Simplex[Int], Double]) =
    def value(e: BarcodeEndpoint[Double]): Double = e match
      case PositiveInfinity() => Double.PositiveInfinity
      case NegativeInfinity() => Double.NegativeInfinity
      case OpenEndpoint(v)    => v
      case ClosedEndpoint(v)  => v
    val ff = new FiniteField(prime)
    import ff.given
    val ctx = CellularCohomologyContext[Simplex[Int], ff.Fp, Double]()
    val fullStream =
      LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = None), 2)
    val bars = ctx.persistentCohomology(fullStream).filter(_.dim == 1).sortBy(b => -(value(b.upper) - value(b.lower)))
    val birth = value(bars(cocycleIndex).lower)
    val krStream =
      LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(r)), 2)
    val essential = ctx.persistentCohomology(krStream).filter(b => b.dim == 1 && !value(b.upper).isFinite)
    val cocycle = essential.filter(b => value(b.lower) == birth).head.annotation.get
    val zInt = cocycle.rawEntries.groupMapReduce(_._1)(t => t._2.toInt)(_ + _)
    (zInt, krStream)

  "computeToroidal on a wedge of two independent circles" should {
    "find (at least) two persistent H^1 bars, simultaneously alive over a shared range" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      bars.length must beGreaterThanOrEqualTo(2)
      val (b0, d0) = bars(0)
      val (b1, d1) = bars(1)
      math.max(b0, b1) must beLessThan(math.min(d0, d1)) // a common r exists
    }

    "with reduce=false, reproduce compute's own per-class theta exactly, point for point" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

      val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1), reduce = false)
      val direct0 = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 0)
      val direct1 = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 1)

      toroidal.basisChange must beEqualTo(Array(Array(1, 0), Array(0, 1)))
      (toroidal.theta(0) must beEqualTo(direct0.theta)) and (toroidal.theta(1) must beEqualTo(direct1.theta))
    }

    "with reduce=true (default) on an ALREADY near-diagonal Gram matrix (the wedge's two loops touch at a single " +
      "point, giving disjoint harmonic-cochain supports -- confirmed empirically: originalGram's off-diagonal " +
      "entry here is under 5% of either diagonal entry), leave it alone (identity basisChange) and still satisfy " +
      "every general invariant -- this test does NOT exercise genuine decorrelation, see the theta-graph tests " +
      "below for that" >> {
        val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
        val bars = CircularCoordinates.h1Bars(metricSpace)
        val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

        val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1))
        val u = toroidal.basisChange.map(_.map(_.toDouble))
        val det = det2(u)
        val g = toroidal.originalGram

        (math.abs(g(0)(1)) must beLessThan(0.05 * math.min(math.abs(g(0)(0)), math.abs(g(1)(1))))) and
          (math.abs(math.abs(det) - 1.0) must beLessThan(1e-6)) and
          (LatticeReduction.isReduced(toroidal.reducedGram) must beTrue) and
          // same rank-2 sublattice of H^1 before/after -- covolume^2 = det(Gram) is a unimodular-change invariant
          (math.abs(det2(toroidal.reducedGram) - det2(toroidal.originalGram)) must beLessThan(
            1e-6 * (1.0 + math.abs(det2(toroidal.originalGram)))
          )) and
          (toroidal.theta.length must beEqualTo(2)) and
          (toroidal.cocycleIndices must beEqualTo(IndexedSeq(0, 1)))
      }

    "reject cocycleIndices whose classes don't share a connected component" >> {
      // The two circles are DELIBERATELY far apart (offset 1000) so they never share a component. A VR
      // filtration also generates plenty of short-lived small-scale H^1 "noise" bars well below the main
      // circle-completing loop's own persistence (ordinary VR behavior, not specific to this fixture) -- so
      // rather than assume a specific total bar count, just confirm the top 2 BY PERSISTENCE are the two real
      // (identical, since the circles are exact translates of each other) circle loops, which they must be
      // since nothing else in either circle's own filtration is anywhere near as persistent.
      val n = 12
      val nearCircle = circlePoints(n)
      val farCircle = circlePoints(n, center = (1000.0, 1000.0))
      val metricSpace = EuclideanMetricSpace(nearCircle ++ farCircle)
      val bars = CircularCoordinates.h1Bars(metricSpace, maxFiltrationValue = Some(3.0))
      bars.length must beGreaterThanOrEqualTo(2)
      val (b0, d0) = bars(0)
      val (b1, d1) = bars(1)
      (math.abs(b0 - b1) must beLessThan(1e-9)) and (math.abs(d0 - d1) must beLessThan(1e-9))
      val r = b0 + (d0 - b0) * 0.5

      CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1), maxFiltrationValue = Some(3.0)) must
        throwA[IllegalArgumentException]
    }

    "reject an empty cocycleIndices" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      CircularCoordinates.computeToroidal(metricSpace, 0.5, Seq.empty) must throwA[IllegalArgumentException]
    }

    "reject duplicate cocycleIndices" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val r = math.max(bars(0)._1, bars(1)._1) + 1e-6
      CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 0)) must throwA[IllegalArgumentException]
    }

    "reject an out-of-range cocycleIndex" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      CircularCoordinates.computeToroidal(metricSpace, 0.5, Seq(0, 1000)) must throwA[IllegalArgumentException]
    }

    "reject an r outside the chosen classes' shared alive range" >> {
      val metricSpace = EuclideanMetricSpace(wedgeOfTwoCircles())
      val bars = CircularCoordinates.h1Bars(metricSpace)
      val tooEarly = math.min(bars(0)._1, bars(1)._1) - 1.0
      CircularCoordinates.computeToroidal(metricSpace, tooEarly, Seq(0, 1)) must throwA[IllegalArgumentException]
    }
  }

  "computeToroidal on a theta graph (shared-edge cycles, unlike the wedge above)" should {
    "genuinely decorrelate: originalGram has a real off-diagonal entry, and reducing it changes basisChange " +
      "away from the identity" >> {
        val metricSpace = EuclideanMetricSpace(thetaGraph())
        val bars = CircularCoordinates.h1Bars(metricSpace)
        bars.length must beGreaterThanOrEqualTo(2)
        val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

        val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1))
        val g = toroidal.originalGram

        // a REAL correlation, not floating-point noise -- at least 10% of the smaller diagonal entry
        (math.abs(g(0)(1)) must beGreaterThan(0.1 * math.min(math.abs(g(0)(0)), math.abs(g(1)(1))))) and
          (toroidal.basisChange must not(beEqualTo(Array(Array(1, 0), Array(0, 1))))) and
          (LatticeReduction.isReduced(toroidal.reducedGram) must beTrue) and
          (math.abs(math.abs(det2(toroidal.basisChange.map(_.map(_.toDouble)))) - 1.0) must beLessThan(1e-6))
      }

    "combining the per-class integer cocycles first and smoothing ONCE gives the same coordinates (up to a " +
      "single shared additive offset mod 1 -- the anchor-vertex ambiguity every circular coordinate already has) " +
      "as CircularCoordinates.computeToroidal's own combine-after-smoothing shortcut -- the independent check " +
      "that the linearity argument the shortcut relies on is actually being applied correctly, not merely that " +
      "some basis change was computed" >> {
        val metricSpace = EuclideanMetricSpace(thetaGraph())
        val bars = CircularCoordinates.h1Bars(metricSpace)
        val r = math.max(bars(0)._1, bars(1)._1) + 1e-6

        val toroidal = CircularCoordinates.computeToroidal(metricSpace, r, Seq(0, 1))
        val u = toroidal.basisChange
        u must not(beEqualTo(Array(Array(1, 0), Array(0, 1)))) // otherwise this test would not be exercising U at all

        val (z0, krStream) = integerCocycleAt(metricSpace, r, 0)
        val (z1, _) = integerCocycleAt(metricSpace, r, 1)
        val allEdges = z0.keySet ++ z1.keySet

        def circularOffset(a: Double, b: Double): Double =
          val d = a - b
          d - math.floor(d)

        forall(0 until 2) { c =>
          val zCombined = allEdges.map(e => e -> (u(0)(c) * z0.getOrElse(e, 0) + u(1)(c) * z1.getOrElse(e, 0))).toMap
          val (thetaDirect, _, component) = CircularCoordinates.harmonicSmoothOnComponent(krStream, zCombined)
          val points = component.toIndexedSeq.map(_.underlying.head)
          points must not(beEmpty)
          val refPoint = points.head
          val offset = circularOffset(thetaDirect(refPoint), toroidal.theta(c)(refPoint))
          forall(points) { p =>
            val thisOffset = circularOffset(thetaDirect(p), toroidal.theta(c)(p))
            val circularDiff = math.min(math.abs(thisOffset - offset), 1.0 - math.abs(thisOffset - offset))
            circularDiff must beLessThan(1e-6)
          }
        }
      }
  }
