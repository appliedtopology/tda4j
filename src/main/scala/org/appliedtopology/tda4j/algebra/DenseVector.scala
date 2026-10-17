package org.appliedtopology.tda4j

import cats.Show

/** A vector of `Double`s of fixed length: the point and direction type of the geometric code (`AlphaShapes.Point`,
  * [[Hyperplane]], [[Hypersphere]]) and the right-hand side of [[DenseMatrix]] solves.
  *
  * A vector is a value. It never changes, two vectors with the same entries are equal and have the same hash code (so a
  * case class holding one, such as `Hypersphere`, can be a key of a hash set), and `0.0` and `-0.0` are the same entry.
  * `NaN` is equal to itself, so a vector with a `NaN` entry is still equal to a copy of itself.
  */
// `final` because it is a value type: a subclass could add state that `equals` and `hashCode` below do not see.
final class DenseVector private (private val entries: Array[Double]):

  /** The number of entries. */
  def size: Int = entries.length

  /** Entry `i`, counting from 0. */
  def apply(i: Int): Double =
    if i < 0 || i >= entries.length then
      throw IndexOutOfBoundsException(s"entry $i of a vector with ${entries.length} entries")
    entries(i)

  /** A copy of the entries (changing it does not change the vector). */
  def toArray: Array[Double] = entries.clone()

  def +(that: DenseVector): DenseVector =
    requireSameSize(that, "add")
    DenseVector.wrap(Array.tabulate(entries.length)(i => entries(i) + that.entries(i)))

  def -(that: DenseVector): DenseVector =
    requireSameSize(that, "subtract")
    DenseVector.wrap(Array.tabulate(entries.length)(i => entries(i) - that.entries(i)))

  def unary_- : DenseVector = DenseVector.wrap(Array.tabulate(entries.length)(i => -entries(i)))

  /** Every entry times `scalar`. */
  def *(scalar: Double): DenseVector = DenseVector.wrap(Array.tabulate(entries.length)(i => entries(i) * scalar))

  /** The inner product, summed in the order of the entries. */
  def dot(that: DenseVector): Double =
    requireSameSize(that, "take the dot product of")
    var sum = 0.0
    var i = 0
    while i < entries.length do
      sum += entries(i) * that.entries(i)
      i += 1
    sum

  /** The Euclidean norm. */
  def norm: Double = math.sqrt(dot(this))

  /** The Euclidean distance to `that`. */
  def distance(that: DenseVector): Double =
    requireSameSize(that, "measure the distance between")
    var sum = 0.0
    var i = 0
    while i < entries.length do
      val d = entries(i) - that.entries(i)
      sum += d * d
      i += 1
    math.sqrt(sum)

  private def requireSameSize(that: DenseVector, action: String): Unit =
    require(
      entries.length == that.entries.length,
      s"cannot $action vectors of different lengths (${entries.length} and ${that.entries.length})"
    )

  override def equals(other: Any): Boolean = other match
    case that: DenseVector =>
      (this eq that) || (entries.length == that.entries.length && {
        var same = true
        var i = 0
        while same && i < entries.length do
          val a = entries(i)
          val b = that.entries(i)
          same = a == b || (a.isNaN && b.isNaN)
          i += 1
        same
      })
    case _ => false

  // Consistent with `equals`: `0.0 + 0.0` and `-0.0 + 0.0` are both `0.0`, and every NaN hashes alike.
  override def hashCode: Int =
    var h = 1
    var i = 0
    while i < entries.length do
      h = 31 * h + java.lang.Double.hashCode(if entries(i).isNaN then Double.NaN else entries(i) + 0.0)
      i += 1
    h

  override def toString: String = entries.mkString("DenseVector(", ", ", ")")

object DenseVector:

  /** The vector with these entries. */
  def apply(entries: Double*): DenseVector = wrap(entries.toArray)

  /** The vector with the entries of `entries` at this moment (the array is copied). */
  def fromArray(entries: Array[Double]): DenseVector = wrap(entries.clone())

  /** The vector of `size` zeros. */
  def zeros(size: Int): DenseVector =
    require(size >= 0, s"a vector cannot have $size entries")
    wrap(new Array[Double](size))

  /** The vector whose entry `i` is `f(i)`. */
  def tabulate(size: Int)(f: Int => Double): DenseVector =
    require(size >= 0, s"a vector cannot have $size entries")
    wrap(Array.tabulate(size)(f))

  // private[tda4j]: takes the array over without copying, so the caller must not keep a reference to it; kept inside
  // the library because a retained array would let a vector change (invariant-bearing state, openness rule 1).
  private[tda4j] def wrap(entries: Array[Double]): DenseVector = new DenseVector(entries)

  given showDenseVector: Show[DenseVector] = Show.fromToString
