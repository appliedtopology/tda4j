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
  *     most memory-efficient engine -- see CLAUDE.md), `"naive"` (reference-grade, slower; the only engine usable with
  *     `complex=alpha`, and the default for `complex=cech`), or `"chunks"` (`complex=vr`/`complex=cech` only -- see
  *     below for why `complex=alpha` refuses it, and why `complex=cech` refuses `engine=ripser` specifically).
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

  private given epsilonForAlpha: Epsilon = Epsilon(1e-5)

  private def dispatch(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]]
  ): PersistenceResult =
    val complex = opts.getOrElse("complex", "vr").toLowerCase
    if complex != "vr" && complex != "alpha" && complex != "cech" then
      throw new IllegalArgumentException(s"unrecognized complex '$complex'; expected 'vr', 'alpha', or 'cech'")

    val engine =
      opts.getOrElse("engine", if complex == "alpha" || complex == "cech" then "naive" else "ripser").toLowerCase
    if complex == "alpha" && engine == "ripser" then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used with complex=alpha: PackedRipserCohomologyContext computes persistent " +
          "cohomology directly from a metric space's Vietoris-Rips complex and has no notion of an alpha complex at all."
      )
    if complex == "alpha" && engine == "chunks" then
      throw new IllegalArgumentException(
        "engine=chunks is not offered for complex=alpha: this exact combination is a known stall/out-of-memory " +
          "risk in the underlying library (see CLAUDE.md and HomologySpec's BarcodeRegressionSpec, which stays " +
          "skipped for exactly this reason). Use engine=naive for alpha complexes."
      )
    if complex == "cech" && engine == "ripser" then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used with complex=cech: PackedRipserCohomologyContext's apparent-pairs and " +
          "insertionDiameter optimizations are proven specifically for the max-pairwise-distance (Vietoris-Rips) " +
          "functional, not Cech's circumradius -- see CLAUDE.md's Cech complexes section. Use engine=naive or " +
          "engine=chunks for Cech complexes."
      )

    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(2)
    val maxFiltrationValue: Option[Double] =
      opts.get("maxfiltrationvalue").map(parseDoubleOption("maxFiltrationValue", _))
    val alphaBackend = opts.getOrElse("alphabackend", "helix")

    opts.getOrElse("field", "z").toLowerCase match
      case "z" =>
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
      case "r" =>
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
      case other =>
        throw new IllegalArgumentException(s"unrecognized field '$other'; expected 'Z' or 'R'")

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
    complex: String,
    engine: String,
    alphaBackend: String,
    requestedMaxDimension: Int,
    maxFiltrationValue: Option[Double],
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    complex match
      case "vr" =>
        // Computing H_k needs (k+1)-dimensional chains -- H_k = ker(d_k)/im(d_{k+1}), so with no (k+1)-chains at
        // all there is no way to tell a genuine k-cycle from one that a not-yet-built (k+1)-simplex would have
        // killed. Both `PackedRipserCohomologyContext` and `PersistenceInChunksContext` now handle this internally
        // (their own `maxDimension`/`maxDim` constructor parameters mean "top homological degree reported,"
        // fixed at the source -- see .claude/WORKLOG-maxdim-semantics-fix.md), so `engine="ripser"`/`"chunks"`
        // both pass `requestedMaxDimension` straight through with no adjustment; `fromBars`'s
        // filter below is a defensive no-op for them now, not load-bearing. `engine="naive"` still needs the
        // manual `buildDimension = requestedMaxDimension + 1` dance: `SimplicialHomologyContext` has no
        // `maxDimension` of its own at all -- the cap lives entirely in the stream it's handed.
        engine match
          case "ripser" =>
            // Backed by PackedRipserCohomologyContext, not RipserCohomologyContext -- see CLAUDE.md and that
            // class's own doc: same algorithm, measured faster and far leaner on memory. RipserCohomologyContext
            // stays in the codebase only as PackedRipserCohomologyContext's cross-validation test oracle, not as
            // a second production option.
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
          case "naive" =>
            // EnumeratingCofaceSimplexStream has no dimension cap of its own (only a filtration-value one) --
            // LimitedCofaceSimplexStream is what actually enforces a dimension cap, the same wrapping
            // RipserCohomologySpec's own naiveBars helper uses.
            val stream = LimitedCofaceSimplexStream(
              EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            val state = SimplicialHomologyContext[Int, C, Double]().persistentHomology(stream)
            state.advanceAll()
            fromBars[Simplex[Int], C](
              state.barcodeAt(Double.PositiveInfinity),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case "chunks" =>
            // barcodeAt, not diagramAt: CellularPersistenceInChunksContext now records a REAL representative for
            // every bar (any dimension <= requestedMaxDimension), via the SAME fromBars/Option[Chain] path
            // "ripser"/"naive" already use below -- see that method's own doc for how (it reuses this class's
            // OWN already-computed reduction state -- boundaries/cleared/paired/killer -- incrementally, via
            // vcolOf, rather than delegating to a second independent engine) and .claude/CLAUDE.md's
            // coefficients-and-representatives principle.
            val stream = EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue)
            val state = PersistenceInChunksContext[Int, C](requestedMaxDimension).persistentHomology(stream)
            fromBars[Simplex[Int], C](
              state.barcodeAt(Double.PositiveInfinity),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case other =>
            throw new IllegalArgumentException(
              s"unrecognized engine '$other' for complex=vr; expected 'ripser', 'naive', or 'chunks'"
            )
      case "alpha" =>
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
        val alphaStream = Alpha(pts.toIndexedSeq, alphaBackend)
        engine match
          case "naive" =>
            val state = SimplicialHomologyContext[Int, C, Double]().persistentHomology(alphaStream)
            state.advanceAll()
            fromBars[Simplex[Int], C](
              state.barcodeAt(Double.PositiveInfinity),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              Int.MaxValue
            )
          case other =>
            // dispatch() already rejects ripser/chunks for alpha; anything else is a genuinely unrecognized engine.
            throw new IllegalArgumentException(s"unrecognized engine '$other' for complex=alpha; expected 'naive'")
      case "cech" =>
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
          case "naive" =>
            val stream = LimitedCofaceSimplexStream(
              CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue),
              requestedMaxDimension + 1
            )
            val state = SimplicialHomologyContext[Int, C, Double]().persistentHomology(stream)
            state.advanceAll()
            fromBars[Simplex[Int], C](
              state.barcodeAt(Double.PositiveInfinity),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case "chunks" =>
            // CellularPersistenceInChunksContext handles the "+1" dance internally (its own maxDim constructor
            // parameter means "top reported degree," fixed at the source -- see .claude/WORKLOG-maxdim-semantics-
            // fix.md), and CechCofaceSimplexStream's own iterateDimension is already naturally bounded (inherited
            // from RipserCofaceSimplexStream's `d < metricSpace.size` guard), so no LimitedCofaceSimplexStream
            // wrapping is needed here -- mirroring engine=chunks's own complex=vr case above exactly. Cross-
            // validated against the naive engine directly on Cech streams in CechStreamSpec (not assumed to carry
            // over from VR/cubical/simplicial-set validation, since this combination had never been exercised
            // before).
            val stream = CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue)
            val state = CellularPersistenceInChunksContext[Simplex[Int], C](requestedMaxDimension)
              .persistentHomology(stream)
            fromBars[Simplex[Int], C](
              state.barcodeAt(Double.PositiveInfinity),
              (_, cell) => cell.underlying.toArray,
              toDouble,
              requestedMaxDimension
            )
          case other =>
            // dispatch() already rejects ripser for cech; anything else is a genuinely unrecognized engine.
            throw new IllegalArgumentException(
              s"unrecognized engine '$other' for complex=cech; expected 'naive' or 'chunks'"
            )
      case other =>
        throw new IllegalArgumentException(s"unrecognized complex '$other'; expected 'vr', 'alpha', or 'cech'")

  // ---------------------------------------------------------------------------------------------------------------
  // dispatch for computeFromCubicalImage/computeFromImage -- a separate function from dispatch/computeGeneric
  // above (not a new "complex" branch inside them) because a cubical grid carries no FiniteMetricSpace[Int] at
  // all, the type dispatch()/computeGeneric are built around.
  // ---------------------------------------------------------------------------------------------------------------

  private def dispatchCubical(opts: Map[String, String], stream: CubicalGridStream): PersistenceResult =
    val engine = opts.getOrElse("engine", "naive").toLowerCase
    if engine == "ripser" then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used for a cubical complex: PackedRipserCohomologyContext is specialized to " +
          "Simplex[Int] Vietoris-Rips complexes and has no notion of a cubical complex at all. Use engine=naive " +
          "or engine=chunks."
      )
    // Default: the grid's own ambient dimension, i.e. "give me everything" -- correctly parallel to complex=alpha
    // above (a cubical grid's own top dimension is already naturally bounded, never artificially truncated the
    // way VR/Cech are), NOT to complex=vr's default of 2.
    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(stream.ambientDim)

    opts.getOrElse("field", "z").toLowerCase match
      case "z" =>
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(2)
        val ff = new FiniteField(prime)
        import ff.given
        computeCubicalGeneric[ff.Fp](stream, engine, maxDimension, _.toInt.toDouble)
      case "r" =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        computeCubicalGeneric[Double](stream, engine, maxDimension, identity)
      case other =>
        throw new IllegalArgumentException(s"unrecognized field '$other'; expected 'Z' or 'R'")

  private def computeCubicalGeneric[C](
    stream: CubicalGridStream,
    engine: String,
    maxDimension: Int,
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    // cellVertices reports a Cube's own doubled-coordinate encoding, not vertex indices -- see
    // PersistenceResult.cycleVertices's own doc for the decode rule and why this differs from the
    // Simplex[Int]-based complexes above.
    val cellVertices: (Int, Cube) => Array[Int] = (_, cell) => cell.encoded.toArray
    engine match
      case "naive" =>
        // No dimension cap is applied to the stream itself, on purpose, mirroring complex=alpha above: a cubical
        // grid's own chain complex terminates on its own (bounded by its ambient dimension), so it is never
        // artificially cut short the way a VR/Cech complex is -- nothing to build one dimension higher for.
        // CubicalHomologyContext has no maxDim of its own at all, same as SimplicialHomologyContext.
        val state = CubicalHomologyContext[C, Double]().persistentHomology(stream)
        state.advanceAll()
        fromBars[Cube, C](state.barcodeAt(Double.PositiveInfinity), cellVertices, toDouble, maxDimension)
      case "chunks" =>
        // Unlike the naive path above, maxDimension IS passed through here as a genuine, correct truncation --
        // CellularPersistenceInChunksContext handles the "+1" dance internally (see .claude/WORKLOG-maxdim-
        // semantics-fix.md), so this can skip real work for a caller who only wants low-dimensional homology, not
        // just filter what's reported after the fact.
        val state = CellularPersistenceInChunksContext[Cube, C](maxDimension).persistentHomology(stream)
        fromBars[Cube, C](state.barcodeAt(Double.PositiveInfinity), cellVertices, toDouble, maxDimension)
      case other =>
        throw new IllegalArgumentException(
          s"unrecognized engine '$other' for a cubical complex; expected 'naive' or 'chunks'"
        )

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
          val items = chain.items
          (items.map(t => cellVertices(dim, t._1)).toArray, items.map(t => toDouble(t._2)).toArray)
        case None =>
          throw new UnsupportedOperationException(
            s"no representative chain was recorded for bar $i (this can happen for engine=ripser bars resolved " +
              "via the apparent-pairs shortcut -- engine=chunks now records a representative for every bar, see " +
              "CellularPersistenceInChunksContext.barcodeAt's own doc)"
          )
    new PersistenceResult(dims, births, deaths, cycleProvider)
