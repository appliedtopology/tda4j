package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.*

import scala.collection.mutable

// The "complex"/"engine"/"field" options, parsed once into enums before anything runs. The CLI passes its flags through
// as strings and relies on this same parsing.
private enum ComplexKind:
  case VR, Alpha, Cech, Witness, DtmRips, DtmAlpha, SparseRips

private object ComplexKind:
  def parse(raw: String): ComplexKind = raw.toLowerCase match
    case "vr"          => VR
    case "alpha"       => Alpha
    case "cech"        => Cech
    case "witness"     => Witness
    case "dtm-rips"    => DtmRips
    case "dtm-alpha"   => DtmAlpha
    case "sparse-rips" => SparseRips
    case "sheehy-rips" =>
      throw new IllegalArgumentException(
        "complex 'sheehy-rips' was renamed 'sparse-rips' (and 'sheehyEpsilon' 'sparseEpsilon') in 0.5.0"
      )
    case other =>
      throw new IllegalArgumentException(
        s"unrecognized complex '$other'; expected 'vr', 'alpha', 'cech', 'witness', 'dtm-rips', 'dtm-alpha', or " +
          "'sparse-rips'"
      )

/** `complex=witness` only: `"lazy"` (JavaPlex's `LazyWitnessStream` -- a flag complex, so `engine=ripser` is valid; see
  * `LazyWitnessSimplexStream`) or `"general"` (JavaPlex's plain `WitnessStream` -- NOT a flag complex, so
  * `engine=ripser`/`"chunks"` are refused, exactly like `complex=cech`'s own `engine=ripser` refusal; see
  * `WitnessCofaceSimplexStream`).
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
  case Ripser, Naive, Chunks, Cohomology, FastCubical, FastAlpha

private object EngineKind:
  def parse(raw: String): EngineKind = raw.toLowerCase match
    case "ripser"       => Ripser
    case "naive"        => Naive
    case "chunks"       => Chunks
    case "cohomology"   => Cohomology
    case "fast-cubical" => FastCubical
    case "fast-alpha"   => FastAlpha
    case other          =>
      throw new IllegalArgumentException(
        s"unrecognized engine '$other'; expected 'ripser', 'naive', 'chunks', 'cohomology', 'fast-cubical', or " +
          "'fast-alpha'"
      )

private enum CoefficientKind:
  case Z, R

private object CoefficientKind:
  def parse(raw: String): CoefficientKind = raw.toLowerCase match
    case "z"   => Z
    case "r"   => R
    case other => throw new IllegalArgumentException(s"unrecognized field '$other'; expected 'Z' or 'R'")

/** TDA4j for MATLAB (through its built-in Java support) and plain Java: every method takes and returns only `int`,
  * `double`, `String`, arrays of those, and result objects ([[PersistenceResult]], [[LandmarkSelectionResult]],
  * [[CircularCoordinatesResult]], [[ToroidalCoordinatesResult]]).
  *
  * Options are a flat, alternating name/value `String[]` (`{"engine", "ripser", "maxDimension", "3"}`), so a new option
  * never changes a signature; every `compute...` method also has an overload without options. The options and their
  * defaults are listed in the user guide's "Calling from MATLAB or Java" page: `complex`, `engine`, `maxDimension`,
  * `maxFiltrationValue`, `field`, `prime`, `epsilon`, `minPersistence`, `minPersistenceFraction`, `includeZeroLength`,
  * `alphaBackend`, `requireValidTriangulation`, `dtmK`, `dtmQ`, `dtmP`, `sparseEpsilon`, `numLandmarks`,
  * `witnessVariant`, `landmarkSelector`, `landmarkSeed`, `nu`, `edgeCollapse`, `sublevel`, `dual`. An unknown option,
  * an unknown value, or a combination that does not apply (an engine for a complex it cannot read) throws
  * `IllegalArgumentException` naming it, before anything is computed.
  *
  * By default a result reports the essential bars and the bars longer than 1% of the input's scale (its minimum
  * enclosing radius, or the value range of an image or relation); zero-length bars are left out unless
  * `includeZeroLength` is `true`. See [[PersistenceResult]].
  */
