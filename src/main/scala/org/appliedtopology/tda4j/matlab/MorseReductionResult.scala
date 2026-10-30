package org.appliedtopology.tda4j.matlab

/** The discrete Morse reduction of the Vietoris-Rips complex of a point cloud or a distance matrix
  * (`TDA4j.morseReductionFromPoints`, `morseReductionFromDistanceMatrix`): how many cells the complex has, how many are
  * left after pairing off the cells that enter together, which they are, and the bars computed on the cells that are
  * left, which are the bars of the complex.
  */
final class MorseReductionResult private[matlab] (
  private val cellCountByDimension: Array[Int],
  private val criticalCountByDimension: Array[Int],
  private val pairs: Int,
  private val critical: Array[Array[Int]],
  private val barRows: Array[Array[Double]]
):
  /** The number of cells of the complex in each dimension, from `0` to the top. */
  def cellCounts(): Array[Int] = cellCountByDimension.clone()

  /** The number of critical cells in each dimension, from `0` to the top. */
  def criticalCounts(): Array[Int] = criticalCountByDimension.clone()

  /** The number of pairs of cells that were cancelled. */
  def pairCount(): Int = pairs

  /** The critical cells, one array of vertex numbers for each. */
  def criticalCells(): Array[Array[Int]] = critical.map(_.clone())

  /** The bars of the complex, computed on the critical cells: one row `(dimension, birth, death)` for each, `Inf` for a
    * bar that never dies. Bars of length zero are not among them.
    */
  def bars(): Array[Array[Double]] = barRows.map(_.clone())
