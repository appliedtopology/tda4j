package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

import SimplexIndexing.binomial

/** `SimplexIndexing.binomial`: exact `Long` binomial coefficients, `0` outside the triangle, and an
  * `IllegalArgumentException` (not a wrong number) when the value does not fit.
  */
class BinomialSpec extends Specification:
  sequential

  private val longMax = BigInt(Long.MaxValue)

  /** Pascal's triangle in `BigInt` as the reference: row `n`, column `k`. */
  private val reference: Vector[Vector[BigInt]] =
    (1 to 260).foldLeft(Vector(Vector(BigInt(1)))) { (rows, n) =>
      val previous = rows.last
      rows :+ Vector.tabulate(n + 1)(k =>
        (if k > 0 then previous(k - 1) else BigInt(0)) + (if k < n then previous(k) else BigInt(0))
      )
    }

  private def overflows(n: Int, k: Int): Boolean =
    try
      binomial(n, k)
      false
    catch case _: IllegalArgumentException => true

  "Every coefficient up to n = 260 is the exact Pascal value, or reports an overflow exactly when it exceeds Long" >> {
    val wrong = for
      n <- 0 to 260
      k <- 0 to n
      expected = reference(n)(k)
      problem =
        if expected <= longMax then
          if overflows(n, k) then Some(s"C($n,$k) fits but reported an overflow")
          else if binomial(n, k) != expected.toLong then Some(s"C($n,$k) = ${binomial(n, k)}, expected $expected")
          else None
        else if !overflows(n, k) then Some(s"C($n,$k) = $expected does not fit but returned ${binomial(n, k)}")
        else None
      if problem.isDefined
    yield problem.get
    wrong.take(5) must beEmpty
  }

  "Pascal's rule C(n,k) = C(n-1,k-1) + C(n-1,k) holds on the whole table that fits in a Long" >> {
    val broken = for
      n <- 2 to 66
      k <- 1 until n
      if binomial(n, k) != binomial(n - 1, k - 1) + binomial(n - 1, k)
    yield (n, k)
    broken must beEmpty
  }

  "Symmetry C(n,k) = C(n,n-k)" >> {
    val broken = for
      n <- 0 to 66
      k <- 0 to n
      if binomial(n, k) != binomial(n, n - k)
    yield (n, k)
    broken must beEmpty
  }

  "Known values at the edge of Long" >> {
    (binomial(66, 33) must be_==(7219428434016265740L)) and
      (binomial(62, 31) must be_==(465428353255261088L)) and
      (binomial(229, 5) must be_==(5022337545L)) and
      (binomial(Int.MaxValue, 1) must be_==(Int.MaxValue.toLong)) and
      (binomial(Int.MaxValue, 2) must be_==(2305843005992468481L)) and
      (binomial(Int.MaxValue, Int.MaxValue - 2) must be_==(2305843005992468481L)) and
      (binomial(100, 3) must be_==(161700L))
  }

  "The first coefficient past Long.MaxValue overflows, in both halves of the row" >> {
    (overflows(67, 33) must beTrue) and (overflows(67, 34) must beTrue) and (overflows(Int.MaxValue, 3) must beTrue) and
      (overflows(200, 100) must beTrue) and (overflows(66, 33) must beFalse)
  }

  "Overflow says which coefficient and what to do, as an IllegalArgumentException" >> {
    binomial(67, 33) must throwWith[IllegalArgumentException](
      "binomial.67, 33. overflows Long -- this complex is too large to index"
    )
  }

  "Outside the triangle, n < 0, k < 0 and k > n, the coefficient is 0" >> {
    (binomial(-1, 0) must be_==(0L)) and (binomial(-5, -5) must be_==(0L)) and (binomial(5, -1) must be_==(0L)) and
      (binomial(5, 6) must be_==(0L)) and (binomial(0, 1) must be_==(0L)) and
      (binomial(Int.MaxValue, Int.MaxValue) must be_==(1L)) and
      // Huge values never reach the multiplication when the coefficient is 0 by definition.
      (binomial(10, Int.MaxValue) must be_==(0L)) and (binomial(10, Int.MinValue) must be_==(0L))
  }

  "The boundary rows: C(n,0) = C(n,n) = 1, C(n,1) = C(n,n-1) = n" >> {
    val broken = for
      n <- Seq(0, 1, 2, 3, 10, 1000, 123456789, Int.MaxValue)
      problem <- Seq(
        Option.when(binomial(n, 0) != 1L)(s"C($n,0)"),
        Option.when(binomial(n, n) != 1L)(s"C($n,$n)"),
        Option.when(n >= 1 && binomial(n, 1) != n.toLong)(s"C($n,1)"),
        Option.when(n >= 1 && binomial(n, n - 1) != n.toLong)(s"C($n,${n - 1})")
      ).flatten
    yield problem
    broken must beEmpty
  }

  "Rows increase up to the middle and the values are exact on a long row: C(60,k) for every k" >> {
    val row = (0 to 60).map(k => binomial(60, k))
    val increasing = (0 until 30).forall(k => row(k) < row(k + 1))
    (increasing must beTrue) and (row.map(BigInt(_)) must be_==(reference(60).toIndexedSeq))
  }

  "Large n with small k, and large k near n, match the exact value (no floating point in the middle)" >> {
    def exact(n: Int, k: Int): BigInt = (0 until k).foldLeft(BigInt(1))((acc, i) => acc * (n - i) / (i + 1))
    val cases = for
      n <- Seq(1000, 99999, 1 << 20, 123456789, Int.MaxValue - 1, Int.MaxValue)
      k <- 0 to 6
    yield (n, k)
    val broken = for
      (n, k) <- cases
      expected = exact(n, k)
      got = if overflows(n, k) then None else Some(BigInt(binomial(n, k)))
      if (expected <= longMax && got != Some(expected)) || (expected > longMax && got.isDefined)
    yield (n, k, expected, got)
    val mirrored = for
      (n, k) <- cases
      if k <= n && !overflows(n, k) && binomial(n, n - k) != binomial(n, k)
    yield (n, k)
    (broken must beEmpty) and (mirrored must beEmpty)
  }
end BinomialSpec
