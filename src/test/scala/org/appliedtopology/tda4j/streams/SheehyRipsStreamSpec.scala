package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.execute.AsResult

/** `SheehyRipsSimplexStream` (Cavanna, Jahanseir & Sheehy, "A Geometric Perspective on Sparse Filtrations",
  * arXiv:1506.03797 -- the greedy-permutation reformulation of Sheehy's original net-tree construction,
  * arXiv:1203.6786). See that class's own doc for the formulas and units convention, and `.claude/
  * WORKLOG-sheehy-rips.md` for the fetched paper pages this is checked against.
  *
  * Validation order follows this codebase's established convention for a new complex type: hand-derived fixtures for
  * `edgeBirth` pinning each of the paper's own case-1/case-2/vanish-excluded/never-reachable branches (the
  * vanish-excluded one is a real correctness gap in the paper's own Algorithm 3, not just a defensive test -- see the
  * class doc), a hand-derived triangle showing this is NOT a flag complex in the naive "max pairwise edge value" sense
  * (three individually-finite edges whose own triangle is still excluded), a degenerate-epsilon-limit check that this
  * reduces to plain Vietoris-Rips cell-for-cell (not just barcode-for-barcode), and a fresh `chunks`-vs-`naive`
  * cross-validation (not assumed to carry over from any other stream).
  */
class SheehyRipsStreamSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  "SheehyRipsSimplexStream.edgeBirth" should {
    "take the unsparsified (case 1) branch and return exactly d once doubled, when both radii have room to grow" >> {
      // lambda_p = lambda_q = 100, epsilon = 0.5, d = 5: case-1 threshold 2*100*1.5/0.5 = 600 >> d.
      SheehyRipsSimplexStream.edgeBirth(100.0, 100.0, 5.0, 0.5) must beCloseTo(5.0, 1e-9)
    }

    "take the sparsified (case 2) branch when only the smaller point's radius has saturated" >> {
      // lambda_p = 1, lambda_q = 100, epsilon = 0.5: case-1 threshold 2*1*1.5/0.5 = 6 < d = 7 (case 2);
      // case-2 threshold (1+100)*1.5/0.5 = 303 >= 7. raw = 7 - 1*1.5/0.5 = 4; vanish(1, 0.5) = 1*2.25/0.5 = 4.5
      // >= raw, so this edge is NOT excluded. Doubled: 8.0.
      SheehyRipsSimplexStream.edgeBirth(1.0, 100.0, 7.0, 0.5) must beCloseTo(8.0, 1e-9)
    }

    "exclude a pair whose case-2 formula would fire only after the smaller point's ball has already vanished" >> {
      // The advisor-derived counterexample to CJS 2015's own Algorithm 3 (see the class doc): epsilon = 1,
      // lambda_p = 1, lambda_q = 10, d = 10. Case-1 threshold 2*1*2/1 = 4 < 10. Case-2 threshold
      // (1+10)*2/1 = 22 >= 10, so the raw (unclamped) formula would return 10 - 1*2/1 = 8. But p vanishes at
      // 1*(2^2)/1 = 4 < 8: for the entire time both balls exist (radius capped at 2 and 4 respectively past
      // alpha=2), their radii sum to at most 6 < 10, so they never actually intersect.
      SheehyRipsSimplexStream.edgeBirth(1.0, 10.0, 10.0, 1.0) must beEqualTo(Double.PositiveInfinity)
    }

    "return Infinity directly when even both saturated radii together can't reach d (never-reachable branch)" >> {
      // lambda_p = 1, lambda_q = 2, epsilon = 0.5: case-2 threshold (1+2)*1.5/0.5 = 9 < d = 100.
      SheehyRipsSimplexStream.edgeBirth(1.0, 2.0, 100.0, 0.5) must beEqualTo(Double.PositiveInfinity)
    }

    "be symmetric in its two lambda arguments" >>
      AsResult {
        org.scalacheck.Prop.forAll(
          Gen.chooseNum(0.01, 50.0),
          Gen.chooseNum(0.01, 50.0),
          Gen.chooseNum(0.01, 100.0),
          Gen.chooseNum(0.01, 0.99)
        ) { (l1, l2, d, eps) =>
          SheehyRipsSimplexStream.edgeBirth(l1, l2, d, eps) == SheehyRipsSimplexStream.edgeBirth(l2, l1, d, eps)
        }
      }

