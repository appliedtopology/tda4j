package org.appliedtopology.tda4j

import scala.math.*
import scala.math.Numeric.IntIsIntegral
import math.Fractional.Implicits.infixFractionalOps
import scala.collection.immutable.ArraySeq

class FiniteField(val p: Int) {
  opaque type Fp = Int

  object Fp {
    def apply(a: Int): Fp = a
    def unapply(a: Fp): Option[Int] = Some(a)
  }

  extension (fp: Fp) {
    def norm: Fp = {
      val aa: Int = fp
      val r: Int = aa % p

      Fp(r match {
        case rr: Int if rr < -(p-1)/2 => rr + p
        case rr: Int if rr > (p-1)/2 => rr - p
        case rr: Int => rr
      })
    }
    def toInt: Int = fp.norm
    def toUInt: Int = ((fp % p) + p) % p // Have to get to the interval (0,p-1)
    def toString: String = s"Fp(${fp.norm})"
  }

  given FpIsFractional: Fractional[Fp] with {
    def computeInverse(a: Fp): Fp = {
      val aa: Int = a.toUInt()
      var u: Int = aa % p
      var v: Int = p
      var x1: Int = 1
      var x2: Int = 0
      var q: Int = 0
      var r: Int = 0
      var x: Int = 0
      while (u != 1) {
        assert(u != 0)
        q = v / u
        r = v - q * u
        x = x2 - q * x1
        v = u
        u = r
        x2 = x1
        x1 = x
      }
      Fp(x1 % p)
    }

    val p2: Int = (p - 1) / 2

    val inverses: ArraySeq[Fp] = ArraySeq.tabulate(p) ( j =>
      if(j==0) 0
      else computeInverse(Fp(j))
    )

    def inverse(fp: Fp): Fp = {
      val ix: Int = fp.toUInt()
      if (ix == 0)
        throw new ArithmeticException("Division by zero")
      else
        inverses(ix)
    }

    def op1(op: Int => Int): Fp => Fp =
      a => norm(Fp(op(Fp.unapply(a).get)))

    def op2(op: (x: Int, y: Int) => Int): (Fp, Fp) => Fp =
      (a, b) => norm(Fp(op(Fp.unapply(a).get, Fp.unapply(b).get)))

    // Members declared in java.util.Comparator
    // Members declared in scala.math.Ordering
    def compare(x: Fp, y: Fp): Int =
      Ordering.Int.compare(Fp.unapply(norm(x)).get, Fp.unapply(norm(y)).get)

    // Members declared in scala.math.Fractional
    def div(x: Fp, y: Fp): Fp = times(x, inverse(y))

    // Members declared in scala.math.Numeric
    def fromInt(x: Int): Fp = norm(Fp(x))

    def minus(x: Fp, y: Fp): Fp = op2(_ - _)(x, y)

    def negate(x: Fp): Fp = op1(-_)(x)

    def parseString(str: String): Option[Fp] =
      IntIsIntegral.parseString(str).map(j => norm(j))

    def plus(x: Fp, y: Fp): Fp = op2(_ + _)(x, y)

    def times(x: Fp, y: Fp): Fp = op2(_ * _)(x, y)

    def toDouble(x: Fp): Double = {
      val Fp(xx) = norm(x)
      IntIsIntegral.toDouble(xx)
    }

    def toFloat(x: Fp): Float = {
      val Fp(xx) = norm(x)
      IntIsIntegral.toFloat(xx)
    }

    def toInt(x: Fp): Fp = {
      val Fp(xx) = norm(x)
      IntIsIntegral.toInt(xx)
    }

    def toLong(x: Fp): Long = {
      val Fp(xx) = norm(x)
      IntIsIntegral.toLong(xx)
    }
  }
}
