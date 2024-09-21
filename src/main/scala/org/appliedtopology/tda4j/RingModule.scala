package org.appliedtopology.tda4j

import scala.annotation.targetName

/** Specifies what it means for the type `Self` to be a module (or vector space) over the [Numeric] (ie ring-like) type
  * `R`.
  *
  * A minimal implementation of this trait will define `zero`, `plus`, `scale`, and at least one of `minus` and `negate`
  *
  * @tparam Self
  *   Type of the module elements.
  * @tparam R
  *   Type of the ring coefficients
  */
trait RingModule {
  type Self
  type R

  def zero: Self
  def isZero(t: Self): Boolean = t == zero
  def plus(x: Self, y: Self): Self
  def minus(x: Self, y: Self): Self = plus(x, negate(y))
  def negate(x: Self): Self = minus(zero, x)
  def scale(x: R, y: Self): Self

  extension (t: Self) {
    @targetName("add")
    def +(rhs: Self): Self = plus(t, rhs)
    @targetName("subtract")
    def -(rhs: Self): Self = minus(t, rhs)
    @targetName("scalarMultiplyRight")
    def <*(rhs: R): Self = scale(rhs, t)
    infix def mul(rhs: R): Self = scale(rhs, t)
    def unary_- : Self = negate(t)
  }

  extension (r: R) {
    @targetName("scalarMultiplyLeft")
    def |*|(t: Self): Self = this.scale(r, t)
    @targetName("scalarMultiplyLeft2")
    def ⊠(t: Self): Self = this.scale(r, t) // unicode ⊠ for boxed times
    @targetName("infixScale")
    infix def scale(t: Self): Self = this.scale(r, t)
  }
}
