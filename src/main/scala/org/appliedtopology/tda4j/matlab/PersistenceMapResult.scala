package org.appliedtopology.tda4j.matlab

import scala.language.experimental.modularity

/** The map on persistent homology induced by a map of Vietoris-Rips complexes, for MATLAB and Java: every method takes
  * and returns only `int`, `double` and arrays of those (`TDA4j.computeInclusionFromPoints` and its relatives make
  * one).
  *
  * In each degree `k` from 0 to [[maxDimension]] the map is a matrix with a row for each bar of the target and a column
  * for each bar of the source ([[sourceBars]] and [[targetBars]], the bars of positive length of that degree in the
  * order the matrix uses). [[entries]] lists its nonzero entries, and [[image]], [[kernel]] and [[cokernel]] are the
  * barcodes of what survives from the source in the target, what the map sends to zero and when, and what only the
  * target has. Each barcode is an N-by-2 matrix of birth and death (`Infinity` for a class that never dies).
  */
final class PersistenceMapResult private[matlab] (
  private val prime: Int,
  private val degrees: IndexedSeq[PersistenceMapResult.Degree]
):
  /** The top degree computed. */
  def maxDimension(): Int = degrees.length - 1

  /** The prime `p` of the coefficient field `Z/p` both diagrams were computed over. */
  def characteristic(): Int = prime

  private def degree(k: Int): PersistenceMapResult.Degree =
    if k < 0 || k >= degrees.length then
      throw new IllegalArgumentException(s"no degree $k: the map was computed in degrees 0 to ${maxDimension()}")
    degrees(k)

  private def copy(rows: Array[Array[Double]]): Array[Array[Double]] = rows.map(_.clone())

  /** The bars of the source in degree `k` that the matrix has a column for: rows of (birth, death). */
  def sourceBars(k: Int): Array[Array[Double]] = copy(degree(k).sourceBars)

  /** The bars of the target in degree `k` that the matrix has a row for: rows of (birth, death). */
  def targetBars(k: Int): Array[Array[Double]] = copy(degree(k).targetBars)

  /** The nonzero entries of the matrix in degree `k`: rows of (source bar, target bar, coefficient), the bars as
    * 0-based positions in [[sourceBars]] and [[targetBars]], the coefficient an integer (balanced over `Z/p`).
    */
  def entries(k: Int): Array[Array[Double]] = copy(degree(k).entries)

  /** The bars of the image in degree `k`: the classes of the source that are not zero in the target. */
  def image(k: Int): Array[Array[Double]] = copy(degree(k).image)

  /** The bars of the kernel in degree `k`: what the map sends to zero, at the time it does. */
  def kernel(k: Int): Array[Array[Double]] = copy(degree(k).kernel)

  /** The bars of the cokernel in degree `k`: what the target has that does not come from the source. */
  def cokernel(k: Int): Array[Array[Double]] = copy(degree(k).cokernel)

  override def toString: String =
    val perDegree =
      degrees.indices.map(k => s"H$k: ${degrees(k).sourceBars.length} -> ${degrees(k).targetBars.length} bars")
    s"PersistenceMapResult(${perDegree.mkString(", ")})"

private[matlab] object PersistenceMapResult:
  /** One degree, every list as rows of numbers. */
  final case class Degree(
    sourceBars: Array[Array[Double]],
    targetBars: Array[Array[Double]],
    entries: Array[Array[Double]],
    image: Array[Array[Double]],
    kernel: Array[Array[Double]],
    cokernel: Array[Array[Double]]
  )
