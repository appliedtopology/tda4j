package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

/** A finished persistence computation, in a shape callable directly from MATLAB (or any other plain-Java caller) via
  * MATLAB's built-in Java interface: every public method here takes/returns only `int`, `double`, `double[][]`,
  * `int[][]`, or another `PersistenceResult` handle (MATLAB's Java bridge holds and passes those around like any other
  * Java object -- already established by `TDA4j.computeFrom*` itself returning one) -- no other Scala types, no
  * generics, no `java.util.Map` -- since none of those marshal reliably across MATLAB's Java bridge. See `TDA4j` for
  * how this gets constructed and `WORKLOG-matlab-api.md` for the design rationale, including what was deliberately left
  * out of this first pass.
  *
  * Bars are indexed `0` until `size() - 1`, in no particular guaranteed order (the underlying engines don't sort their
  * output beyond grouping by dimension). An essential (never-dying) class reports `death(i) ==
  * Double.POSITIVE_INFINITY`.
  *
  * Representative-chain access (`cycleVertices`/`cycleCoefficients`) is engine-dependent in *what kind* of chain it
  * returns, not in *whether* one is available -- every engine records one for every bar; see the per-method doc. For
  * `engine="ripser"` the chain is a representative *cocycle*; for `engine="naive"` it is a representative *cycle*. Both
  * are reported the same way here (a list of simplices, each given as its sorted vertex array, with a parallel
  * coefficient array) because MATLAB-side code that only wants "the simplices spanning this bar" doesn't need to care
  * which. Boundary-matrix export is not implemented yet -- flagged as a follow-up in WORKLOG-matlab-api.md, not
  * silently missing.
  */
