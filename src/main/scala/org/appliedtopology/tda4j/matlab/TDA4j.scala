package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.barcode.*

import scala.collection.mutable

/** Static entry point for computing persistent (co)homology from MATLAB (or any plain-Java caller) via MATLAB's
  * built-in Java interface. Every public method takes/returns only `double`, `int`, `String`, `double[][]`, `int[]`, or
  * `String[]` -- deliberately not `java.util.Map` or anything generic, since MATLAB's Java bridge doesn't marshal those
  * reliably. See `WORKLOG-matlab-api.md` for the full design rationale and what's still open, `PersistenceResult` for
  * what `computeFrom*` returns, and `LandmarkSelectionResult` for what `selectLandmarksFrom*` returns.
  *
  * `selectLandmarksFrom{Points,DistanceMatrix}`/`computeFrom{Points,DistanceMatrix}AndLandmarks`/
  * `coveringRadiusFrom{Points,DistanceMatrix}` (further down this file) are a separate, TWO-STEP alternative to the
  * one-shot `complex=witness` path below -- pick landmarks and read back the covering radius `R` first, then compute
  * (or query `R` for a landmark set you picked yourself) -- for the JavaPlex tutorial's own "pick landmarks, read R,
  * use 2R" recipe, which the one-shot path can't reproduce (it never reports `R` back). Each of those entry points
  * documents its own, STRICTER recognized-options set on itself, separate from the list below (see
  * `.claude/WORKLOG-witness-two-step-api.md`).
  *
  * Options are passed as a flat, alternating key/value `String[]` (`{"engine","ripser","maxDimension","3"}`) rather
  * than fixed parameters, so that adding a new option never changes any method's call signature. Recognized keys
  * (`computeFromPoints`/`computeFromDistanceMatrix`/`computeFromCubicalImage`/`computeFromImage` only -- see above for
  * the two-step entry points' own separate lists):
  *
  *   - `"complex"`: `"vr"` (default), `"alpha"`, `"cech"`, `"witness"`, `"dtm-rips"`, `"dtm-alpha"`, or
  *     `"sheehy-rips"`.
  *   - `"engine"`: `"ripser"` (default for `complex=vr`, and for `complex=witness` with `witnessVariant=lazy`; backed
  *     by `PackedRipserCohomologyContext`, the fastest and most memory-efficient engine -- see CLAUDE.md), `"naive"`
  *     (reference-grade, slower; the default for
  *     `complex=alpha`/`complex=cech`/`complex=dtm-rips`/`complex=dtm-alpha`/`complex=sheehy-rips`, and for
  *     `complex=witness` with `witnessVariant=general`), `"chunks"` (`complex=vr`/`complex=cech`/`complex=dtm-rips`/
  *     `complex=sheehy-rips`/`complex=witness` with `witnessVariant=lazy` only -- see below for why `complex=alpha`/
  *     `complex=dtm-alpha` refuse it, and why `complex=cech`/`complex=dtm-rips`/`complex=sheehy-rips`/
  *     `witnessVariant=general` refuse `engine=ripser` specifically), or `"cohomology"` (backed by
  *     `CellularCohomologyContext` -- persistent COhomology, generic over `CellT: OrderedCell`, valid for every
  *     `complex` value including `alpha`; unlike `engine=ripser`, not specialized to Vietoris-Rips, so it also works
  *     for `complex=alpha`/`complex=cech`/`complex=witness`/`complex=dtm-rips`/`complex=dtm-alpha`/
  *     `complex=sheehy-rips`, but without `ripser`'s VR-specific speed optimizations -- see
  *     `.claude/DESIGN-generic-cohomology.md`). Every essential bar's representative is a genuine cocycle (`d(rep) =
  *     0`); a finite bar's representative is a valid witness on its own living interval but is NOT expected to have
  *     zero coboundary over the whole complex -- see `Cohomology.scala`'s own doc for why.
  *   - `"alphaBackend"`: `"helix"` (default) or `"DQP"`, only consulted when `complex=alpha`. `complex=dtm-alpha`
  *     always uses DQP (needs power/weighted Delaunay, which Helix does not support) -- this option is not consulted
  *     there.
  *   - `"dtmK"`: integer, REQUIRED when `complex=dtm-rips` or `complex=dtm-alpha` (no default -- there is no
  *     universally sensible neighbour count). The `k` of `streams.DistanceToMeasure`: how many nearest neighbours (self
  *     included) define each point's own distance-to-measure value. See `alpha.AlphaComplexDQP.dtm`/
  *     `streams.DtmRipsSimplexStream`'s own docs (Chazal-Cohen-Steiner-Merigot 2011; Anai et al., "DTM-based
  *     filtrations," arXiv:1811.04757).
  *   - `"dtmQ"`: double, default `2.0`, only consulted when `complex=dtm-rips` or `complex=dtm-alpha` -- the DTM's own
  *     exponent (`streams.DistanceToMeasure`'s `q`), not the filtration's ball-radius exponent below.
  *   - `"dtmP"`: double, default `1.0` (GUDHI's own default, and the only variant checked against an external reference
  *     implementation -- see `streams.DtmRipsSimplexStream`'s own doc), only consulted when `complex=dtm-rips`; must be
  *     `1.0` or `2.0`. Not consulted for `complex=dtm-alpha`, which is inherently the `p=2` ball equation by
  *     construction (see `alpha.AlphaComplexDQP.dtm`'s own doc).
  *   - `"sheehyEpsilon"`: double, REQUIRED when `complex=sheehy-rips` (no default -- there is no universally sensible
  *     sparsity/approximation-quality tradeoff, and silently picking one could produce a barely-sparsified or
  *     wildly-approximate complex without the caller noticing). Must be strictly between `0` and `1`. Cavanna,
  *     Jahanseir & Sheehy's own `epsilon` (arXiv:1506.03797): the resulting barcode is a `(1+epsilon)`-multiplicative
  *     approximation to plain `complex=vr`'s own barcode -- see `streams.SheehyRipsSimplexStream`'s own doc for the
  *     full construction, its units convention, and a documented gap in the source paper's own published algorithm this
  *     implementation closes.
  *   - `"numLandmarks"`: integer, REQUIRED when `complex=witness` (no default -- there is no universally sensible
  *     landmark count). The number of landmarks to select from the input point cloud/distance matrix via
  *     `"landmarkSelector"` -- see `streams.LandmarkSelector`.
  *   - `"witnessVariant"`: `"lazy"` (default) or `"general"`, only consulted when `complex=witness` -- see
  *     `WitnessVariantKind`'s own doc for the distinction (flag complex vs. not).
  *   - `"landmarkSelector"`: `"maxmin"` (default, sequential furthest-point sampling -- a covering-radius guarantee,
  *     JavaPlex's own recommended default) or `"random"` (uniform, seeded by `"landmarkSeed"`), only consulted when
  *     `complex=witness`.
  *   - `"landmarkSeed"`: integer, default `0`, only consulted when `complex=witness` and `landmarkSelector=random`.
  *   - `"nu"`: integer, default `2` (JavaPlex's own default), only consulted when `complex=witness` and
  *     `witnessVariant=lazy` -- see `streams.WitnessMetricSpace`'s own doc; must be `0`, `1`, or `2`.
  *   - `"maxDimension"`: integer, default `2` -- the highest HOMOLOGICAL degree you want back (i.e. "give me
  *     H_0..H_k"), not the highest simplex dimension to build. Computing H_k correctly needs (k+1)-dimensional chains
  *     (H_k = ker(d_k)/im(d_{k+1}) -- with no (k+1)-chains at all there's no way to tell a genuine k-cycle from one a
  *     not-yet-built (k+1)-simplex would have killed). For `engine="ripser"`/`"chunks"`,
  *     `PackedRipserCohomologyContext`/ `PersistenceInChunksContext` both now handle this internally (fixed at their
  *     own source -- see `.claude/WORKLOG-maxdim-semantics-fix.md`); for `engine="naive"`, this facade still builds one
  *     dimension higher internally and drops that extra top dimension from what's reported, since
  *     `SimplicialHomologyContext` has no `maxDimension` of its own at all -- it would otherwise look spuriously
  *     essential, a well-known truncation artifact of the top dimension of any truncated chain complex, not real
  *     information (confirmed the hard way in this facade's first pass -- see WORKLOG-matlab-api.md). `complex=alpha`/
  *     `complex=dtm-alpha` ignore this option entirely and report every dimension their complex naturally has: an alpha
  *     complex's chain complex terminates on its own (bounded by ambient dimension, or higher under cosphericity -- see
  *     CLAUDE.md), it is never artificially cut short the way a VR complex is by this option, so its own top dimension
  *     is genuine information, not scaffolding. `complex=dtm-rips`/`complex=sheehy-rips` need the same "build one
  *     dimension higher, drop it" handling as `complex=vr`/`complex=cech` (both are just as unboundedly deep).
  *   - `"maxFiltrationValue"`: double, default (when omitted) is the point cloud's own `minimumEnclosingRadius`
  *     (Ripser's own default truncation, not unbounded -- see CLAUDE.md's "enclosing-radius default" note). Pass a very
  *     large number for the old always-unbounded behavior. Consulted for `complex=vr` (a diameter), `complex=cech` (a
  *     RADIUS -- Cech's own filtration units, not doubled the way a VR diameter would be), `complex=dtm-rips` (DOUBLED
  *     units, exactly like `complex=vr` -- see `streams.DtmRipsSimplexStream`'s own doc for why its default is safe
  *     there too), and `complex=witness` with `witnessVariant=lazy` (`WitnessMetricSpace`'s own "distance" units -- the
  *     enclosing-radius default is valid here too, see `streams.LazyWitnessSimplexStream`'s own doc); also consulted
  *     for `complex=sheehy-rips` (DOUBLED units, exactly like `complex=vr`) but with a DIFFERENT omitted-key default --
  *     `minimumEnclosingRadius` would itself be unbounded here, since an edge to this construction's own anchor point
  *     can be arbitrarily large, so omitting this key instead resolves to
  *     `streams.SheehyRipsSimplexStream.maxFiniteFiltrationValue`, and any value given here only ever narrows that,
  *     never widens past it (see that class's own doc for why it is always clamped regardless of what is passed); not
  *     consulted for `complex=alpha`/`complex=dtm-alpha` (always untruncated -- see CLAUDE.md's "Alpha complex"
  *     section) nor for `complex=witness` with `witnessVariant=general` (defaults to `+Infinity` there instead --
  *     `minimumEnclosingRadius` is NOT a valid truncation for a non-flag complex, see
  *     `streams.WitnessCofaceSimplexStream`'s own doc).
  *   - `"edgeCollapse"`: `"true"` or `"false"` (default), only consulted when `complex=vr` -- `require`d `false` (or
  *     omitted) for every other `complex` value. `streams.EdgeCollapse` (Boissonnat-Pritam/Glisse-Pritam, SoCG
  *     2020/2022, `.claude/WORKLOG-edge-collapse.md`): reduces the Vietoris-Rips 1-skeleton to a smaller weighted graph
  *     with the SAME persistent homology at every filtration level, before anything is built on top of it -- a
  *     preprocessing step, not a different complex, so it changes nothing about `PersistenceResult`'s own output shape.
  *     Measured 73-76% of edges removed and a 43-47x REDUCTION-phase speedup on random point clouds (n=30, 50);
  *     construction-phase speedup is far more modest (1.45-1.74x) -- the dominant cost this helps with is reducing the
  *     resulting (now much smaller) chain complex, not enumerating candidates in the first place, see the worklog for
  *     the measurement and the source-level reason why. Applies uniformly to every `"engine"` value;
  *     `engine="ripser"`'s own REDUCTION should benefit the same way `"naive"`/`"chunks"`/`"cohomology"`'s measured did
  *     (fewer real simplices to reduce, regardless of which algorithm reduces them), but this specific combination has
  *     not itself been measured, only the other three -- see the worklog.
  *   - `"field"`: `"Z"` (default -- a prime finite field, `prime=2` unless overridden; the standard convention in the
  *     TDA research literature, e.g. Ripser/GUDHI) or `"R"` (floating point with an epsilon tolerance,
  *     `Field.DoubleApproximated` -- notably what this codebase's own existing cross-validation specs default to
  *     instead, an established-convention-vs-existing-test-suite mismatch worth knowing about, not silently resolved
  *     either way; see WORKLOG-matlab-api.md).
  *   - `"prime"`: integer, default `2`, only consulted when `field=Z`.
  *   - `"epsilon"`: double, default `1e-9`, only consulted when `field=R`.
  *
  * Unrecognized keys, and unrecognized values for `complex`/`engine`/`field`, throw `IllegalArgumentException`
  * immediately rather than silently falling back to a default -- a typo in a MATLAB string literal should fail loudly,
  * not produce a quietly-wrong barcode.
  */

