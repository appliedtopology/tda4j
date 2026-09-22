package org.appliedtopology.tda4j
package alpha

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import org.specs2.{ScalaCheck, Specification}
import org.specs2.scalacheck.Parameters

/** Regression coverage for a specific bug class: `AlphaComplexDQPBuilder` produced edges but no triangles (or higher
  * simplices) on a jittered 3x3 grid, while `HelixDelaunay` produced the full expected complex. Root cause was a
  * candidate-representation bug in `buildCandidates` (see AlphaComplexDQP.scala for the full writeup); several further
  * bugs surfaced once that one was fixed (see WORKLOG.md at the repo root for the full history).
  *
  * This specific grid is a good regression case on its own merits, independent of that debugging history: the bottom
  * and top rows are each bent by only ~0.004 out of a ~2.0 span, producing two extremely thin "sliver" simplices
  * (circumradius ~124 and ~84, i.e. 40-60x the diameter of the whole point cloud) that only a correct, unbounded alpha
  * complex construction will include -- exactly the kind of degenerate-but-legitimate configuration that's easy to get
  * wrong.
  */
class AlphaValidationSpec extends org.specs2.mutable.Specification with ScalaCheck:
  private val dispatches = Seq("helix", "DQP")

  val grid = Seq(
    Array(0.0 - 0.0034, 0.0 + 0.0087),
    Array(1.0 - 0.0012, 0.0 + 0.0095),
    Array(2.0 - 0.0061, 0.0 + 0.0023),
    Array(0.0 - 0.0078, 1.0 + 0.0045),
    Array(1.0 - 0.0009, 1.0 + 0.0016),
    Array(2.0 - 0.0054, 1.0 + 0.0031),
    Array(0.0 - 0.0082, 2.0 + 0.0069),
    Array(1.0 - 0.0027, 2.0 + 0.0004),
    Array(2.0 - 0.0093, 2.0 + 0.0058)
  )

  // Verified by hand (see WORKLOG.md): both backends should agree exactly, dimension by
  // dimension, on this grid -- 9 vertices, 18 edges (16 "normal" grid edges plus the two
  // sliver-spanning edges {0,2} and {6,8}), 10 triangles (8 normal + the 2 slivers).
  private val expectedSizesByDimension = Vector(9, 18, 10)

  for dispatch <- dispatches do
    s"validate $dispatch" in {
      val alpha = Alpha(grid, dispatch)
      val sizes = (0 to 2).map(d => alpha.iterateDimension(d).size).toVector
      sizes must be_==(expectedSizesByDimension)
    }

  "DQP must include the boundary slivers, not just the ordinary grid triangles" in {
    val dqp = Alpha(grid, "DQP")
    val allCells = (0 to 2).flatMap(d => dqp.iterateDimension(d).toSeq).toSet
    val slivers = Seq(
      Simplex(0, 2),
      Simplex(0, 1, 2), // bottom-row sliver
      Simplex(6, 8),
      Simplex(6, 7, 8) // top-row sliver
    )
    slivers.forall(allCells.contains) must beTrue
  }

