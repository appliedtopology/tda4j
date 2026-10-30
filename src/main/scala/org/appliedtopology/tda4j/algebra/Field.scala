package org.appliedtopology.tda4j

import cats.Show
import scala.annotation.implicitNotFound

// format: off
@implicitNotFound("tda4j: no coefficient field for ${Self}. Pick one: `given Double is Field = Field.DoubleApproximated(1e-9)` for real coefficients; `val ff = FiniteField(17); import ff.given` for Z/17 (any prime); or use a lab, `import TDAlab.F17.{*, given}`.")
// format: on
trait Field:
  type Self

  def plus(x: Self, y: Self): Self
  def minus(x: Self, y: Self): Self
  def times(x: Self, y: Self): Self
  def divide(x: Self, y: Self): Self
  def isEqual(x: Self, y: Self): Boolean
  def negate(x: Self): Self
  def invert(x: Self): Self
  def zero: Self
  def one: Self

  def showForSelf: Show[Self]

  /** A hash code of `x` that agrees with [[isEqual]]: elements the field calls equal have the same hash, whatever their
    * representation, so that [[Chain]] can hash consistently with its equality. Nothing is required of unequal
    * elements, so the default, `0` for every element, is right for any field; override it where equality is exact and
    * the elements can be told apart (`Z/p` hashes the residue in `0 until p`). A field that compares within a
    * tolerance, like [[Field.DoubleApproximated]], hashes every element alike: tolerance is not transitive, and no hash
    * of the value can agree with it.
    */
  def hash(x: Self): Int = 0

  /** `x` as a real number, for plots and printouts: the integer `k` (`|k| <= 64`, the smallest first) with `k * 1 == x`
    * in the field, which is the balanced representative for `Z/p`; `NaN` when there is none. A field of real numbers
    * overrides it with the value itself ([[Field.DoubleApproximated]] does). It is decided by the field, never by the
    * runtime class of `x`: on Scala.js every boxed number is a `Double`, and an `Fp` would be taken for one.
    */
  def toDouble(x: Self): Double =
    if isEqual(x, zero) then 0.0
    else
      var pos = zero
      var k = 1
      var found = Double.NaN
      while found.isNaN && k <= 64 do
        pos = plus(pos, one)
        if isEqual(x, pos) then found = k
        else if isEqual(x, negate(pos)) then found = -k
        k += 1
      found

  extension (x: Self)
    infix def +(y: Self): Self = plus(x, y)
    infix def -(y: Self): Self = minus(x, y)
    infix def *(y: Self): Self = times(x, y)
    infix def /(y: Self): Self = divide(x, y)
    infix def eql(y: Self): Boolean = isEqual(x, y)
    def unary_- : Self = negate(x)
    def isZero: Boolean = x eql zero

object Field:
  def DoubleApproximated(epsilon: Double): Double is Field = new (Double is Field):
    val fr = summon[Fractional[Double]]

    override def plus(x: Double, y: Double): Double = fr.plus(x, y)

    override def minus(x: Double, y: Double): Double = fr.minus(x, y)

    override def times(x: Double, y: Double): Double = fr.times(x, y)

    override def divide(x: Double, y: Double): Double = fr.div(x, y)

    override def isEqual(x: Double, y: Double): Boolean = fr.abs(fr.minus(x, y)) < epsilon

    // Equality within `epsilon` is not transitive, so only a constant hash agrees with it (the trait's default).
    override def hash(x: Double): Int = 0

    override def negate(x: Double): Double = fr.negate(x)

    override def invert(x: Double): Double = fr.div(fr.one, x)

    override def zero: Double = fr.zero

    override def one: Double = fr.one

    override def showForSelf = Show.fromToString[Double]

    override def toDouble(x: Double): Double = x

  /** Opt-in (`import Field.showFromField`): `Show` for any coefficient type via its `Field`'s `showForSelf`. Not a
    * top-level given -- `Double` already has cats' `Show`, and `Fp` has its own in `FiniteField`.
    */
  given showFromField: [F: Field] => Show[F] = summon[F is Field].showForSelf
