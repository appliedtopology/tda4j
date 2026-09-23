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
  * built-in Java interface. Every public method takes/returns only `double`, `int`, `String`, `double[][]`, or
  * `String[]` -- deliberately not `java.util.Map` or anything generic, since MATLAB's Java bridge doesn't marshal those
  * reliably. See `WORKLOG-matlab-api.md` for the full design rationale and what's still open, and `PersistenceResult`
  * for what comes back.
  *
  * Options are passed as a flat, alternating key/value `String[]` (`{"engine","ripser","maxDimension","3"}`) rather
  * than fixed parameters, so that adding a new option never changes any method's call signature. Recognized keys:
  *
  *   - `"complex"`: `"vr"` (default), `"alpha"`, or `"cech"`.
  *   - `"engine"`: `"ripser"` (default for `complex=vr`; backed by `PackedRipserCohomologyContext`, the fastest and
  *     most memory-efficient engine -- see CLAUDE.md), `"naive"` (reference-grade, slower; the default for
  *     `complex=alpha`/`complex=cech`), `"chunks"` (`complex=vr`/`complex=cech` only -- see below for why
  *     `complex=alpha` refuses it, and why `complex=cech` refuses `engine=ripser` specifically), or `"cohomology"`
  *     (backed by `CellularCohomologyContext` -- persistent COhomology, generic over `CellT: OrderedCell`, valid for
  *     every `complex` value including `alpha`; unlike `engine=ripser`, not specialized to Vietoris-Rips, so it also
  *     works for `complex=alpha`/`complex=cech`, but without `ripser`'s VR-specific speed optimizations -- see
  *     `.claude/DESIGN-generic-cohomology.md`). Every essential bar's representative is a genuine cocycle (`d(rep) =
  *     0`); a finite bar's representative is a valid witness on its own living interval but is NOT expected to have
  *     zero coboundary over the whole complex -- see `Cohomology.scala`'s own doc for why.
  *   - `"alphaBackend"`: `"helix"` (default) or `"DQP"`, only consulted when `complex=alpha`.
  *   - `"maxDimension"`: integer, default `2` -- the highest HOMOLOGICAL degree you want back (i.e. "give me
  *     H_0..H_k"), not the highest simplex dimension to build. Computing H_k correctly needs (k+1)-dimensional chains
  *     (H_k = ker(d_k)/im(d_{k+1}) -- with no (k+1)-chains at all there's no way to tell a genuine k-cycle from one a
  *     not-yet-built (k+1)-simplex would have killed). For `engine="ripser"`/`"chunks"`,
  *     `PackedRipserCohomologyContext`/ `PersistenceInChunksContext` both now handle this internally (fixed at their
  *     own source -- see `.claude/WORKLOG-maxdim-semantics-fix.md`); for `engine="naive"`, this facade still builds one
  *     dimension higher internally and drops that extra top dimension from what's reported, since
  *     `SimplicialHomologyContext` has no `maxDimension` of its own at all -- it would otherwise look spuriously
  *     essential, a well-known truncation artifact of the top dimension of any truncated chain complex, not real
  *     information (confirmed the hard way in this facade's first pass -- see WORKLOG-matlab-api.md). `complex=alpha`
  *     ignores this option entirely and reports every dimension its complex naturally has: an alpha complex's chain
  *     complex terminates on its own (bounded by ambient dimension, or higher under cosphericity -- see CLAUDE.md), it
  *     is never artificially cut short the way a VR complex is by this option, so its own top dimension is genuine
  *     information, not scaffolding.
  *   - `"maxFiltrationValue"`: double, default is the point cloud's own `minimumEnclosingRadius` (Ripser's own default
  *     truncation, not unbounded -- see CLAUDE.md's "enclosing-radius default" note). Pass a very large number for the
  *     old always-unbounded behavior. Consulted for `complex=vr` (a diameter) and `complex=cech` (a RADIUS -- Cech's
  *     own filtration units, not doubled the way a VR diameter would be); not consulted for `complex=alpha`, which
  *     always computes its complete, untruncated complex (see CLAUDE.md's "Alpha complex" section for why).
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
  case VR, Alpha, Cech

private object ComplexKind:
  def parse(raw: String): ComplexKind = raw.toLowerCase match
    case "vr"    => VR
    case "alpha" => Alpha
    case "cech"  => Cech
    case other   =>
      throw new IllegalArgumentException(s"unrecognized complex '$other'; expected 'vr', 'alpha', or 'cech'")

private enum EngineKind:
  case Ripser, Naive, Chunks, Cohomology

private object EngineKind:
  def parse(raw: String): EngineKind = raw.toLowerCase match
    case "ripser"     => Ripser
    case "naive"      => Naive
    case "chunks"     => Chunks
    case "cohomology" => Cohomology
    case other        =>
      throw new IllegalArgumentException(
        s"unrecognized engine '$other'; expected 'ripser', 'naive', 'chunks', or 'cohomology'"
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
    val metricSpace = ExplicitMetricSpace(distances.toIndexedSeq.map(_.toIndexedSeq))
    dispatch(opts, metricSpace, None)

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
    *   - `"engine"`: `"naive"` (default) or `"chunks"` -- `"ripser"` is never offered here:
    *     `PackedRipserCohomologyContext` is specialized to `Simplex[Int]` Vietoris-Rips complexes and has no notion of
    *     a cubical complex at all.
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
    "sublevel"
  )

  private def parseOptions(options: Array[String]): Map[String, String] =
    if options.length % 2 != 0 then
      throw new IllegalArgumentException(
        s"options must be a flat key,value,key,value,... array (even length), got ${options.length} entries"
      )
    val m = mutable.Map.empty[String, String]
    var i = 0
    while i < options.length do
      val rawKey = options(i)
      val key = rawKey.toLowerCase
      if !recognizedKeys.contains(key) then
        throw new IllegalArgumentException(
          s"unrecognized option '$rawKey'; recognized options are: ${recognizedKeys.toSeq.sorted.mkString(", ")}"
        )
      m(key) = options(i + 1)
      i += 2
    m.toMap

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

  private def dispatch(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]]
  ): PersistenceResult =
    val complex = ComplexKind.parse(opts.getOrElse("complex", "vr"))

    val engine = EngineKind.parse(
      opts.getOrElse(
        "engine",
        if complex == ComplexKind.Alpha || complex == ComplexKind.Cech then "naive" else "ripser"
      )
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
      case _ => ()

    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(2)
    val maxFiltrationValue: Option[Double] =
      opts.get("maxfiltrationvalue").map(parseDoubleOption("maxFiltrationValue", _))
    val alphaBackend = opts.getOrElse("alphabackend", "helix")

    CoefficientKind.parse(opts.getOrElse("field", "z")) match
      case CoefficientKind.Z =>
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(2)
        val ff = new FiniteField(prime)
        import ff.given
        computeGeneric[ff.Fp](
          metricSpace,
          points,
          complex,
          engine,
          alphaBackend,
          maxDimension,
          maxFiltrationValue,
          _.toInt.toDouble
        )
      case CoefficientKind.R =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        computeGeneric[Double](
          metricSpace,
          points,
          complex,
          engine,
          alphaBackend,
          maxDimension,
          maxFiltrationValue,
          identity
        )

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
        engine match
          case EngineKind.Ripser =>
            // Backed by PackedRipserCohomologyContext, not RipserCohomologyContext -- see CLAUDE.md and that
            // class's own doc: same algorithm, measured faster and far leaner on memory. RipserCohomologyContext
            // stays in the codebase only as PackedRipserCohomologyContext's cross-validation test oracle, not as
            // a second production option. Doesn't go through `PersistenceEngine`: it consumes a metric space
            // directly, not a stream -- see that trait's own doc for why this is an honest asymmetry.
            val ctx = PackedRipserCohomologyContext[C](
              metricSpace,
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
              requestedMaxDimension
            )
          case EngineKind.Naive =>
            // EnumeratingCofaceSimplexStream has no dimension cap of its own (only a filtration-value one) --
            // LimitedCofaceSimplexStream is what actually enforces a dimension cap, the same wrapping
            // RipserCohomologySpec's own naiveBars helper uses.
            val stream = LimitedCofaceSimplexStream(
              EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(stream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case EngineKind.Chunks =>
            // barcodeAt, not diagramAt: CellularPersistenceInChunksContext now records a REAL representative for
            // every bar (any dimension <= requestedMaxDimension), via the SAME fromBars/Option[Chain] path
            // Ripser/Naive already use above -- see PersistenceEngine's own doc for how (it reuses this class's
            // OWN already-computed reduction state -- boundaries/cleared/paired/killer -- incrementally, via
            // vcolOf, rather than delegating to a second independent engine) and .claude/CLAUDE.md's
            // coefficients-and-representatives principle.
            val stream = EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              PersistenceEngine.chunks[Simplex[Int], C](requestedMaxDimension).barcode(stream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case EngineKind.Cohomology =>
            // CellularCohomologyContext, generic over CellT: OrderedCell -- see
            // .claude/DESIGN-generic-cohomology.md. Same "build one dimension higher, drop it via fromBars"
            // dance as engine=Naive above, for the identical reason (H_k needs (k+1)-dimensional chains); this
            // engine has no maxDim/maxDimension parameter of its own at all (deliberately -- see that class's
            // own doc), so the cap lives entirely in the stream, exactly like engine=Naive.
            val stream = LimitedCofaceSimplexStream(
              EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(stream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
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
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(alphaStream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              Int.MaxValue
            )
          case EngineKind.Cohomology =>
            // No stream-level dimension cap here either, for the same reason as engine=Naive above: an alpha
            // complex's chain complex terminates on its own. CellularCohomologyContext accepts `alphaStream`
            // directly -- it's a StratifiedSimplexStream[Int, Double], hence a CellStream[Simplex[Int], Double].
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(alphaStream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              Int.MaxValue
            )
          case EngineKind.Ripser | EngineKind.Chunks =>
            // dispatch() already rejects both of these for complex=alpha before computeGeneric is ever reached.
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
        engine match
          case EngineKind.Naive =>
            val stream = LimitedCofaceSimplexStream(
              CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            fromBars[Simplex[Int], C](
              PersistenceEngine.naive[Simplex[Int], C].barcode(stream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
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
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case EngineKind.Cohomology =>
            // Same shape as engine=Naive above for complex=cech -- Cech's top dimension is not naturally
            // bounded, so the same "build one dimension higher via LimitedCofaceSimplexStream, drop it via
            // fromBars" dance applies, for the identical reason as complex=vr's own engine=Cohomology branch.
            val stream = LimitedCofaceSimplexStream(
              CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            fromBars[Simplex[Int], C](
              PersistenceEngine.cohomology[Simplex[Int], C].barcode(stream),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case EngineKind.Ripser =>
            // dispatch() already rejects this for complex=cech before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=cech")

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
          "engine=chunks, or engine=cohomology."
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
    engine match
      case EngineKind.Naive =>
        // No dimension cap is applied to the stream itself, on purpose, mirroring complex=alpha above: a cubical
        // grid's own chain complex terminates on its own (bounded by its ambient dimension), so it is never
        // artificially cut short the way a VR/Cech complex is -- nothing to build one dimension higher for.
        // CellularHomologyContext[Cube, ...] has no maxDim of its own at all, same as Simplex[Int].
        fromBars[Cube, C](PersistenceEngine.naive[Cube, C].barcode(stream), cellVertices, toDouble, maxDimension)
      case EngineKind.Chunks =>
        // Unlike the naive path above, maxDimension IS passed through here as a genuine, correct truncation --
        // CellularPersistenceInChunksContext handles the "+1" dance internally (see .claude/WORKLOG-maxdim-
        // semantics-fix.md), so this can skip real work for a caller who only wants low-dimensional homology, not
        // just filter what's reported after the fact.
        fromBars[Cube, C](
          PersistenceEngine.chunks[Cube, C](maxDimension).barcode(stream),
          cellVertices,
          toDouble,
          maxDimension
        )
      case EngineKind.Cohomology =>
        // Same shape as engine=Naive above: no stream-level dimension cap (a cubical grid's own top dimension
        // is already naturally bounded), CellularCohomologyContext computes to that natural top dimension, and
        // maxDimension is applied purely as a post-hoc filter via fromBars.
        fromBars[Cube, C](
          PersistenceEngine.cohomology[Cube, C].barcode(stream),
          cellVertices,
          toDouble,
          maxDimension
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
  private def fromBars[CellT, C](
    bars: List[PersistenceBar[Double, Chain[CellT, C]]],
    cellVertices: (Int, CellT) => Array[Int],
    toDouble: C => Double,
    keepDimensionsUpTo: Int
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
    new PersistenceResult(dims, births, deaths, cycleProvider)