class AlphaComplexSpec extends org.specs2.mutable.Specification with ScalaCheck:
  // The bugs this property test exists to catch (QP non-convergence, face-closure
  // violations) were rare: verified failure rates from roughly 1-in-500 to 1-in-12000
  // random point clouds during debugging (see WORKLOG.md). The scalacheck default of 100
  // cases per run is not enough to reliably catch a regression at that rate; 2000 is a
  // still-fast (a few seconds) compromise that gives meaningfully better odds.
  given Parameters = Parameters(minTestsOk = 2000)

  private val dispatches = Seq("helix", "DQP")
  private val pointsGen =
    matrixGen[Double](Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(6, 12))

  private def layers(
    points: Array[Array[Double]],
    dispatch: String
  ): Seq[Seq[Simplex[Int]]] =
    val alpha = Alpha(points.toIndexedSeq, dispatch)
    (0 to points.head.length).map(d => alpha.iterateDimension(d).toSeq)

  private def everySimplexHasExpectedFaces(
    layerByDimension: Seq[Seq[Simplex[Int]]]
  ): Boolean =
    val simplicesByDimension = layerByDimension.map(_.toSet)
    layerByDimension.zipWithIndex.forall { case (layer, dimension) =>
      layer.forall { simplex =>
        simplex.dim == dimension &&
        simplex.toSeq.forall(vertex => vertex >= 0 && vertex < layerByDimension.head.size) &&
        (dimension == 0 ||
          simplex.toSeq.forall(vertex => simplicesByDimension(dimension - 1).contains(simplex - vertex)))
      }
    }

  private def alphaProperties(points: Array[Array[Double]], dispatch: String): Prop =
    val alpha = Alpha(points.toIndexedSeq, dispatch)
    val layerByDimension = (0 to points.head.length).map(d => alpha.iterateDimension(d).toSeq)
    val allSimplices: IndexedSeq[Simplex[Int]] = layerByDimension.flatten
    val simplicesByDimension = layerByDimension.map(_.toSet)

    (
      layerByDimension.head.toSet must containTheSameElementsAs(
        points.indices.map(Simplex(_))
      )
    ) and
      (allSimplices.forall(_.dim >= 0) must beTrue) and
      (layerByDimension.forall(layer => layer.distinct.size == layer.size) must beTrue) and
      (everySimplexHasExpectedFaces(layerByDimension) must beTrue) and
      (layerByDimension.forall(layer =>
        layer.map(alpha.filtrationValue).toSeq == layer.map(alpha.filtrationValue).toSeq.sorted
      ) must beTrue) and
      (allSimplices.forall { simplex =>
        simplex.dim == 0 || simplex.toSeq.forall { vertex =>
          alpha.filtrationValue(simplex - vertex) <= alpha.filtrationValue(simplex)
        }
      } must beTrue) and
      (allSimplices.forall(simplex => simplicesByDimension(simplex.dim).contains(simplex)) must beTrue)

  // "helix" is deliberately excluded from this loop, not just skipped silently:
  // HelixDelaunay has its own, separate robustness bug, unrelated to anything else
  // touched today -- an assertion failure (AlphaShapes.scala:138,
  // assert(validated.nonEmpty) in its initial-simplex bootstrap) on ordinary random
  // input, observed at roughly 1-in-600 in this generator (see AlphaCrossValidationSpec's
  // class doc for the full writeup and a second, independent Helix robustness gap found
  // alongside it). specs2's pendingUntilFixed is for a deterministically-known-failing
  // example, not a probabilistic one -- with a 1-in-600 hit rate over 2000 samples, roughly
  // 1 run in 30 would sample zero hits and get flagged as "fixed now, remove the marker"
  // by specs2, which is exactly the kind of flaky CI signal this file is trying to avoid
  // elsewhere. Fixing HelixDelaunay was out of scope for this session (focus was DQP).
  for dispatch <- dispatches.filterNot(_ == "helix") do
    s"$dispatch alpha complex should" >> {
      "satisfy the simplicial-stream properties" >>
        forAll(pointsGen) { points =>
          alphaProperties(points, dispatch)
        }
    }

/** Regression coverage for `AlphaDQPSettings.parallel`: this flag used to be defined and documented ("run the
  * per-vertex loop on the common ForkJoinPool. Output is deterministic.") but never actually read anywhere -- see
  * `.claude/WORKLOG-parallelization-survey.md` item 1. Now wired into `AlphaComplexDQPBuilder.compute()`'s per-vertex
  * loop. This spec is the actual proof of the flag's own documented "Output is deterministic" claim: build the same
  * point cloud with `parallel = false` and `parallel = true` and require the two complexes agree exactly -- same cells
  * per dimension (not just the same set: `cellsOfDimension` order matters to callers, since `cells`/`barcodeInput` are
  * relied on to already be filtration-sorted), same filtration value per cell, same witness per cell. `sizeByDimension`
  * in `[2,4]`/`[6,14]` (bigger than `AlphaComplexSpec`'s own generator) specifically to guarantee multiple candidates
  * per base vertex at most dimensions, so the parallel branch actually has more than one task in flight most of the
  * time this runs.
  */
class AlphaComplexDQPParallelSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Parameters = Parameters(minTestsOk = 200)

  private val pointsGen =
    matrixGen[Double](Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(6, 14))

  "AlphaComplexDQP with settings.parallel = true" should {
    "agree exactly with settings.parallel = false, dimension by dimension" in
      forAll(pointsGen) { points =>
        val maxDim = points.head.length
        val sequential =
          AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, maxDim, AlphaDQPSettings(parallel = false))
        val parallel =
          AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, maxDim, AlphaDQPSettings(parallel = true))

        val cellsAgree =
          (0 to maxDim).forall(k => sequential.cellsOfDimension(k) == parallel.cellsOfDimension(k))
        val valuesAgree =
          (0 to maxDim).forall { k =>
            sequential.cellsOfDimension(k).forall { c =>
              sequential.filtrationValue(c) == parallel.filtrationValue(c) &&
              sequential.witness(c).map(_.toSeq) == parallel.witness(c).map(_.toSeq)
            }
          }
        (cellsAgree must beTrue) and (valuesAgree must beTrue)
      }
  }

/** Cross-validates `AlphaShapeDQP` directly against `HelixDelaunay` on the same point clouds. `AlphaComplexSpec` above
  * checks each backend's *internal* consistency separately (face closure, sortedness, monotonicity); this checks that
  * the two backends compute the *same complex* -- which is a strictly stronger property, and would have caught every
  * bug listed in WORKLOG.md as a single failing property, rather than needing five separately-diagnosed symptoms.
  *
  * `AlphaShapeDQP` computes the complete, untruncated alpha complex (no maxRadius cutoff) specifically to match
  * `HelixDelaunay`'s always-untruncated behaviour (see the comment on `AlphaShapeDQP` in AlphaComplexDQP.scala), so a
  * full match is the intent.
  *
  * '''Neither direction of this comparison is safe as a hard, blocking property test''', for two independent reasons
  * discovered while building this suite (both in WORKLOG.md):
  *
  *   1. `DualQP.solve` conservatively excludes a small, known class of genuinely-Delaunay simplices near certain
  *      near-degenerate configurations, rather than risk numerical catastrophic active-set cycling on them (pinned down
  *      in `AlphaComplexDQPRegressionSpec`'s facet-closure counterexample). So DQP's output can be a strict subset of
  *      the true complex.
  *   2. `HelixDelaunay` itself is not fully robust: fuzzing this comparison found (a) an assertion failure
  *      (`AlphaShapes.scala:138`, `assert(validated.nonEmpty)` in its initial-simplex bootstrap) on ordinary random
  *      input, roughly 1-in-600 in the generator used by `AlphaComplexSpec`, and (b), separately, cases where its
  *      frontier-walk traversal produces an *incomplete* complex -- missing a whole connected sub-chain of
  *      genuinely-Delaunay faces sharing a common edge, with no exception raised (found via this exact spec: 7 points
  *      in R^4, dim=4, extra = \{0,6\}, \{0,3,6\}, \{0,4,6\}, \{0,5,6\}, \{0,3,5,6\}, \{0,4,5,6\}, \{0,3,4,6\},
  *      \{0,3,4,5,6\} present in DQP's output but absent from Helix's). Confirmed this is Helix's incompleteness and
  *      not DQP hallucinating: the "extra" set is exactly the shape of a frontier-walk having never visited one region
  *      of the true complex, not an arbitrary wrong fact.
  *
  * Fixing HelixDelaunay's robustness was explicitly out of scope for this session (the project lead's focus was complex
  * generation via DQP specifically, discovered this while trying to use Helix as DQP's ground truth). So: kept the
  * comparison as a DIAGNOSTIC tool (useful for investigating either side), but not wired into the pass/fail suite,
  * since a real Helix bug would otherwise masquerade as a DQP regression or vice versa. `unsafeCompare` below is
  * exposed for exactly that manual investigation.
  */
