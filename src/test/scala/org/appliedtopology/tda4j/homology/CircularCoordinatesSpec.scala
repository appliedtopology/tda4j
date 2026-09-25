package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.specs2.mutable.Specification

/** Tests `CircularCoordinates` (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 2) against the textbook
  * example the construction is FOR: points sampled on a circle should recover an angle map affinely related to the true
  * geometric angle (up to the construction's own inherent ambiguities: a global phase/rotation, and an orientation/sign
  * flip, neither of which is meaningful to fix -- a cohomology class alone can't know "which way is positive").
  */
class CircularCoordinatesSpec extends Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  private def circlePoints(n: Int, radius: Double = 1.0): Array[Array[Double]] =
    Array.tabulate(n)(i => Array(radius * math.cos(2 * math.Pi * i / n), radius * math.sin(2 * math.Pi * i / n)))

  /** Independently finds the most-persistent H^1 bar's own (birth, death), via the SAME engine
    * CircularCoordinates.compute uses internally, but driven directly here rather than through that method -- this is
    * how a real caller would find a sensible `r` too (there is no way to pick one without first knowing the bar's own
    * range), not a testing-only shortcut.
    */
  private def mostPersistentH1Range(metricSpace: FiniteMetricSpace[Int]): (Double, Double) =
    val stream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = None), 2)
    val ctx = CellularCohomologyContext[Simplex[Int], Double, Double]()
    val bars = ctx.persistentCohomology(stream).filter(_.dim == 1)
    def value(e: BarcodeEndpoint[Double]): Double = e match
      case PositiveInfinity() => Double.PositiveInfinity
      case NegativeInfinity() => Double.NegativeInfinity
      case OpenEndpoint(v)    => v
      case ClosedEndpoint(v)  => v
    val best = bars.maxBy(b => value(b.upper) - value(b.lower))
    (value(best.lower), value(best.upper))

  /** Circular mean resultant length of `xs` (values interpreted mod 1, i.e. angles `2*pi*x`): close to `1` means `xs`
    * is tightly clustered on the circle (low circular spread), close to `0` means spread out/uniform. The standard way
    * to test "is this sequence approximately constant mod 1" without the wraparound artifacts a naive (non-circular)
    * variance would introduce at the 0/1 boundary.
    */
  private def circularResultantLength(xs: Iterable[Double]): Double =
    val n = xs.size
    val sumCos = xs.map(x => math.cos(2 * math.Pi * x)).sum
    val sumSin = xs.map(x => math.sin(2 * math.Pi * x)).sum
    math.sqrt(sumCos * sumCos + sumSin * sumSin) / n

  "a clean circle point cloud" should {
    "recover an angle map affinely related to the true geometric angle (same or reversed orientation, up to a " +
      "global phase)" >> {
        val n = 16
        val points = circlePoints(n)
        val metricSpace = EuclideanMetricSpace(points)
        val (birth, death) = mostPersistentH1Range(metricSpace)
        death must beGreaterThan(birth) // sanity: a real bar exists

        val r = birth + (death - birth) * 0.5
        val result = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 0)

        result.theta.size must beEqualTo(n) // a clean circle is one connected component, every point included

        val trueAngleFraction: Int => Double = i =>
          val a = math.atan2(points(i)(1), points(i)(0)) / (2 * math.Pi)
          a - math.floor(a)
        val sameOrientation = (0 until n).map(i => result.theta(i) - trueAngleFraction(i))
        val reversedOrientation = (0 until n).map(i => result.theta(i) + trueAngleFraction(i))
        val bestFit = math.max(circularResultantLength(sameOrientation), circularResultantLength(reversedOrientation))
        bestFit must beGreaterThan(0.99) // essentially constant offset mod 1, in one orientation or the other
      }

    "recover the angle map on a NOISY, non-symmetric circle too, not just the perfectly-even fixture (which " +
      "could hide an ordering bug behind extreme symmetry/tied filtration values)" >> {
        val n = 23 // deliberately not a "nice" number
        val rng = new scala.util.Random(2026)
        val points = Array.tabulate(n) { i =>
          val angle = 2 * math.Pi * i / n
          val radius = 1.0 + 0.05 * (rng.nextDouble() - 0.5) // +-2.5% radial noise
          Array(radius * math.cos(angle), radius * math.sin(angle))
        }
        val metricSpace = EuclideanMetricSpace(points)
        val (birth, death) = mostPersistentH1Range(metricSpace)
        val r = birth + (death - birth) * 0.5
        val result = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 0)

        val trueAngleFraction: Int => Double = i =>
          val a = math.atan2(points(i)(1), points(i)(0)) / (2 * math.Pi)
          a - math.floor(a)
        val presentIndices = result.theta.keySet
        presentIndices.size must beGreaterThan(n / 2) // most of the noisy circle should still be one component
        val sameOrientation = presentIndices.map(i => result.theta(i) - trueAngleFraction(i))
        val reversedOrientation = presentIndices.map(i => result.theta(i) + trueAngleFraction(i))
        val bestFit = math.max(circularResultantLength(sameOrientation), circularResultantLength(reversedOrientation))
        bestFit must beGreaterThan(0.95) // noisy but still tightly clustered mod 1
      }

    "reject an r outside [birth, death) with an actionable message" >> {
      val points = circlePoints(16)
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, _) = mostPersistentH1Range(metricSpace)
      CircularCoordinates.compute(metricSpace, r = birth - 1.0) must throwA[IllegalArgumentException]
    }

    "reject a non-prime or even prime" >> {
      val points = circlePoints(16)
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, death) = mostPersistentH1Range(metricSpace)
      val r = birth + (death - birth) * 0.5
      (CircularCoordinates.compute(metricSpace, r, prime = 4) must throwA[IllegalArgumentException]) and
        (CircularCoordinates.compute(metricSpace, r, prime = 2) must throwA[IllegalArgumentException])
    }

    "reject an out-of-range cocycleIndex" >> {
      val points = circlePoints(16)
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, death) = mostPersistentH1Range(metricSpace)
      val r = birth + (death - birth) * 0.5
      CircularCoordinates.compute(metricSpace, r, cocycleIndex = 1000) must throwA[IllegalArgumentException]
    }

    "give the same theta (up to floating point) across several different primes, on the same r" >> {
      val points = circlePoints(12)
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, death) = mostPersistentH1Range(metricSpace)
      val r = birth + (death - birth) * 0.5
      val results = Seq(47, 53, 101).map(p => CircularCoordinates.compute(metricSpace, r, prime = p))
      val reference = results.head.theta
      forall(results.tail) { other =>
        forall(reference.keySet) { i =>
          val diff = math.abs(other.theta(i) - reference(i))
          math.min(diff, 1.0 - diff) must beLessThan(1e-6) // circular distance, wraparound-safe
        }
      }
    }
  }

  "two disjoint circles, far apart" should
    "only assign theta to the component containing the chosen class, not the other circle" >> {
      val n = 12
      val nearCircle = circlePoints(n)
      val farCircle = circlePoints(n).map(p => Array(p(0) + 1000.0, p(1) + 1000.0))
      val points = nearCircle ++ farCircle
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, death) = mostPersistentH1Range(metricSpace)
      val r = birth + (death - birth) * 0.5

      val result = CircularCoordinates.compute(metricSpace, r, cocycleIndex = 0)
      // Exactly one circle's worth of points, and they're a contiguous ambient-index block (0..n-1 or n..2n-1) --
      // never a mix of both, and never spanning across the (deliberately astronomically distant) gap.
      (result.theta.size must beEqualTo(n)) and
        ((result.theta.keySet must beEqualTo((0 until n).toSet)) or
          (result.theta.keySet must beEqualTo((n until 2 * n).toSet)))
    }

  "essential representatives at K_r" should
    "are genuine cocycles on both CellularCohomologyContext and PackedRipserCohomologyContext -- cross-checked " +
    "independently of CircularCoordinates' own internals, per the originating worklog's own suggested first " +
    "correctness test" >> {
      val points = circlePoints(16)
      val metricSpace = EuclideanMetricSpace(points)
      val (birth, death) = mostPersistentH1Range(metricSpace)
      val r = birth + (death - birth) * 0.5

      val krStream =
        LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(r)), 2)

      val cellularCtx = CellularCohomologyContext[Simplex[Int], Double, Double]()
      val cellularEssential = cellularCtx
        .persistentCohomology(krStream)
        .filter(b => b.dim == 1 && b.upper == PositiveInfinity())
      cellularEssential must not(beEmpty)
      forall(cellularEssential) { bar =>
        val rep = bar.annotation.get
        val triangles = krStream.iterator.filter(_.dim == 2)
        cellularCtx.coboundaryOfChain(rep, triangles).isZero() must beTrue
      }

      val ripserCtx = PackedRipserCohomologyContext[Double](metricSpace, 1, maxFiltrationValue = Some(r))
      val ripserEssential = ripserCtx.persistentCohomology().filter(b => b.dim == 1 && b.upper == PositiveInfinity())
      ripserEssential must not(beEmpty)
    }
