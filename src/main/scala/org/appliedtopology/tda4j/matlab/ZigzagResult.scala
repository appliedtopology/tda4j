package org.appliedtopology.tda4j.matlab

/** The zigzag persistent homology of a sequence of simplicial complexes (`TDA4j.zigzagFromSteps`,
  * `zigzagFromComplexes`): bars `[birth, death)` over the positions `0..lastPosition()` of the sequence, a bar being a
  * class that is in the homology of the complexes at the positions `birth` to `death - 1` and in neither neighbour,
  * with `death` infinite for a class still there at the last position. Each bar has a representative: a cycle in the
  * complex at its birth.
  */
final class ZigzagResult private[matlab] (
  private val rows: Array[Array[Double]],
  private val cycles: Array[(Array[Array[Int]], Array[Double])],
  private val last: Int
):
  /** The number of bars. */
  def size(): Int = rows.length

  /** The bars as an N-by-3 matrix with a row `(dimension, birth, death)` for each. */
  def toArray(): Array[Array[Double]] = rows.map(_.clone())

  /** The last position of the sequence: the number of steps, or one less than the number of complexes. */
  def lastPosition(): Int = last

  /** The simplices of bar `i`'s representative, as arrays of vertex numbers. */
  def cycleVertices(i: Int): Array[Array[Int]] = cycles(i)._1.map(_.clone())

  /** The coefficients of bar `i`'s representative, parallel to `cycleVertices(i)`. */
  def cycleCoefficients(i: Int): Array[Double] = cycles(i)._2.clone()
