package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.execute.AsResult

/** `EdgeCollapse` (Boissonnat-Pritam/Glisse-Pritam, `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 5).
  * Validation order follows this codebase's established convention for a new construction: hand-derived fixtures
  * pinning the two documented outcomes (shift to a later value; outright removal) by direct computation first, THEN
  * property-based and tie-heavy barcode cross-validation against plain (uncollapsed) VR -- agreement between the
  * two is the real oracle here (`WORKLOG-edge-collapse.md`), not a comparison against any external tool (this
  * session could not verify the reference algorithm's own paper directly -- see the class doc).
  */
class EdgeCollapseStreamSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  "EdgeCollapse.collapse on a hand-built domination fixture" should {
    // Points 0,1,2 mutually at distance 1 (an equilateral-flavored core); point 3 at distance 2 from BOTH 0 and 1
    // (symmetric with respect to edge (0,1), the one edge under test), and from 2 at distance 1.5. Processing
    // order (descending by ORIGINAL value): (0,3)=2, (1,3)=2, then (0,1)=(0,2)=(1,2)=1. (0,3): common={1,2}
    // (1 joins at max(1,2)=2; 2 joins at max(1,1.5)=1.5), dominated by 1 at t=2 (1 is self-adjacent and
    // adjacent to 2 via d(1,2)=1<=2) -- REMOVED. (1,3): now common={2} only (0 is no longer adjacent to 3),
    // trivially dominated by 2 (single common neighbor) -- REMOVED. (2,3): common is now EMPTY (neither 0 nor 1
    // is still adjacent to 3) -- survives UNCHANGED at 1.5. (0,1): common={2} only (3 is no longer adjacent to
    // EITHER 0 or 1, having lost both its edges above) -- trivially dominated by 2 -- REMOVED. (0,2),(1,2): each
    // now has an EMPTY common set for the identical reason -- both survive UNCHANGED at 1.
    "removes edge (0,1) (and (0,3), (1,3)), leaving (0,2), (1,2), (2,3) unchanged -- a cascade where two earlier " +
      "removals (in descending-filtration-value order) strip vertex 3 of its OWN connection to both 0 and 1, so " +
      "by the time (0,1) itself is processed its only remaining common neighbor (2) trivially dominates it" >> {
      val space = ExplicitMetricSpace(
        Seq(Seq(0.0, 1.0, 1.0, 2.0), Seq(1.0, 0.0, 1.0, 2.0), Seq(1.0, 1.0, 0.0, 1.5), Seq(2.0, 2.0, 1.5, 0.0))
      )
      val collapsed = EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity))
      (collapsed.distance(0, 1) must beEqualTo(Double.PositiveInfinity)) and
        (collapsed.distance(0, 3) must beEqualTo(Double.PositiveInfinity)) and
        (collapsed.distance(1, 3) must beEqualTo(Double.PositiveInfinity)) and
        (collapsed.distance(0, 2) must beEqualTo(1.0)) and
        (collapsed.distance(1, 2) must beEqualTo(1.0)) and
        (collapsed.distance(2, 3) must beEqualTo(1.5))
    }

    // A genuine SHIFT (not outright removal) needs enough points that the tested edge's two candidate dominators
    // both fail only once EVERY common neighbor is active, via a cascade of its OWN making -- found by search
    // (not hand-designed) on random 5-point clouds, then hand-verified term-by-term against `EdgeCollapse`'s own
    // definition (not just trusted from the search) before being pinned here as a regression fixture; the real
    // correctness oracle for this construction throughout is the barcode-agreement suite below, not this one
    // example -- this fixture exists to give a human-checkable trace that the shift CODE PATH is genuinely
    // exercised, not just reachable in principle.
    //
    // Processing order (descending): (3,4)=9.727, (0,1)=9.171, (1,2)=8.257, (0,3)=8.020, (1,3)=6.995,
    // (2,4)=6.802 [under test], (0,4)=6.046, (1,4)=5.858, (2,3)=5.773, (0,2)=2.324.
    //  - (3,4): common={0,1,2} (join times 8.02, 6.995, 6.802, all <= 9.727) -- 0 dominates (adjacent to both 1
    //    and 2, and itself) -- REMOVED.
    //  - (0,1): common={2,3,4} (join times 8.257, 8.02, 6.046) -- 2 dominates (adjacent to 3 via 5.773, to 4 via
    //    6.802 -- still its ORIGINAL value, unprocessed at this point -- and to itself) -- REMOVED.
    //  - (1,2): common={3,4} (3 no longer adjacent to 4, which was removed above, so vertex 0 no longer
    //    qualifies) -- neither 3 nor 4 is (still) adjacent to the other ((3,4) removed) -- NOT dominated --
    //    survives UNCHANGED at 8.257.
    //  - (0,3): common={2} only (1 lost its edge to 0 above) -- trivially dominated by 2 -- REMOVED.
    //  - (1,3): common={2} only (0 lost its edge to 1 above), joining at 8.257 (unchanged) -- empty active set at
    //    (1,3)'s own birth 6.995 (2 isn't active until 8.257) -- NOT dominated at birth -- survives UNCHANGED.
    //  - (2,4) [under test]: common={0,1} (3 lost its edge to 4 above) -- 0 joins at max(2.324,6.046)=6.046,
    //    1 joins at max(8.257,5.858)=8.257. At t=6.802 (birth): active={0} only, trivially dominated. Pushing to
    //    t=8.257 (1 joins): active={0,1} -- neither dominates (0-1 was removed above, so neither is adjacent to
    //    the other) -- domination breaks exactly here, at the LARGEST candidate -- SHIFTS to 8.257 (not removed:
    //    a finite failure point was found, not "dominated at every candidate").
    "shifts edge (2,4) from 6.802 to 8.257 (exactly matching the point at which its own would-be dominators " +
      "(0, then 1) each stop qualifying, both via an EARLIER cascade elsewhere in the same descending pass) " +
      "rather than removing it outright" >> {
      val pts = Array(
        Array(8.463243195926422, 4.0847280919596685),
        Array(0.1844386250790142, 8.031404043213556),
        Array(6.546935795037694, 2.7693418423902214),
        Array(1.024453591412785, 1.087407899625793),
        Array(5.846592877439953, 9.535171457217388)
      )
      val space = EuclideanMetricSpace(pts)
      val collapsed = EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity))
      (collapsed.distance(2, 4) must beCloseTo(8.25655308485726, 1e-9)) and
        (collapsed.distance(2, 4) must beCloseTo(space.distance(1, 2), 1e-9)) and
        (collapsed.distance(0, 1) must beEqualTo(Double.PositiveInfinity)) and
        (collapsed.distance(3, 4) must beEqualTo(Double.PositiveInfinity)) and
        (collapsed.distance(1, 2) must beCloseTo(space.distance(1, 2), 1e-9))
    }
  }

  "EdgeCollapse.collapse on a generic (all-distinct-distances) triangle" should {
    "removes exactly the longest edge -- the two shorter edges already connect its endpoints through the third " +
      "vertex, so the longest edge's own momentary hollow-triangle-then-instantly-refilled contribution is a " +
      "zero-persistence blip either way (conceptually the same kind of redundancy apparent pairs also exploit, " +
      "though by a completely different mechanism)" >> {
      val space = ExplicitMetricSpace(Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0)))
      val collapsed = EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity))
      (collapsed.distance(0, 1) must beEqualTo(1.0)) and
        (collapsed.distance(0, 2) must beEqualTo(2.0)) and
        (collapsed.distance(1, 2) must beEqualTo(Double.PositiveInfinity))
    }
  }

  "EdgeCollapse.collapse on two disjoint, far-apart edges (no shared vertex, no possible common neighbor)" should {
    "collapses nothing -- an edge with no common neighbor at all can never be dominated" >> {
      val space = ExplicitMetricSpace(
        Seq(
          Seq(0.0, 1.0, 1000.0, 1000.0),
          Seq(1.0, 0.0, 1000.0, 1000.0),
          Seq(1000.0, 1000.0, 0.0, 1.0),
          Seq(1000.0, 1000.0, 1.0, 0.0)
        )
      )
      // bound = 10, strictly between the two "real" edges (1.0) and the four "cross" pairs (1000.0) -- a bound of
      // Infinity would admit the cross pairs too (IEEE-754 Infinity <= Infinity), making this a complete graph
      // on 4 vertices instead of two genuinely disjoint edges (CLAUDE.md's own documented hazard, re-confirmed
      // here by first getting this test wrong the same way).
      val collapsed = EdgeCollapse.collapse(space, maxFiltrationValue = Some(10.0))
      val stats = collapsed.stats
      stats.edgesRemoved must beEqualTo(0)
    }
  }

  "EdgeCollapsedMetricSpace.minimumEnclosingRadius" should {
    "equals the bound actually used for the collapse (the original space's own enclosing radius by default), " +
      "never something inflated by the collapsed graph's own new +Infinity entries" >> {
      val rng = new scala.util.Random(7)
      val pts = Array.fill(14)(Array(rng.nextDouble() * 10, rng.nextDouble() * 10))
      val space = EuclideanMetricSpace(pts)
      val collapsed = EdgeCollapse.collapse(space)
      collapsed.minimumEnclosingRadius must beEqualTo(space.minimumEnclosingRadius)
    }
  }

  "EdgeCollapse re-applied to its own output" should {
    "may remove further edges (GUDHI's own doc: one pass is not necessarily minimal) but its barcode still " +
      "agrees with plain VR either way -- this is a single faithfully-ordered pass, not a fixed-point iteration " +
      "to convergence, so idempotence is NOT expected or asserted, only that a second round stays correct" >> {
      val rng = new scala.util.Random(11)
      val pts = Array.fill(20)(Array(rng.nextDouble() * 10, rng.nextDouble() * 10))
      val space = EuclideanMetricSpace(pts)
      val homDim = 2
      val once = EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity))
      // NOT Some(Double.PositiveInfinity) here: `once` already has genuine +Infinity entries for its own
      // collapsed-away pairs, and IEEE-754's Infinity <= Infinity would re-admit every one of them into the
      // second collapse's own initial edge set, undoing the first round before the second even starts (the
      // exact hazard this file's own "two disjoint edges" fixture above documents) -- rely on the default
      // instead, which the dedicated minimumEnclosingRadius test above confirms resolves safely to `once`'s
      // own validUpTo.
      val twice = EdgeCollapse.collapse(once)
      (twice.stats.edgesAfter must beLessThanOrEqualTo(once.stats.edgesAfter)) and
        (barcode(space, homDim) must beEqualTo(barcode(twice, homDim)))
    }
  }

  // Zero-persistence (birth == death) bars are filtered out before comparing: `diagramAt` reports them literally
  // (this codebase's engines don't cancel a bar just because it happens to have zero length), but edge collapse
  // is SPECIFICALLY expected to eliminate exactly this kind of momentary flicker (a dominated edge's own
  // hollow-simplex-then-instantly-refilled contribution, see the class doc's triangle example) -- so plain VR
  // can have MORE zero-persistence entries than the collapsed complex while still agreeing on every bar that
  // represents a genuine feature. An earlier draft of this helper compared the two RAW and got a real-looking
  // "collapsed barcode disagrees with plain VR" failure that was actually this artifact, not a collapse bug --
  // found the same way `SheehyRipsStreamSpec`'s own cross-validation test warns about (a spurious "missing bars"
  // discrepancy from an unrelated truncation/degeneracy effect, not the construction under test).
  def barcode(
    space: FiniteMetricSpace[Int],
    homDim: Int,
    maxFiltrationValue: Option[Double] = Some(Double.PositiveInfinity)
  ): Set[(Int, Double, Double)] =
    val stream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(space, maxFiltrationValue = maxFiltrationValue), homDim + 1)
    SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
      .filter(t => t._1 <= homDim && t._2 != t._3)
      .toSet

  "the barcode of the edge-collapsed complex" should {
    "agrees EXACTLY with plain (uncollapsed) VR's own barcode, across random point clouds -- the real oracle for " +
      "this construction (persistence-preservation is the whole point of the papers this implements), not " +
      "agreement with any external tool" >> {
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.chooseNum(-10.0, 10.0), Gen.chooseNum(2, 3), Gen.chooseNum(6, 11))) {
          pts =>
            val space = EuclideanMetricSpace(pts)
            val homDim = 2
            val plain = barcode(space, homDim)
            val collapsed = barcode(EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity)), homDim)
            plain == collapsed
        }
      }
    }

    "agrees on a tie-heavy integer-grid point cloud (many repeated pairwise distances, unlike a generic random " +
      "cloud) -- CLAUDE.md's own established discriminator for a new stream-adjacent construction" >> {
      val pts = (for i <- 0 until 3; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)).toArray
      val space = EuclideanMetricSpace(pts)
      val homDim = 2
      barcode(space, homDim) must beEqualTo(barcode(EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity)), homDim))
    }

    "agrees under the DEFAULT (truncated, minimumEnclosingRadius) bound too, not just the untruncated comparison " +
      "above -- the realistic usage pattern, matching every other stream's own default in this codebase" >> {
      val rng = new scala.util.Random(23)
      val pts = Array.fill(12)(Array(rng.nextDouble() * 10, rng.nextDouble() * 10))
      val space = EuclideanMetricSpace(pts)
      val homDim = 2
      barcode(space, homDim, maxFiltrationValue = None) must beEqualTo(
        barcode(EdgeCollapse.collapse(space), homDim, maxFiltrationValue = None)
      )
    }

    "every representative on the collapsed complex is a genuine cycle (zero boundary) -- confirms the collapsed " +
      "stream satisfies the ordering contract well enough for the reduction algorithm itself to behave, not just " +
      "that the final bar VALUES happen to agree" >> {
      val rng = new scala.util.Random(29)
      val pts = Array.fill(16)(Array(rng.nextDouble() * 10, rng.nextDouble() * 10))
      val space = EuclideanMetricSpace(pts)
      val stream = LimitedCofaceSimplexStream(
        EnumeratingCofaceSimplexStream(EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity)), maxFiltrationValue = Some(Double.PositiveInfinity)),
        3
      )
      val bars = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(stream).barcodeAt(Double.PositiveInfinity)
      forall(bars) { bar => Chain.from(bar.annotation.get.boundary).isZero() must beTrue }
    }
  }

  "EdgeCollapse.collapse on a multi-cluster point cloud" should {
    "removes a real, non-trivial fraction of edges -- confirms the operation is not vacuously a no-op on a " +
      "realistic (not adversarially sparse) point cloud" >> {
      def cluster(cx: Double, cy: Double, seed: Int): Array[Array[Double]] =
        val rng = new scala.util.Random(seed)
        Array.fill(8)(Array(cx + (rng.nextDouble() - 0.5) * 0.5, cy + (rng.nextDouble() - 0.5) * 0.5))
      val pts = cluster(0.0, 0.0, 101) ++ cluster(20.0, 0.0, 102) ++ cluster(10.0, 20.0, 103)
      val space = EuclideanMetricSpace(pts)
      val stats = EdgeCollapse.collapse(space, maxFiltrationValue = Some(Double.PositiveInfinity)).stats
      stats.edgesRemoved must beGreaterThan(0)
    }
  }
