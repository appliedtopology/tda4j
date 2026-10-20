package org.appliedtopology.tda4j

import org.apache.commons.numbers.combinatorics.BinomialCoefficient
import org.specs2.mutable.Specification

import scala.util.Random

/** JVM only: `SimplexIndexing.binomial` against commons-numbers' `BinomialCoefficient.value`, which it replaces: the
  * same value wherever the value fits in a `Long`, an exception on exactly the same inputs where it does not.
  */
class BinomialOracleSpec extends Specification:
  sequential

  /** The commons-numbers value, `None` where it overflows (the old call site mapped `n < 0`, `k < 0`, `k > n` to 0). */
  private def commons(n: Int, k: Int): Option[Long] =
    if k < 0 || n < 0 || n < k then Some(0L)
    else
      try Some(BinomialCoefficient.value(n, k))
      catch case _: ArithmeticException => None

  private def ours(n: Int, k: Int): Option[Long] =
    try Some(SimplexIndexing.binomial(n, k))
    catch case _: IllegalArgumentException => None

  "Every n up to 140 and every k from -2 to n + 2: the same value, and an overflow on the same inputs" >> {
    val differ = for
      n <- -2 to 140
      k <- -2 to n + 2
      if ours(n, k) != commons(n, k)
    yield (n, k, ours(n, k), commons(n, k))
    differ must beEmpty
  }

  "Random large n with small k and large k near n: the same value or overflow" >> {
    val rng = Random(41)
    val cases = Seq.fill(20000) {
      val n = if rng.nextBoolean() then rng.nextInt(Int.MaxValue) else rng.nextInt(5000)
      val k = rng.nextInt(8) match
        case 0 => rng.nextInt(math.max(n, 1))
        case 1 => math.max(n - rng.nextInt(8), 0)
        case _ => rng.nextInt(8)
      (n, k)
    }
    val differ = cases.filter((n, k) => ours(n, k) != commons(n, k))
    differ must beEmpty
  }

  "Large n with k up to 40, where overflow sets in" >> {
    val differ = for
      n <- Seq(100, 1000, 100000, 1 << 20, Int.MaxValue)
      k <- 0 to 40
      if ours(n, k) != commons(n, k)
    yield (n, k)
    differ must beEmpty
  }
end BinomialOracleSpec
