package org.appliedtopology.tda4j

import scalaz.Show

import scala.annotation.targetName

/** Specifies what it means for the type `T` to be a module (or vector space) over the [Numeric] (ie ring-like) type
  * `R`.
  *
  * A minimal implementation of this trait will define `zero`, `plus`, `scale`, and at least one of `minus` and `negate`
  *
  * @tparam T
  *   Type of the module elements.
  * @tparam R
  *   Type of the ring coefficients
  */
trait RingModule[T, R] {
  rmod =>
  def zero: T
  def plus(x: T, y: T): T
  def minus(x: T, y: T): T = plus(x, negate(y))
  def negate(x: T): T = minus(zero, x)
  def scale(x: R, y: T): T

  extension (t: T) {
    @targetName("add")
    def +(rhs: T): T = plus(t, rhs)
    @targetName("subtract")
    def -(rhs: T): T = minus(t, rhs)
    @targetName("scalarMultiplyRight")
    def <*(rhs: R): T = rmod.scale(rhs, t)
    infix def mul(rhs: R): T = rmod.scale(rhs,t)
    def unary_- : T = negate(t)
  }

  extension (r: R) {
    @targetName("scalarMultiplyLeft")
    def *>(t: T): T = rmod.scale(r, t)
    @targetName("scalarMultiplyLeft2")
    def ⊠(t:T): T = rmod.scale(r,t) // unicode ⊠ for boxed times
    @targetName("infixScale")
    infix def scale(t:T):T = rmod.scale(r,t)
  }
}

object RingModule:
  def apply[T, R](using rm: RingModule[T, R]): RingModule[T, R] = rm