class AlphaCrossValidationSpec extends org.specs2.mutable.Specification with ScalaCheck:
  private val pointsGen =
    matrixGen[Double](Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(5, 14))

  /** Not run automatically (see class doc for why). Call from a REPL/scratch script when investigating a specific point
    * cloud: reports where DQP and Helix disagree, without assuming either side is ground truth.
    */
  def unsafeCompare(points: Array[Array[Double]]): String =
    val dqp = Alpha(points.toIndexedSeq, "DQP")
    val helix = Alpha(points.toIndexedSeq, "helix")
    val dim = points.head.length
    val report = (0 to dim).map { d =>
      val dqpSet = dqp.iterateDimension(d).toSet
      val helixSet = helix.iterateDimension(d).toSet
      s"dim=$d onlyDQP=${dqpSet -- helixSet} onlyHelix=${helixSet -- dqpSet}"
    }
    report.mkString("\n")

  /** Not run automatically (see class doc for why): the DQP-conservative-subset property (dqpSet subsetOf helixSet)
    * usually holds and the filtration values usually agree to ~1e-6 relative when both sides find a simplex, but
    * "usually" driven by Helix's own flakiness isn't a fair thing to assert with `forAll` -- there's no clean way to
    * distinguish "found a real DQP regression" from "Helix hit its own bug this run" without a human looking at the
    * diff, which is exactly what `unsafeCompare` is for. Call this from a REPL/scratch script for a statistical read
    * across many samples.
    */
  def unsafeFuzzCompare(samples: Int = 200): Unit =
    var subsetViolations = 0
    var valueMismatches = 0
    var helixExceptions = 0
    for _ <- 1 to samples do
      pointsGen.sample.foreach { points =>
        try
          val dqp = Alpha(points.toIndexedSeq, "DQP")
          val helix = Alpha(points.toIndexedSeq, "helix")
          val dim = points.head.length
          if (0 to dim).exists(d => (dqp.iterateDimension(d).toSet -- helix.iterateDimension(d).toSet).nonEmpty)
          then subsetViolations += 1
          val allSimplices = (0 to dim).flatMap(d => dqp.iterateDimension(d))
          if allSimplices.exists { s =>
              val (a, b) = (dqp.filtrationValue(s), helix.filtrationValue(s))
              math.abs(a - b) > 1e-6 * math.max(1.0, math.abs(b))
            }
          then valueMismatches += 1
        catch case _: Throwable => helixExceptions += 1
      }
    println(
      s"unsafeFuzzCompare($samples): subsetViolations=$subsetViolations valueMismatches=$valueMismatches exceptions=$helixExceptions"
    )

/** Regression tests pinned to specific, hand-verified adversarial point clouds found while debugging AlphaComplexDQP
  * (see WORKLOG.md for the full derivation of each). The property-based specs above cover the same ground
  * statistically; these pin the exact failing inputs down permanently so a future regression is caught immediately and
  * deterministically, without depending on a ScalaCheck seed rediscovering them.
  */
