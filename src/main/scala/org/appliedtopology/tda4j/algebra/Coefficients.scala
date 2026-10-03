package org.appliedtopology.tda4j

/** A coefficient field chosen at runtime by its characteristic: `0` for real coefficients (`Double`, compared within
  * `precision`), a prime `p` for `Z/p`. `import coefficients.given` brings the field into scope; the coefficient type
  * is the type member `C`. `FiniteField.DefaultPrime` (17) is the library's default characteristic -- deliberately
  * not 2: F₂ hides every sign and all odd torsion.
  */
trait Coefficients:
  type C
  given field: (C is Field) = compiletime.deferred
  def fromInt(x: Int): C
  def characteristic: Int

object Coefficients:
  def apply(characteristic: Int, precision: Double = 1e-9): Coefficients = characteristic match
    case 0 =>
      new Coefficients:
        type C = Double
        override given field: (Double is Field) = Field.DoubleApproximated(precision)
        def fromInt(x: Int): Double = x.toDouble
        def characteristic = 0
    case p if p > 1 && BigInt(p).isProbablePrime(certainty = 100) =>
      val ff = FiniteField(p)
      new Coefficients:
        type C = ff.Fp
        override given field: (ff.Fp is Field) = ff.fpField
        def fromInt(x: Int): ff.Fp = ff.Fp(x)
        def characteristic = p
    case other =>
      throw IllegalArgumentException(s"tda4j: the coefficient characteristic must be 0 (reals) or a prime, got $other")
