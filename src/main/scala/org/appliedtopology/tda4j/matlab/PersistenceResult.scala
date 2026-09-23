package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

/** A finished persistence computation, in a shape callable directly from MATLAB (or any other plain-Java caller) via
  * MATLAB's built-in Java interface: every public method here takes/returns only `int`, `double`, `double[][]`, or
  * `int[][]` -- no Scala types, no generics, no `java.util.Map` -- since none of those marshal reliably across MATLAB's
  * Java bridge. See `TDA4j` for how this gets constructed and `WORKLOG-matlab-api.md` for the design rationale,
  * including what was deliberately left out of this first pass.
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
