package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}
import org.appliedtopology.tda4j.homology.HomologyFixtures.naiveBars

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.{mutable as s2mutable, ScalaCheck}
import org.specs2.execute.Result

/** Cross-validation for `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR algorithm, arXiv:2301.07191v3) against
  * ground truth built two independent ways: a brute-force subset scan, and the existing (already cross-validated)
  * `EnumeratingCofaceSimplexStream`. `matrixGen` comes from `VietorisRipsSpec.scala`.
  */
class IncrementalVietorisRipsSpec extends s2mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  // Same construction as RipserCohomologySpec.midThreshold: the interior midpoint of two adjacent distinct
  // pairwise distances, so it never collides with a simplex's own filtration value.
  private def midThreshold(metricSpace: FiniteMetricSpace[Int]): Double =
    val distances = (for
      x <- metricSpace.elements
      y <- metricSpace.elements
      if x != y
    yield metricSpace.distance(x, y)).toSeq.distinct.sorted
    if distances.size < 2 then distances.headOption.getOrElse(0.0) + 1.0
    else (distances(distances.size / 2 - 1) + distances(distances.size / 2)) / 2.0

  private def bruteForce(metricSpace: FiniteMetricSpace[Int], dim: Int, threshold: Double): Set[Simplex[Int]] =
    (0 until metricSpace.size)
      .combinations(dim + 1)
      .map(vs => Simplex(vs*))
      .filter { spx =>
        val vs = spx.toSeq.toIndexedSeq
        (for i <- vs.indices; j <- vs.indices if i < j yield metricSpace.distance(vs(i), vs(j)))
          .forall(_ <= threshold)
      }
      .toSet

  private def restrictToThreshold(bars: List[(Int, Double, Double)], t: Double): List[(Int, Double, Double)] =
    bars
      .filter { case (_, b, _) => b <= t }
      .map { case (dim, b, d) => (dim, b, if d > t then Double.PositiveInfinity else d) }

  val maxDim = 3

  "IncrementalVietorisRipsSimplexStream" >> {
    "agrees with brute-force subset enumeration at a finite threshold" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(4, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val t = midThreshold(metricSpace)
        val stream = IncrementalVietorisRipsSimplexStream(metricSpace, maxDim, Some(t))
        Result.foreach(0 to maxDim) { d =>
          stream.iterateDimension(d).toSet === bruteForce(metricSpace, d, t)
        }
      }

    "matches EnumeratingCofaceSimplexStream exactly at the untruncated (explicit +Infinity) threshold" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(4, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        // Both classes now default to metricSpace.minimumEnclosingRadius, not +Infinity (see
        // CLAUDE.md/WORKLOG-mst-and-perf.md) -- this comparison wants the genuinely untruncated complex on
        // both sides, so both need it explicitly.
        val incremental = IncrementalVietorisRipsSimplexStream(metricSpace, maxDim, Some(Double.PositiveInfinity))
        val enumerating =
          EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity))
        Result.foreach(0 to maxDim) { d =>
          incremental.iterateDimension(d).toSet === enumerating.iterateDimension(d).toSet
        }
      }

    "emits every dimension already sorted in filtration order, with no repeats" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(4, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val t = midThreshold(metricSpace)
        val stream = IncrementalVietorisRipsSimplexStream(metricSpace, maxDim, Some(t))
        Result.foreach(0 to maxDim) { d =>
          val bucket = stream.iterateDimension(d).toSeq
          (bucket.map(stream.filtrationValue) must beSorted) and
            (bucket.distinct.size === bucket.size)
        }
      }

    "the L(v) early-exit bound in Table-Lookup is a pure optimization: disabling it changes nothing" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 15))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val t = midThreshold(metricSpace)
        val withBound =
          IncrementalVietorisRipsSimplexStream(metricSpace, maxDim, Some(t), useLargestNeighborBound = true)
        val withoutBound =
          IncrementalVietorisRipsSimplexStream(metricSpace, maxDim, Some(t), useLargestNeighborBound = false)
        Result.foreach(0 to maxDim) { d =>
          withBound.iterateDimension(d).toSet === withoutBound.iterateDimension(d).toSet
        }
      }

    "agrees with the naive homology engine's barcode at the untruncated threshold" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val homDim = 2
        // Both classes now default to metricSpace.minimumEnclosingRadius, not +Infinity -- both sides need
        // it explicitly to compare the genuinely untruncated complex.
        val incrementalBars =
          naiveBars(IncrementalVietorisRipsSimplexStream(metricSpace, homDim, Some(Double.PositiveInfinity)))
        val enumeratingBars =
          naiveBars(
            LimitedCofaceSimplexStream(
              EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity)),
              homDim
            )
          )
        incrementalBars must containTheSameElementsAs(enumeratingBars)
      }

    "a thresholded stream's barcode equals the untruncated stream's barcode restricted to [0, t]" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val homDim = 2
        val t = midThreshold(metricSpace)
        val thresholdedBars = naiveBars(IncrementalVietorisRipsSimplexStream(metricSpace, homDim, Some(t)))
        val untruncatedBars =
          naiveBars(IncrementalVietorisRipsSimplexStream(metricSpace, homDim, Some(Double.PositiveInfinity)))
        thresholdedBars must containTheSameElementsAs(restrictToThreshold(untruncatedBars, t))
      }

    // The load-bearing check for the minimumEnclosingRadius default itself (see
    // RipserCohomologySpec's own version of this check, and CLAUDE.md/WORKLOG-mst-and-perf.md): is the
    // default just another threshold value, subject to the same restriction oracle as any explicit t?
    "the minimumEnclosingRadius default equals the untruncated barcode restricted to [0, minimumEnclosingRadius]" >>
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
        val metricSpace = EuclideanMetricSpace(points)
        val homDim = 2
        val t = metricSpace.minimumEnclosingRadius
        val defaultBars = naiveBars(IncrementalVietorisRipsSimplexStream(metricSpace, homDim)) // exercises the default
        val untruncatedBars =
          naiveBars(IncrementalVietorisRipsSimplexStream(metricSpace, homDim, Some(Double.PositiveInfinity)))
        defaultBars must containTheSameElementsAs(restrictToThreshold(untruncatedBars, t))
      }

    // Algorithm 4 as written does `Σ ← V ∪ E` unconditionally, so the paper's own Σ always carries the full
    // 1-skeleton regardless of d. This class deliberately does not: maxDimension behaves like
    // LimitedCofaceSimplexStream's maxDim (see the class doc), so maxDimension = 0 must yield vertices only,
    // no edges. Pinned directly rather than left to the property tests above, all of which use maxDim >= 2.
    "maxDimension = 0 yields vertices only, with no edges (a deliberate departure from the paper's own Σ ← V ∪ E)" >> {
      val metricSpace = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))
      // Explicit +Infinity: this fixture's own minimumEnclosingRadius (2.0, from the middle point) is less
      // than its longest edge (3.0), so the default would exclude that edge -- irrelevant to what this test
      // checks (dimension bounding), but would make the comparison below fail for an unrelated reason.
      val stream = IncrementalVietorisRipsSimplexStream(metricSpace, 0, Some(Double.PositiveInfinity))
      stream.iterateDimension(0).toSet === bruteForce(metricSpace, 0, Double.PositiveInfinity)
      stream.iterateDimension.isDefinedAt(1) must beFalse
    }

    "maxDimension = 1 yields vertices and edges, matching brute force" >> {
      val metricSpace = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))
      // Explicit +Infinity -- see the maxDimension = 0 case above for why.
      val stream = IncrementalVietorisRipsSimplexStream(metricSpace, 1, Some(Double.PositiveInfinity))
      stream.iterateDimension(0).toSet === bruteForce(metricSpace, 0, Double.PositiveInfinity)
      stream.iterateDimension(1).toSet === bruteForce(metricSpace, 1, Double.PositiveInfinity)
      stream.iterateDimension.isDefinedAt(2) must beFalse
    }

    // largestNeighbor(v) falls back to the v-itself sentinel exactly when upperNeighbors(v) is empty -- the case
    // this property-based suite never hits, since every generated cloud above has at least 6 points. A single
    // point exercises it directly: no edges exist at all, so every vertex's own sentinel is what Table-Lookup's
    // bound rests on (vacuously, since New for a lone vertex is already empty, but the table is still built).
    "a single-point metric space yields just the one vertex, at every dimension bound" >> {
      val metricSpace = EuclideanMetricSpace(Array(Array(0.0)))
      Result.foreach(Seq(0, 1, 3)) { dim =>
        val stream = IncrementalVietorisRipsSimplexStream(metricSpace, dim)
        Result.foreach(0 to dim) { d =>
          stream.iterateDimension(d).toSet === bruteForce(metricSpace, d, Double.PositiveInfinity)
        }
      }
    }

    // Two coincident points: distance 0 both by the `<=` edge test and by brute force at threshold 0, so the
    // edge and both vertices should all appear even at the tightest possible non-trivial threshold.
    "coincident points (distance zero) still form an edge, at threshold zero" >> {
      val metricSpace = EuclideanMetricSpace(Array(Array(0.0), Array(0.0)))
      val stream = IncrementalVietorisRipsSimplexStream(metricSpace, 1, maxFiltrationValue = Some(0.0))
      stream.iterateDimension(0).toSet === bruteForce(metricSpace, 0, 0.0)
      stream.iterateDimension(1).toSet === bruteForce(metricSpace, 1, 0.0)
    }
  }
