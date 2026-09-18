package org.appliedtopology.tda4j.matlab

/** A finished persistence computation, in a shape callable directly from MATLAB (or any other plain-Java caller) via
  * MATLAB's built-in Java interface: every public method here takes/returns only `int`, `double`, `double[][]`, or
  * `int[][]` -- no Scala types, no generics, no `java.util.Map` -- since none of those marshal reliably across MATLAB's
  * Java bridge. See `Tda4j` for how this gets constructed and `WORKLOG-matlab-api.md` for the design rationale,
  * including what was deliberately left out of this first pass.
  *
  * Bars are indexed `0` until `size() - 1`, in no particular guaranteed order (the underlying engines don't sort their
  * output beyond grouping by dimension). An essential (never-dying) class reports `death(i) ==
  * Double.POSITIVE_INFINITY`.
  *
  * Representative-chain access (`cycleVertices`/`cycleCoefficients`) is best-effort and engine-dependent -- see the
  * per-method doc. For `engine="ripser"` the chain is a representative *cocycle*; for `engine="naive"` it is a
  * representative *cycle*. Both are reported the same way here (a list of simplices, each given as its sorted vertex
  * array, with a parallel coefficient array) because MATLAB-side code that only wants "the simplices spanning this bar"
  * doesn't need to care which. Boundary-matrix export is not implemented yet -- flagged as a follow-up in
  * WORKLOG-matlab-api.md, not silently missing.
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

  /** The simplices making up bar `i`'s representative chain, each as its sorted array of vertex indices (0-based,
    * matching the row indices of whatever point/distance matrix was passed to `Tda4j`). Throws
    * `UnsupportedOperationException` if this bar's engine doesn't record representative chains (`engine="chunks"`
    * currently never does) or if this specific bar has no recorded representative (can happen for `engine="ripser"`,
    * whose apparent-pairs shortcut skips writing one down for some bars).
    */
  def cycleVertices(i: Int): Array[Array[Int]] = cycleProvider(i)._1

  /** Coefficients parallel to `cycleVertices(i)`. Reported as `double` regardless of the underlying coefficient field
    * -- for a finite field `Z/pZ` this is the representative integer value cast to `double`, for the default
    * real-valued field it's the value itself. See `cycleVertices` for the exceptions this can throw.
    */
  def cycleCoefficients(i: Int): Array[Double] = cycleProvider(i)._2