    "match an independent bisection against the DEFINITION (not Algorithm 3's closed form), across the case-1/" +
      "case-2/vanish-excluded boundary" >> {
        // Independent of the closed-form branches in edgeBirth itself: directly bisects for the smallest alpha in
        // [0, vanish(lo)] with d <= r(lambdaP, alpha) + r(lambdaQ, alpha), r(lambda, alpha) = min(alpha,
        // lambda*(1+eps)/eps) -- exactly CJS 2015's own ball-radius definition, not the derived closed form. This
        // is the check that would actually catch a wrong case-2 constant or threshold: the earlier "reduces to
        // plain VR" and H0-approximation tests only ever exercise case 1 in practice (random clouds rarely
        // sparsify at the sizes those tests use -- see the class doc/worklog), so neither one independently
        // exercises case 2 at all. `d` is generated across the case-1/case-2 boundary (2*lo*(1+eps)/eps) up to and
        // beyond Lemma 6's own neighbor bound (kappa*lo, kappa = (1+eps)*(2+eps)/eps -- the point past which the
        // edge must be excluded), so every branch of `edgeBirth` gets exercised, not just case 1.
        def bisectEdgeBirth(lambdaP: Double, lambdaQ: Double, d: Double, epsilon: Double): Double =
          val lo = math.min(lambdaP, lambdaQ)
          def r(lambda: Double, alpha: Double): Double = math.min(alpha, lambda * (1.0 + epsilon) / epsilon)
          val vanishLo = lo * (1.0 + epsilon) * (1.0 + epsilon) / epsilon
          def reachable(alpha: Double): Boolean = d <= r(lambdaP, alpha) + r(lambdaQ, alpha)
          if !reachable(vanishLo) then Double.PositiveInfinity
          else
            var low = 0.0
            var high = vanishLo
            for _ <- 1 to 200 do
              val mid = (low + high) / 2.0
              if reachable(mid) then high = mid else low = mid
            2.0 * high

        AsResult {
          org.scalacheck.Prop.forAll(
            Gen.chooseNum(0.5, 20.0),
            Gen.chooseNum(0.5, 20.0),
            Gen.chooseNum(0.05, 0.95)
          ) { (lambdaP, lambdaQ, eps) =>
            val lo = math.min(lambdaP, lambdaQ)
            val kappa = (1.0 + eps) * (2.0 + eps) / eps
            org.scalacheck.Prop.forAll(Gen.chooseNum(0.0, kappa * lo * 1.5)) { d =>
              val fromFormula = SheehyRipsSimplexStream.edgeBirth(lambdaP, lambdaQ, d, eps)
              val fromBisection = bisectEdgeBirth(lambdaP, lambdaQ, d, eps)
              if fromFormula.isPosInfinity || fromBisection.isPosInfinity then
                fromFormula.isPosInfinity == fromBisection.isPosInfinity
              else math.abs(fromFormula - fromBisection) < 1e-6
            }
          }
        }
      }
  }

  "SheehyRipsSimplexStream.filtrationValueOverride" should
    "exclude a triangle via the global min-vanish check even though all three of its edges are individually finite" >> {
      // a = 0 (lambda 1), b = 1 (lambda 100), c = 2 (lambda 100), epsilon = 0.5.
      // edge(a,b): d=4, case-1 (threshold 6) -> raw 2, doubled 4; vanish(1,0.5) doubled = 9 >= 4, kept.
      // edge(a,c): identical by symmetry -> 4.
      // edge(b,c): d=50, lo=hi=100, case-1 threshold 2*100*1.5/0.5=600 -> raw 25, doubled 50; vanish(100,.5)
      //   doubled = 900 >= 50, kept as an EDGE.
      // Triangle raw = max(4,4,50) = 50; vertex vanish-doubled values are (9, 900, 900), min = 9. 50 > 9, so the
      // triangle itself must be excluded even though {a,b}, {a,c}, {b,c} are each present as edges -- the
      // discriminator between "flag complex from pairwise values" (wrong here) and the real construction.
      val ambient = ExplicitMetricSpace(
        IndexedSeq(
          IndexedSeq(0.0, 4.0, 4.0),
          IndexedSeq(4.0, 0.0, 50.0),
          IndexedSeq(4.0, 50.0, 0.0)
        )
      )
      val permutation = GreedyPermutation(IndexedSeq(0, 1, 2), Map(0 -> 1.0, 1 -> 100.0, 2 -> 100.0))
      val fv = SheehyRipsSimplexStream.filtrationValueOverride(ambient, permutation, 0.5)
      (fv(Simplex(0, 1)) must beCloseTo(4.0, 1e-9)) and
        (fv(Simplex(0, 2)) must beCloseTo(4.0, 1e-9)) and
        (fv(Simplex(1, 2)) must beCloseTo(50.0, 1e-9)) and
        (fv(Simplex(0, 1, 2)) must beEqualTo(Double.PositiveInfinity))
    }

  "LandmarkSelector.maxmin run to full size (GreedyPermutation)" should {
    "give the seed point lambda = Infinity and every other lambda a positive, real value" >>
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(3, 12))) { pts =>
          val ambient = EuclideanMetricSpace(pts)
          val selection = LandmarkSelector.maxmin(ambient, ambient.size, firstLandmark = 0)
          selection.insertionRadius(selection.landmarks.head).isPosInfinity &&
          selection.landmarks.tail.forall(p =>
            selection.insertionRadius(p).isFinite && selection.insertionRadius(p) >= 0.0
          )
        }
      }

    "give a non-increasing lambda sequence along the permutation order" >>
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(3, 12))) { pts =>
          val ambient = EuclideanMetricSpace(pts)
          val selection = LandmarkSelector.maxmin(ambient, ambient.size, firstLandmark = 0)
          val lambdas = selection.landmarks.map(selection.insertionRadius)
          lambdas.sliding(2).forall { case Seq(a, b) => a >= b; case _ => true }
        }
      }
  }

  "SheehyRipsSimplexStream" should {
    "reduce to plain Vietoris-Rips cell-for-cell at a small enough epsilon (no sparsification triggers)" >>
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(4, 8))) { pts =>
          val ambient = EuclideanMetricSpace(pts)
          val selection = LandmarkSelector.maxmin(ambient, ambient.size, firstLandmark = 0)
          val lambdaMin = selection.landmarks.tail.map(selection.insertionRadius).min
          val diameter =
            ambient.minimumEnclosingRadius * 2.0 + 1.0 // a safe finite upper bound on every pairwise distance
          // Solve 2*lambdaMin*(1+eps)/eps >= diameter for a small, safely-sufficient eps (see the class doc /
          // WORKLOG for the derivation: small eps, not large, is what avoids sparsification here).
          val eps =
            if diameter <= 2.0 * lambdaMin then 0.5
            else math.min(0.5, 0.5 * lambdaMin / (diameter - 2.0 * lambdaMin))
          val permutation = GreedyPermutation(selection.landmarks, selection.insertionRadius)
          val sheehyStream =
            new SheehyRipsSimplexStream(ambient, permutation, eps, maxFiltrationValue = Some(Double.PositiveInfinity))
          val vrStream = RipserCofaceSimplexStream(ambient, maxFiltrationValue = Some(Double.PositiveInfinity))
          (0 to 2).forall { d =>
            val sheehyCells = sheehyStream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
            val vrCells = vrStream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
            sheehyCells == vrCells &&
            sheehyCells.forall(c => math.abs(sheehyStream.filtrationValue(c) - vrStream.filtrationValue(c)) < 1e-6)
          }
        }
      }

    "reject epsilon outside (0,1)" >> {
      val ambient = EuclideanMetricSpace(Array(Array(0.0), Array(1.0)))
      (SheehyRipsSimplexStream(ambient, epsilon = 0.0) must throwAn[IllegalArgumentException]) and
        (SheehyRipsSimplexStream(ambient, epsilon = 1.0) must throwAn[IllegalArgumentException])
    }

    "cross-validate chunks against naive on a small random point cloud (fresh, not assumed from any other stream)" >>
      // Both streams limited to cell-dimension <= 3 (homological degree <= 2, matching PersistenceInChunksContext's
      // own maxDim = 2 below) -- NOT "maxDim = ambient.size" (CechStreamSpec's own convention for its typically-tiny
      // clouds): bounding the dimension costs nothing this test cares about, since it's checking
      // reduction-ALGORITHM agreement, not exercising every dimension the construction can produce, and it keeps
      // the comparison cheap regardless of how many points are generated.
      //
      // Deliberately uses the DEFAULT maxFiltrationValue (None), NOT an explicit Some(Double.PositiveInfinity):
      // an earlier draft of this test passed the latter and it silently readmitted every excluded (Infinity-valued)
      // pair via IEEE-754's Infinity <= Infinity, so chunks and naive were agreeing on the full, unsparsified
      // complex, not the real one (caught by advisor, not this test itself -- see the class doc's own note on this
      // hazard, now closed at the constructor level too). This property test does NOT itself assert that
      // sparsification occurred -- whether it does is data-dependent (measured: even n=18, epsilon=0.7 random
      // clouds often don't sparsify at all, since a case-1 edge's own threshold is bounded below by a constant
      // multiple of lambda regardless of epsilon) and making that a per-sample requirement here just makes the
      // property flaky. The DETERMINISTIC fixture right below covers "does real sparsification happen and do the
      // two engines still agree on it" together, reliably.
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.chooseNum(-10.0, 10.0), Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) {
          pts =>
            val ambient = EuclideanMetricSpace(pts)
            val stream = LimitedCofaceSimplexStream(SheehyRipsSimplexStream(ambient, epsilon = 0.5), 3)
            // homDim = 2 (top homological degree compared) needs cell-dimension <= homDim+1 = 3 (matching
            // CLAUDE.md's "engines internally build one dimension higher" convention), which is exactly the cap
            // above. PersistenceInChunksContext(maxDim=2) correctly drops its own dim==3 ESSENTIAL bars internally
            // (truncation artifacts: a capped cell-dim-3 stream has no dimension-4 simplex a tetrahedron could be
            // a boundary of, so every not-otherwise-paired tetrahedron looks spuriously "essential");
            // SimplicialHomologyContext has no such awareness and reports them anyway. An earlier draft of this
            // test compared the two diagrams raw and got a real-looking "chunks is missing bars" discrepancy that
            // was actually this artifact, not a chunks bug -- filtering both to dim <= homDim before comparing is
            // what every other maxDim-aware cross-validation in this codebase already does.
            val homDim = 2
            val naiveBarcode = SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
              .filter(_._1 <= homDim)
            val chunksBarcode = PersistenceInChunksContext[Int, Double](homDim)
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
              .filter(_._1 <= homDim)
            naiveBarcode.toSet == chunksBarcode.toSet
        }
      }

    "genuinely sparsify (fewer edges than plain VR) on a multi-cluster point cloud, with chunks and naive still agreeing" >> {
      // Deterministic, not a property -- three tight clusters (5 points each, radius ~0.5) far apart (~50 apart),
      // found by direct search (see WORKLOG-sheehy-rips.md): the greedy permutation gives each cluster's early
      // point a large lambda (anchoring it) and every other cluster member a small lambda (they're close to an
      // already-chosen neighbor), so cross-cluster edges between two SMALL-lambda points get excluded outright
      // by the vanish check while the complex stays connected through the large-lambda anchors. Measured 105 -> 26
      // edges at epsilon = 0.5 -- this is the non-flaky complement to the property test above, which deliberately
      // does not require sparsification on every random sample.
      def cluster(cx: Double, cy: Double, seed: Int): Array[Array[Double]] =
        val rng = new scala.util.Random(seed)
        Array.fill(5)(Array(cx + (rng.nextDouble() - 0.5) * 0.5, cy + (rng.nextDouble() - 0.5) * 0.5))
      val pts = cluster(0.0, 0.0, 1) ++ cluster(50.0, 0.0, 2) ++ cluster(25.0, 50.0, 3)
      val ambient = EuclideanMetricSpace(pts)
      val stream = SheehyRipsSimplexStream(ambient, epsilon = 0.5)
      val vrEdgeCount =
        RipserCofaceSimplexStream(ambient, maxFiltrationValue = Some(Double.PositiveInfinity)).iterator
          .count(_.dim == 1)
      val sheehyEdgeCount = stream.iterator.count(_.dim == 1)
      val homDim = ambient.size - 1
      val naiveBarcode = SimplicialHomologyContext[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
      val chunksBarcode =
        PersistenceInChunksContext[Int, Double](homDim).persistentHomology(stream).diagramAt(Double.PositiveInfinity)

      // Also checks CJS 2015 Theorem 5's H0 claim on THIS fixture specifically (not just the random-cloud
      // property below, which rarely sparsifies at all -- see that test's own note): asserts at least one
      // sparse/VR death ratio is genuinely > 1, i.e. sparsification actually changes a reported value here, not
      // just the edge count.
      def finiteH0Deaths(s: CofaceSimplexStream[Int, Double]): Seq[Double] =
        SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(s)
          .diagramAt(Double.PositiveInfinity)
          .collect { case (0, _, d) if d.isFinite => d }
          .toSeq
          .sorted
      val sheehyDeaths = finiteH0Deaths(stream)
      val vrDeaths =
        finiteH0Deaths(RipserCofaceSimplexStream(ambient, maxFiltrationValue = Some(Double.PositiveInfinity)))

      (sheehyEdgeCount must be_<(vrEdgeCount)) and
        (naiveBarcode.toSet must beEqualTo(chunksBarcode.toSet)) and
        (sheehyDeaths.zip(vrDeaths).exists { case (s, v) => s > v } must beTrue)
    }
  }

  "SheehyRipsSimplexStream's H0 barcode" should
    "is a (1+epsilon)-multiplicative approximation to plain VR's own H0 barcode (CJS 2015 Theorem 5)" >>
    // This test's real, narrower value: it does not reuse edgeBirth/filtrationValueOverride's own CLOSED FORM,
    // only the PUBLIC Theorem 5 claim, so it independently exercises the units convention (doubling cancels
    // out of every ratio) and gross over/under-exclusion. It does NOT independently exercise case 2's own
    // constant or threshold: at these sizes/epsilon values, random clouds usually don't sparsify at all (every
    // edge lands in case 1, ratio exactly 1 -- confirmed empirically while writing this test), so this
    // property mostly compares plain VR against itself; the bisection-based `edgeBirth` test above is what
    // actually exercises case 2 independently, and the cluster fixture above is what independently confirms
    // real sparsification changes a reported value at all.
    //
    // The bound is tightened to [1, 1+epsilon], not CJS 2015's own symmetric [1/(1+epsilon), 1+epsilon]: every
    // sparse edge's value is either exactly its VR counterpart (case 1) or strictly larger (case 2 only fires
    // once d exceeds case 1's own threshold, and its own value is provably >= d there too -- see the class
    // doc), and excluded pairs are effectively "even larger" (infinite) -- so a sparse death can never be
    // EARLIER than its VR counterpart, only the same or later. A ratio below 1 would mean sparsification
    // somehow merged two components before plain VR itself would have -- a real bug the symmetric bound alone
    // would not catch.
    AsResult {
      org.scalacheck.Prop.forAll(
        matrixGen(Gen.chooseNum(-10.0, 10.0), Gen.chooseNum(2, 3), Gen.chooseNum(15, 25)),
        Gen.chooseNum(0.8, 0.9)
      ) { (pts, eps) =>
        val ambient = EuclideanMetricSpace(pts)
        val sheehyH0 = LimitedCofaceSimplexStream(SheehyRipsSimplexStream(ambient, epsilon = eps), 1)
        val vrH0 = LimitedCofaceSimplexStream(RipserCofaceSimplexStream(ambient), 1)
        def finiteDeaths(s: CofaceSimplexStream[Int, Double]): Seq[Double] =
          SimplicialHomologyContext[Int, Double, Double]()
            .persistentHomology(s)
            .diagramAt(Double.PositiveInfinity)
            .collect { case (0, _, d) if d.isFinite => d }
            .toSeq
            .sorted
        val sheehyDeaths = finiteDeaths(sheehyH0)
        val vrDeaths = finiteDeaths(vrH0)
        sheehyDeaths.size == vrDeaths.size &&
        sheehyDeaths.zip(vrDeaths).forall { case (s, v) =>
          // v == 0.0 happens whenever two generated points coincide exactly (a real, if rare, occurrence
          // under bounded-coordinate generation, not a pathological input to guard against) -- plain VR merges
          // them at birth (death 0.0), and SheehyRipsSimplexStream's own edgeBirth agrees exactly (case 1 with
          // d=0 gives raw 0, and vanish(lambda,epsilon) is 0 only when lambda=0 too, in which case raw=0 <=
          // vanish=0 still holds) -- so s == 0.0 too, and s/v is 0.0/0.0 = NaN despite the two deaths being
          // genuinely, exactly equal. Comparing for exact equality first sidesteps the division entirely.
          (s == v) || {
            val ratio = s / v
            ratio >= 1.0 - 1e-9 && ratio <= (1.0 + eps) + 1e-9
          }
        }
      }
    }
