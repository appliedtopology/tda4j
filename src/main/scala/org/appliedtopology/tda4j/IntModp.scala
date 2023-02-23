package org.appliedtopology.tda4j

import scala.math._
import scala.math.Numeric.IntIsIntegral


class IntModp(val p: Int) {
  opaque type Intp = Int

  object Intp {
    def apply(a: Int): Intp = a

    def unapply(a: Intp): Option[Int] = Some(a)
  }

  given IntpIsFractional : Fractional[Intp] with {
    def inverse(a: Intp): Intp = {
      val Intp(aa): Int = a
      var u: Int = aa % p
      var v: Int = p
      var x1: Int = 1
      var x2: Int = 0
      var q: Int = 0
      var r: Int = 0
      var x: Int = 0
      while (u != 1) {
        q = floorDiv(v, u)
        r = v - q * u
        x = x2 - q * x1
        v = u
        u = r
        x2 = x1
        x1 = x
      }
      return Intp(x1 % p)
    }

    val p2: Int = (p - 1) / 2

    val inverses: Map[Intp, Intp] = Map.from(
      Range(1, p - 1).map(j => (j -> inverse(j))) ++
        Range(-(p - 1), -1).map(j => (j -> -inverse(-j)))
    ) // fix this with actual implementation


    def norm(a: Intp): Intp = {
      val Intp(aa) = a
      val r: Int = aa % p

      return Intp(r match {
        case rr: Int if (rr < -p2) => rr + p
        case rr: Int if (rr > p2) => rr - p
        case rr: Int => rr
      })
    }

    def op1(op: Int => Int): Intp => Intp =
      a => norm(Intp(op(Intp.unapply(a).get)))

    def op2(op: (x: Int, y: Int) => Int): (Intp, Intp) => Intp =
      (a, b) => norm(Intp(op(Intp.unapply(a).get, Intp.unapply(b).get)))

    // Members declared in java.util.Comparator
    // Members declared in scala.math.Ordering
    def compare(x: Intp, y: Intp): Int = Ordering.Int.compare(Intp.unapply(norm(x)).get, Intp.unapply(norm(y)).get)

    // Members declared in scala.math.Fractional
    def div(x: Intp, y: Intp): Intp = times(x, inverses(norm(y)))

    // Members declared in scala.math.Numeric
    def fromInt(x: Int): Intp = norm(Intp(x))

    def minus(x: Intp, y: Intp): Intp = op2(_ - _)(x, y)

    def negate(x: Intp): Intp = op1(-_)(x)

    def parseString(str: String): Option[Intp] =
      IntIsIntegral.parseString(str).map(j => norm(j))

    def plus(x: Intp, y: Intp): Intp = op2(_ + _)(x, y)

    def times(x: Intp, y: Intp): Intp = op2(_ * _)(x, y)

    def toDouble(x: Intp): Double = {
      val Intp(xx) = norm(x)
      return IntIsIntegral.toDouble(xx)
    }

    def toFloat(x: Intp): Float = {
      val Intp(xx) = norm(x)
      return IntIsIntegral.toFloat(xx)
    }

    def toInt(x: Intp): Intp = {
      val Intp(xx) = norm(x)
      return IntIsIntegral.toInt(xx)
    }

    def toLong(x: Intp): Long = {
      val Intp(xx) = norm(x)
      return IntIsIntegral.toLong(xx)
    }
  }
}