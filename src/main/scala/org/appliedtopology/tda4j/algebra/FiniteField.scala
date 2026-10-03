package org.appliedtopology.tda4j

import cats.Show

import scala.collection.immutable.ArraySeq

class FiniteField(val p: Int):
  opaque type Fp = Int

  object Fp:
    def apply(a: Int): Fp = a % p
    def unapply(a: Fp): Some[Int] = Some(a)
    // In the opaque type's companion = its implicit scope: `x.show` works with no import.
    given showFp: Show[Fp] = Show.show(x => summon[Fp is Field].showForSelf.show(x))

  extension (fp: Fp)
    def norm: Fp =
      val aa: Int = fp
      val r: Int = aa % p

      Fp(r match
        case rr: Int if rr < -(p - 1) / 2 => rr + p
        case rr: Int if rr > p / 2        =>
          rr - p // p / 2, not (p - 1) / 2: identical for odd p, and stable at p = 2 (was 1 -> -1 -> 1)
        case rr: Int => rr)
    def toInt: Int = fp.norm
    def toUInt: Int = ((fp % p) + p) % p // Have to get to the interval (0,p-1)

  given (Fp is Field) = new (Fp is Field):
    override def showForSelf: Show[Fp] = Show.show[Fp] { fpx =>
      val Fp(x) = fpx.norm
      s"Fp(${x})"
    }

    def computeInverse(a: Fp): Fp =
      val aa: Int = a.toUInt
      var u: Int = aa % p
      var v: Int = p
      var x1: Int = 1
      var x2: Int = 0
      var q: Int = 0
      var r: Int = 0
      var x: Int = 0
      while u != 1 do
        q = v / u
        r = v - q * u
        x = x2 - q * x1
        v = u
        u = r
        x2 = x1
        x1 = x
      Fp(x1 % p).norm

    val inverses: ArraySeq[Fp] = ArraySeq.tabulate(p)(j =>
      if j == 0 then 0
      else computeInverse(Fp(j))
    )

    def inverse(fp: Fp): Fp =
      val ix: Int = fp.toUInt
      if ix == 0 then throw new ArithmeticException("Division by zero")
      else inverses(ix)

    def invert(x: Fp): Fp = inverse(x)
    def isEqual(x: Fp, y: Fp): Boolean =
      val Fp(xv) = norm(x)
      val Fp(yv) = norm(y)
      xv == yv
    def zero: Fp = Fp(0)
    def one: Fp = Fp(1)

    def divide(x: Fp, y: Fp): Fp = times(x, inverse(y))
    def minus(x: Fp, y: Fp): Fp = norm(Fp(x - y))
    def negate(x: Fp): Fp = norm(Fp(-x))
    def plus(x: Fp, y: Fp): Fp = norm(Fp(x + y))
    // Long product: inputs need not be normalized (`Fp(a)` and the inverse table are not), and for p > 46341 an Int
    // product of two residues overflows.
    def times(x: Fp, y: Fp): Fp = norm(Fp(((x.toLong * y.toLong) % p).toInt))