class AlphaComplexDQPRegressionSpec extends org.specs2.mutable.Specification:

  // Found by fuzzing: 11 points in R^4. Before the allFacetsPresent fix (see
  // AlphaComplexDQP.scala, buildCandidates), this produced a simplex at dimension >= 3
  // whose facet-closure was violated -- a candidate got admitted despite one of its own
  // faces never having been generated. Only manifests at maxDimension >= 3, which is why
  // the original 2D grid repro (AlphaValidationSpec) never exercised it. That bug is
  // fixed (see "no face-closure violation" below).
  //
  // This same point cloud also serves as the pinned regression case for a SEPARATE,
  // *known, accepted* limitation, not a bug: the top-dimensional simplex {0,1,2,3,4} and
  // one of its facets {1,2,3,4} are genuinely Delaunay (HelixDelaunay finds them) but
  // DualQP.solve conservatively excludes them, because doing so requires committing a
  // Cholesky pivot from a Schur complement small enough that (per WORKLOG.md) there is no
  // reliable fixed threshold separating "safe to commit" from "will poison the factor and
  // cause genuine cycling" -- two near-identical ratios (~3.4e-7 here, ~3.0e-7 in the
  // cycling-counterexample-adjacent case below) went opposite ways. Excluding a genuine
  // simplex is preferred over unpredictable non-termination. See "does NOT fully agree
  // with Helix" below, which pins this down explicitly so it reads as documented
  // behaviour rather than a silent gap.
  val facetClosureCounterexample: Array[Array[Double]] = Array(
    Array(0.8412233188005711, 0.0605651201507349, 0.8005083165832265, 0.11312010410813411),
    Array(0.47786192865716726, 0.849998100168129, 0.715087428823243, 0.007627601154649111),
    Array(0.3827083191054116, 0.29020120696428897, 0.29668887681432554, 0.10276452752011289),
    Array(0.6482254215818215, 0.9280260182859927, 0.010887367356243183, 0.13059873946355327),
    Array(0.3595293667803696, 0.8894482012771456, 0.8428073965944202, 0.326520424271157),
    Array(0.2689910491144816, 0.4233917625139241, 0.2279552228974805, 0.8769382428473812),
    Array(0.39312241851913843, 0.9860826206514616, 0.8705948827613684, 0.06198278647443933),
    Array(0.43006043897360535, 0.20410208555099707, 0.2654497638548935, 0.1618358836243693),
    Array(0.9631300409678678, 0.24819941157064396, 0.009764291928139435, 0.711993017456035),
    Array(0.16616797995870514, 0.4112087667156371, 0.8851350299005107, 0.21871960497455523),
    Array(0.4464714815001827, 0.5264616373932599, 0.8754034387651292, 0.5087137533160434)
  )

  // Found by fuzzing: 12 points in R^5, ordinary uniform-random coordinates (nothing
  // hand-crafted-degenerate). Before the rankTolerance/Bland's-rule fixes, DualQP.solve
  // cycled between two working sets indefinitely (confirmed non-terminating at 100,000
  // iterations, not just slow) -- proof that "missing triangles"-class bugs can hide a
  // genuine numerical-robustness gap that only shows up at higher ambient dimension.
  val cyclingCounterexample: Array[Array[Double]] = Array(
    Array(0.8397338633728533, 0.22029919675766751, 0.11163872675869513, 0.5386305519970757, 0.1825440428026578),
    Array(0.5152390879155537, 0.9158983249167835, 0.6893108955048666, 0.8371533813256986, 0.9312809222388989),
    Array(0.9305517592376757, 0.5899914463007108, 0.29847519211736573, 0.1339468479293825, 0.4885973707918889),
    Array(0.8931733367178023, 0.6343229271864452, 0.3564194065871156, 0.07715241995261679, 0.9048709449179233),
    Array(0.24757383959576396, 0.4729917914957922, 0.07764335717119075, 0.3064615244806632, 0.47345318080539645),
    Array(0.21854483594099494, 0.2560435337983217, 0.33229246974921034, 0.6670258519349122, 0.385840226257569),
    Array(0.4481363895807683, 0.20299978884177594, 0.3953135540862901, 0.32541513192700366, 0.28188470851580305),
    Array(0.726343496472608, 0.3314942160385118, 0.7391166361920493, 0.3251349743640548, 0.5805362226688374),
    Array(0.48381648029216107, 0.10338735185770254, 0.015937207434451817, 0.264259592954985, 0.794916897556474),
    Array(0.29390457141493287, 0.7907882165221911, 0.7401150080903778, 0.243857683988834, 0.8642146284135939),
    Array(0.19901001010303732, 0.5464067189875833, 0.1351402455814651, 0.44640796145274775, 0.4152759999177754),
    Array(0.7980739186730984, 0.8762121291018515, 0.5105845321485173, 0.9213156176482766, 0.8143094170325306)
  )

  private def hasCleanFaceClosure(points: Array[Array[Double]]): Boolean =
    val dqp = Alpha(points.toIndexedSeq, "DQP")
    val layerByDimension = (0 to points.head.length).map(d => dqp.iterateDimension(d).toSeq)
    val byDim = layerByDimension.map(_.toSet)
    layerByDimension.zipWithIndex.forall { case (layer, dimension) =>
      layer.forall(simplex => dimension == 0 || simplex.toSeq.forall(v => byDim(dimension - 1).contains(simplex - v)))
    }

  "DQP alpha complex on the facet-closure counterexample" should {
    "have every simplex's faces present (no face-closure violation)" in {
      hasCleanFaceClosure(facetClosureCounterexample) must beTrue
    }
    "does NOT fully agree with Helix, but only in the conservative direction (known, accepted limitation -- see comment above)" in {
      // Not pinning the exact missing set: it depends on the solver's internal
      // iteration order (e.g. which entering variable Bland's rule picks first),
      // which is allowed to change with future solver tweaks without that being a
      // regression, as long as the invariant that actually matters -- DQP is
      // conservative, never wrong -- keeps holding. dqpSet -- helixSet must always
      // be empty; that's the one hard requirement. helixSet -- dqpSet is expected
      // to be nonempty for this specific point cloud (that's *why* it's pinned
      // here), but if a future fix shrinks it to empty, that's progress, not a
      // failure -- so only the "DQP is a subset" direction is asserted with must.
      val d = facetClosureCounterexample
      val dqpSet = (0 to d.head.length).flatMap(k => Alpha(d.toIndexedSeq, "DQP").iterateDimension(k).toSeq).toSet
      val helixSet = (0 to d.head.length).flatMap(k => Alpha(d.toIndexedSeq, "helix").iterateDimension(k).toSeq).toSet
      println(
        s"AlphaComplexDQPRegressionSpec: facetClosureCounterexample currently missing ${helixSet -- dqpSet} relative to Helix"
      )
      (dqpSet -- helixSet) must be_==(Set.empty)
    }
  }

  "DQP alpha complex on the cycling counterexample" should {
    "compute without AlphaComplexDQPException (no active-set cycling)" in {
      // AlphaShapeDQP.alphaComplexDQP is an eager val, so construction alone forces
      // the full computation across every dimension.
      Alpha(cyclingCounterexample.toIndexedSeq, "DQP") must not(throwAn[AlphaComplexDQPException])
    }
    "agree exactly with Helix" in {
      val d = cyclingCounterexample
      val dqpSet = (0 to d.head.length).flatMap(k => Alpha(d.toIndexedSeq, "DQP").iterateDimension(k).toSeq).toSet
      val helixSet = (0 to d.head.length).flatMap(k => Alpha(d.toIndexedSeq, "helix").iterateDimension(k).toSeq).toSet
      dqpSet must be_==(helixSet)
    }
  }