final class PersistenceResult private[matlab] (
  private val dims: Array[Int],
  private val births: Array[Double],
  private val deaths: Array[Double],
  private val cycleProvider: Int => (Array[Array[Int]], Array[Double])
):
  def size(): Int = dims.length

  /** The whole barcode as one N-by-3 matrix: column 0 is dimension, column 1 is birth, column 2 is death (`+Inf` for an
    * essential class). This is the primary, MATLAB-idiomatic way to consume a result -- immediately plottable,
    * sortable, filterable with ordinary MATLAB matrix operations.
    */
  def toArray(): Array[Array[Double]] =
    Array.tabulate(dims.length) { i =>
      Array(dims(i).toDouble, births(i), deaths(i))
    }

  def dimension(i: Int): Int = dims(i)
  def birth(i: Int): Double = births(i)
  def death(i: Int): Double = deaths(i)

  /** The cells making up bar `i`'s representative chain, each as an `int[]` identifying that cell -- the array's own
    * meaning depends on which complex this result came from, since the underlying cell type differs:
    *
    *   - `complex="vr"`/`"alpha"`/`"cech"` (a `Simplex[Int]`): the simplex's sorted vertex array (0-based, matching the
    *     row indices of whatever point/distance matrix was passed to `TDA4j`).
    *   - `complex="cubical"` (a `Cube`): the cell's own doubled-coordinate encoding (`Cube.encoded`, see
    *     `Cubical.scala`) -- NOT vertex indices. Axis `k`'s entry is `2*a` for a degenerate (point) factor at lattice
    *     coordinate `a`, or `2*a+1` for a non-degenerate (unit-interval) factor spanning `[a, a+1]`; decode coordinate
    *     `k` as `a = v(k)/2` (integer division) plus, when `v(k)` is odd, a unit interval starting there.
    *
    * Throws `UnsupportedOperationException` if this specific bar has no recorded representative. Every engine records
    * one for every bar, at every dimension, for every complex type above, so this indicates an engine bug rather than
    * an expected gap -- see `.claude/CLAUDE.md`'s coefficients-and-representatives design principle for why this
    * matters.
    */
  def cycleVertices(i: Int): Array[Array[Int]] = cycleProvider(i)._1

  /** Coefficients parallel to `cycleVertices(i)`. Reported as `double` regardless of the underlying coefficient field
    * -- for a finite field `Z/pZ` this is the representative integer value cast to `double`, for the default
    * real-valued field it's the value itself. See `cycleVertices` for the exceptions this can throw.
    */
  def cycleCoefficients(i: Int): Array[Double] = cycleProvider(i)._2

  /** This result's own bars of dimension `dim`, as plain `PersistenceBar[Double, Nothing]` (no representative chain --
    * [[BarcodeDistance]]/[[Vectorization]] only ever look at `dim`/`lower`/`upper`) for feeding into those two objects.
    * An essential class (`death(i) == Double.PositiveInfinity`) becomes a `PositiveInfinity` upper endpoint, exactly
    * what both consume directly for the essential-bar handling documented on each.
    */
  private def barsOfDimension(dim: Int): IndexedSeq[PersistenceBar[Double, Nothing]] =
    (0 until size())
      .filter(dims(_) == dim)
      .map { i =>
        val upper: BarcodeEndpoint[Double] =
          if deaths(i).isPosInfinity then PositiveInfinity[Double]() else OpenEndpoint(deaths(i))
        PersistenceBar[Double, Nothing](dim, ClosedEndpoint(births(i)), upper)
      }

  /** `groundNorm` follows this facade's own existing `maxFiltrationValue` convention (`TDA4j`'s own doc: "pass a very
    * large number for the old always-unbounded behavior") rather than introducing a new one: `Double.PositiveInfinity`
    * means [[BarcodeDistance.GroundNorm.LInfinity]] (the usual TDA convention, and the default every method below
    * without a `groundNorm` parameter uses), any finite value `p >= 1.0` means [[BarcodeDistance.GroundNorm.LP]].
    */
  private def toGroundNorm(groundNorm: Double): BarcodeDistance.GroundNorm =
    if groundNorm.isPosInfinity then BarcodeDistance.GroundNorm.LInfinity else BarcodeDistance.GroundNorm.LP(groundNorm)

  /** Bottleneck distance (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 4) between this result's and
    * `other`'s dimension-`dimension` bars, under the L-infinity ground norm -- see [[BarcodeDistance]] for the full
    * matching/essential-bar policy this implements. `Double.PositiveInfinity` back means the two diagrams have
    * different numbers of essential (never-dying) classes in this dimension, so no finite matching exists; that is a
    * real answer, not a failure.
    */
  def bottleneckDistance(other: PersistenceResult, dimension: Int): Double =
    bottleneckDistance(other, dimension, Double.PositiveInfinity)

  /** As the two-argument [[bottleneckDistance]], under an explicit ground norm (see `toGroundNorm`'s own doc for the
    * `Double.PositiveInfinity`-means-L-infinity convention).
    */
  def bottleneckDistance(other: PersistenceResult, dimension: Int, groundNorm: Double): Double =
    BarcodeDistance.bottleneckDistance(
      barsOfDimension(dimension),
      other.barsOfDimension(dimension),
      toGroundNorm(groundNorm)
    )

  /** Wasserstein distance, order `1.0`, L-infinity ground norm -- see [[bottleneckDistance]] and [[BarcodeDistance]]
    * for the shared essential-bar policy and ground-norm convention.
    */
  def wassersteinDistance(other: PersistenceResult, dimension: Int): Double =
    wassersteinDistance(other, dimension, 1.0, Double.PositiveInfinity)

  /** As the two-argument [[wassersteinDistance]], under an explicit order (must be finite and `>= 1.0` -- pass
    * `Double.PositiveInfinity` to `bottleneckDistance` directly instead, rather than here).
    */
  def wassersteinDistance(other: PersistenceResult, dimension: Int, order: Double): Double =
    wassersteinDistance(other, dimension, order, Double.PositiveInfinity)

  /** As the two-argument [[wassersteinDistance]], under an explicit order and ground norm. */
  def wassersteinDistance(other: PersistenceResult, dimension: Int, order: Double, groundNorm: Double): Double =
    BarcodeDistance.wassersteinDistance(
      barsOfDimension(dimension),
      other.barsOfDimension(dimension),
      order,
      toGroundNorm(groundNorm)
    )

  /** The first `numLevels` persistence landscape functions (Bubenik 2013, `.claude/WORKLOG-mainstream-feature-gap-
    * analysis.md` item 8) of this result's dimension-`dimension` bars, sampled at `resolution` evenly-spaced points
    * across `[tMin, tMax]` -- see [[Vectorization.landscape]] for the exact sampling convention, the closed-form check
    * it satisfies, and why an essential (never-dying) bar needs no special handling here. Returns `levels(k)(j)`: level
    * `k` (`0` = the outer envelope) at the `j`-th sample point.
    */
  def landscape(dimension: Int, numLevels: Int, tMin: Double, tMax: Double, resolution: Int): Array[Array[Double]] =
    Vectorization.landscape(barsOfDimension(dimension), numLevels, tMin, tMax, resolution)

  /** The persistence image (Adams et al. 2017, `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 8) of this
    * result's dimension-`dimension` bars, with the weight cap defaulted to that diagram's own maximum finite
    * persistence (the paper's suggested default) -- see the eight-argument overload to pass one explicitly, and
    * [[Vectorization.persistenceImage]] for the exact construction (isotropic Gaussian bumps in birth-persistence
    * coordinates, exact per-pixel integration, piecewise-linear weighting) and why essential (never-dying) bars are
    * dropped rather than given a special-cased value. Returns `image(r)(c)`: `r` indexes `birthResolution` pixels
    * spanning `[birthMin, birthMax]`, `c` indexes `persistenceResolution` pixels spanning `[persistenceMin,
    * persistenceMax]`.
    */
  def persistenceImage(
    dimension: Int,
    sigma: Double,
    birthMin: Double,
    birthMax: Double,
    persistenceMin: Double,
    persistenceMax: Double,
    birthResolution: Int,
    persistenceResolution: Int
  ): Array[Array[Double]] =
    Vectorization.persistenceImage(
      barsOfDimension(dimension),
      sigma,
      (birthMin, birthMax),
      (persistenceMin, persistenceMax),
      birthResolution,
      persistenceResolution
    )

  /** As the eight-argument [[persistenceImage]], with an explicit weight cap (the persistence value at and beyond which
    * [[Vectorization]]'s piecewise-linear weighting saturates to `1.0`) instead of the diagram's own maximum finite
    * persistence.
    */
  def persistenceImage(
    dimension: Int,
    sigma: Double,
    birthMin: Double,
    birthMax: Double,
    persistenceMin: Double,
    persistenceMax: Double,
    birthResolution: Int,
    persistenceResolution: Int,
    weightCap: Double
  ): Array[Array[Double]] =
    Vectorization.persistenceImage(
      barsOfDimension(dimension),
      sigma,
      (birthMin, birthMax),
      (persistenceMin, persistenceMax),
      birthResolution,
      persistenceResolution,
      Some(weightCap)
    )
