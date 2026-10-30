package org.appliedtopology.tda4j

import cats.Show

import scala.compiletime.asMatchable

/** A coefficient field chosen at runtime: `Coefficients(0)` for real coefficients (`Double`, compared within
  * `precision`), `Coefficients(p)` for `Z/p` with a prime `p`, and `Coefficients.Rationals` for exact rational numbers.
  * `import coefficients.given` brings the field into scope; the coefficient type is the type member `C`. The library's
  * default is `FiniteField.DefaultPrime` (17).
  */
trait Coefficients:
  type C
  given field: (C is Field) = compiletime.deferred
  def fromInt(x: Int): C

  /** `p` for `Z/p`, and `0` for the real and the rational numbers. */
  def characteristic: Int

object Coefficients:

  /** An exact rational number, in lowest terms with a positive denominator, so that equal numbers are equal values.
    * `Coefficients.Rational(1, 3)`; the arithmetic is the field `Rational.rationalField` (also the implicit one). The
    * numerator and denominator are `BigInt`s that grow as the reductions combine columns, which a coefficient in `Z/p`
    * or a `Double` does not do.
    */
  final class Rational private (val numerator: BigInt, val denominator: BigInt):
    def isZero: Boolean = numerator.signum == 0

    /** The nearest `Double`, for plots and printouts. */
    def toDouble: Double =
      if numerator.isValidLong && denominator.isValidLong then numerator.toDouble / denominator.toDouble
      else (BigDecimal(numerator) / BigDecimal(denominator, java.math.MathContext.DECIMAL64)).toDouble

    override def equals(other: Any): Boolean = other.asMatchable match
      case that: Rational => numerator == that.numerator && denominator == that.denominator
      case _              => false

    override def hashCode: Int = scala.util.hashing.MurmurHash3.mixLast(numerator.hashCode, denominator.hashCode)

    override def toString: String = if denominator == 1 then numerator.toString else s"$numerator/$denominator"

  object Rational:
    val zero: Rational = new Rational(0, 1)
    val one: Rational = new Rational(1, 1)

    /** `numerator / denominator` in lowest terms. */
    def apply(numerator: BigInt, denominator: BigInt): Rational =
      require(denominator.signum != 0, "tda4j: a rational number with denominator 0")
      val g = numerator.gcd(denominator)
      val sign = denominator.signum
      if g == 1 && sign > 0 then new Rational(numerator, denominator)
      else if numerator.signum == 0 then zero
      else new Rational(numerator / g * sign, denominator / g * sign)

    def apply(integer: BigInt): Rational = new Rational(integer, 1)
    def apply(numerator: Long, denominator: Long): Rational = apply(BigInt(numerator), BigInt(denominator))

    // In the data type's companion = its implicit scope: `x.show` works with no import.
    given showRational: Show[Rational] = Show.fromToString[Rational]

    given rationalField: (Rational is Field) = new (Rational is Field):
      def plus(x: Rational, y: Rational): Rational =
        if x.denominator == y.denominator then Rational(x.numerator + y.numerator, x.denominator)
        else Rational(x.numerator * y.denominator + y.numerator * x.denominator, x.denominator * y.denominator)
      def minus(x: Rational, y: Rational): Rational = plus(x, negate(y))
      def times(x: Rational, y: Rational): Rational =
        Rational(x.numerator * y.numerator, x.denominator * y.denominator)
      def divide(x: Rational, y: Rational): Rational = times(x, invert(y))
      def isEqual(x: Rational, y: Rational): Boolean = x == y
      def negate(x: Rational): Rational = new Rational(-x.numerator, x.denominator)
      def invert(x: Rational): Rational =
        require(!x.isZero, "tda4j: division by zero in the rational numbers")
        Rational(x.denominator, x.numerator)
      def zero: Rational = Rational.zero
      def one: Rational = Rational.one
      override def hash(x: Rational): Int = x.hashCode
      def showForSelf: Show[Rational] = showRational
      override def toDouble(x: Rational): Double = x.toDouble
  end Rational

  /** Exact rational coefficients: no tolerance, and no characteristic to choose. The bars have the same endpoints as
    * over `Z/p` for every prime `p` that does not divide a torsion coefficient of the complex; the numbers inside the
    * reductions can grow.
    */
  val Rationals: Coefficients { type C = Rational } =
    new Coefficients:
      type C = Rational
      override given field: (Rational is Field) = Rational.rationalField
      def fromInt(x: Int): Rational = Rational(BigInt(x))
      def characteristic = 0
      override def toString = "Coefficients.Rationals"

  def apply(characteristic: Int, precision: Double = 1e-9): Coefficients = characteristic match
    case 0 =>
      new Coefficients:
        type C = Double
        override given field: (Double is Field) = Field.DoubleApproximated(precision)
        def fromInt(x: Int): Double = x.toDouble
        def characteristic = 0
    case p if p > FiniteField.MaxPrime && BigInt(p).isProbablePrime(certainty = 100) =>
      throw IllegalArgumentException(
        s"tda4j: the coefficient characteristic is at most ${FiniteField.MaxPrime}, got $p; for a larger prime, open an issue or a pull request"
      )
    case p if p > 1 && BigInt(p).isProbablePrime(certainty = 100) =>
      val ff = FiniteField(p)
      new Coefficients:
        type C = ff.Fp
        override given field: (ff.Fp is Field) = ff.fpField
        def fromInt(x: Int): ff.Fp = ff.Fp(x)
        def characteristic = p
    case other =>
      throw IllegalArgumentException(s"tda4j: the coefficient characteristic must be 0 (reals) or a prime, got $other")