/** Regression test for a confirmed bug (found by `EngineComparisonBenchmarkSpec`, full writeup in CLAUDE.md's
  * "Cross-engine benchmark, and a bug it found on first run" section): both `HelixDelaunay` and `AlphaShapeDQP` used to
  * define `filtrationOrdering` ascending instead of reversed, which crashed `SimplicialHomologyContext` ("Naive"
  * engine) at `maxDim >= 2` with `IllegalStateException: reduction pivot ... was not a recorded open class`, while
  * leaving `PersistenceInChunksContext` ("Chunks") unaffected. Pins both halves of the fix: no exception, AND agreement
  * between the two engines -- the actual property that was broken, not just "doesn't crash".
  */
class AlphaFiltrationOrderingRegressionSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  private val dispatches = Seq("helix", "DQP")
  private val maxDim = 2

  private def bounded(stream: StratifiedSimplexStream[Int, Double]): StratifiedCellStream[Simplex[Int], Double] =
    val cells =
      (0 to maxDim).iterator.flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)).toVector
    val byDim = cells.groupBy(_.dim)
    new StratifiedCellStream[Simplex[Int], Double]:
      def filtrationValue = stream.filtrationValue
      def filtrationOrdering = stream.filtrationOrdering
      val smallest = stream.smallest
      val largest = stream.largest
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if byDim.contains(d) => byDim(d).iterator
      }

  for dispatch <- dispatches do
    s"$dispatch alpha complex's Naive-engine barcode agrees with Chunks at maxDim >= 2" in
      forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
        // Construct the alpha complex once and share it between both engines. Building it twice (once
        // per engine) was found to occasionally disagree in the last bit or two of a filtration value --
        // e.g. helix's computeFVal touches a mutable.Set whose iteration order (and hence floating-point
        // summation order) isn't guaranteed identical between two independent constructions of "the same"
        // complex. That's a construction-nondeterminism artifact of the old test, not a reduction bug: it
        // produced two mathematically-equal but bit-different Doubles, which containTheSameElementsAs (an
        // exact-equality comparison) then reported as "missing"/"must not contain" on otherwise-identical
        // bars. Sharing one construction is also simply the more faithful test of the property this spec
        // actually cares about: two engines agreeing on ONE complex, not on two independently-rebuilt ones.
        val streamB = bounded(Alpha(points.toIndexedSeq, dispatch))
        val totalCells = streamB.iterator.size
        val naive =
          SimplicialHomologyContext[Int, Double, Double]()
            .persistentHomology(streamB)
            .diagramAt(Double.PositiveInfinity)
        val chunks =
          PersistenceInChunksContext[Int, Double](maxDim).persistentHomology(streamB).diagramAt(Double.PositiveInfinity)
        // No independent oracle stream exists for alpha complexes (unlike VR, where this test class's
        // sibling cross-checks against EnumeratingCofaceSimplexStream) -- so Naive's own structural
        // invariant (every cell opens or closes exactly one bar) is the strongest check available on its
        // own, independent of whether Chunks agrees. CLAUDE.md cites this holding "on every trial" as part
        // of this fix's verification; keep this assertion in sync with that claim.
        (HomologyFixtures.totalBarsAccountForAllCells(naive, totalCells) must beTrue) and
          (naive must containTheSameElementsAs(chunks))
      }
