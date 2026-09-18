package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

class RipserCohomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given Parameters = Parameters(minTestsOk = 200)

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def toTuple(bar: PersistenceBar[Double, Chain[Simplex[Int], Double]]): (Int, Double, Double) =
    (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))

  // Explicit +Infinity: EnumeratingCofaceSimplexStream now also defaults to metricSpace.minimumEnclosingRadius
  // (see CLAUDE.md/WORKLOG-mst-and-perf.md), and every caller of naiveBars in this file pairs it with
  // cohomologyBarsUnthresholded -- both sides need to be the same, genuinely untruncated filtration.
  //
  // Builds to maxDim + 1 (LimitedCofaceSimplexStream caps SIMPLEX dimension, unlike RipserCohomologyContext's
  // now-fixed maxDimension, which caps reported HOMOLOGICAL degree -- see .claude/WORKLOG-maxdim-semantics-fix.md)
  // and drops the resulting dim == maxDim + 1 bars (spuriously essential by construction, same truncation
  // artifact RipserCohomologyContext itself used to have) before comparing. Needed once RipserCohomologyContext
  // was fixed to correctly resolve dim == maxDim: without this, naiveBars alone still has the old artifact at
  // maxDim, and disagrees with the NOW-correct cohomologyBars at that exact dimension for reasons that have
  // nothing to do with a reduction bug.
  private def naiveBars(metricSpace: FiniteMetricSpace[Int], maxDim: Int): List[(Int, Double, Double)] =
    val vrStream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Double.PositiveInfinity),
      maxDim + 1
    )
    SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(vrStream)
      .diagramAt(Double.PositiveInfinity)
      .filter(_._1 <= maxDim)

  private def cohomologyBars(metricSpace: FiniteMetricSpace[Int], maxDim: Int): List[(Int, Double, Double)] =
    RipserCohomologyContext[Double](metricSpace, maxDim).persistentCohomology().map(toTuple)

  // `naiveBars` builds an EnumeratingCofaceSimplexStream, which has no threshold parameter at all (still
  // unbounded regardless of RipserCohomologyContext's own default) -- so any test comparing the two needs
  // BOTH sides untruncated, or it's comparing two different filtrations rather than cross-validating one
  // reduction algorithm against another. Used wherever the naive-engine comparison is the point, not the
  // minimumEnclosingRadius default itself (that default has its own dedicated check further down).
  private def cohomologyBarsUnthresholded(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int
  ): List[(Int, Double, Double)] =
    RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = Double.PositiveInfinity)
      .persistentCohomology()
      .map(toTuple)

  private def totalSimplices(n: Int, maxDim: Int): Long =
    (0 to maxDim).map(d => binomial(n, d + 1)).sum

  // Hand-verified calibration example (see WORKLOG-cohomology.md's "clearing is required for
  // correctness" section for the full derivation): 3 colinear points at 0, 1, 3, all pairwise distances
  // distinct (1, 2, 3), so H_0's finite deaths are unambiguous MST edges, not tie-broken guesses. This
  // is the exact counterexample that caught a first draft of this engine (without clearing) reporting
  // spurious essential H^1 classes.
  private val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))

  "Persistent cohomology at requested H^1, 3-point line: the cycle dies at the same value its filling triangle appears" >> {
    // Pre-maxDim-semantics-fix, this test requested maxDimension=1 expecting NO triangle to ever be built
    // (the old, buggy "maxDimension = top simplex dimension" semantics) and asserted the resulting 1-cycle
    // stayed essential. That premise is no longer achievable for THIS fixture: maxDimension now means "top
    // HOMOLOGICAL DEGREE reported" (see .claude/WORKLOG-maxdim-semantics-fix.md), so correctly resolving H^1
    // REQUIRES considering the real dimension-2 coboundary regardless of what's requested -- and for exactly
    // 3 points, the triangle {0,1,2} unavoidably exists (and is born at the same value, 3.0, as its own
    // longest edge {0,2}) the instant all three edges do. There is no threshold or maxDimension choice that
    // gives this specific 3-point fixture edges but not the triangle. This is the mathematically CORRECT
    // answer, not a truncation artifact: the "hole" the 3-cycle would otherwise trace is filled in the same
    // instant it closes, a genuine zero-persistence bar -- exactly what real `ripser --dim 1` would also
    // report on this same point cloud, since Ripser always builds one dimension higher internally too. The
    // "filled triangle" test right below covers the same zero-persistence pairing at maxDimension=2; this
    // test's remaining value is confirming maxDimension=1 produces the IDENTICAL answer (not merely "some
    // essential-looking placeholder"), i.e. that requesting a lower degree changes only what's REPORTED, not
    // what's correctly computed underneath it.
    val bars =
      RipserCohomologyContext[Double](threePointLine, 1, maxFiltrationValue = Double.PositiveInfinity)
        .persistentCohomology()
        .map(toTuple)
    bars must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, Double.PositiveInfinity),
        (1, 3.0, 3.0)
      )
    )
  }

  "Persistent cohomology of the filled triangle has zero essential H^1 classes (contractible)" >> {
    // maxDimension = 2: the triangle now exists, born at 3.0 (its longest edge), tied with edge {0,2}'s
    // own filtration value -- a genuine zero-persistence pair (also the apparent-pairs shortcut's own
    // canonical example, see `zeroApparentCofacet`'s doc), which must still be EMITTED, not dropped. A
    // first draft that skipped clearing reported 2 spurious essential H^1 classes instead of 0.
    // maxFiltrationValue = +Infinity, explicitly: {0,2} and the triangle are both born at 3.0, past
    // threePointLine's own minimumEnclosingRadius (2.0) -- the default would exclude both entirely rather
    // than emit the zero-length pair this test exists to check.
    val bars =
      RipserCohomologyContext[Double](threePointLine, 2, maxFiltrationValue = Double.PositiveInfinity)
        .persistentCohomology()
        .map(toTuple)
    bars must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, Double.PositiveInfinity),
        (1, 3.0, 3.0) // zero-length: {0,2} paired with the triangle, both born at 3.0
      )
    )
  }

  "Cohomology's finite bars agree with the naive homology engine on the calibration example" >> {
    val maxDim = 2
    val naive = naiveBars(threePointLine, maxDim).filter { case (_, b, d) => b < d }
    val cohomology = cohomologyBarsUnthresholded(threePointLine, maxDim).filter { case (_, b, d) => b < d }
    cohomology must containTheSameElementsAs(naive)
  }

  "Cohomology's finite bars agree with the naive homology engine on random Vietoris-Rips point clouds" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val naive = naiveBars(metricSpace, maxDim).filter { case (_, b, d) => b < d }
      val cohomology = cohomologyBarsUnthresholded(metricSpace, maxDim).filter { case (_, b, d) => b < d }
      cohomology must containTheSameElementsAs(naive)
    }

  // Pinned adversarial example (see WORKLOG-cohomology.md's "Apparent pairs: resolved" section for the
  // full derivation), found by the property above rather than by hand: edge {2,7} and triangle {2,7,11}
  // are a genuine, mutual apparent pair (both born at 0.47710577497688794), but edge {2,7}'s coboundary
  // ALSO contains triangle {2,6,7}, tied at the same value -- a different, non-apparent-paired cofacet.
  // A first draft of the apparent-pairs shortcut stored only the single (tau, sign) term in `basis(tau)`
  // instead of sigma's full coboundary, silently dropping the {2,6,7} term. That broke a completely
  // unrelated edge, {7,11} (born 0.4416078462172273, a lower value, hence processed strictly later):
  // its own reduction needed to pick up {2,6,7} after cancelling {2,7,11} via `basis`, and instead
  // reduced through to a wrong, much later pivot (0.8477007042116179 instead of the correct
  // 0.47710577497688794). Every hand-built fixture in this file, including `threePointLine`'s own
  // apparent pair above, was too small to contain this shape.
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

  "Cohomology's finite bars agree with the naive engine on the apparent-pair singleton-vs-full-chain regression example" >> {
    val maxDim = 2
    val naive = naiveBars(apparentPairCollisionCloud, maxDim).filter { case (_, b, d) => b < d }
    val cohomology = cohomologyBarsUnthresholded(apparentPairCollisionCloud, maxDim).filter { case (_, b, d) => b < d }
    cohomology must containTheSameElementsAs(naive)
  }

  "The on-the-fly apparent-pair substitution actually fires on the collision regression example" >> {
    // Not just "the barcode is unchanged": persistentCohomology now defers coboundaryOf(sigma) for
    // every apparent pair and only recomputes it via substitution when some OTHER column's reduction
    // actually reaches tau as an unresolved pivot (see WORKLOG-lazy-enumeration.md). A test that only
    // checks the final barcode can't distinguish "the fallback fired and computed correctly" from "the
    // fallback never fired at all" -- this cloud is the confirmed case (WORKLOG-cohomology.md's
    // "Apparent pairs: resolved" section): edge {7,11}'s reduction needs {2,7,11}'s apparent-pair
    // partner's coboundary, which is exactly what the substitution recomputes on the fly.
    val ctx = RipserCohomologyContext[Double](apparentPairCollisionCloud, 2)
    ctx.persistentCohomology()
    ctx.substitutionCount must be_>(0)
  }

  "The substitution never fires when useApparentPairs is disabled" >> {
    val ctx = RipserCohomologyContext[Double](apparentPairCollisionCloud, 2, useApparentPairs = false)
    ctx.persistentCohomology()
    ctx.substitutionCount must be_==(0)
  }

  "The substitution fires across randomized Vietoris-Rips point clouds, not just the pinned collision example" >> {
    // The two tests above only prove the substitution path CAN fire (on one hand-pinned cloud) and can be
    // turned off. Neither proves the randomized cross-validation property above actually EXERCISES it --
    // that property could pass at 200/200 trials while the substitution path never once fires, which would
    // make it useless as a regression guard for this session's own change. Plain sampling (not `forAll`)
    // so the accumulation across trials is unambiguous, rather than fighting specs2-scalacheck's Prop/Result
    // execution-order semantics for a check that only needs to run once, after all trials.
    val gen = matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))
    val totalSubstitutions = (1 to 200).map { _ =>
      val metricSpace = EuclideanMetricSpace(gen.sample.get)
      val ctx = RipserCohomologyContext[Double](metricSpace, 2)
      ctx.persistentCohomology()
      ctx.substitutionCount
    }.sum
    totalSubstitutions must be_>(0)
  }

  "Every simplex is accounted for: finite*2 + essential == total simplices, per dimension count" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      // NOT totalSimplices(n, maxDim) (the binomial formula): that assumes every combinatorially-possible
      // subset exists, true only at maxFiltrationValue = +Infinity. The default now thresholds at
      // metricSpace.minimumEnclosingRadius, so ctx.totalSimplexCount (the count of what was actually
      // assembled) is the only correct total here too, same as the explicit-threshold test below.
      val ctx = RipserCohomologyContext[Double](metricSpace, maxDim)
      val bars = ctx.persistentCohomology().map(toTuple)
      HomologyFixtures.totalBarsAccountForAllCells(bars, ctx.totalSimplexCount, topDimension = maxDim) must beTrue
    }

  // Session 2 (sparse Rips / maxFiltrationValue): picks t as the midpoint of two ADJACENT entries in the
  // sorted list of distinct pairwise distances. Since every simplex's filtration value is itself some
  // pairwise distance, and t sits strictly between two distinct, adjacent such values by construction, no
  // filtration value in THIS cloud can equal t -- avoids having to litigate the closed/open death
  // convention just to get a test to pass. This guarantee is specific to how t is built here (a strict
  // interior midpoint of adjacent sorted distances) -- it is not a general property of any threshold.
  // Falls back to something past the one distinct distance in the (exceedingly rare, for random doubles)
  // degenerate all-equidistant case.
  private def midThreshold(metricSpace: FiniteMetricSpace[Int]): Double =
    val distances = (for
      x <- metricSpace.elements
      y <- metricSpace.elements
      if x != y
    yield metricSpace.distance(x, y)).toSeq.distinct.sorted
    if distances.size < 2 then distances.headOption.getOrElse(0.0) + 1.0
    else (distances(distances.size / 2 - 1) + distances(distances.size / 2)) / 2.0

  // The free oracle: a Vietoris-Rips filtration thresholded at t is EXACTLY the untruncated filtration's
  // own persistence restricted to [0, t] -- a bar born after t never existed at all (dropped); a bar that
  // straddles t is truncated to essential at t (it survives at least that far, but the threshold means we
  // can never observe it dying, same as querying an incremental engine before its stream finishes). Both
  // sides come from the SAME engine, so tie-breaks are identical and the comparison can use the FULL bar
  // list, zero-length bars included -- a much stronger check than the naive-engine cross-validation
  // above, which is restricted to birth < death because the two engines' tie-breaks differ.
  private def restrictToThreshold(bars: List[(Int, Double, Double)], t: Double): List[(Int, Double, Double)] =
    bars
      .filter { case (_, b, _) => b <= t }
      .map { case (dim, b, d) => (dim, b, if d > t then Double.PositiveInfinity else d) }

  "Thresholded persistent cohomology is exactly the untruncated barcode restricted to [0, t]" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val t = midThreshold(metricSpace)
      val untruncated = cohomologyBarsUnthresholded(metricSpace, maxDim)
      val thresholded = RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = t)
        .persistentCohomology()
        .map(toTuple)
      thresholded must containTheSameElementsAs(restrictToThreshold(untruncated, t))
    }

  "A threshold larger than the cloud's own diameter matches the untruncated barcode exactly" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val maxPairwiseDistance =
        (for x <- metricSpace.elements; y <- metricSpace.elements yield metricSpace.distance(x, y)).max
      val thresholded =
        RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = maxPairwiseDistance + 1.0)
          .persistentCohomology()
          .map(toTuple)
      thresholded must containTheSameElementsAs(cohomologyBarsUnthresholded(metricSpace, maxDim))
    }

  "maxFiltrationValue defaults to metricSpace.minimumEnclosingRadius, and passing it explicitly changes nothing" >> {
    val defaultBars = cohomologyBars(apparentPairCollisionCloud, 2)
    val explicitBars =
      RipserCohomologyContext[Double](
        apparentPairCollisionCloud,
        2,
        maxFiltrationValue = apparentPairCollisionCloud.minimumEnclosingRadius
      ).persistentCohomology().map(toTuple)
    explicitBars must containTheSameElementsAs(defaultBars)
  }

  // The load-bearing check for the minimumEnclosingRadius default (see CLAUDE.md/WORKLOG-mst-and-perf.md):
  // is it just another threshold value, subject to the SAME restrictToThreshold oracle as any other t, or
  // does it behave differently? Beyond minimumEnclosingRadius every vertex is within range of some common
  // apex, so the (unboundedly-many-dimensions) complex is a cone from that point on -- but this codebase's
  // maxDim is always finite, and a bounded maxDim can still see NEW dimension-maxDim simplices born past
  // minimumEnclosingRadius with nothing available to kill them (no maxDim+1 simplex exists to pair against).
  // Real Ripser has this exact same property (enclosing_radius as the default threshold, used with routinely
  // bounded dim_max) -- this is not a maxDim=2-specific safety margin, it's how the optimization has always
  // worked: it truncates the FILTRATION the same way any other maxFiltrationValue does, dropping whatever's
  // born after the cutoff, essential-izing whatever straddles it. Confirmed here against maxDim=2 specifically
  // (not just asserted from the general argument) since that's what every other test in this file uses.
  "The minimumEnclosingRadius default is exactly the untruncated barcode restricted to [0, minimumEnclosingRadius]" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val t = metricSpace.minimumEnclosingRadius
      val untruncated =
        RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = Double.PositiveInfinity)
          .persistentCohomology()
          .map(toTuple)
      val defaulted = cohomologyBars(metricSpace, maxDim) // no maxFiltrationValue given -- exercises the default
      defaulted must containTheSameElementsAs(restrictToThreshold(untruncated, t))
    }

  "A threshold strictly between two pairwise distances excludes the longer edge and the triangle entirely" >> {
    // threePointLine: pairwise distances 1, 2, 3. At t=2.5 (strictly between 2 and 3), edge {0,2} and the
    // triangle {0,1,2} -- both born at 3.0, per the "filled triangle" test above -- never come into
    // existence at all, not merely truncated at their death: their own BIRTH (3.0) exceeds t. What
    // remains is a plain path graph (0-1-2, no cycle) -- H^1 is trivial, not just capped to essential.
    val bars =
      RipserCohomologyContext[Double](threePointLine, 2, maxFiltrationValue = 2.5).persistentCohomology().map(toTuple)
    bars must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, Double.PositiveInfinity)
      )
    )
  }

  "Every simplex is accounted for under a threshold too: finite*2 + essential == the actually-assembled simplex count" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val t = midThreshold(metricSpace)
      val ctx = RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = t)
      val bars = ctx.persistentCohomology().map(toTuple)
      // NOT totalSimplices(n, maxDim) (the binomial formula): that assumes every combinatorially-possible
      // subset exists, true only at maxFiltrationValue = +Infinity. A thresholded complex genuinely
      // excludes most subsets outright (see sparseCofacets) -- ctx.totalSimplexCount is the count of what
      // was actually assembled, the only correct total once a threshold is in play.
      HomologyFixtures.totalBarsAccountForAllCells(bars, ctx.totalSimplexCount, topDimension = maxDim) must beTrue
    }

  "Memoizing filtrationValue changes nothing about the computed barcode" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val unmemoized =
        RipserCohomologyContext[Double](metricSpace, maxDim, memoizeFiltrationValue = false)
          .persistentCohomology()
          .map(toTuple)
      val memoized =
        RipserCohomologyContext[Double](metricSpace, maxDim, memoizeFiltrationValue = true)
          .persistentCohomology()
          .map(toTuple)
      memoized must containTheSameElementsAs(unmemoized)
    }

  "Essential representatives are genuine cocycles (zero coboundary), including at the top dimension" >> {
    // Exact arithmetic (Fp), not Double -- zero-detection during reduction must not be confused with
    // floating-point noise (same precedent as the naive engine's own representative-cycle test).
    val f11 = new FiniteField(11)
    import f11.given

    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 10))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      val ctx = RipserCohomologyContext[f11.Fp](metricSpace, maxDim)
      val bars = ctx.persistentCohomology()
      // Only ESSENTIAL bars (upper == +infinity) have a representative with zero coboundary by
      // construction: Algorithm 1's V_j satisfies delta(V_j) = R_j throughout, and R_j is zero
      // precisely when the bar is essential -- for a FINITE bar, delta(V_j) = R_j equals the reduced
      // PIVOT chain, which is nonzero by definition (that's what makes it finite). Confirmed directly
      // by inspection during development: a finite bar's representative has a nonzero coboundary that
      // exactly matches its own reduced column, not zero -- asserting isZero() there would be a test
      // bug, not a property of the algorithm. Checked at EVERY dimension up to and including maxDim:
      // coboundaryOf used to be vacuously empty at the top dimension (no cofacets were ever enumerated
      // beyond maxDimension), which would have made this check pass there for the wrong reason -- fixed
      // at the source (see .claude/WORKLOG-maxdim-semantics-fix.md), so dim == maxDim is now a genuine,
      // non-vacuous instance of this same property, not a case to exclude.
      forall(bars.filter(b => b.dim <= maxDim && b.upper == PositiveInfinity[Double]())) { bar =>
        val rep = bar.annotation.get
        ctx.coboundaryOfChain(rep).isZero() must beTrue
      }
    }
  }
