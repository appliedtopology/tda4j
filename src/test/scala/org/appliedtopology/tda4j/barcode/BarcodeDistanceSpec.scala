package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.BarcodeDistance.GroundNorm
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

class BarcodeDistanceSpec extends Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-25)

  private def bar(dim: Int, birth: Double, death: Double): PersistenceBar[Double, Nothing] =
    PersistenceBar[Double, Nothing](dim, ClosedEndpoint(birth), OpenEndpoint(death))
  private def essentialBar(dim: Int, birth: Double): PersistenceBar[Double, Nothing] =
    PersistenceBar[Double, Nothing](dim, ClosedEndpoint(birth), PositiveInfinity())

  "hand-computed examples (ground norm L-infinity)" >> {
    "a single finite bar against an empty diagram costs exactly half its persistence" >> {
      val diagram1 = List(bar(0, 0.0, 5.0))
      BarcodeDistance.bottleneckDistance(diagram1, List.empty[PersistenceBar[Double, Nothing]]) must beCloseTo(
        2.5,
        1e-9
      )
      BarcodeDistance.wassersteinDistance(diagram1, List.empty[PersistenceBar[Double, Nothing]]) must beCloseTo(
        2.5,
        1e-9
      )
    }

    "matching two close bars directly beats sending both to the diagonal" >> {
      // point-to-point cost is max(|0-1|,|5-4|) = 1, versus 2.5 + 1.5 = 4 for the two diagonal costs.
      val diagram1 = List(bar(0, 0.0, 5.0))
      val diagram2 = List(bar(0, 1.0, 4.0))
      BarcodeDistance.bottleneckDistance(diagram1, diagram2) must beCloseTo(1.0, 1e-9)
      BarcodeDistance.wassersteinDistance(diagram1, diagram2, order = 1.0) must beCloseTo(1.0, 1e-9)
    }

    "two identical diagrams are at distance 0" >> {
      val diagram = List(bar(0, 0.0, 5.0), bar(0, 1.0, 3.0))
      BarcodeDistance.bottleneckDistance(diagram, diagram) must beCloseTo(0.0, 1e-9)
      BarcodeDistance.wassersteinDistance(diagram, diagram) must beCloseTo(0.0, 1e-9)
    }

    "two empty diagrams are at distance 0" >> {
      val empty = List.empty[PersistenceBar[Double, Nothing]]
      BarcodeDistance.bottleneckDistance(empty, empty) must beEqualTo(0.0)
      BarcodeDistance.wassersteinDistance(empty, empty) must beEqualTo(0.0)
    }
  }

  "ground norm convention (diagonal distance formula, cross-checked by hand)" >> {
    "L-infinity: a bar of persistence pi costs pi/2 to the diagonal" >> {
      val diagram1 = List(bar(0, 0.0, 10.0))
      val empty = List.empty[PersistenceBar[Double, Nothing]]
      BarcodeDistance.bottleneckDistance(diagram1, empty, GroundNorm.LInfinity) must beCloseTo(5.0, 1e-9)
    }
    "L-p: a bar of persistence pi costs pi * 2^(1/p - 1) to the diagonal (independently re-derived formula)" >> {
      val pi = 10.0
      val diagram1 = List(bar(0, 0.0, pi))
      val empty = List.empty[PersistenceBar[Double, Nothing]]
      for p <- Seq(1.0, 1.5, 2.0, 4.0) do
        val expected = pi * math.pow(2.0, 1.0 / p - 1.0)
        BarcodeDistance.bottleneckDistance(diagram1, empty, GroundNorm.LP(p)) must beCloseTo(expected, 1e-9)
      ok
    }
    "L-p converges to L-infinity as p grows" >> {
      val diagram1 = List(bar(0, 0.0, 5.0), bar(0, 1.0, 4.0))
      val diagram2 = List(bar(0, 0.5, 6.0))
      val atInfinity = BarcodeDistance.bottleneckDistance(diagram1, diagram2, GroundNorm.LInfinity)
      val atLargeP = BarcodeDistance.bottleneckDistance(diagram1, diagram2, GroundNorm.LP(500.0))
      atLargeP must beCloseTo(atInfinity, 0.01)
    }
  }

  "essential (never-dying) bars" >> {
    "mismatched essential-bar counts force distance to +Infinity" >> {
      val diagram1 = List(essentialBar(1, 0.0))
      val diagram2 = List.empty[PersistenceBar[Double, Nothing]]
      BarcodeDistance.bottleneckDistance(diagram1, diagram2) must beEqualTo(Double.PositiveInfinity)
      BarcodeDistance.wassersteinDistance(diagram1, diagram2) must beEqualTo(Double.PositiveInfinity)
    }

    "matched essential bars are compared by birth value alone, ignoring finite bars entirely" >> {
      val diagram1 = List(essentialBar(1, 2.0), bar(1, 0.0, 1.0))
      val diagram2 = List(essentialBar(1, 5.0), bar(1, 0.0, 1.0))
      // essential cost = |2-5| = 3, finite parts are identical (cost 0) -> bottleneck = wasserstein = 3
      BarcodeDistance.bottleneckDistance(diagram1, diagram2) must beCloseTo(3.0, 1e-9)
      BarcodeDistance.wassersteinDistance(diagram1, diagram2) must beCloseTo(3.0, 1e-9)
    }

    "several essential bars are paired by sorted birth order (provably cost-minimal on the line)" >> {
      val diagram1 = List(essentialBar(0, 0.0), essentialBar(0, 10.0))
      val diagram2 = List(essentialBar(0, 1.0), essentialBar(0, 9.0))
      // sorted pairing: (0,1) and (9,10) -> costs 1 and 1 -> bottleneck max = 1, wasserstein sum = 2
      // the crossed pairing (0,9),(1,10) would cost max 9 -- much worse, confirming sorting matters.
      BarcodeDistance.bottleneckDistance(diagram1, diagram2) must beCloseTo(1.0, 1e-9)
      BarcodeDistance.wassersteinDistance(diagram1, diagram2, order = 1.0) must beCloseTo(2.0, 1e-9)
    }
  }

  "metric-like properties" >> {
    "symmetric under swapping the two diagrams" >>
      forAll(diagramGen, diagramGen) { (d1, d2) =>
        val bAB = BarcodeDistance.bottleneckDistance(d1, d2)
        val bBA = BarcodeDistance.bottleneckDistance(d2, d1)
        val wAB = BarcodeDistance.wassersteinDistance(d1, d2)
        val wBA = BarcodeDistance.wassersteinDistance(d2, d1)
        (bAB must beCloseTo(bBA, 1e-9)) and (wAB must beCloseTo(wBA, 1e-9))
      }

    "bottleneck never exceeds Wasserstein, at any order (order -> Infinity is the max aggregation)" >>
      forAll(diagramGen, diagramGen, Gen.oneOf(1.0, 1.5, 2.0, 3.0)) { (d1, d2, order) =>
        val bottleneck = BarcodeDistance.bottleneckDistance(d1, d2)
        val wasserstein = BarcodeDistance.wassersteinDistance(d1, d2, order)
        bottleneck must beLessThanOrEqualTo(wasserstein + 1e-9)
      }

    "a diagram is at distance 0 from itself" >>
      forAll(diagramGen) { d =>
        (BarcodeDistance.bottleneckDistance(d, d) must beCloseTo(0.0, 1e-9)) and
          (BarcodeDistance.wassersteinDistance(d, d) must beCloseTo(0.0, 1e-9))
      }
  }

  "brute-force cross-check on small finite diagrams" >> {
    // Independent oracle: re-derives the same "augmented, diagonal-padded" matching from scratch and
    // solves it by literal permutation search, rather than calling into Hungarian/HopcroftKarp.
    def bruteForceCost(
      left: Seq[(Double, Double)],
      right: Seq[(Double, Double)],
      order: Double,
      groundNorm: GroundNorm
    ): Double =
      def diagonalDistance(pi: Double): Double = groundNorm match
        case GroundNorm.LInfinity => pi / 2.0
        case GroundNorm.LP(p)     => pi * math.pow(2.0, 1.0 / p - 1.0)
      def pointDistance(x: (Double, Double), y: (Double, Double)): Double =
        val db = math.abs(x._1 - y._1)
        val dd = math.abs(x._2 - y._2)
        groundNorm match
          case GroundNorm.LInfinity => math.max(db, dd)
          case GroundNorm.LP(p)     => math.pow(math.pow(db, p) + math.pow(dd, p), 1.0 / p)
      val nL = left.size
      val nR = right.size
      val n = nL + nR
      if n == 0 then 0.0
      else
        val matrix = Array.tabulate(n, n) { (i, j) =>
          if i < nL && j < nR then pointDistance(left(i), right(j))
          else if i < nL && j >= nR then diagonalDistance(left(i)._2 - left(i)._1)
          else if i >= nL && j < nR then diagonalDistance(right(j)._2 - right(j)._1)
          else 0.0
        }
        val best = (0 until n).permutations
          .map(perm =>
            if order.isPosInfinity then perm.zipWithIndex.map((r, c) => matrix(r)(c)).max
            else perm.zipWithIndex.map((r, c) => math.pow(matrix(r)(c), order)).sum
          )
          .min
        if order.isPosInfinity then best else math.pow(best, 1.0 / order)

    val smallFinitePointGen: Gen[(Double, Double)] =
      for
        b <- Gen.choose(0.0, 5.0)
        p <- Gen.choose(0.01, 5.0)
      yield (b, b + p)
    val smallFiniteDiagramGen: Gen[Seq[(Double, Double)]] =
      Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, smallFinitePointGen))

    "bottleneck matches brute-force min-max matching" >>
      forAll(smallFiniteDiagramGen, smallFiniteDiagramGen, Gen.oneOf(GroundNorm.LInfinity, GroundNorm.LP(2.0))) {
        (pts1, pts2, groundNorm) =>
          val diagram1 = pts1.map((b, d) => bar(0, b, d))
          val diagram2 = pts2.map((b, d) => bar(0, b, d))
          val expected = bruteForceCost(pts1, pts2, Double.PositiveInfinity, groundNorm)
          BarcodeDistance.bottleneckDistance(diagram1, diagram2, groundNorm) must beCloseTo(expected, 1e-6)
      }

    "wasserstein matches brute-force min-sum-of-powers matching" >>
      forAll(
        smallFiniteDiagramGen,
        smallFiniteDiagramGen,
        Gen.oneOf(1.0, 2.0),
        Gen.oneOf(GroundNorm.LInfinity, GroundNorm.LP(2.0))
      ) { (pts1, pts2, order, groundNorm) =>
        val diagram1 = pts1.map((b, d) => bar(0, b, d))
        val diagram2 = pts2.map((b, d) => bar(0, b, d))
        val expected = bruteForceCost(pts1, pts2, order, groundNorm)
        BarcodeDistance.wassersteinDistance(diagram1, diagram2, order, groundNorm) must beCloseTo(expected, 1e-6)
      }
  }

  "bottleneck on larger diagrams (the brute force above stops at 3 bars)" >> {
    // The dense formulation: the (nL + nR)^2 augmented cost matrix, a binary search over its distinct entries, and a
    // perfect-matching test at each threshold.
    def dense(pts1: Seq[(Double, Double)], pts2: Seq[(Double, Double)], groundNorm: GroundNorm): Double =
      def diag(p: (Double, Double)) = groundNorm match
        case GroundNorm.LInfinity => (p._2 - p._1) / 2
        case GroundNorm.LP(q)     => (p._2 - p._1) * math.pow(2.0, 1.0 / q - 1.0)
      def dist(a: (Double, Double), b: (Double, Double)) =
        val (db, dd) = (math.abs(a._1 - b._1), math.abs(a._2 - b._2))
        groundNorm match
          case GroundNorm.LInfinity => math.max(db, dd)
          case GroundNorm.LP(q)     => math.pow(math.pow(db, q) + math.pow(dd, q), 1.0 / q)
      val (nL, nR) = (pts1.size, pts2.size)
      val n = nL + nR
      val m = Array.tabulate(n, n) { (i, j) =>
        if i < nL && j < nR then dist(pts1(i), pts2(j))
        else if i < nL then diag(pts1(i))
        else if j < nR then diag(pts2(j))
        else 0.0
      }
      val candidates = m.flatten.distinct.sorted
      def feasible(t: Double) =
        HopcroftKarp.maximumMatching(n, n, i => (0 until n).filter(m(i)(_) <= t)).count(_ != -1) == n
      if n == 0 then 0.0 else candidates.find(feasible).get

    val pointGen: Gen[(Double, Double)] =
      for
        b <- Gen.choose(0, 40).map(_ / 8.0) // a grid, so ties between distances occur
        p <- Gen.choose(0, 30).map(_ / 8.0)
      yield (b, b + p)
    val diagramGen: Gen[Seq[(Double, Double)]] = Gen.choose(0, 40).flatMap(n => Gen.listOfN(n, pointGen))

    "agree with the dense formulation, ties and zero-persistence bars included" >>
      forAll(diagramGen, diagramGen, Gen.oneOf(GroundNorm.LInfinity, GroundNorm.LP(2.0), GroundNorm.LP(3.0))) {
        (pts1, pts2, groundNorm) =>
          val diagram1 = pts1.map((b, d) => bar(0, b, d))
          val diagram2 = pts2.map((b, d) => bar(0, b, d))
          BarcodeDistance.bottleneckDistance(diagram1, diagram2, groundNorm) must beCloseTo(
            dense(pts1, pts2, groundNorm),
            1e-9
          )
      }.set(minTestsOk = TestPlatform.propertyCases(200))
  }

  "input validation at the diagram-comparison boundary" >> {
    "rejects a diagram mixing several dimensions (likely an unfiltered barcode passed by mistake)" >> {
      val mixed = List(bar(0, 0.0, 1.0), bar(1, 0.0, 1.0))
      BarcodeDistance.bottleneckDistance(mixed, List.empty[PersistenceBar[Double, Nothing]]) must throwA[
        IllegalArgumentException
      ]
    }

    "rejects a bar with a -Infinity birth value rather than silently producing NaN" >> {
      val badBar = PersistenceBar[Double, Nothing](0, NegativeInfinity(), ClosedEndpoint(1.0))
      BarcodeDistance.bottleneckDistance(List(badBar), List.empty[PersistenceBar[Double, Nothing]]) must throwA[
        IllegalArgumentException
      ]
    }
  }

  "bottleneckDistanceByDimension / wassersteinDistanceByDimension" >> {
    "computes one distance per dimension present in either diagram, treating a missing side as empty" >> {
      val diagram1 = List(bar(0, 0.0, 1.0), bar(1, 0.0, 2.0))
      val diagram2 = List(bar(0, 0.0, 1.0)) // no dimension-1 bars at all
      val byDim = BarcodeDistance.bottleneckDistanceByDimension(diagram1, diagram2)
      byDim(0) must beCloseTo(0.0, 1e-9) // identical dimension-0 bars
      byDim(1) must beCloseTo(1.0, 1e-9) // dimension-1 bar of persistence 2 vs. nothing -> 2/2 = 1
    }
  }

  "stability: perturbing points by at most epsilon changes VR bottleneck distance by at most 2*epsilon" >> {
    given shc: SimplicialHomologyEngine[Int, Double, Double] = SimplicialHomologyEngine()
    import shc.{*, given}

    // A fixed, modest point cloud (kept small so naive-engine VR construction stays fast under ScalaCheck).
    val basePoints = Array(
      Array(0.0, 0.0),
      Array(1.0, 0.0),
      Array(0.5, 0.9),
      Array(2.0, 0.2),
      Array(1.3, 1.4),
      Array(0.1, 1.6)
    )
    val n = basePoints.length
    val dim = basePoints(0).length

    // maxDim=1 needs the stream truncated one cell-dimension higher (CLAUDE.md's "engines internally build
    // one dimension higher"); the resulting dim==2 bars are an artifact of having no dim-3 cells to pair
    // against (never genuinely essential) and must be dropped, not compared -- CLAUDE.md's own "callers
    // truncate the stream ... and drop dim == k + 1 bars" rule, confirmed the hard way: leaving them in
    // produced spurious dim-2 "essential" bars whose count differs between the base and perturbed clouds,
    // which BarcodeDistance correctly (if confusingly, until traced back here) reports as +Infinity.
    //
    // maxFiltrationValue = Some(Double.PositiveInfinity), NOT the default enclosing-radius truncation: the
    // classical stability theorem (Cohen-Steiner-Edelsbrunner-Harer 2007) bounds d_B by 2*epsilon for a FIXED
    // filtration function perturbed pointwise by epsilon -- but the enclosing radius itself is a function of
    // the point cloud, so it shifts by up to 2*epsilon too between the base and perturbed clouds, and comparing
    // two diagrams truncated at two DIFFERENT radii is a different (still bounded, but not by this clean
    // constant) comparison than the theorem's own precondition. Confirmed the hard way: with the default
    // truncation, ScalaCheck's own shrinking found an epsilon of ~1.3e-5 where the measured distance exceeded
    // 2*epsilon by ~9e-7 -- not floating-point noise (too large relative to the O(1)-magnitude coordinates
    // here), but exactly this truncation-boundary interaction. Untruncated removes the confound and tests the
    // theorem's actual precondition instead of a superficially similar but different claim.
    def barcodeOf(points: Array[Array[Double]]): List[PersistenceBar[Double, Chain[Simplex[Int], Double]]] =
      val vrStream = LimitedCofaceSimplexStream(
        EnumeratingCofaceSimplexStream(
          EuclideanMetricSpace(points),
          maxFiltrationValue = Some(Double.PositiveInfinity)
        ),
        2
      )
      persistentHomology(vrStream).barcodeAt(Double.PositiveInfinity).filter(_.dim <= 1)

    val baseBars = barcodeOf(basePoints)

    // The theorem bounds each POINT's own Euclidean (L2) displacement by epsilon, not each COORDINATE
    // independently by epsilon (an L-infinity box around a point can reach ~epsilon*sqrt(dim) in Euclidean
    // distance) -- confirmed the hard way: an early version of this test generated per-coordinate deltas
    // directly and failed intermittently by a small but real margin (a ~5% excess over 2*epsilon on one
    // ScalaCheck-shrunk case), traced via a direct brute-force cross-check on the actual VR-derived diagrams
    // (matching BarcodeDistance's own answer exactly, ruling out a matching bug) to exactly this L-infinity-
    // vs-L2 mismatch: that case's worst-displaced point moved ~1.24x further in Euclidean norm than the nominal
    // per-coordinate epsilon alone would suggest. Clipping each point's own raw delta vector to Euclidean norm
    // <= epsilon (rescaling only when it would otherwise exceed epsilon) tests the theorem's actual
    // precondition instead of a superficially similar but looser one.
    def clipToEuclideanBall(raw: IndexedSeq[Double], epsilon: Double): IndexedSeq[Double] =
      val norm = math.sqrt(raw.map(v => v * v).sum)
      if norm <= epsilon then raw else raw.map(_ * (epsilon / norm))

    forAll(Gen.choose(0.001, 0.1)) { epsilon =>
      val deltasGen = Gen.listOfN(n, Gen.listOfN(dim, Gen.choose(-epsilon, epsilon)))
      forAll(deltasGen) { rawPerPoint =>
        val clippedPerPoint = rawPerPoint.map(v => clipToEuclideanBall(v.toIndexedSeq, epsilon))
        val perturbed = Array.tabulate(n, dim)((i, j) => basePoints(i)(j) + clippedPerPoint(i)(j))
        val perturbedBars = barcodeOf(perturbed)
        val byDim = BarcodeDistance.bottleneckDistanceByDimension(baseBars, perturbedBars)
        forall(byDim.values) { d =>
          d must beLessThanOrEqualTo(2.0 * epsilon + 1e-9)
        }
      }
    }
  }

  "matchings" >> {
    type Bars = List[PersistenceBar[Double, Nothing]]
    val noBars: Bars = Nil

    // The cost a matching realizes under the L-infinity ground norm: pairs by max(|db|, |dd|), the rest to the diagonal.
    def costs(d1: Bars, d2: Bars, m: BarcodeDistance.Matching): Seq[Double] =
      def pt(b: PersistenceBar[Double, Nothing]) = DiagramPoint.of(b)
      m.pairs.map((i, j) =>
        math.max(math.abs(pt(d1(i)).birth - pt(d2(j)).birth), math.abs(pt(d1(i)).death - pt(d2(j)).death))
      ) ++
        m.leftToDiagonal.map(i => pt(d1(i)).persistence / 2.0) ++ m.rightToDiagonal.map(j =>
          pt(d2(j)).persistence / 2.0
        )
    def usesEveryBarOnce(d1: Bars, d2: Bars, m: BarcodeDistance.Matching): Boolean =
      (m.pairs.map(_._1) ++ m.leftToDiagonal).sorted == d1.indices.toList &&
        (m.pairs.map(_._2) ++ m.rightToDiagonal).sorted == d2.indices.toList

    "a close pair is matched directly, a lone bar goes to the diagonal" >> {
      val near = BarcodeDistance.wassersteinMatching(List(bar(0, 0.0, 5.0)), List(bar(0, 1.0, 4.0))).get
      near.pairs must beEqualTo(Vector((0, 0)))
      near.cost must beCloseTo(1.0, 1e-9)
      val alone = BarcodeDistance.bottleneckMatching(List(bar(0, 0.0, 5.0)), noBars).get
      alone.pairs must beEmpty
      alone.leftToDiagonal must beEqualTo(Vector(0))
      alone.cost must beCloseTo(2.5, 1e-9)
    }

    "matching the far apart bars across would cost more than the diagonal: the nearer partner wins" >> {
      // (0,10) and (0,1) against (0,9.5): the long bars pair, the short one goes to the diagonal.
      val m = BarcodeDistance.wassersteinMatching(List(bar(0, 0.0, 10.0), bar(0, 0.0, 1.0)), List(bar(0, 0.0, 9.5))).get
      m.pairs must beEqualTo(Vector((0, 0)))
      m.leftToDiagonal must beEqualTo(Vector(1))
      m.cost must beCloseTo(0.5 + 0.5, 1e-9)
    }

    "essential bars are matched in order of birth, and unequal numbers have no matching" >> {
      val d1 = List(essentialBar(0, 3.0), essentialBar(0, 1.0))
      val d2 = List(essentialBar(0, 0.0), essentialBar(0, 2.5))
      val m = BarcodeDistance.wassersteinMatching(d1, d2).get
      m.pairs.toSet must beEqualTo(Set((1, 0), (0, 1)))
      m.cost must beCloseTo(1.5, 1e-9)
      BarcodeDistance.wassersteinMatching(d1, List(essentialBar(0, 0.0))) must beNone
      BarcodeDistance.bottleneckMatching(d1, List(essentialBar(0, 0.0))) must beNone
    }

    "the Wasserstein matching realizes the distance, using every bar once" >> {
      val prop = forAll(diagramGen, diagramGen) { (d1: Bars, d2: Bars) =>
        val m = BarcodeDistance.wassersteinMatching(d1, d2, order = 1.0).get
        usesEveryBarOnce(d1, d2, m) &&
        math.abs(costs(d1, d2, m).sum - BarcodeDistance.wassersteinDistance(d1, d2)) < 1e-9 &&
        math.abs(m.cost - BarcodeDistance.wassersteinDistance(d1, d2)) < 1e-9
      }
      prop
    }

    "the bottleneck matching realizes the distance, using every bar once" >> {
      val prop = forAll(diagramGen, diagramGen) { (d1: Bars, d2: Bars) =>
        val m = BarcodeDistance.bottleneckMatching(d1, d2).get
        val realized = costs(d1, d2, m).maxOption.getOrElse(0.0)
        usesEveryBarOnce(d1, d2, m) &&
        math.abs(realized - BarcodeDistance.bottleneckDistance(d1, d2)) < 1e-9 &&
        math.abs(m.cost - BarcodeDistance.bottleneckDistance(d1, d2)) < 1e-9
      }
      prop
    }
  }

  "slicedWassersteinDistance" >> {
    type Bars = List[PersistenceBar[Double, Nothing]]

    "a diagram against itself, and against its copy with essential bars added, is 0" >> {
      val d = List(bar(0, 0.0, 5.0), bar(0, 1.0, 3.0))
      BarcodeDistance.slicedWassersteinDistance(d, d) must beCloseTo(0.0, 1e-12)
      BarcodeDistance.slicedWassersteinDistance(d, d :+ essentialBar(0, 0.0)) must beCloseTo(0.0, 1e-12)
    }

    "one bar against nothing tends to persistence * sqrt(2) / pi" >> {
      val d = List(bar(0, 0.0, 2.0))
      BarcodeDistance.slicedWassersteinDistance(d, List.empty[PersistenceBar[Double, Nothing]], directions = 1000) must
        beCloseTo(2.0 * math.sqrt(2.0) / math.Pi, 1e-5)
    }

    "is symmetric and at most sqrt(2) times the 1-Wasserstein distance" >> {
      val prop = forAll(diagramGen, diagramGen) { (d1: Bars, d2: Bars) =>
        val sw = BarcodeDistance.slicedWassersteinDistance(d1, d2, directions = 60)
        math.abs(sw - BarcodeDistance.slicedWassersteinDistance(d2, d1, directions = 60)) < 1e-9 &&
        sw <= math.sqrt(2.0) * BarcodeDistance.wassersteinDistance(d1, d2) + 1e-9
      }
      prop
    }

    "rejects zero directions" >> {
      BarcodeDistance.slicedWassersteinDistance(Nil, Nil, directions = 0) must throwA[IllegalArgumentException]
    }
  }

  private def diagramGen: Gen[List[PersistenceBar[Double, Nothing]]] =
    val pointGen =
      for
        b <- Gen.choose(0.0, 5.0)
        p <- Gen.choose(0.0, 5.0)
      yield bar(0, b, b + p)
    Gen.choose(0, 4).flatMap(n => Gen.listOfN(n, pointGen))