object TDA4j:
  def computeFromPoints(points: Array[Array[Double]]): PersistenceResult =
    computeFromPoints(points, Array.empty[String])

  /** Persistence of a point cloud, one row per point, with Euclidean distances: any `complex`. */
  def computeFromPoints(points: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validatePoints(points)
    val opts = parseOptions(options)
    val metricSpace = EuclideanMetricSpace(points)
    dispatch(opts, metricSpace, Some(points))

  def computeFromDistanceMatrix(distances: Array[Array[Double]]): PersistenceResult =
    computeFromDistanceMatrix(distances, Array.empty[String])

  /** Persistence from a square, symmetric distance matrix (zero diagonal): every `complex` that needs only distances
    * (`vr`, `witness`, `dtm-rips`, `sparse-rips`), not `alpha`, `cech` or `dtm-alpha`.
    */
  def computeFromDistanceMatrix(distances: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validateSquare(distances)
    val opts = parseOptions(options)
    dispatch(opts, explicitMetricSpace(distances), None)

  def computeFromRelation(relation: Array[Array[Double]]): PersistenceResult =
    computeFromRelation(relation, Array.empty[String])

  /** Persistence of the Dowker complex of a relation: `relation(x)(w)` is the strength of the tie between row `x` and
    * column `w` (smaller is stronger, `Infinity` for never; non-negative). The matrix need not be square or symmetric.
    * Options: `engine` (`naive`, the default, or `cohomology`: the complex is not a flag complex), `maxDimension`
    * (default 2), `maxFiltrationValue` (default `Infinity`), `dual` (`true` for the complex on the columns, which has
    * the same diagram), `field`, `prime`, `epsilon`, and the bar options.
    */
  def computeFromRelation(relation: Array[Array[Double]], options: Array[String]): PersistenceResult =
    validateRelation(relation)
    val opts = parseOptionsWithKeys(options, dowkerKeys)
    dispatchDowker(opts, relation)

  // The witness complex in two steps: select landmarks (and read the covering radius R), then compute, e.g. with
  // maxFiltrationValue 2R as in the JavaPlex tutorials. The one-shot complex=witness path never reports R.

  def selectLandmarksFromPoints(points: Array[Array[Double]], options: Array[String]): LandmarkSelectionResult =
    validatePoints(points)
    val opts = parseOptionsWithKeys(options, landmarkSelectionKeys)
    val selection = resolveLandmarkSelection(opts, EuclideanMetricSpace(points))
    new LandmarkSelectionResult(selection.landmarks.toArray, selection.coveringRadius)

  /** Step 1 of the two-step witness complex: select landmarks (options `numLandmarks`, required, `landmarkSelector`,
    * `landmarkSeed`, and no others) and report them, 0-based, with their covering radius.
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

  /** As [[selectLandmarksFromPoints]], from a distance matrix. */
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

  /** Step 2 of the two-step witness complex: its persistence for the given landmarks (0-based row numbers of `points`,
    * non-empty, distinct; a number equal to `points.length` is reported as a likely 1-based index). Options:
    * `witnessVariant`, `nu`, `engine`, `maxDimension`, `maxFiltrationValue`, `field`, `prime`, `epsilon`, the bar
    * options, and `complex` only as `"witness"`.
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

  /** As [[computeFromPointsAndLandmarks]], from a distance matrix. */
  def computeFromDistanceMatrixAndLandmarks(distances: Array[Array[Double]], landmarks: Array[Int]): PersistenceResult =
    computeFromDistanceMatrixAndLandmarks(distances, landmarks, Array.empty[String])

  /** The covering radius `R = max over x of min over landmarks l of d(x, l)` of any landmark set. */
  def coveringRadiusFromPoints(points: Array[Array[Double]], landmarks: Array[Int]): Double =
    validatePoints(points)
    val metricSpace = EuclideanMetricSpace(points)
    validateLandmarks(landmarks, metricSpace.size)
    LandmarkSelector.coveringRadius(metricSpace, landmarks.toIndexedSeq)

  /** As [[coveringRadiusFromPoints]], from a distance matrix. */
  def coveringRadiusFromDistanceMatrix(distances: Array[Array[Double]], landmarks: Array[Int]): Double =
    validateSquare(distances)
    val metricSpace = explicitMetricSpace(distances)
    validateLandmarks(landmarks, metricSpace.size)
    LandmarkSelector.coveringRadius(metricSpace, landmarks.toIndexedSeq)

  /** The loops of the point cloud's Vietoris-Rips complex: one row `(birth, death)` per persistent H¹ class, most
    * persistent first. Row `i` is `cocycleIndex = i` of [[circularCoordinates]]; pick `r` between its birth and death.
    */
  def h1Bars(points: Array[Array[Double]]): Array[Array[Double]] =
    validatePoints(points)
    CircularCoordinates.h1Bars(EuclideanMetricSpace(points)).map((b, d) => Array(b, d)).toArray

  def circularCoordinates(points: Array[Array[Double]], r: Double): CircularCoordinatesResult =
    circularCoordinates(points, r, 0, 47)

  /** Circular coordinates for loop `cocycleIndex` of [[h1Bars]] at scale `r`, over the field with `prime` elements (an
    * odd prime; default 47): see `CircularCoordinates.compute`. Throws `NoIntegerCocycleException` if the class has no
    * integer lift at `prime` (retry with a larger prime), `IllegalArgumentException` for an invalid argument.
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

  def toroidalCoordinates(
    points: Array[Array[Double]],
    r: Double,
    cocycleIndices: Array[Int]
  ): ToroidalCoordinatesResult =
    toroidalCoordinates(points, r, cocycleIndices, 47, true)

  /** Toroidal coordinates for several loops of [[h1Bars]] alive together at `r`: see
    * `CircularCoordinates.computeToroidal`. `reduce` (default `true`) applies the lattice reduction.
    */
  def toroidalCoordinates(
    points: Array[Array[Double]],
    r: Double,
    cocycleIndices: Array[Int],
    prime: Int,
    reduce: Boolean
  ): ToroidalCoordinatesResult =
    validatePoints(points)
    val result =
      CircularCoordinates.computeToroidal(EuclideanMetricSpace(points), r, cocycleIndices.toIndexedSeq, prime, reduce)
    val thetaArrays = result.theta.map { thetaMap =>
      val arr = Array.fill(points.length)(Double.NaN)
      thetaMap.foreach((i, t) => arr(i) = t)
      arr
    }.toArray
    new ToroidalCoordinatesResult(
      thetaArrays,
      result.cocycleIndices.toArray,
      result.basisChange,
      result.originalGram,
      result.reducedGram,
      result.r,
      result.prime
    )

  def computeFromCubicalImage(shape: Array[Int], flatValues: Array[Double]): PersistenceResult =
    computeFromCubicalImage(shape, flatValues, Array.empty[String])

  /** Persistence of an image or voxel grid of any dimension: the values in row-major order (last axis fastest) and the
    * size along each axis. Options: `engine` (`naive`, the default, `chunks`, `cohomology`, or `fast-cubical` for two
    * dimensions and up), `maxDimension` (default: the grid's dimension), `sublevel` (`false` for superlevel sets, whose
    * filtration values are negated intensities), `field`, `prime`, `epsilon`, and the bar options.
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
    // The range is the same under sublevel=false (values are negated, not rescaled).
    dispatchCubical(opts, stream, valueRange(flatValues.iterator))

  def computeFromImage(pixels: Array[Array[Double]]): PersistenceResult =
    computeFromImage(pixels, Array.empty[String])

  /** [[computeFromCubicalImage]] for a 2-D image given as a matrix, `pixels(row)(column)`. */
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
    "representativetype",
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
    "sparseepsilon",
    "edgecollapse",
    "requirevalidtriangulation",
    "minpersistence",
    "minpersistencefraction",
    "includezerolength"
  )

  /** The options of landmark selection (step 1 of the two-step witness complex), and no others: a landmark option
    * passed in step 2 (whose landmarks are given) is an error rather than silently ignored.
    */
  private val landmarkSelectionKeys = Set("numlandmarks", "landmarkselector", "landmarkseed")

  /** The options of step 2 of the two-step witness complex: no landmark-selection options (the landmarks are given),
    * and `complex` accepted only as `"witness"`.
    */
  private val witnessFromLandmarksKeys =
    Set(
      "complex",
      "witnessvariant",
      "nu",
      "engine",
      "representativetype",
      "maxdimension",
      "maxfiltrationvalue",
      "field",
      "prime",
      "epsilon",
      "minpersistence",
      "minpersistencefraction",
      "includezerolength"
    )

  /** The options of `computeFromRelation`: a relation has no `complex`, landmarks or alpha backend. */
  private val dowkerKeys = Set(
    "engine",
    "representativetype",
    "maxdimension",
    "maxfiltrationvalue",
    "dual",
    "field",
    "prime",
    "epsilon",
    "minpersistence",
    "minpersistencefraction",
    "includezerolength"
  )

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

  /** Same shape check as `validatePoints` (non-empty, every row the same length), worded for a relation matrix rather
    * than a point cloud -- `DowkerGeometry`'s own constructor separately rejects a negative entry (which needs no
    * MATLAB-specific rewording, it already names the right thing).
    */
  private def validateRelation(relation: Array[Array[Double]]): Unit =
    if relation.isEmpty then throw new IllegalArgumentException("relation must have at least one row")
    val w = relation(0).length
    if relation.exists(_.length != w) then
      throw new IllegalArgumentException("every row of relation must have the same number of columns")

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

  /** The witness complex's engine: `general` (not a flag complex) defaults to `cohomology` and refuses `ripser` and
    * `chunks`; `lazy` (a flag complex) defaults to `ripser` and allows all four. Shared by the one-shot and two-step
    * paths.
    */
  private def resolveWitnessEngine(opts: Map[String, String], witnessVariant: WitnessVariantKind): EngineKind =
    val engine = EngineKind.parse(
      opts.getOrElse("engine", if witnessVariant == WitnessVariantKind.General then "cohomology" else "ripser")
    )
    if engine == EngineKind.FastCubical then
      throw new IllegalArgumentException(
        "engine=fast-cubical is not offered for complex=witness (either variant): FastCubicalHomologyEngine is " +
          "specialized to CubicalGridStream and has no notion of a witness complex at all."
      )
    if engine == EngineKind.FastAlpha then
      throw new IllegalArgumentException(
        "engine=fast-alpha is not offered for complex=witness (either variant): FastAlphaHomologyEngine is " +
          "specialized to HelixDelaunay and has no notion of a witness complex at all. Use complex=alpha for " +
          "engine=fast-alpha."
      )
    if witnessVariant == WitnessVariantKind.General then
      engine match
        case EngineKind.Ripser =>
          throw new IllegalArgumentException(
            "engine=ripser cannot be used with complex=witness/witnessVariant=general: the general witness " +
              "complex is not a flag complex, so the Ripser engine does not apply. Use " +
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
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(FiniteField.DefaultPrime)
        val ff = new FiniteField(prime)
        import ff.given
        compute[ff.Fp](_.toInt.toDouble)
      case CoefficientKind.R =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        compute[Double](identity)

  /** The persistence threshold, resolved from `minPersistence`/`minPersistenceFraction` -- validated BEFORE any
    * computation starts (a typo should not cost a long Vietoris-Rips run), applied to the finished result by the thin
    * `dispatch*` wrappers below. Default: [[PersistenceFilter.DefaultFraction]] of the input's scale (its minimum
    * enclosing radius, or a cubical image's / Dowker relation's value range -- passed by-name, so it is only computed
    * when a fraction of it is needed); `0` for either option means "report every bar".
    */
  private final case class ThresholdSpec(minPersistence: Option[Double], fraction: Double, includeZeroLength: Boolean):
    def apply(result: PersistenceResult, scale: => Double): PersistenceResult =
      (if includeZeroLength then result else result.withoutZeroLength).withPersistenceThreshold(
        minPersistence,
        fraction,
        scale
      )

  /** max - min of the finite values -- the "scale" of an input with no metric (a cubical image's pixels, a Dowker
    * relation's entries); `0` if there are none.
    */
  private def valueRange(values: Iterator[Double]): Double =
    val finite = values.filter(v => !v.isInfinite && !v.isNaN).toArray
    if finite.isEmpty then 0.0 else finite.max - finite.min

  private def parseThresholdSpec(opts: Map[String, String]): ThresholdSpec =
    val absolute = opts.get("minpersistence").map(parseDoubleOption("minPersistence", _))
    val fraction = opts.get("minpersistencefraction").map(parseDoubleOption("minPersistenceFraction", _))
    if absolute.isDefined && fraction.isDefined then
      throw new IllegalArgumentException(
        "minPersistence (an absolute threshold) and minPersistenceFraction (a fraction of the input's scale) " +
          "are alternatives: pass at most one of them"
      )
    absolute.foreach(a =>
      if !(a >= 0.0) || a.isInfinite then
        throw new IllegalArgumentException(s"option 'minPersistence' must be a finite number >= 0, got '$a'")
    )
    fraction.foreach(f =>
      if !(f >= 0.0) || f.isInfinite then
        throw new IllegalArgumentException(s"option 'minPersistenceFraction' must be a finite number >= 0, got '$f'")
    )
    ThresholdSpec(
      absolute,
      fraction.getOrElse(PersistenceFilter.DefaultFraction),
      opts.get("includezerolength").exists(v => parseBooleanOption("includeZeroLength", v))
    )

  private def dispatch(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]]
  ): PersistenceResult =
    val threshold = parseThresholdSpec(opts)
    threshold(dispatchFull(opts, metricSpace, points), metricSpace.minimumEnclosingRadius)

  private def dispatchFull(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]]
  ): PersistenceResult =
    val complex = ComplexKind.parse(opts.getOrElse("complex", "vr"))

    // Parsed BEFORE the engine default below, since complex=witness's own default depends on it (lazy behaves
    // like complex=vr -- a flag complex, defaults to ripser; general behaves like complex=alpha/cech -- not a
    // flag complex, defaults to cohomology).
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
              complex == ComplexKind.SparseRips
            then "cohomology"
            else "ripser"
          )
        )
    val cycles = wantsCycles(opts, engine)
    if engine == EngineKind.FastCubical then
      throw new IllegalArgumentException(
        "engine=fast-cubical is only valid for computeFromCubicalImage/computeFromImage: " +
          "FastCubicalHomologyEngine is specialized to CubicalGridStream and has no notion of a point cloud or " +
          "distance matrix at all (unlike ripser/naive/chunks/cohomology, which every complex here can offer some " +
          "subset of)."
      )
    if engine == EngineKind.FastAlpha && complex != ComplexKind.Alpha then
      throw new IllegalArgumentException(
        s"engine=fast-alpha is only valid for complex=alpha: FastAlphaHomologyEngine is specialized to " +
          s"HelixDelaunay and has no notion of complex=${opts.getOrElse("complex", "vr")} at all."
      )
    (complex, engine) match
      case (ComplexKind.Alpha, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=alpha: PackedRipserCohomologyEngine computes persistent " +
            "cohomology directly from a metric space's Vietoris-Rips complex and has no notion of an alpha complex at all."
        )
      case (ComplexKind.Alpha, EngineKind.Chunks) =>
        throw new IllegalArgumentException(
          "engine=chunks is not offered for complex=alpha: it can stall or run out of memory on alpha complexes. " +
            "Use engine=naive, engine=cohomology or (with the helix backend) engine=fast-alpha."
        )
      case (ComplexKind.Cech, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=cech: the Ripser engine relies on simplices being filtered " +
            "by diameter, and a Cech filtration is by radius. Use engine=chunks, engine=naive or engine=cohomology."
        )
      case (ComplexKind.DtmRips, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=dtm-rips: PackedRipserCohomologyEngine assumes vertices are " +
            "born at filtration 0 and uses insertionDiameter, an incremental formula proven only for the plain " +
            "max-pairwise-distance functional -- neither holds for the DTM-weighted filtration. Use engine=naive, " +
            "engine=chunks, or engine=cohomology for complex=dtm-rips."
        )
      case (ComplexKind.SparseRips, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=sparse-rips: a simplex's filtration value here is not the " +
            "maximum ambient pairwise distance among its vertices (some pairs are excluded outright, others take a " +
            "sparsified value), so the Ripser engine does not apply. Use engine=naive, engine=chunks, or " +
            "engine=cohomology for complex=sparse-rips."
        )
      case (ComplexKind.DtmAlpha, EngineKind.Ripser) =>
        throw new IllegalArgumentException(
          "engine=ripser cannot be used with complex=dtm-alpha: PackedRipserCohomologyEngine has no notion of an " +
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
    val alphaBackend = opts.getOrElse("alphabackend", "default")

    // No separate alphaBackend="dqp" check here -- AlphaShapes.apply's own `require` already rejects that
    // combination with an actionable message, the single place this is checked (mirroring how the fast-alpha
    // dispatch below pattern-matches the CONSTRUCTED type rather than re-comparing the raw alphaBackend string,
    // since alphaBackend="default" is also a valid spelling that resolves to HelixDelaunay).
    val requireValidTriangulation =
      opts.get("requirevalidtriangulation").exists(v => parseBooleanOption("requireValidTriangulation", v))
    if requireValidTriangulation && complex != ComplexKind.Alpha then
      throw new IllegalArgumentException(
        s"option 'requireValidTriangulation' is only valid for complex=alpha -- got " +
          s"complex=${opts.getOrElse("complex", "vr")}"
      )

    val edgeCollapse = opts.get("edgecollapse").exists(v => parseBooleanOption("edgeCollapse", v))
    if edgeCollapse && complex != ComplexKind.VR then
      throw new IllegalArgumentException(
        s"option 'edgeCollapse' is only valid for complex=vr (EdgeCollapse operates on a flag complex's " +
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

    val sparseEpsilon: Double =
      if complex == ComplexKind.SparseRips then
        parseDoubleOption(
          "sparseEpsilon",
          opts.getOrElse(
            "sparseepsilon",
            throw new IllegalArgumentException("option 'sparseEpsilon' is required for complex=sparse-rips")
          )
        )
      else 0.0 // unused for any other complex

    dispatchByField(opts) { [C] => (toDouble: C => Double) =>
      computeGeneric[C](
        metricSpace,
        points,
        complex,
        engine,
        cycles,
        alphaBackend,
        requireValidTriangulation,
        maxDimension,
        maxFiltrationValue,
        witnessVariant,
        witnessLandmarks,
        witnessNu,
        dtmK,
        dtmQ,
        dtmP,
        sparseEpsilon,
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

  /** Whether to report cycles: the `representativeType` option, `cycles` (the default) or `cocycles`. Every engine but
    * `fast-cubical` and `fast-alpha` gives both, the other kind from its pairing ([[Involution]]).
    */
  private def wantsCycles(opts: Map[String, String], engine: EngineKind): Boolean =
    val cyclesOnly = engine == EngineKind.FastCubical || engine == EngineKind.FastAlpha
    opts.get("representativetype").map(_.toLowerCase) match
      case None             => true
      case Some("cycles")   => true
      case Some("cocycles") =>
        if !cyclesOnly then false
        else
          throw new IllegalArgumentException(
            s"engine=${if engine == EngineKind.FastCubical then "fast-cubical" else "fast-alpha"} gives cycles " +
              "only; for cocycles use engine=cohomology, or leave engine unset"
          )
      case Some(other) =>
        throw new IllegalArgumentException(s"option 'representativeType' must be cycles or cocycles, got '$other'")

  private def naiveFor[CellT: OrderedCell, C: Field](cycles: Boolean): PersistenceEngine[CellT, C] =
    if cycles then PersistenceEngine.naive[CellT, C] else PersistenceEngine.naiveCocycles[CellT, C]

  private def chunksFor[CellT: OrderedCell, C: Field](maxDim: Int, cycles: Boolean): PersistenceEngine[CellT, C] =
    if cycles then PersistenceEngine.chunks[CellT, C](maxDim) else PersistenceEngine.chunksCocycles[CellT, C](maxDim)

  private def cohomologyFor[CellT: OrderedCell, C: Field](cycles: Boolean): PersistenceEngine[CellT, C] =
    if cycles then PersistenceEngine.cohomologyCycles[CellT, C] else PersistenceEngine.cohomology[CellT, C]

  private def computeGeneric[C](
    metricSpace: FiniteMetricSpace[Int],
    points: Option[Array[Array[Double]]],
    complex: ComplexKind,
    engine: EngineKind,
    cycles: Boolean,
    alphaBackend: String,
    requireValidTriangulation: Boolean,
    requestedMaxDimension: Int,
    maxFiltrationValue: Option[Double],
    witnessVariant: WitnessVariantKind,
    witnessLandmarks: IndexedSeq[Int],
    witnessNu: Int,
    dtmK: Int,
    dtmQ: Double,
    dtmP: Double,
    sparseEpsilon: Double,
    edgeCollapse: Boolean,
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    complex match
      case ComplexKind.VR =>
        // Computing H_k needs (k+1)-dimensional chains -- H_k = ker(d_k)/im(d_{k+1}), so with no (k+1)-chains at
        // all there is no way to tell a genuine k-cycle from one that a not-yet-built (k+1)-simplex would have
        // killed. Both `PackedRipserCohomologyEngine` and `PersistenceInChunksEngine` now handle this internally
        // (their own `maxDimension`/`maxDim` constructor parameters mean "top homological degree reported,"
        // fixed at the source -- see .claude/WORKLOG-maxdim-semantics-fix.md), so `engine=Ripser`/`Chunks`
        // both pass `requestedMaxDimension` straight through with no adjustment; `fromBars`'s
        // filter below is a defensive no-op for them now, not load-bearing. `engine=Naive`/`Cohomology` still need
        // the manual `buildDimension = requestedMaxDimension + 1` dance via `PersistenceEngine`'s own adapters
        // below: neither `SimplicialHomologyEngine` nor `CellularCohomologyEngine` has a `maxDimension` of its
        // own at all -- the cap lives entirely in the stream each is handed.
        // `edgeCollapse` replaces the metric space every engine branch below consumes (including `engine=ripser`'s
        // own direct `PackedRipserCohomologyEngine(collapsedMetricSpace, ...)` call, which takes a metric space,
        // not a stream) -- one swap here benefits every engine uniformly, the same "wire once" shape the boundary
        // matrix below already uses for a different property of the complex. `maxFiltrationValue` is passed
        // straight through to `EdgeCollapse.collapse` too: a truncated collapse (only edges within that bound
        // ever considered) composes correctly with the SAME bound applied again below when building the actual
        // stream -- see `EdgeCollapse`'s own doc for why restricting twice to the same bound is safe.
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
            // Backed by PackedRipserCohomologyEngine, not RipserCohomologyEngine -- see CLAUDE.md and that
            // class's own doc: same algorithm, measured faster and far leaner on memory. RipserCohomologyEngine
            // stays in the codebase only as PackedRipserCohomologyEngine's cross-validation test oracle, not as
            // a second production option. Doesn't go through `PersistenceEngine`: it consumes a metric space
            // directly, not a stream -- see that trait's own doc for why this is an honest asymmetry.
            val ctx = PackedRipserCohomologyEngine[C](
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
              if cycles then ctx.persistentHomology(includeZeroLength = true)
              else ctx.persistentCohomology(includeZeroLength = true),
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
              naiveFor[Simplex[Int], C](cycles).barcode(vrStreamForBoundary, includeZeroLength = true),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // barcodeAt, not diagramAt: CellularPersistenceInChunksEngine now records a REAL representative for
            // every bar (any dimension <= requestedMaxDimension), via the SAME fromBars/Option[Chain] path
            // Ripser/Naive already use above -- see PersistenceEngine's own doc for how (it reuses this class's
            // OWN already-computed reduction state -- boundaries/cleared/paired/killer -- incrementally, via
            // vcolOf, rather than delegating to a second independent engine) and .claude/CLAUDE.md's
            // coefficients-and-representatives principle.
            val stream = EnumeratingCofaceSimplexStream(collapsedMetricSpace, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              chunksFor[Simplex[Int], C](requestedMaxDimension, cycles)
                .barcode(stream, includeZeroLength = true),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            // CellularCohomologyEngine, generic over CellT: OrderedCell -- see
            // .claude/DESIGN-generic-cohomology.md. Same "build one dimension higher, drop it via fromBars"
            // dance as engine=Naive above, for the identical reason (H_k needs (k+1)-dimensional chains); this
            // engine has no maxDim/maxDimension parameter of its own at all (deliberately -- see that class's
            // own doc), so the cap lives entirely in the stream, exactly like engine=Naive. Reuses
            // vrStreamForBoundary directly, same as engine=Naive above.
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(vrStreamForBoundary, includeZeroLength = true),
              vrCellVertices,
              toDouble,
              requestedMaxDimension,
              vrBoundaryMatrixOf
            )
          case EngineKind.FastCubical | EngineKind.FastAlpha =>
            // dispatch() already rejects both of these for complex=vr before computeGeneric is ever reached.
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
        if engine == EngineKind.FastAlpha && maxFiltrationValue.isDefined then
          throw new IllegalArgumentException(
            "engine=fast-alpha computes the whole alpha complex, so it does not take maxFiltrationValue: leave " +
              "maxFiltrationValue out, or use engine=cohomology or engine=naive for the complex up to that radius."
          )
        // With maxFiltrationValue, alphaBackend=default builds the complex up to that radius with whichever of Helix
        // and DQP is expected to be faster (AlphaShapes.prefersDQP); the same complex in general position.
        val alphaStream =
          AlphaShapes(pts.toIndexedSeq, AlphaBackend.parse(alphaBackend), requireValidTriangulation, maxFiltrationValue)
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
              naiveFor[Simplex[Int], C](cycles).barcode(alphaStream, includeZeroLength = true),
              alphaCellVertices,
              toDouble,
              Int.MaxValue,
              alphaBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            // No stream-level dimension cap here either, for the same reason as engine=Naive above: an alpha
            // complex's chain complex terminates on its own. CellularCohomologyEngine accepts `alphaStream`
            // directly -- it's a LevelwiseSimplexStream[Int, Double], hence a CellStream[Simplex[Int], Double].
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(alphaStream, includeZeroLength = true),
              alphaCellVertices,
              toDouble,
              Int.MaxValue,
              alphaBoundaryMatrixOf
            )
          case EngineKind.FastAlpha =>
            // Pattern-matched, not a string check on alphaBackend directly: alphaBackend="default" ALSO
            // currently resolves to HelixDelaunay (see AlphaShapes.apply's own dispatch), so checking the
            // actual constructed type is what correctly accepts that case too, not just alphaBackend="helix"
            // literally.
            alphaStream match
              case helix: DelaunayAlphaShapes =>
                if helix.ambientDimension < 2 then
                  throw new IllegalArgumentException(
                    s"engine=fast-alpha requires ambient dimension >= 2, got a " +
                      s"${helix.ambientDimension}-dimensional point cloud. Use engine=naive, engine=chunks, or " +
                      s"engine=cohomology instead."
                  )
                // FastAlphaHomologyEngine's own FastAlphaTriangulationException (a rare, real HelixDelaunay
                // triangulation limitation -- see that class's own doc) is deliberately NOT caught and
                // rewrapped here: its own message is already written for an unsuspecting MATLAB/CLI caller,
                // not just a Scala developer, the same way NoIntegerCocycleException's own message already is
                // for circularCoordinates -- catching and re-throwing a DIFFERENT exception here would only
                // lose the original's own stack trace for no benefit.
                fromBars[Simplex[Int], C](
                  FastAlphaHomologyEngine[C]().persistentHomology(helix, includeZeroLength = true),
                  alphaCellVertices,
                  toDouble,
                  Int.MaxValue,
                  alphaBoundaryMatrixOf
                )
              case _ =>
                throw new IllegalArgumentException(
                  s"engine=fast-alpha requires alphaBackend=helix (FastAlphaHomologyEngine is specialized to " +
                    "HelixDelaunay's own triangulation and cannot consume AlphaShapeDQP's output at all) -- got " +
                    s"alphaBackend=$alphaBackend."
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
              naiveFor[Simplex[Int], C](cycles).barcode(cechStreamForBoundary, includeZeroLength = true),
              cechCellVertices,
              toDouble,
              requestedMaxDimension,
              cechBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // CellularPersistenceInChunksEngine handles the "+1" dance internally (its own maxDim constructor
            // parameter means "top reported degree," fixed at the source -- see .claude/WORKLOG-maxdim-semantics-
            // fix.md), and CechCofaceSimplexStream's own iterateDimension is already naturally bounded (inherited
            // from RipserCofaceSimplexStream's `d < metricSpace.size` guard), so no LimitedCofaceSimplexStream
            // wrapping is needed here -- mirroring engine=Chunks's own complex=vr case above exactly. Cross-
            // validated against the naive engine directly on Cech streams in CechStreamSpec (not assumed to carry
            // over from VR/cubical/simplicial-set validation, since this combination had never been exercised
            // before).
            val stream = CechCofaceSimplexStream(euclideanMetricSpace, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              chunksFor[Simplex[Int], C](requestedMaxDimension, cycles)
                .barcode(stream, includeZeroLength = true),
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
              cohomologyFor[Simplex[Int], C](cycles).barcode(cechStreamForBoundary, includeZeroLength = true),
              cechCellVertices,
              toDouble,
              requestedMaxDimension,
              cechBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical | EngineKind.FastAlpha =>
            // dispatch() already rejects all of these for complex=cech before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=cech")
      case ComplexKind.DtmRips =>
        // Just as unboundedly deep as complex=vr/complex=cech -- the same "build one dimension higher, drop it"
        // dance for engine=Naive/Cohomology, for the identical reason (H_k needs (k+1)-dimensional chains).
        // Unlike complex=alpha/complex=cech, needs no real coordinates -- DistanceToMeasure only needs a
        // FiniteMetricSpace, so this works from computeFromDistanceMatrix too (BruteForce k-NN, not JVPTree:
        // metricSpace here may not obey the triangle inequality -- see DistanceToMeasure's own doc).
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
              naiveFor[Simplex[Int], C](cycles).barcode(dtmStreamForBoundary, includeZeroLength = true),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            fromBars[Simplex[Int], C](
              chunksFor[Simplex[Int], C](requestedMaxDimension, cycles)
                .barcode(dtmStream, includeZeroLength = true),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(dtmStreamForBoundary, includeZeroLength = true),
              dtmCellVertices,
              toDouble,
              requestedMaxDimension,
              dtmBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical | EngineKind.FastAlpha =>
            // dispatch() already rejects all of these for complex=dtm-rips before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=dtm-rips")
      case ComplexKind.SparseRips =>
        // Just as unboundedly deep as complex=vr/complex=cech/complex=dtm-rips -- the same "build one dimension
        // higher, drop it" dance for engine=Naive/Cohomology, for the identical reason (H_k needs (k+1)-dimensional
        // chains). Needs no real coordinates -- SheehyRipsSimplexStream only needs a FiniteMetricSpace (the
        // greedy permutation it builds on is purely metric), so this works from computeFromDistanceMatrix too.
        // maxFiltrationValue is passed straight through: SheehyRipsSimplexStream's own constructor always clamps
        // it to maxFiniteFiltrationValue regardless (see that class's own doc), so there is no separate "resolve
        // the omitted-key default here" step the way complex=vr/complex=cech need.
        val sparseStream = SheehyRipsSimplexStream(metricSpace, sparseEpsilon, maxFiltrationValue = maxFiltrationValue)
        val sparseCellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
        val sparseStreamForBoundary = LimitedCofaceSimplexStream(sparseStream, requestedMaxDimension + 1)
        val sparseBoundaryMatrixOf = () =>
          buildBoundaryMatrix[Simplex[Int], C](
            sparseStreamForBoundary.iterator.toIndexedSeq,
            sparseCellVertices,
            toDouble,
            sparseStreamForBoundary.filtrationValue
          )
        engine match
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              naiveFor[Simplex[Int], C](cycles).barcode(sparseStreamForBoundary, includeZeroLength = true),
              sparseCellVertices,
              toDouble,
              requestedMaxDimension,
              sparseBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            fromBars[Simplex[Int], C](
              chunksFor[Simplex[Int], C](requestedMaxDimension, cycles)
                .barcode(sparseStream, includeZeroLength = true),
              sparseCellVertices,
              toDouble,
              requestedMaxDimension,
              sparseBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(sparseStreamForBoundary, includeZeroLength = true),
              sparseCellVertices,
              toDouble,
              requestedMaxDimension,
              sparseBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.FastCubical | EngineKind.FastAlpha =>
            // dispatch() already rejects all of these for complex=sparse-rips before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=sparse-rips")
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
              naiveFor[Simplex[Int], C](cycles).barcode(dtmAlphaStream, includeZeroLength = true),
              dtmAlphaCellVertices,
              toDouble,
              Int.MaxValue,
              dtmAlphaBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(dtmAlphaStream, includeZeroLength = true),
              dtmAlphaCellVertices,
              toDouble,
              Int.MaxValue,
              dtmAlphaBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical | EngineKind.FastAlpha =>
            // dispatch() already rejects all of these for complex=dtm-alpha before computeGeneric is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for complex=dtm-alpha")
      case ComplexKind.Witness =>
        computeWitnessFromLandmarks[C](
          metricSpace,
          witnessLandmarks,
          witnessVariant,
          engine,
          cycles,
          witnessNu,
          requestedMaxDimension,
          maxFiltrationValue,
          toDouble
        )

  /** The witness complex's persistence for a resolved landmark set, shared by the one-shot and two-step paths. Its
    * cells are over landmark numbers `0 until landmarks.size`; `cellVertices` maps them back to point numbers. Both
    * variants are built one dimension above `requestedMaxDimension` for the naive and cohomology engines, which
    * `fromBars` drops.
    */
  private def computeWitnessFromLandmarks[C](
    metricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int],
    witnessVariant: WitnessVariantKind,
    engine: EngineKind,
    cycles: Boolean,
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
            // the case PackedRipserCohomologyEngine is proven for (any FiniteMetricSpace[Int] diameter), not
            // VR-specific at all despite the class's own name -- see WitnessMetricSpace's own doc and
            // WitnessStreamSpec's direct cross-check against the naive engine.
            val geometry = WitnessGeometry(metricSpace, landmarks)
            val wms = WitnessMetricSpace(geometry, nu)
            val ctx =
              PackedRipserCohomologyEngine[C](wms, requestedMaxDimension, maxFiltrationValue = maxFiltrationValue)
            fromBars[ctx.DiameterIndex, C](
              if cycles then ctx.persistentHomology(includeZeroLength = true)
              else ctx.persistentCohomology(includeZeroLength = true),
              (dim, cell) => ctx.si.decodeToArray(cell.index, dim + 1).map(landmarks),
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Naive =>
            fromBars[Simplex[Int], C](
              naiveFor[Simplex[Int], C](cycles).barcode(lazyStreamForBoundary, includeZeroLength = true),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Chunks =>
            // No LimitedCofaceSimplexStream wrapping needed -- PersistenceInChunksEngine handles the "+1"
            // dance internally, and LazyWitnessSimplexStream's own iterateDimension is already naturally
            // bounded (inherited from RipserCofaceSimplexStream), mirroring complex=cech's own chunks case.
            val stream = LazyWitnessSimplexStream(metricSpace, landmarks, nu, maxFiltrationValue = maxFiltrationValue)
            fromBars[Simplex[Int], C](
              chunksFor[Simplex[Int], C](requestedMaxDimension, cycles)
                .barcode(stream, includeZeroLength = true),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(lazyStreamForBoundary, includeZeroLength = true),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              lazyBoundaryMatrixOf
            )
          case EngineKind.FastCubical | EngineKind.FastAlpha =>
            // Both dispatch() (one-shot) and dispatchWitnessFromLandmarks (step 2) already reject both of these
            // via resolveWitnessEngine before this method is ever reached.
            throw new IllegalArgumentException(s"engine=$engine is not offered for witnessVariant=lazy")
      case WitnessVariantKind.General =>
        // Not a flag complex -- minimumEnclosingRadius is not a valid truncation here (see
        // WitnessCofaceSimplexStream's own doc), so an unset maxFiltrationValue means +Infinity,
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
              naiveFor[Simplex[Int], C](cycles).barcode(generalStreamForBoundary, includeZeroLength = true),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              generalBoundaryMatrixOf
            )
          case EngineKind.Cohomology =>
            fromBars[Simplex[Int], C](
              cohomologyFor[Simplex[Int], C](cycles).barcode(generalStreamForBoundary, includeZeroLength = true),
              cellVertices,
              toDouble,
              requestedMaxDimension,
              generalBoundaryMatrixOf
            )
          case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical | EngineKind.FastAlpha =>
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
    val threshold = parseThresholdSpec(opts)
    threshold(dispatchWitnessFromLandmarksFull(opts, metricSpace, landmarks), metricSpace.minimumEnclosingRadius)

  private def dispatchWitnessFromLandmarksFull(
    opts: Map[String, String],
    metricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int]
  ): PersistenceResult =
    parseWitnessComplexOption(opts)
    val witnessVariant = resolveWitnessVariant(opts)
    val engine = resolveWitnessEngine(opts, witnessVariant)
    val cycles = wantsCycles(opts, engine)
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
        cycles,
        nu,
        maxDimension,
        maxFiltrationValue,
        toDouble
      )
    }

  // ---------------------------------------------------------------------------------------------------------------
  // dispatch for computeFromRelation -- a separate function from dispatch/computeGeneric above (not a new
  // "complex" branch inside them) because a Dowker relation carries no FiniteMetricSpace[Int]/point cloud at all,
  // the type dispatch()/computeGeneric are built around (same reason dispatchCubical below is separate).
  // ---------------------------------------------------------------------------------------------------------------

  private def dispatchDowker(opts: Map[String, String], relation: Array[Array[Double]]): PersistenceResult =
    val threshold = parseThresholdSpec(opts)
    threshold(dispatchDowkerFull(opts, relation), valueRange(relation.iterator.flatten))

  private def dispatchDowkerFull(opts: Map[String, String], relation: Array[Array[Double]]): PersistenceResult =
    val engine = EngineKind.parse(opts.getOrElse("engine", "cohomology"))
    val cycles = wantsCycles(opts, engine)
    if engine == EngineKind.FastCubical then
      throw new IllegalArgumentException(
        "engine=fast-cubical is not offered for computeFromRelation: FastCubicalHomologyEngine is specialized " +
          "to CubicalGridStream and has no notion of a Dowker complex at all."
      )
    if engine == EngineKind.FastAlpha then
      throw new IllegalArgumentException(
        "engine=fast-alpha is not offered for computeFromRelation: FastAlphaHomologyEngine is specialized to " +
          "HelixDelaunay and has no notion of a Dowker complex at all."
      )
    if engine == EngineKind.Ripser then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used with computeFromRelation: the Dowker complex is not a flag complex in " +
          "general, so the Ripser engine does not apply. Use engine=naive or engine=cohomology."
      )
    if engine == EngineKind.Chunks then
      throw new IllegalArgumentException(
        "engine=chunks is not offered for computeFromRelation -- use engine=naive or engine=cohomology " +
          "(same status complex=witness/witnessVariant=general has)."
      )
    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(2)
    val maxFiltrationValue: Option[Double] =
      opts.get("maxfiltrationvalue").map(parseDoubleOption("maxFiltrationValue", _))
    val dual = opts.get("dual").exists(v => parseBooleanOption("dual", v))
    dispatchByField(opts) { [C] => (toDouble: C => Double) =>
      computeDowker[C](relation, engine, cycles, maxDimension, maxFiltrationValue, dual, toDouble)
    }

  /** The Dowker complex is not a flag complex, so the enclosing radius is no cutoff: an unset `maxFiltrationValue` is
    * `Infinity`. Built one dimension above `requestedMaxDimension`, which `fromBars` drops.
    */
  private def computeDowker[C](
    relation: Array[Array[Double]],
    engine: EngineKind,
    cycles: Boolean,
    requestedMaxDimension: Int,
    maxFiltrationValue: Option[Double],
    dual: Boolean,
    toDouble: C => Double
  )(using C is Field): PersistenceResult =
    val geometry =
      val base = DowkerGeometry(relation)
      if dual then base.dual else base
    val resolvedMaxFiltrationValue = maxFiltrationValue.getOrElse(Double.PositiveInfinity)
    val cellVertices: (Int, Simplex[Int]) => Array[Int] = (_, cell) => cell.underlying.toArray
    val streamForBoundary = LimitedCofaceSimplexStream(
      DowkerCofaceSimplexStream(geometry, resolvedMaxFiltrationValue),
      requestedMaxDimension + 1
    )
    val boundaryMatrixOf = () =>
      buildBoundaryMatrix[Simplex[Int], C](
        streamForBoundary.iterator.toIndexedSeq,
        cellVertices,
        toDouble,
        streamForBoundary.filtrationValue
      )
    engine match
      case EngineKind.Naive =>
        fromBars[Simplex[Int], C](
          naiveFor[Simplex[Int], C](cycles).barcode(streamForBoundary, includeZeroLength = true),
          cellVertices,
          toDouble,
          requestedMaxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Cohomology =>
        fromBars[Simplex[Int], C](
          cohomologyFor[Simplex[Int], C](cycles).barcode(streamForBoundary, includeZeroLength = true),
          cellVertices,
          toDouble,
          requestedMaxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Ripser | EngineKind.Chunks | EngineKind.FastCubical | EngineKind.FastAlpha =>
        // dispatchDowker already rejects every one of these before this method is ever reached.
        throw new IllegalArgumentException(s"engine=$engine is not offered for computeFromRelation")

  // ---------------------------------------------------------------------------------------------------------------
  // dispatch for computeFromCubicalImage/computeFromImage -- a separate function from dispatch/computeGeneric
  // above (not a new "complex" branch inside them) because a cubical grid carries no FiniteMetricSpace[Int] at
  // all, the type dispatch()/computeGeneric are built around.
  // ---------------------------------------------------------------------------------------------------------------

  private def dispatchCubical(
    opts: Map[String, String],
    stream: CubicalGridStream,
    valueRangeOfImage: => Double
  ): PersistenceResult =
    val threshold = parseThresholdSpec(opts)
    threshold(dispatchCubicalFull(opts, stream), valueRangeOfImage)

  private def dispatchCubicalFull(opts: Map[String, String], stream: CubicalGridStream): PersistenceResult =
    val engine =
      EngineKind.parse(
        opts.getOrElse(
          "engine",
          if stream.ambientDim >= 2 && !opts.get("representativetype").exists(_.equalsIgnoreCase("cocycles"))
          then "fast-cubical"
          else "cohomology"
        )
      )
    val cycles = wantsCycles(opts, engine)
    if engine == EngineKind.Ripser then
      throw new IllegalArgumentException(
        "engine=ripser cannot be used for a cubical complex: PackedRipserCohomologyEngine is specialized to " +
          "Simplex[Int] Vietoris-Rips complexes and has no notion of a cubical complex at all. Use engine=naive, " +
          "engine=chunks, engine=cohomology, or (ambient dimension 2 only) engine=fast-cubical."
      )
    if engine == EngineKind.FastAlpha then
      throw new IllegalArgumentException(
        "engine=fast-alpha cannot be used for a cubical complex: FastAlphaHomologyEngine is specialized to " +
          "HelixDelaunay and has no notion of a cubical complex at all. Use engine=fast-cubical for a cubical " +
          "grid's own fast engine, or engine=naive/chunks/cohomology otherwise."
      )
    // FastCubicalHomologyEngine's own `require` throws IllegalArgumentException too, but with a message written
    // for a library caller who already has a `CubicalGridStream` in hand, not a MATLAB/CLI caller who only
    // supplied a `shape`/`flatValues` array -- catching it here first gives an error that names the actual
    // option/argument to change. ambientDim < 2 is the only remaining rejection (a degenerate 1-axis "image"):
    // ambientDim >= 3 is the hybrid path (.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md), no longer
    // rejected here at all -- see that note for why there's no principled place to draw a NEW hardcoded ceiling
    // (chunks, which the hybrid path hands the residual middle dimensions to, is already fully general over d).
    if engine == EngineKind.FastCubical && stream.ambientDim < 2 then
      throw new IllegalArgumentException(
        s"engine=fast-cubical requires ambient dimension >= 2, got a ${stream.ambientDim}-dimensional shape. " +
          s"Use engine=naive, engine=chunks, or engine=cohomology instead."
      )
    // Default: the grid's own ambient dimension, i.e. "give me everything" -- correctly parallel to complex=alpha
    // above (a cubical grid's own top dimension is already naturally bounded, never artificially truncated the
    // way VR/Cech are), NOT to complex=vr's default of 2.
    val maxDimension = opts.get("maxdimension").map(parseIntOption("maxDimension", _)).getOrElse(stream.ambientDim)

    CoefficientKind.parse(opts.getOrElse("field", "z")) match
      case CoefficientKind.Z =>
        val prime = opts.get("prime").map(parseIntOption("prime", _)).getOrElse(FiniteField.DefaultPrime)
        val ff = new FiniteField(prime)
        import ff.given
        computeCubicalGeneric[ff.Fp](stream, engine, cycles, maxDimension, _.toInt.toDouble)
      case CoefficientKind.R =>
        val epsilon = opts.get("epsilon").map(parseDoubleOption("epsilon", _)).getOrElse(1e-9)
        given Double is Field = Field.DoubleApproximated(epsilon)
        computeCubicalGeneric[Double](stream, engine, cycles, maxDimension, identity)

  private def computeCubicalGeneric[C](
    stream: CubicalGridStream,
    engine: EngineKind,
    cycles: Boolean,
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
        // CellularHomologyEngine[Cube, ...] has no maxDim of its own at all, same as Simplex[Int].
        fromBars[Cube, C](
          naiveFor[Cube, C](cycles).barcode(stream, includeZeroLength = true),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Chunks =>
        // Unlike the naive path above, maxDimension IS passed through here as a genuine, correct truncation --
        // CellularPersistenceInChunksEngine handles the "+1" dance internally (see .claude/WORKLOG-maxdim-
        // semantics-fix.md), so this can skip real work for a caller who only wants low-dimensional homology, not
        // just filter what's reported after the fact.
        fromBars[Cube, C](
          chunksFor[Cube, C](maxDimension, cycles).barcode(stream, includeZeroLength = true),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Cohomology =>
        // Same shape as engine=Naive above: no stream-level dimension cap (a cubical grid's own top dimension
        // is already naturally bounded), CellularCohomologyEngine computes to that natural top dimension, and
        // maxDimension is applied purely as a post-hoc filter via fromBars.
        fromBars[Cube, C](
          cohomologyFor[Cube, C](cycles).barcode(stream, includeZeroLength = true),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.FastCubical =>
        // Called directly, like engine=Ripser below, not through PersistenceEngine[CellT,C]: FastCubicalHomologyEngine
        // is specialized to the concrete CubicalGridStream (its dual-graph construction reads `.shape`/`.ambientDim`/
        // `.topCellValue` directly), not generic over `CellT: OrderedCell` the way naive/chunks/cohomology are -- the
        // same "honest asymmetry" PersistenceEngine's own doc comment already states for Ripser. dispatchCubical
        // already rejected ambientDim < 2 before this is ever reached; maxDimension is passed through to fromBars
        // for real filtering now that ambientDim >= 3 is possible here too (unlike the old ambientDim=2-only
        // engine, where the grid's own natural top dimension was always <= 1 and nothing was ever filtered).
        fromBars[Cube, C](
          FastCubicalHomologyEngine[C]().persistentHomology(stream, includeZeroLength = true),
          cellVertices,
          toDouble,
          maxDimension,
          boundaryMatrixOf
        )
      case EngineKind.Ripser =>
        // dispatchCubical already rejects this before computeCubicalGeneric is ever reached.
        throw new IllegalArgumentException("engine=ripser is not offered for a cubical complex")
      case EngineKind.FastAlpha =>
        // dispatchCubical already rejects this before computeCubicalGeneric is ever reached.
        throw new IllegalArgumentException("engine=fast-alpha is not offered for a cubical complex")

  // ---------------------------------------------------------------------------------------------------------------
  // barcode/chain -> PersistenceResult conversion
  // ---------------------------------------------------------------------------------------------------------------

  private def endpointToDouble(e: BarcodeEndpoint[Double]): Double = e match
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v

  /** `cellVertices(dim, cell)` gives a chain cell's vertex array; it takes the bar's degree `dim` because the Ripser
    * engine's packed cells do not carry their size.
    */
  /** The boundary matrix of `cells` (in filtration order), one column per cell. Built from the same stream the naive
    * engine reads for that complex, whichever engine computed the bars, so it is the same for every `engine`.
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