/** Parsed, validated forms of the `"complex"`/`"engine"`/`"field"` string options -- `TDA4j`'s public methods still
  * take/return only MATLAB-marshalable primitives (`String[]` included), so the string parsing itself can't go away,
  * but every dispatch decision downstream of `dispatch`/`dispatchCubical` matches on these enums instead of
  * re-lowercasing and re-comparing the same raw strings at each of several call sites. The CLI (`cli.TDA4jCLI`) stays a
  * thin translator passing strings straight through to this same facade -- it does not get its own copy of this
  * parsing, by design (see CLAUDE.md's CLI section: the CLI was chosen to mirror this facade 1:1 specifically to avoid
  * a second dispatch system to keep in sync).
  */
private enum ComplexKind:
  case VR, Alpha, Cech, Witness, DtmRips, DtmAlpha, SheehyRips

private object ComplexKind:
  def parse(raw: String): ComplexKind = raw.toLowerCase match
    case "vr"          => VR
    case "alpha"       => Alpha
    case "cech"        => Cech
    case "witness"     => Witness
    case "dtm-rips"    => DtmRips
    case "dtm-alpha"   => DtmAlpha
    case "sheehy-rips" => SheehyRips
    case other         =>
      throw new IllegalArgumentException(
        s"unrecognized complex '$other'; expected 'vr', 'alpha', 'cech', 'witness', 'dtm-rips', 'dtm-alpha', or " +
          "'sheehy-rips'"
      )

/** `complex=witness` only: `"lazy"` (JavaPlex's `LazyWitnessStream` -- a flag complex, so `engine=ripser` is valid; see
  * `streams.LazyWitnessSimplexStream`) or `"general"` (JavaPlex's plain `WitnessStream` -- NOT a flag complex, so
  * `engine=ripser`/`"chunks"` are refused, exactly like `complex=cech`'s own `engine=ripser` refusal; see
  * `streams.WitnessCofaceSimplexStream`).
  */
private enum WitnessVariantKind:
  case Lazy, General

private object WitnessVariantKind:
  def parse(raw: String): WitnessVariantKind = raw.toLowerCase match
    case "lazy"    => WitnessVariantKind.Lazy
    case "general" => WitnessVariantKind.General
    case other     =>
      throw new IllegalArgumentException(s"unrecognized witnessVariant '$other'; expected 'lazy' or 'general'")

private enum EngineKind:
  case Ripser, Naive, Chunks, Cohomology, FastCubical

private object EngineKind:
  def parse(raw: String): EngineKind = raw.toLowerCase match
    case "ripser"       => Ripser
    case "naive"        => Naive
    case "chunks"       => Chunks
    case "cohomology"   => Cohomology
    case "fast-cubical" => FastCubical
    case other          =>
      throw new IllegalArgumentException(
        s"unrecognized engine '$other'; expected 'ripser', 'naive', 'chunks', 'cohomology', or 'fast-cubical'"
      )

private enum CoefficientKind:
  case Z, R

private object CoefficientKind:
  def parse(raw: String): CoefficientKind = raw.toLowerCase match
    case "z"   => Z
    case "r"   => R
    case other => throw new IllegalArgumentException(s"unrecognized field '$other'; expected 'Z' or 'R'")

