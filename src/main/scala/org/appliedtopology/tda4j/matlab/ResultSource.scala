package org.appliedtopology.tda4j.matlab

/** What a [[PersistenceResult]] was computed from: enough to draw its representatives over its input, and to say in a
  * saved result how it was made.
  *
  * @param method
  *   the `TDA4j` entry point (`computeFromPoints`, ...)
  * @param options
  *   the options as passed, name-value
  * @param complex
  *   `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sparse-rips`, `cubical` or `dowker`
  * @param cocycles
  *   whether the representatives are cocycles (option `representativeType`)
  * @param field
  *   `Z` (a prime field), `R` (the reals), or empty when a loaded file does not say
  * @param prime
  *   the prime of field `Z`
  * @param landmarks
  *   the witness complex's landmarks, when they were passed in (empty otherwise)
  * @param relativeTo
  *   the vertices of the subcomplex a relative computation was modulo (empty otherwise)
  */
private[matlab] final case class ResultSource(
  method: String,
  options: Array[String],
  complex: String,
  cocycles: Boolean,
  field: String,
  prime: Int,
  input: ResultInput,
  landmarks: Array[Int],
  relativeTo: Array[Int]
)

/** The input a result's cells live on. */
private[matlab] enum ResultInput:
  /** One row per point; a simplex's vertices are row numbers. */
  case Points(points: Array[Array[Double]])

  /** An image or voxel grid, values in row-major order as given (not negated for a superlevel filtration; NaN for a
    * masked pixel); a cube's coordinates are doubled grid coordinates.
    */
  case Image(shape: Array[Int], values: Array[Double])

  /** No coordinates to draw on: a `distance-matrix`, a `relation`, or what a loaded file names. */
  case Abstract(kind: String)

  /** The input in words, after "computed from". */
  def words: String = this match
    case Points(_)      => "a point cloud"
    case Image(_, _)    => "an image"
    case Abstract(kind) => s"a ${kind.replace('-', ' ')}"

private[matlab] object ResultSource:
  /** The source of a computation: `options` as passed, `opts` as `TDA4j` parsed them (lower-case keys), after the
    * computation validated them.
    */
  def apply(
    method: String,
    options: Array[String],
    opts: Map[String, String],
    complex: String,
    input: ResultInput,
    landmarks: Array[Int] = Array.emptyIntArray,
    relativeTo: Array[Int] = Array.emptyIntArray
  ): ResultSource =
    val reals = opts.get("field").exists(_.equalsIgnoreCase("r"))
    val prime =
      opts.get("prime").flatMap(_.toIntOption).getOrElse(org.appliedtopology.tda4j.FiniteField.DefaultPrime)
    ResultSource(
      method,
      options.clone(),
      complex,
      opts.get("representativetype").exists(_.equalsIgnoreCase("cocycles")),
      if reals then "R" else "Z",
      prime,
      input,
      landmarks.clone(),
      relativeTo.clone()
    )
