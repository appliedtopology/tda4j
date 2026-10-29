package org.appliedtopology.tda4j

import LayeredInorderSimplexStream.sortIndices

import org.specs2.mutable.Specification

/** `sortIndices` is a stable sort: the same permutation as the library's stable sort on the same keys. The inputs are
  * the ones its merge treats differently: sizes at and around its insertion blocks and powers of two, many equal keys,
  * sequences that are in order or in reverse, and ones that are nearly in order (local disorder, a displaced block).
  */
class SortIndicesSpec extends Specification:
  private val sizes = Seq(0, 1, 2, 15, 16, 17, 31, 32, 33, 47, 64, 100, 257, 1000, 4099)

  private def inputs(n: Int, rng: scala.util.Random): Seq[(String, Array[Int])] = Seq(
    "random" -> Array.fill(n)(rng.nextInt(1000000)),
    "few distinct keys" -> Array.fill(n)(rng.nextInt(4)),
    "one key" -> Array.fill(n)(7),
    "in order" -> Array.tabulate(n)(identity),
    "reversed" -> Array.tabulate(n)(n - _),
    "local disorder" -> Array.tabulate(n)(i => i + rng.nextInt(24)),
    "a displaced block" -> Array.tabulate(n)(i => if i >= n / 3 && i < n / 3 + 40 then i + n / 2 else i),
    "two interleaved runs" -> Array.tabulate(n)(i => if i % 2 == 0 then i else n + i)
  )

  private def mismatch(keys: Array[Int]): Boolean =
    val expected = keys.indices.sortBy(i => keys(i)).toArray // stable
    !java.util.Arrays.equals(sortIndices(keys.length, (x, y) => Integer.compare(keys(x), keys(y))), expected)

  "sortIndices" should {
    "give the stable order, for every size and kind of input around its blocks" in {
      val rng = new scala.util.Random(31L)
      val failures = for
        n <- sizes
        (name, keys) <- inputs(n, rng)
        if mismatch(keys)
      yield s"$name, $n keys"
      failures.take(3) must beEmpty
    }

    "keep equal keys in their original order when a descending comparison is asked for" in {
      val rng = new scala.util.Random(32L)
      val keys = Array.fill(500)(rng.nextInt(5))
      val order = sortIndices(keys.length, (x, y) => Integer.compare(keys(y), keys(x)))
      val expected = keys.indices.sortBy(i => -keys(i)).toArray
      java.util.Arrays.equals(order, expected) must beTrue
    }
  }