object TDA4j:
  def computeFromPoints(points: Array[Array[Double]]): PersistenceResult =
    computeFromPoints(points, Array.empty[String])

  /** Vietoris-Rips or alpha-complex persistence from a point cloud (one row per point, Euclidean distance). This is the
    * only entry point that supports `complex=alpha`, since alpha complexes need actual coordinates, not just pairwise
    * distances.
    */
  def computeFromPoints(points: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validatePoints(points)
    val opts = parseOptions(options)
    val metricSpace = EuclideanMetricSpace(points)
    dispatch(opts, metricSpace, Some(points))

  def computeFromDistanceMatrix(distances: Array[Array[Double]]): PersistenceResult =
    computeFromDistanceMatrix(distances, Array.empty[String])

  /** Vietoris-Rips persistence from a precomputed pairwise-distance matrix (square, symmetric, zero diagonal --
    * expected but not checked beyond squareness, matching `ExplicitMetricSpace`'s own contract). Use this when your
    * dissimilarity measure isn't Euclidean distance on the rows you'd otherwise pass to `computeFromPoints`.
    * `complex=alpha` is not available here -- alpha complexes need real coordinates.
    */
  def computeFromDistanceMatrix(distances: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validateSquare(distances)
    val opts = parseOptions(options)
    dispatch(opts, explicitMetricSpace(distances), None)

  // ---------------------------------------------------------------------------------------------------------------
  // the two-step witness-complex recipe: select landmarks (and read back R), THEN compute -- an alternative to
  // computeFrom{Points,DistanceMatrix}'s own one-shot complex=witness path (which stays exactly as it was: pick
  // landmarks internally, compute, return only the barcode). Use the two-step form when you want the JavaPlex
  // tutorial's own recipe (pick landmarks, read R, pass 2R as maxFiltrationValue) or want to reuse/inspect/
  // hand-edit a landmark set across more than one computation. See the user guide's "Witness complexes" section
  // for a worked MATLAB example.
  // ---------------------------------------------------------------------------------------------------------------

  def selectLandmarksFromPoints(points: Array[Array[Double]], options: Array[String]): LandmarkSelectionResult =
    validatePoints(points)
    val opts = parseOptionsWithKeys(options, landmarkSelectionKeys)
    val selection = resolveLandmarkSelection(opts, EuclideanMetricSpace(points))
    new LandmarkSelectionResult(selection.landmarks.toArray, selection.coveringRadius)

  /** Step 1 (point-cloud input) of the two-step witness recipe: pick landmarks via `"landmarkSelector"` (`"maxmin"`
    * default or `"random"`, seeded by `"landmarkSeed"`) and read back the covering radius `R` -- without yet building
    * any complex. Recognizes ONLY `"numLandmarks"` (REQUIRED), `"landmarkSelector"`, and `"landmarkSeed"` -- a STRICTER
    * allowlist than `computeFromPoints`'s own (see `landmarkSelectionKeys`'s own doc for why). Pass
    * `LandmarkSelectionResult.landmarks()` straight into `computeFromPointsAndLandmarks` for step 2, or into
    * `coveringRadiusFromPoints` if you want `R` for a DIFFERENT (e.g. hand-edited) landmark set.
    */
  def selectLandmarksFromPoints(points: Array[Array[Double]]): LandmarkSelectionResult =
    selectLandmarksFromPoints(points, Array.empty[String])

  def selectLandmarksFromDistanceMatrix(
    distances: Array[Array[Double]],
    options: Array[String]
  ): LandmarkSelectionResult =
    validateSquare(distances)
    val opts = parseOptionsWithKeys(options, landmarkSelectionKeys)
    val selection = resolveLandmarkSelection(opts, explicitMetricSpace(distances))
    new LandmarkSelectionResult(selection.landmarks.toArray, selection.coveringRadius)

  /** Step 1 (distance-matrix input) -- see `selectLandmarksFromPoints`'s own doc; identical recipe, just from a
    * precomputed pairwise-distance matrix instead of point coordinates.
    */
  def selectLandmarksFromDistanceMatrix(distances: Array[Array[Double]]): LandmarkSelectionResult =
    selectLandmarksFromDistanceMatrix(distances, Array.empty[String])

  def computeFromPointsAndLandmarks(
    points: Array[Array[Double]],
    landmarks: Array[Int],
    options: Array[String]
  ): PersistenceResult =
    validatePoints(points)
    val metricSpace = EuclideanMetricSpace(points)
    validateLandmarks(landmarks, metricSpace.size)
    val opts = parseOptionsWithKeys(options, witnessFromLandmarksKeys)
    dispatchWitnessFromLandmarks(opts, metricSpace, landmarks.toIndexedSeq)

  /** Step 2 (point-cloud input) of the two-step witness recipe: compute the witness complex barcode for an EXPLICIT,
    * caller-supplied landmark set (0-based ambient indices into `points`) -- typically
    * `LandmarkSelectionResult.landmarks()` from step 1, but any hand-picked or reused set works too; this method never
    * re-selects landmarks itself. Always computes `complex=witness` (there is nothing else it could compute) --
    * `"complex"` is accepted as an option ONLY when its value is `"witness"`, so a caller migrating from the one-shot
    * `computeFromPoints` who still types that flag out of habit isn't silently ignored, but a genuine mismatch (e.g. a
    * stray `"complex","vr"`) IS caught. Recognizes `"witnessVariant"`, `"nu"`, `"engine"`, `"maxDimension"`,
    * `"maxFiltrationValue"`, `"field"`, `"prime"`, `"epsilon"` -- see `computeFromPoints`'s own doc for what each
    * means; NOT `"numLandmarks"`/`"landmarkSelector"`/ `"landmarkSeed"`, since landmarks are supplied directly here,
    * not selected.
    *
    * `landmarks` must be non-empty, every entry in `[0, points.length)`, and free of duplicates -- checked eagerly with
    * an actionable message (an out-of-range index equal to `points.length` specifically hints at a 1-based-indexing
    * mistake, MATLAB's own default convention).
    */
  def computeFromPointsAndLandmarks(points: Array[Array[Double]], landmarks: Array[Int]): PersistenceResult =
    computeFromPointsAndLandmarks(points, landmarks, Array.empty[String])

  def computeFromDistanceMatrixAndLandmarks(
    distances: Array[Array[Double]],
    landmarks: Array[Int],
    options: Array[String]
  ): PersistenceResult =
    validateSquare(distances)
    val metricSpace = explicitMetricSpace(distances)
    validateLandmarks(landmarks, metricSpace.size)
    val opts = parseOptionsWithKeys(options, witnessFromLandmarksKeys)
    dispatchWitnessFromLandmarks(opts, metricSpace, landmarks.toIndexedSeq)

  /** Step 2 (distance-matrix input) -- see `computeFromPointsAndLandmarks`'s own doc; identical recipe, just from a
    * precomputed pairwise-distance matrix instead of point coordinates.
    */
  def computeFromDistanceMatrixAndLandmarks(distances: Array[Array[Double]], landmarks: Array[Int]): PersistenceResult =
    computeFromDistanceMatrixAndLandmarks(distances, landmarks, Array.empty[String])

  /** The covering radius `R = max_x min_{l in landmarks} d(x,l)` of an ARBITRARY landmark set -- not necessarily one
    * `selectLandmarksFrom*` chose (e.g. a hand-picked or externally-computed set) -- for the same `2R` threshold recipe
    * `LandmarkSelectionResult.coveringRadius()` supports for a `selectLandmarksFrom*`-chosen set. Same landmark
    * validation as `computeFromPointsAndLandmarks`.
    */
  def coveringRadiusFromPoints(points: Array[Array[Double]], landmarks: Array[Int]): Double =
    validatePoints(points)
    val metricSpace = EuclideanMetricSpace(points)
    validateLandmarks(landmarks, metricSpace.size)
    LandmarkSelector.coveringRadius(metricSpace, landmarks.toIndexedSeq)

  /** See `coveringRadiusFromPoints`'s own doc; identical, just from a precomputed pairwise-distance matrix. */
  def coveringRadiusFromDistanceMatrix(distances: Array[Array[Double]], landmarks: Array[Int]): Double =
    validateSquare(distances)
    val metricSpace = explicitMetricSpace(distances)
    validateLandmarks(landmarks, metricSpace.size)
    LandmarkSelector.coveringRadius(metricSpace, landmarks.toIndexedSeq)

  // ---------------------------------------------------------------------------------------------------------------
  // circular coordinates (homology.CircularCoordinates, .claude/WORKLOG-mainstream-feature-gap-analysis.md item 2)
  // -- a genuinely different SHAPE of result from PersistenceResult (a per-point angle, not a barcode), so its own
  // small entry points rather than a new complex=circular value on computeFromPoints.
  // ---------------------------------------------------------------------------------------------------------------

  /** The `(birth, death)` range of every persistent H¹ class of `points`' own Vietoris-Rips complex, as an N-by-2 array
    * (column 0 birth, column 1 death, `+Inf` for an essential bar), sorted by persistence descending -- row `i` here is
    * exactly `circularCoordinates`'s own `cocycleIndex = i`. There is no way to pick a meaningful `r` for
    * `circularCoordinates` without first knowing a target bar's own range, so this is the intended first call for a
    * MATLAB caller, not merely a diagnostic -- see `homology.CircularCoordinates.h1Bars`'s own doc.
    */
  def h1Bars(points: Array[Array[Double]]): Array[Array[Double]] =
    validatePoints(points)
    CircularCoordinates.h1Bars(EuclideanMetricSpace(points)).map((b, d) => Array(b, d)).toArray

  def circularCoordinates(points: Array[Array[Double]], r: Double): CircularCoordinatesResult =
    circularCoordinates(points, r, 0, 47)

  /** Circular coordinates (de Silva-Morozov-Vejdemo-Johansson) for one persistent H¹ class of `points`' own
    * Vietoris-Rips complex -- see `homology.CircularCoordinates.compute`'s own doc for `r`/`cocycleIndex`/`prime`'s
    * exact meaning and the full construction, and `h1Bars` above for how to find a valid `r`. Throws
    * `IllegalArgumentException` for an invalid `r`/`cocycleIndex`/`prime`, or `NoIntegerCocycleException` (a
    * `RuntimeException`, so it crosses MATLAB's Java bridge the same way `IllegalArgumentException` already does) if
    * the chosen class has no exact integer lift at `prime` -- see that exception's own doc for what to do about it
    * (usually: retry with a larger `prime`).
    */
  def circularCoordinates(
    points: Array[Array[Double]],
    r: Double,
    cocycleIndex: Int,
    prime: Int
  ): CircularCoordinatesResult =
    validatePoints(points)
    val result = CircularCoordinates.compute(EuclideanMetricSpace(points), r, cocycleIndex, prime)
    val thetaArray = Array.fill(points.length)(Double.NaN)
    result.theta.foreach((i, t) => thetaArray(i) = t)
    new CircularCoordinatesResult(thetaArray, result.birth, result.death, result.r, result.prime)

  def computeFromCubicalImage(shape: Array[Int], flatValues: Array[Double]): PersistenceResult =
    computeFromCubicalImage(shape, flatValues, Array.empty[String])

  /** Cubical persistence of a dense n-dimensional grid (an image or voxel volume): a flat, row-major array of
    * per-pixel/voxel values plus an explicit `shape` -- the same convention `CubicalImage.fromFlatArray` uses (last
    * axis fastest-varying, so `shape=(rows,cols)`/a flattened `Array[Array[Double]]` matches an ordinary 2D image).
    * `computeFromImage` below is a `double[][]`-typed 2D convenience wrapper over this, MATLAB's own natural matrix
    * shape for the common image case.
    *
    * There is no `"complex"` option here -- a cubical grid is a different SHAPE of input entirely (no metric space, no
    * point coordinates), not a different value for an existing option, so it gets its own entry point rather than a new
    * `"complex"` value on `computeFromPoints`/`computeFromDistanceMatrix`. Recognized options:
    *
    *   - `"engine"`: `"naive"` (default), `"chunks"`, `"cohomology"`, or `"fast-cubical"` -- `"ripser"` is never
    *     offered here: `PackedRipserCohomologyContext` is specialized to `Simplex[Int]` Vietoris-Rips complexes and has
    *     no notion of a cubical complex at all. `"fast-cubical"` (`homology.FastCubicalHomologyContext`, Le Breton-
    *     Szustakowski-Piraud's dual-graph union-find) is refused unless the grid's own ambient dimension is exactly 2
    *     (a 3D image needs `"naive"`/`"chunks"`/`"cohomology"` instead) -- see CLAUDE.md's Cubical complexes section.
    *   - `"maxDimension"`: integer, default is the grid's own ambient dimension (i.e. "give me everything"). Unlike
    *     `complex=vr`/`"cech"` above, a cubical grid's own top dimension is ALREADY naturally bounded by its ambient
    *     dimension (an image's own dimensionality) and is never artificially cut short the way an unbounded VR/Cech
    *     complex is -- so this option is purely an opt-in performance cap for a caller who only wants low-dimensional
    *     homology, not a correctness necessity.
    *   - `"sublevel"`: `"true"` (default) or `"false"` -- sublevel-set (ascending intensity) filtration, GUDHI/DIPHA/
    *     Perseus's own convention, or superlevel-set (`"false"` -- the standard "sublevel of -f is superlevel of f"
    *     trick, see `CubicalImage.scala`'s own doc). Reported birth/death values under `sublevel=false` are in
    *     NEGATED-intensity units, not raw pixel values -- documented, expected behavior of this trick, not a bug.
    *   - `"field"`/`"prime"`/`"epsilon"`: same as `computeFromPoints` above.
    */
  def computeFromCubicalImage(
    shape: Array[Int],
    flatValues: Array[Double],
    options: Array[String]
  ): PersistenceResult =
    validateShape(shape, flatValues)
    val opts = parseOptions(options)
    val sublevel = opts.get("sublevel").forall(v => parseBooleanOption("sublevel", v))
    val stream = CubicalImage.fromFlatArray(shape.toIndexedSeq, flatValues.toIndexedSeq, sublevel)
    dispatchCubical(opts, stream)

  def computeFromImage(pixels: Array[Array[Double]]): PersistenceResult =
    computeFromImage(pixels, Array.empty[String])

  /** 2D convenience over `computeFromCubicalImage`: `pixels(i)(j)` as a dense grid, shape `(pixels.length,
    * pixels(0).length)` -- MATLAB's own natural matrix type, so the common 2D image case needs no explicit
    * shape/flattening. See `computeFromCubicalImage` for recognized options; this delegates to it directly.
    */
  def computeFromImage(pixels: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validatePoints(pixels) // reuses the existing "non-empty, rectangular" check -- the same shape requirement
    val cols = pixels(0).length
    computeFromCubicalImage(Array(pixels.length, cols), pixels.flatten, options)

  // ---------------------------------------------------------------------------------------------------------------
  // option parsing
  // ---------------------------------------------------------------------------------------------------------------

  private val recognizedKeys = Set(
    "complex",
    "engine",
    "alphabackend",
    "maxdimension",
    "maxfiltrationvalue",
    "field",
    "prime",
    "epsilon",
    "sublevel",
    "numlandmarks",
    "witnessvariant",
    "landmarkselector",
    "landmarkseed",
    "nu",
    "dtmk",
    "dtmq",
    "dtmp",
    "sheehyepsilon",
    "edgecollapse"
  )

  /** `numLandmarks`/`landmarkSelector`/`landmarkSeed` only -- the STRICT allowlist `selectLandmarksFromPoints`/
    * `selectLandmarksFromDistanceMatrix` (step 1 of the two-step witness recipe) parse against, via
    * `parseOptionsWithKeys` below rather than the permissive `recognizedKeys` every one-shot method uses. This matters
    * more here than it would elsewhere: a caller who passes `numLandmarks` alongside an ALREADY-CHOSEN landmark array
    * in step 2 (`witnessFromLandmarksKeys` below, which deliberately excludes it) would otherwise have that option
    * silently dropped -- a quiet, easy-to-make footgun a strict, per-entry-point allowlist catches immediately instead.
    */
  private val landmarkSelectionKeys = Set("numlandmarks", "landmarkselector", "landmarkseed")

  /** Step 2 of the two-step witness recipe (`computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`):
    * landmarks are supplied directly, so `numLandmarks`/`landmarkSelector`/`landmarkSeed` are deliberately NOT here
    * (see `landmarkSelectionKeys`'s own doc for why silently accepting them would be worse than rejecting them).
    * `"complex"` IS allowed, but only ever checked against `"witness"` -- these methods are inherently
    * `complex=witness` (there is nothing else they could compute), but a CLI/MATLAB caller migrating from the one-shot
    * entry point will naturally still type `--complex witness`/`"complex","witness"` out of habit; accepting that exact
    * value and rejecting any OTHER value catches a genuine mismatch (e.g. a copy-pasted `complex=vr`) instead of
    * silently ignoring it.
    */
  private val witnessFromLandmarksKeys =
    Set("complex", "witnessvariant", "nu", "engine", "maxdimension", "maxfiltrationvalue", "field", "prime", "epsilon")

  private def parseOptionsWithKeys(options: Array[String], allowedKeys: Set[String]): Map[String, String] =
    if options.length % 2 != 0 then
      throw new IllegalArgumentException(
        s"options must be a flat key,value,key,value,... array (even length), got ${options.length} entries"
      )
    val m = mutable.Map.empty[String, String]
    var i = 0
    while i < options.length do
      val rawKey = options(i)
      val key = rawKey.toLowerCase
      if !allowedKeys.contains(key) then
        throw new IllegalArgumentException(
          s"unrecognized option '$rawKey'; recognized options are: ${allowedKeys.toSeq.sorted.mkString(", ")}"
        )
      m(key) = options(i + 1)
      i += 2
    m.toMap

  private def parseOptions(options: Array[String]): Map[String, String] =
    parseOptionsWithKeys(options, recognizedKeys)

  private def parseWitnessComplexOption(opts: Map[String, String]): Unit =
    opts.get("complex").foreach { raw =>
      if ComplexKind.parse(raw) != ComplexKind.Witness then
        throw new IllegalArgumentException(
          s"complex='$raw' is meaningless here: this entry point always computes a witness complex -- omit " +
            "'complex', or pass 'witness'"
        )
    }

  private def validateLandmarks(landmarks: Array[Int], n: Int): Unit =
    if landmarks.isEmpty then throw new IllegalArgumentException("landmarks must have at least one entry")
    landmarks.foreach { l =>
      if l == n then
        throw new IllegalArgumentException(
          s"landmark index $l is out of range [0, $n) -- indices are 0-based; did you pass a 1-based index?"
        )
      if l < 0 || l >= n then throw new IllegalArgumentException(s"landmark index $l is out of range [0, $n)")
    }
    if landmarks.distinct.length != landmarks.length then
      throw new IllegalArgumentException(
        "landmarks must not contain duplicate indices (WitnessGeometry would silently treat a repeated index " +
          "as two distinct landmarks at distance zero from each other)"
      )

  private def validatePoints(points: Array[Array[Double]]): Unit =
    if points.isEmpty then throw new IllegalArgumentException("points must have at least one row")
    val d = points(0).length
    if points.exists(_.length != d) then
      throw new IllegalArgumentException("every row of points must have the same number of columns")

  private def validateSquare(distances: Array[Array[Double]]): Unit =
    if distances.isEmpty then throw new IllegalArgumentException("distances must have at least one row")
    val n = distances.length
    if distances.exists(_.length != n) then
      throw new IllegalArgumentException(s"distances must be square ($n x $n), got a ragged/non-square array")

  private def explicitMetricSpace(distances: Array[Array[Double]]): FiniteMetricSpace[Int] =
    ExplicitMetricSpace(distances.toIndexedSeq.map(_.toIndexedSeq))

  private def validateShape(shape: Array[Int], flatValues: Array[Double]): Unit =
    if shape.isEmpty then throw new IllegalArgumentException("shape must have at least one axis")
    if shape.exists(_ <= 0) then throw new IllegalArgumentException("shape must be strictly positive along every axis")
    val expected = shape.map(_.toLong).product
    if flatValues.length.toLong != expected then
      throw new IllegalArgumentException(
        s"flatValues has ${flatValues.length} entries, expected $expected for shape ${shape.mkString("[", ",", "]")}"
      )

  private def parseBooleanOption(name: String, raw: String): Boolean =
    raw.toLowerCase match
      case "true"  => true
      case "false" => false
      case other   => throw new IllegalArgumentException(s"option '$name' must be 'true' or 'false', got '$other'")

  // ---------------------------------------------------------------------------------------------------------------
  // dispatch: string options -> concrete engine/field choice
  // ---------------------------------------------------------------------------------------------------------------

  /** `"witnessVariant"` alone, defaulting to `"lazy"` -- shared by the one-shot `dispatch` (only when
    * `complex=witness`) and step 2's `dispatchWitnessFromLandmarks` (always, since those entry points are inherently
    * witness-only).
    */
  private def resolveWitnessVariant(opts: Map[String, String]): WitnessVariantKind =
    WitnessVariantKind.parse(opts.getOrElse("witnessvariant", "lazy"))

  /** `"engine"`, defaulted and validated against `witnessVariant`: `witnessVariant=general` defaults to `naive` (not a
    * flag complex, same reasoning as `complex=alpha`/`complex=cech`'s own defaults) and refuses `ripser`/`chunks`
    * outright; `witnessVariant=lazy` defaults to `ripser` (it really is a flag complex -- see
    * `streams.WitnessMetricSpace`'s own doc) and allows all four. Pulled out of `dispatch`'s own
    * `(complex, engine) match` refusal block so step 2 gets the identical default-and-refusal behavior without
    * re-deriving it.
    */
  private def resolveWitnessEngine(opts: Map[String, String], witnessVariant: WitnessVariantKind): EngineKind =
    val engine = EngineKind.parse(
      opts.getOrElse("engine", if witnessVariant == WitnessVariantKind.General then "naive" else "ripser")
    )
    if engine == EngineKind.FastCubical then
      throw new IllegalArgumentException(
        "engine=fast-cubical is not offered for complex=witness (either variant): FastCubicalHomologyContext is " +
          "specialized to CubicalGridStream and has no notion of a witness complex at all."
      )
    if witnessVariant == WitnessVariantKind.General then
      engine match
        case EngineKind.Ripser =>
          throw new IllegalArgumentException(
            "engine=ripser cannot be used with complex=witness/witnessVariant=general: the general witness " +
              "complex is not a flag complex (see streams.WitnessCofaceSimplexStream's own doc), so " +
              "PackedRipserCohomologyContext's diameter-based optimizations do not apply -- use " +
              "witnessVariant=lazy instead, or engine=naive/cohomology."
          )
        case EngineKind.Chunks =>
          throw new IllegalArgumentException(
            "engine=chunks is not offered for complex=witness/witnessVariant=general: use witnessVariant=lazy " +
              "instead, or engine=naive/cohomology."
          )
        case _ => ()
    engine

  private def resolveWitnessNu(opts: Map[String, String]): Int =
    opts.get("nu").map(parseIntOption("nu", _)).getOrElse(2)

  /** `"numLandmarks"` (required) + `"landmarkSelector"`/`"landmarkSeed"` -- landmark SELECTION, shared by the one-shot
    * `dispatch` (only when `complex=witness`) and step 1 (`selectLandmarksFromPoints`/
    * `selectLandmarksFromDistanceMatrix`, always). Step 2 never calls this: its landmarks are supplied directly, not
    * selected.
    */
  private def resolveLandmarkSelection(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int]
  ): LandmarkSelection =
    val numLandmarks = opts
      .get("numlandmarks")
      .map(parseIntOption("numLandmarks", _))
      .getOrElse(throw new IllegalArgumentException("option 'numLandmarks' is required"))
    opts.getOrElse("landmarkselector", "maxmin").toLowerCase match
      case "maxmin" => LandmarkSelector.maxmin(metricSpace, numLandmarks)
      case "random" =>
        val seed = opts.get("landmarkseed").map(parseIntOption("landmarkSeed", _)).getOrElse(0)
        LandmarkSelector.random(metricSpace, numLandmarks, seed.toLong)
      case other =>
        throw new IllegalArgumentException(s"unrecognized landmarkSelector '$other'; expected 'maxmin' or 'random'")

  /** `"field"`/`"prime"`/`"epsilon"` -> a resolved coefficient type `C`, shared by `dispatch` (the one-shot path) and
    * step 2's `dispatchWitnessFromLandmarks`, so the `Z`/`R` branching -- and the `given C is Field` each branch brings
    * into scope -- exists in exactly one place for both. (`dispatchCubical` has its own, still-separate copy of the
    * identical `Z`/`R` match -- not routed through this helper in this pass, since the cubical path isn't part of what
    * this arc touches or tests; a future session could fold it in too.) `compute` is a polymorphic function value so
    * `C` (and the `given` it needs) stay resolved together, at the call site, rather than threading a `C` type
    * parameter and a separate `toDouble: C => Double` through every caller by hand.
    */
  private def dispatchByField[T](opts: Map[String, String])(compute: [C] => (C => Double) => (C is Field) ?=> T): T =
    CoefficientKind.parse(opts.getOrElse("field", "z")) match
      case CoefficientKind.Z =>
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(2)
        val ff = new FiniteField(prime)
        import ff.given
        compute[ff.Fp](_.toInt.toDouble)
      case CoefficientKind.R =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        compute[Double](identity)

  private def dispatch(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]]
  ): PersistenceResult =
    val complex = ComplexKind.parse(opts.getOrElse("complex", "vr"))

    // Parsed BEFORE the engine default below, since complex=witness's own default depends on it (lazy behaves
    // like complex=vr -- a flag complex, defaults to ripser; general behaves like complex=alpha/cech -- not a
    // flag complex, defaults to naive).
    val witnessVariant =
      if complex == ComplexKind.Witness then resolveWitnessVariant(opts)
      else WitnessVariantKind.Lazy // unused for any other complex; a harmless placeholder, never consulted below

    val isDtm = complex == ComplexKind.DtmRips || complex == ComplexKind.DtmAlpha
    val engine =
      if complex == ComplexKind.Witness then resolveWitnessEngine(opts, witnessVariant)
      else
        EngineKind.parse(
          opts.getOrElse(
            "engine",
            if complex == ComplexKind.Alpha || complex == ComplexKind.Cech || isDtm ||
              complex == ComplexKind.SheehyRips
            then "naive"
            else "ripser"
          )
        )
    if engine == EngineKind.FastCubical then
      throw new IllegalArgumentException(
        "engine=fast-cubical is only valid for computeFromCubicalImage/computeFromImage: " +
          "FastCubicalHomologyContext is specialized to CubicalGridStream and has no notion of a point cloud or " +
          "distance matrix at all (unlike ripser/naive/chunks/cohomology, which every complex here can offer some " +
          "subset of)."
      )
    (complex, engine) match
      case (ComplexKind.Alpha, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=alpha: PackedRipserCohomologyContext computes persistent " +
            "cohomology directly from a metric space's Vietoris-Rips complex and has no notion of an alpha complex at all."
        )
      case (ComplexKind.Alpha, EngineKind.Chunks) =>
        throw new IllegalArgumentException(
          "engine=chunks is not offered for complex=alpha: this exact combination is a known stall/out-of-memory " +
            "risk in the underlying library (see CLAUDE.md and HomologySpec's BarcodeRegressionSpec, which stays " +
            "skipped for exactly this reason). Use engine=naive for alpha complexes."
        )
      case (ComplexKind.Cech, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=cech: PackedRipserCohomologyContext's apparent-pairs and " +
            "insertionDiameter optimizations are proven specifically for the max-pairwise-distance (Vietoris-Rips) " +
            "functional, not Cech's circumradius -- see CLAUDE.md's Cech complexes section. Use engine=naive or " +
            "engine=chunks for Cech complexes."
        )
      case (ComplexKind.DtmRips, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=dtm-rips: PackedRipserCohomologyContext assumes vertices are " +
            "born at filtration 0 and uses insertionDiameter, an incremental formula proven only for the plain " +
            "max-pairwise-distance functional -- neither holds for the DTM-weighted filtration. Use engine=naive, " +
            "engine=chunks, or engine=cohomology for complex=dtm-rips."
        )
      case (ComplexKind.SheehyRips, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=sheehy-rips: a simplex's filtration value here is not the " +
            "maximum ambient pairwise distance among its vertices (some pairs are excluded outright, others take a " +
            "sparsified value), so PackedRipserCohomologyContext's insertionDiameter/apparent-pairs machinery does " +
            "not apply -- see streams.SheehyRipsSimplexStream's own doc. Use engine=naive, engine=chunks, or " +
            "engine=cohomology for complex=sheehy-rips."
        )
      case (ComplexKind.DtmAlpha, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=dtm-alpha: PackedRipserCohomologyContext has no notion of an " +
            "alpha complex at all -- same reason as complex=alpha."
        )
      case (ComplexKind.DtmAlpha, EngineKind.Chunks) =>
        throw new IllegalArgumentException(
          "engine=chunks is not offered for complex=dtm-alpha: same known stall/out-of-memory risk as " +
            "complex=alpha (they share the same underlying AlphaComplexDQP machinery). Use engine=naive."
        )
      case _ => () // Witness's own refusals already enforced inside resolveWitnessEngine above.

    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(2)
    val maxFiltrationValue: Option[Double] =
      opts.get("maxfiltrationvalue").map(parseDoubleOption("maxFiltrationValue", _))
    val alphaBackend = opts.getOrElse("alphabackend", "helix")

    val edgeCollapse = opts.get("edgecollapse").exists(v => parseBooleanOption("edgeCollapse", v))
    if edgeCollapse && complex != ComplexKind.VR then
      throw new IllegalArgumentException(
        s"option 'edgeCollapse' is only valid for complex=vr (streams.EdgeCollapse operates on a flag complex's " +
          s"own 1-skeleton) -- got complex=${opts.getOrElse("complex", "vr")}"
      )

    val witnessLandmarks: IndexedSeq[Int] =
      if complex == ComplexKind.Witness then resolveLandmarkSelection(opts, metricSpace).landmarks
      else IndexedSeq.empty // unused for any other complex
    val witnessNu = if complex == ComplexKind.Witness then resolveWitnessNu(opts) else 2

    val dtmK: Int =
      if isDtm then
        parseIntOption(
          "dtmK",
          opts.getOrElse(
            "dtmk",
            throw new IllegalArgumentException("option 'dtmK' is required for complex=dtm-rips/complex=dtm-alpha")
          )
        )
      else 0 // unused for any other complex
    val dtmQ = opts.get("dtmq").map(parseDoubleOption("dtmQ", _)).getOrElse(2.0)
    val dtmP = opts.get("dtmp").map(parseDoubleOption("dtmP", _)).getOrElse(1.0)

    val sheehyEpsilon: Double =
      if complex == ComplexKind.SheehyRips then
        parseDoubleOption(
          "sheehyEpsilon",
          opts.getOrElse(
            "sheehyepsilon",
            throw new IllegalArgumentException("option 'sheehyEpsilon' is required for complex=sheehy-rips")
          )
        )
      else 0.0 // unused for any other complex

    dispatchByField(opts) { [C] => (toDouble: C => Double) =>
      computeGeneric[C](
        metricSpace,
        points,
        complex,
        engine,
        alphaBackend,
        maxDimension,
        maxFiltrationValue,
        witnessVariant,
        witnessLandmarks,
        witnessNu,
        dtmK,
        dtmQ,
        dtmP,
        sheehyEpsilon,
        edgeCollapse,
        toDouble
      )
    }

  private def parseIntOption(name: String, raw: String): Int =
    raw.toIntOption.getOrElse(throw new IllegalArgumentException(s"option '$name' must be an integer, got '$raw'"))

  private def parseDoubleOption(name: String, raw: String): Double =
    raw.toDoubleOption.getOrElse(throw new IllegalArgumentException(s"option '$name' must be a number, got '$raw'"))

  // ---------------------------------------------------------------------------------------------------------------
  // per-field computation, shared across both coefficient-field choices
  // ---------------------------------------------------------------------------------------------------------------

  private def computeGeneric[C](
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]],
    complex: ComplexKind,
    engine: EngineKind,
    alphaBackend: String,
    requestedMaxDimension: Int,
    maxFiltrationValue: Option[Double],
    witnessVariant: WitnessVariantKind,
    witnessLandmarks: IndexedSeq[Int],
    witnessNu: Int,
    dtmK: Int,
    dtmQ: Double,
    dtmP: Double,
    sheehyEpsilon: Double,
    edgeCollapse: Boolean,
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    complex match
      case ComplexKind.VR =>
        // Computing H_k needs (k+1)-dimensional chains -- H_k = ker(d_k)/im(d_{k+1}), so with no (k+1)-chains at
        // all there is no way to tell a genuine k-cycle from one that a not-yet-built (k+1)-simplex would have
        // killed. Both `PackedRipserCohomologyContext` and `PersistenceInChunksContext` now handle this internally
        // (their own `maxDimension`/`maxDim` constructor parameters mean "top homological degree reported,"
        // fixed at the source -- see .claude/WORKLOG-maxdim-semantics-fix.md), so `engine=Ripser`/`Chunks`
        // both pass `requestedMaxDimension` straight through with no adjustment; `fromBars`'s
        // filter below is a defensive no-op for them now, not load-bearing. `engine=Naive`/`Cohomology` still need
        // the manual `buildDimension = requestedMaxDimension + 1` dance via `PersistenceEngine`'s own adapters
        // below: neither `SimplicialHomologyContext` nor `CellularCohomologyContext` has a `maxDimension` of its
        // own at all -- the cap lives entirely in the stream each is handed.
        // `edgeCollapse` replaces the metric space every engine branch below consumes (including `engine=ripser`'s
        // own direct `PackedRipserCohomologyContext(collapsedMetricSpace, ...)` call, which takes a metric space,
        // not a stream) -- one swap here benefits every engine uniformly, the same "wire once" shape the boundary
        // matrix below already uses for a different property of the complex. `maxFiltrationValue` is passed
        // straight through to `EdgeCollapse.collapse` too: a truncated collapse (only edges within that bound
        // ever considered) composes correctly with the SAME bound applied again below when building the actual
        // stream -- see `streams.EdgeCollapse`'s own doc for why restricting twice to the same bound is safe.
        val collapsedMetricSpace: FiniteMetricSpace[Int] =
          if edgeCollapse then EdgeCollapse.collapse(metricSpace, maxFiltrationValue) else metricSpace
        // Shared by every engine branch below: the boundary matrix is a property of the complex, not of which
        // reduction algorithm ran over it, so it's built ONCE here (the same construction engine=Naive/Cohomology
        // already need below) and reused -- see `buildBoundaryMatrix`'s own doc.
        val vrCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val vrStreamForBoundary =
          LimitedCofaceSimplexStream(
            EnumeratingCofaceSimplexStream(collapsedMetricSpace, maxFiltrationValue = maxFiltrationValue),
            requestedMaxDimension + 1
          )
        val vrBoundaryMatrixOf =
          () =>
            buildBoundaryMatrix[Simplex[Int], C](
              vrStreamForBoundary.iterator.toIndexedSeq,
              vrCellVertices,
              toDouble,
              vrStreamForBoundary.filtrationValue
            )

        engine match
          case EngineKind.Ripser =>
            // Backed by PackedRipserCohomologyContext, not RipserCohomologyContext -- see CLAUDE.md and that
            // class's own doc: same algorithm, measured faster and far leaner on memory. RipserCohomologyContext
            // stays in the codebase only as PackedRipserCohomologyContext's cross-validation test oracle, not as
            // a second production option. Doesn't go through `PersistenceEngine`: it consumes a metric space
            // directly, not a stream -- see that trait's own doc for why this is an honest asymmetry.
            val ctx = PackedRipserCohomologyContext[C](
              collapsedMetricSpace,
              requestedMaxDimension,
              maxFiltrationValue = maxFiltrationValue
            )
            // A packed bar's annotation is keyed by DiameterIndex, which carries a combinatorial index but not its
            // own vertex count -- unlike Simplex[Int]'s `.underlying`, decoding needs `size` from outside. Every
            // cell in one bar's cocycle is a simplex of the SAME dimension as the bar itself (`bar.dim`), so
            // `dim + 1` (vertex count) is exactly the `size` `si.decodeToArray` needs -- known from the bar, not
            // guessed.
            fromBars[ctx.DiameterIndex, C](
              ctx.persistentCohomology(),
              (dim, cell) => ctx.si.decodeToArray(cell.index, dim + 1),
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.Naive =>
            // EnumeratingCofaceSimplexStream has no dimension cap of its own (only a filtration-value one) --
            // LimitedCofaceSimplexStream is what actually enforces a dimension cap, the same wrapping
            // RipserCohomologySpec's own naiveBars helper uses. Reuses vrStreamForBoundary directly -- an
            // identical construction to what this branch built for itself before the boundary matrix existed.
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(vrStreamForBoundary),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // barcodeAt, not diagramAt: CellularPersistenceInChunksContext now records a REAL representative for
            // every bar (any dimension <= requestedMaxDimension), via the SAME fromBars/Option[Chain] path
            // Ripser/Naive already use above -- see PersistenceEngine's own doc for how (it reuses this class's
            // OWN already-computed reduction state -- boundaries/cleared/paired/killer -- incrementally, via
            // vcolOf, rather than delegating to a second independent engine) and .claude/CLAUDE.md's
            // coefficients-and-representatives principle.
            val stream = EnumeratingCofaceSimplexStream(collapsedMetricSpace, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(stream),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            // CellularCohomologyContext, generic over CellT: OrderedCell -- see
            // .claude/DESIGN-generic-cohomology.md. Same "build one dimension higher, drop it via fromBars"
            // dance as engine=Naive above, for the identical reason (H_k needs (k+1)-dimensional chains); this
            // engine has no maxDim/maxDimension parameter of its own at all (deliberately -- see that class's
            // own doc), so the cap lives entirely in the stream, exactly like engine=Naive. Reuses
            // vrStreamForBoundary directly, same as engine=Naive above.
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(vrStreamForBoundary),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.FastCubical =>
            // dispatch() already rejects this for complex=vr before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=vr")
      case ComplexKind.Alpha =>
        // No dimension cap is applied here at all, on purpose: an alpha complex's chain complex terminates on its
        // own (bounded by ambient dimension, or higher under cosphericity -- see CLAUDE.md), it is never
        // artificially cut off the way a VR complex is by `requestedMaxDimension` above, so its own top dimension
        // is genuine information, not a truncation artifact -- nothing to drop. `requestedMaxDimension` is
        // correctly ignored here (see the class doc).
        val pts = points.getOrElse(
          throw new IllegalArgumentException(
            "complex=alpha requires point coordinates -- use computeFromPoints, not computeFromDistanceMatrix"
          )
        )
        val alphaStream = AlphaShapes(pts.toIndexedSeq, alphaBackend)
        val alphaCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val alphaBoundaryMatrixOf =
          () =>
            buildBoundaryMatrix[Simplex[Int], C](
              alphaStream.iterator.toIndexedSeq,
              alphaCellVertices,
              toDouble,
              alphaStream.filtrationValue
            )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(alphaStream),
              alphaCellVertices,
              toDouble,
              Int.MaxValue,
              alphaBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            // No stream-level dimension cap here either, for the same reason as engine=Naive above: an alpha
            // complex's chain complex terminates on its own. CellularCohomologyContext accepts `alphaStream`
            // directly -- it's a StratifiedSimplexStream[Int, Double], hence a CellStream[Simplex[Int], Double].
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(alphaStream),
              alphaCellVertices,
              toDouble,
              Int.MaxValue,
              alphaBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical =>
            // dispatch() already rejects all of these for complex=alpha before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=alpha")
      case ComplexKind.Cech =>
        // Cech grows unboundedly in dimension just like VR -- unlike alpha/cubical, its own top dimension is NOT
        // naturally bounded (a Cech complex over n points can, in principle, reach an (n-1)-simplex) -- so it needs
        // the SAME "build one dimension higher than requested, then drop it" dance the VR case above uses, for the
        // identical reason: H_k needs (k+1)-dimensional chains to tell a genuine k-cycle from one a not-yet-built
        // (k+1)-simplex would have killed. `maxFiltrationValue` here is in CECH RADIUS units (see class doc above),
        // not a VR diameter -- `CechCofaceSimplexStream` consumes it directly, no doubling.
        val pts = points.getOrElse(
          throw new IllegalArgumentException(
            "complex=cech requires point coordinates -- use computeFromPoints, not computeFromDistanceMatrix"
          )
        )
        val euclideanMetricSpace = EuclideanMetricSpace(pts)
        val cechCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val cechStreamForBoundary = LimitedCofaceSimplexStream(
          CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue),
          requestedMaxDimension + 1
        )
        val cechBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            cechStreamForBoundary.iterator.toIndexedSeq,
            cechCellVertices,
            toDouble,
            cechStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(cechStreamForBoundary),
              cechCellVertices,
              toDouble,
              requestedMaxDimension,
              cechBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // CellularPersistenceInChunksContext handles the "+1" dance internally (its own maxDim constructor
            // parameter means "top reported degree," fixed at the source -- see .claude/WORKLOG-maxdim-semantics-
            // fix.md), and CechCofaceSimplexStream's own iterateDimension is already naturally bounded (inherited
            // from RipserCofaceSimplexStream's `d < metricSpace.size` guard), so no LimitedCofaceSimplexStream
            // wrapping is needed here -- mirroring engine=Chunks's own complex=vr case above exactly. Cross-
            // validated against the naive engine directly on Cech streams in CechStreamSpec (not assumed to carry
            // over from VR/cubical/simplicial-set validation, since this combination had never been exercised
            // before).
            val stream = CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(stream),
              cechCellVertices,
              toDouble,
              requestedMaxDimension,
              cechBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            // Same shape as engine=Naive above for complex=cech -- Cech's top dimension is not naturally
            // bounded, so the same "build one dimension higher via LimitedCofaceSimplexStream, drop it via
            // fromBars" dance applies, for the identical reason as complex=vr's own engine=Cohomology branch.
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(cechStreamForBoundary),
              cechCellVertices,
              toDouble,
              requestedMaxDimension,
              cechBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical =>
            // dispatch() already rejects both of these for complex=cech before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=cech")
      case ComplexKind.DtmRips =>
        // Just as unboundedly deep as complex=vr/complex=cech -- the same "build one dimension higher, drop it"
        // dance for engine=Naive/Cohomology, for the identical reason (H_k needs (k+1)-dimensional chains).
        // Unlike complex=alpha/complex=cech, needs no real coordinates -- streams.DistanceToMeasure only needs a
        // FiniteMetricSpace, so this works from computeFromDistanceMatrix too (BruteForce k-NN, not JVPTree:
        // metricSpace here may not obey the triangle inequality -- see streams.DistanceToMeasure's own doc).
        val f = DistanceToMeasure(metricSpace, dtmK, dtmQ)
        val dtmStream = DtmRipsSimplexStream(metricSpace, f, dtmP, maxFiltrationValue = maxFiltrationValue)
        val dtmCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val dtmStreamForBoundary = LimitedCofaceSimplexStream(dtmStream, requestedMaxDimension + 1)
        val dtmBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            dtmStreamForBoundary.iterator.toIndexedSeq,
            dtmCellVertices,
            toDouble,
            dtmStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(dtmStreamForBoundary),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(dtmStream),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(dtmStreamForBoundary),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical =>
            // dispatch() already rejects both of these for complex=dtm-rips before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=dtm-rips")
      case ComplexKind.SheehyRips =>
        // Just as unboundedly deep as complex=vr/complex=cech/complex=dtm-rips -- the same "build one dimension
        // higher, drop it" dance for engine=Naive/Cohomology, for the identical reason (H_k needs (k+1)-dimensional
        // chains). Needs no real coordinates -- streams.SheehyRipsSimplexStream only needs a FiniteMetricSpace (the
        // greedy permutation it builds on is purely metric), so this works from computeFromDistanceMatrix too.
        // maxFiltrationValue is passed straight through: SheehyRipsSimplexStream's own constructor always clamps
        // it to maxFiniteFiltrationValue regardless (see that class's own doc), so there is no separate "resolve
        // the omitted-key default here" step the way complex=vr/complex=cech need.
        val sheehyStream = SheehyRipsSimplexStream(metricSpace, sheehyEpsilon, maxFiltrationValue = maxFiltrationValue)
        val sheehyCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val sheehyStreamForBoundary = LimitedCofaceSimplexStream(sheehyStream, requestedMaxDimension + 1)
        val sheehyBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            sheehyStreamForBoundary.iterator.toIndexedSeq,
            sheehyCellVertices,
            toDouble,
            sheehyStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(sheehyStreamForBoundary),
              sheehyCellVertices,
              toDouble,
              requestedMaxDimension,
              sheehyBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(sheehyStream),
              sheehyCellVertices,
              toDouble,
              requestedMaxDimension,
              sheehyBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(sheehyStreamForBoundary),
              sheehyCellVertices,
              toDouble,
              requestedMaxDimension,
              sheehyBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical =>
            // dispatch() already rejects both of these for complex=sheehy-rips before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=sheehy-rips")
      case ComplexKind.DtmAlpha =>
        // No dimension cap applied, exactly like complex=alpha -- see that case's own comment.
        val pts = points.getOrElse(
          throw new IllegalArgumentException(
            "complex=dtm-alpha requires point coordinates -- use computeFromPoints, not computeFromDistanceMatrix"
          )
        )
        val ac =
          AlphaComplexDQP.dtm(pts, dtmK, Double.PositiveInfinity, pts.headOption.map(_.length).getOrElse(0), dtmQ)
        val dtmAlphaStream = AlphaComplexDQPStream(pts, ac)
        val dtmAlphaCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val dtmAlphaBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            dtmAlphaStream.iterator.toIndexedSeq,
            dtmAlphaCellVertices,
            toDouble,
            dtmAlphaStream.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(dtmAlphaStream),
              dtmAlphaCellVertices,
              toDouble,
              Int.MaxValue,
              dtmAlphaBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(dtmAlphaStream),
              dtmAlphaCellVertices,
              toDouble,
              Int.MaxValue,
              dtmAlphaBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical =>
            // dispatch() already rejects all of these for complex=dtm-alpha before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=dtm-alpha")
      case ComplexKind.Witness =>
        computeWitnessFromLandmarks[C](
          metricSpace,
          witnessLandmarks,
          witnessVariant,
          engine,
          witnessNu,
          requestedMaxDimension,
          maxFiltrationValue,
          toDouble
        )

  /** The actual witness-complex computation, given an ALREADY-RESOLVED landmark set -- shared by `computeGeneric`'s
    * `ComplexKind.Witness` case (one-shot: landmarks were just selected from `numLandmarks`/`landmarkSelector` a few
    * lines up in `dispatch`) and `dispatchWitnessFromLandmarks` below (step 2 of the two-step recipe: `landmarks` is
    * whatever the caller passed to `computeFromPointsAndLandmarks`/ `computeFromDistanceMatrixAndLandmarks`, already
    * validated). Neither caller-specific concern (how landmarks were obtained, how `witnessVariant`/`engine`/`nu` were
    * parsed and defaulted) appears here at all -- this function only knows how to build a barcode from a metric space
    * and an already-decided landmark set.
    *
    * Both variants grow unboundedly in dimension just like VR/Cech (up to `landmarks.size - 1`), so naive/ cohomology
    * need the same "build one dimension higher, drop it via fromBars" dance those use. Cells are `Simplex[Int]` over
    * LOCAL landmark indices (`0 until landmarks.size`) -- every `cellVertices` below maps back through `landmarks(i)`
    * to the caller's own ambient point cloud, exactly the translation
    * `streams.LazyWitnessSimplexStream`/`WitnessCofaceSimplexStream`'s own docs call for.
    */
  private def computeWitnessFromLandmarks[C](
    metricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int],
    witnessVariant: WitnessVariantKind,
    engine: EngineKind,
    nu: Int,
    requestedMaxDimension: Int,
    maxFiltrationValue: Option[Double],
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    val cellVertices: (Int, Simplex[Int]) => Array[Int] =
      (_, cell) => cell.underlying.toArray.map(landmarks)
    witnessVariant match
      case WitnessVariantKind.Lazy =>
        // Shared across all four engine branches below, same reasoning as complex=vr's own vrBoundaryMatrixOf.
        val lazyStreamForBoundary = LimitedCofaceSimplexStream(
          LazyWitnessSimplexStream(metricSpace, landmarks, nu, maxFiltrationValue = maxFiltrationValue),
          requestedMaxDimension + 1
        )
        val lazyBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            lazyStreamForBoundary.iterator.toIndexedSeq,
            cellVertices,
            toDouble,
            lazyStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Ripser =>
            // The lazy witness complex IS a flag complex under WitnessMetricSpace's own "distance" -- exactly
            // the case PackedRipserCohomologyContext is proven for (any FiniteMetricSpace[Int] diameter), not
            // VR-specific at all despite the class's own name -- see streams.WitnessMetricSpace's own doc and
            // WitnessStreamSpec's direct cross-check against the naive engine.
            val geometry = WitnessGeometry(metricSpace, landmarks)
            val wms = WitnessMetricSpace(geometry, nu)
            val ctx =
              PackedRipserCohomologyContext[C](wms, requestedMaxDimension, maxFiltrationValue = maxFiltrationValue)
            fromBars[ctx.DiameterIndex, C](
              ctx.persistentCohomology(),
              (dim, cell) => ctx.si.decodeToArray(cell.index, dim + 1).map(landmarks),
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(lazyStreamForBoundary),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // No LimitedCofaceSimplexStream wrapping needed -- PersistenceInChunksContext handles the "+1"
            // dance internally, and LazyWitnessSimplexStream's own iterateDimension is already naturally
            // bounded (inherited from RipserCofaceSimplexStream), mirroring complex=cech's own chunks case.
            val stream = LazyWitnessSimplexStream(metricSpace, landmarks, nu, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(stream),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(lazyStreamForBoundary),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.FastCubical =>
            // Both dispatch() (one-shot) and dispatchWitnessFromLandmarks (step 2) already reject this via
            // resolveWitnessEngine before this method is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for witnessVariant=lazy")
      case WitnessVariantKind.General =>
        // Not a flag complex -- minimumEnclosingRadius is not a valid truncation here (see
        // streams.WitnessCofaceSimplexStream's own doc), so an unset maxFiltrationValue means +Infinity,
        // NOT "fall back to the metric space's own enclosing radius" the way every other complex above does.
        val geometry = WitnessGeometry(metricSpace, landmarks)
        val resolvedMaxFiltrationValue = maxFiltrationValue.getOrElse(Double.PositiveInfinity)
        val generalStreamForBoundary = LimitedCofaceSimplexStream(
          WitnessCofaceSimplexStream(geometry, resolvedMaxFiltrationValue),
          requestedMaxDimension + 1
        )
        val generalBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            generalStreamForBoundary.iterator.toIndexedSeq,
            cellVertices,
            toDouble,
            generalStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(generalStreamForBoundary),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              generalBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(generalStreamForBoundary),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              generalBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical =>
            // Both dispatch() (one-shot) and dispatchWitnessFromLandmarks (step 2) already reject these via
            // resolveWitnessEngine before this method is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for witnessVariant=general")

  /** Step 2 of the two-step witness recipe: `computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`
    * both parse their own (strict-allowlist) options here, then delegate to `computeWitnessFromLandmarks` -- the same
    * core the one-shot `dispatch`/`computeGeneric` path uses, just with `landmarks` supplied directly instead of
    * resolved from `numLandmarks`/`landmarkSelector`.
    */
  private def dispatchWitnessFromLandmarks(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int]
  ): PersistenceResult =
    parseWitnessComplexOption(opts)
    val witnessVariant = resolveWitnessVariant(opts)
    val engine = resolveWitnessEngine(opts, witnessVariant)
    val nu = resolveWitnessNu(opts)
    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(2)
    val maxFiltrationValue: Option[Double] =
      opts.get("maxfiltrationvalue").map(parseDoubleOption("maxFiltrationValue", _))
    dispatchByField(opts) { [C] => (toDouble: C => Double) =>
      computeWitnessFromLandmarks[C](
        metricSpace,
        landmarks,
        witnessVariant,
        engine,
        nu,
        maxDimension,
        maxFiltrationValue,
        toDouble
      )
    }

  // ---------------------------------------------------------------------------------------------------------------
  // dispatch for computeFromCubicalImage/computeFromImage -- a separate function from dispatch/computeGeneric
  // above (not a new "complex" branch inside them) because a cubical grid carries no FiniteMetricSpace[Int] at
  // all, the type dispatch()/computeGeneric are built around.
  // ---------------------------------------------------------------------------------------------------------------

  private def dispatchCubical(opts: Map[String, String], stream: CubicalGridStream): PersistenceResult =
    val engine = EngineKind.parse(opts.getOrElse("engine", "naive"))
    if engine == EngineKind.Ripser then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used for a cubical complex: PackedRipserCohomologyContext is specialized to " +
          "Simplex[Int] Vietoris-Rips complexes and has no notion of a cubical complex at all. Use engine=naive, " +
          "engine=chunks, engine=cohomology, or (ambient dimension 2 only) engine=fast-cubical."
      )
    // FastCubicalHomologyContext's own `require` throws IllegalArgumentException too, but with a message written
    // for a library caller who already has a `CubicalGridStream` in hand, not a MATLAB/CLI caller who only
    // supplied a `shape`/`flatValues` array -- catching it here first gives an error that names the actual
    // option/argument to change.
    if engine == EngineKind.FastCubical && stream.ambientDim != 2 then
      throw new IllegalArgumentException(
        s"engine=fast-cubical currently supports ambient dimension 2 only (FastCubicalHomologyContext's own dual-" +
          s"graph union-find has no dimension-3 formulation yet -- see .claude/DESIGN-fast-cubical-engine.md), " +
          s"got a ${stream.ambientDim}-dimensional shape. Use engine=naive, engine=chunks, or engine=cohomology " +
          s"for a 3D image."
      )
    // Default: the grid's own ambient dimension, i.e. "give me everything" -- correctly parallel to complex=alpha
    // above (a cubical grid's own top dimension is already naturally bounded, never artificially truncated the
    // way VR/Cech are), NOT to complex=vr's default of 2.
    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(stream.ambientDim)

    CoefficientKind.parse(opts.getOrElse("field", "z")) match
      case CoefficientKind.Z =>
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(2)
        val ff = new FiniteField(prime)
        import ff.given
        computeCubicalGeneric[ff.Fp](stream, engine, maxDimension, _.toInt.toDouble)
      case CoefficientKind.R =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        computeCubicalGeneric[Double](stream, engine, maxDimension, identity)

  private def computeCubicalGeneric[C](
    stream: CubicalGridStream,
    engine: EngineKind,
    maxDimension: Int,
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    // cellVertices reports a Cube's own doubled-coordinate encoding, not vertex indices -- see
    // PersistenceResult.cycleVertices's own doc for the decode rule and why this differs from the
    // Simplex[Int]-based complexes above.
    val cellVertices: (Int, Cube) => Array[Int] = (_, cell) => cell.encoded.toArray
    val boundaryMatrixOf =
      () => buildBoundaryMatrix[Cube, C](stream.iterator.toIndexedSeq, cellVertices, toDouble, stream.filtrationValue)
    engine match
      case EngineKind.Naive =>
        // No dimension cap is applied to the stream itself, on purpose, mirroring complex=alpha above: a cubical
        // grid's own chain complex terminates on its own (bounded by its ambient dimension), so it is never
        // artificially cut short the way a VR/Cech complex is -- nothing to build one dimension higher for.
        // CellularHomologyContext[Cube, ...] has no maxDim of its own at all, same as Simplex[Int].
        fromBars[Cube, C](
          PersistenceEngine.naive[Cube, C].barcode(stream),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Chunks =>
        // Unlike the naive path above, maxDimension IS passed through here as a genuine, correct truncation --
        // CellularPersistenceInChunksContext handles the "+1" dance internally (see .claude/WORKLOG-maxdim-
        // semantics-fix.md), so this can skip real work for a caller who only wants low-dimensional homology, not
        // just filter what's reported after the fact.
        fromBars[Cube, C](
          PersistenceEngine.chunks[Cube, C](maxDimension).barcode(stream),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Cohomology =>
        // Same shape as engine=Naive above: no stream-level dimension cap (a cubical grid's own top dimension
        // is already naturally bounded), CellularCohomologyContext computes to that natural top dimension, and
        // maxDimension is applied purely as a post-hoc filter via fromBars.
        fromBars[Cube, C](
          PersistenceEngine.cohomology[Cube, C].barcode(stream),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.FastCubical =>
        // Called directly, like engine=Ripser below, not through PersistenceEngine[CellT,C]: FastCubicalHomologyContext
        // is specialized to the concrete CubicalGridStream (its dual-graph construction reads `.shape`/`.ambientDim`/
        // `.topCellValue` directly), not generic over `CellT: OrderedCell` the way naive/chunks/cohomology are -- the
        // same "honest asymmetry" PersistenceEngine's own doc comment already states for Ripser. dispatchCubical
        // already rejected ambientDim != 2 before this is ever reached, and the stream's own natural top dimension
        // for ambientDim=2 IS dimension 1 (H_2 is identically zero for any subcomplex of a 2D grid -- nothing above
        // dimension 1 for maxDimension to ever filter here), so maxDimension is passed through to fromBars purely
        // for uniformity with every other branch, not because it does real filtering work in practice.
        fromBars[Cube, C](
          FastCubicalHomologyContext[C]().persistentHomology(stream),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Ripser =>
        // dispatchCubical already rejects this before computeCubicalGeneric is ever reached.
        throw new IllegalArgumentException("engine=ripser is not offered for a cubical complex")

  // ---------------------------------------------------------------------------------------------------------------
  // barcode/chain -> PersistenceResult conversion
  // ---------------------------------------------------------------------------------------------------------------

  private def endpointToDouble(e: BarcodeEndpoint[Double]): Double = e match
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v

  /** `cellVertices(dim, cell)` recovers a chain cell's vertex array -- generalized from a hardcoded `.underlying`
    * (which only `Simplex[Int]` has) as of routing `engine="ripser"` through `PackedRipserCohomologyContext`: its cells
    * are `DiameterIndex`, decoded via `ctx.si.decodeToArray(cell.index, dim + 1)` at the call site instead. Takes `dim`
    * (the bar's own dimension, hence the cocycle's -- every cell in one bar's annotation is a simplex of that same
    * dimension) because `DiameterIndex` doesn't carry its own vertex count the way `Simplex[Int]` does; a caller
    * decoding it needs `size` from somewhere else, and the bar itself already has it.
    */
  /** The boundary matrix of `cells` (already in filtration order), one column per cell -- shared by every `fromBars`
    * call site below via a `() => BoundaryMatrixData` thunk each complex branch builds once (from the SAME
    * stream/metric-space construction `engine=naive` already consumes for that complex, regardless of which engine
    * actually computed this result's own bars -- see `PersistenceResult.BoundaryMatrixData`'s own doc for why that's
    * the right choice) and reuses across all of that complex's engine branches, so the boundary matrix a MATLAB caller
    * sees is consistent across `engine` choices by construction, not by keeping several copies of "how do you build the
    * stream for this complex" in sync by hand.
    */
  private def buildBoundaryMatrix[CellT: OrderedCell, C: Field](
    cells: Iterable[CellT],
    cellVertices: (Int, CellT) => Array[Int],
    toDouble: C => Double,
    filtrationValue: CellT => Double
  ): BoundaryMatrixData =
    val ordered = cells.toIndexedSeq
    val index: Map[CellT, Int] = ordered.zipWithIndex.toMap
    val columnDims = ordered.map(_.dim).toArray
    val columnVertices = Array.tabulate(ordered.length)(j => cellVertices(columnDims(j), ordered(j)))
    val columnFiltrationValues = ordered.map(filtrationValue).toArray
    val rows = mutable.ArrayBuffer.empty[Int]
    val cols = mutable.ArrayBuffer.empty[Int]
    val values = mutable.ArrayBuffer.empty[Double]
    for (cell, j) <- ordered.zipWithIndex do
      for (faceCell, coefficient) <- cell.boundary[C] do
        rows += index(faceCell)
        cols += j
        values += toDouble(coefficient)
    BoundaryMatrixData(rows.toArray, cols.toArray, values.toArray, columnDims, columnVertices, columnFiltrationValues)

  private def fromBars[CellT, C](
    bars: List[PersistenceBar[Double, Chain[CellT, C]]],
    cellVertices: (Int, CellT) => Array[Int],
    toDouble: C => Double,
    keepDimensionsUpTo: Int,
    boundaryMatrixOf: () => BoundaryMatrixData
  )(using C is Field): PersistenceResult =
    // Drop the top-of-the-built-complex dimension: it was only ever built as scaffolding for the requested
    // dimension below it, and (for the VR engines, which built one dimension higher than requested precisely for
    // this reason -- see computeGeneric) is itself now subject to the exact same truncation artifact, one level
    // up. Reporting it would misrepresent a construction boundary as a genuine (co)homology fact.
    val indexed = bars.filter(_.dim <= keepDimensionsUpTo).toIndexedSeq
    val dims = indexed.map(_.dim).toArray
    val births = indexed.map(b => endpointToDouble(b.lower)).toArray
    val deaths = indexed.map(b => endpointToDouble(b.upper)).toArray
    val cycleProvider: Int => (Array[Array[Int]], Array[Double]) = i =>
      val dim = indexed(i).dim
      indexed(i).annotation match
        case Some(chain) =>
          chain.collapseAll()
          val items = chain.rawEntries
          (items.map(t => cellVertices(dim, t._1)).toArray, items.map(t => toDouble(t._2)).toArray)
        case None =>
          throw new UnsupportedOperationException(
            s"no representative chain was recorded for bar $i; every engine records one for every bar, so this " +
              "indicates a bug in the engine that produced this result, not an expected gap"
          )
    new PersistenceResult(dims, births, deaths, cycleProvider, boundaryMatrixOf)
