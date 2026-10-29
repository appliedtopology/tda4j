package org.appliedtopology.tda4j

import org.specs2.mutable

/** `Parallel.sortInts` and `Parallel.sortLongs` sort a range of an array in place and leaves the rest alone, whatever
  * the platform's way of doing it is. The examples are the inputs a quicksort gets wrong or slows down on: sorted,
  * reversed, all equal, few distinct values (many ties around the pivot), and a range in the middle of a larger array.
  * The JVM runs `java.util.Arrays.sort`, Scala.js its own in-place sort, which these examples are there for.
  */
class ParallelSortSpec extends mutable.Specification:

  private def sortedRange(xs: Array[Int], from: Int, to: Int): Array[Int] =
    val expected = xs.clone()
    java.util.Arrays.sort(expected, from, to)
    expected

  private def check(xs: Array[Int], from: Int, to: Int) =
    val expected = sortedRange(xs, from, to)
    val actual = xs.clone()
    Parallel.sortInts(actual, from, to)
    actual.toSeq must beEqualTo(expected.toSeq)

  private val rng = new scala.util.Random(20261025)

  "sortInts sorts random ranges of random arrays, and the elements outside the range stay" >> {
    val problems = for
      size <- Seq(0, 1, 2, 3, 15, 16, 17, 33, 100, 1000)
      round <- 0 until 20
      xs = Array.fill(size)(rng.nextInt(2000) - 1000)
      from = if size == 0 then 0 else rng.nextInt(size + 1)
      to = from + (if size == from then 0 else rng.nextInt(size - from + 1))
      expected = sortedRange(xs, from, to)
      actual = xs.clone()
      _ = Parallel.sortInts(actual, from, to)
      if actual.toSeq != expected.toSeq
    yield s"size $size, range $from until $to"
    problems must beEmpty
  }

  "sortInts takes the extremes of Int and non-negative keys alike" >>
    check(
      Array(Int.MaxValue, 0, Int.MinValue, -1, 1, Int.MaxValue, Int.MinValue, 7, 7, 7, 3, 2, 1, 0, -5, -9, 12, 11),
      0,
      18
    )

  "sortInts is not thrown by sorted, reversed, constant and few-valued inputs" >> {
    val n = 5000
    val inputs = Seq(
      "ascending" -> Array.tabulate(n)(identity),
      "descending" -> Array.tabulate(n)(n - _),
      "constant" -> Array.fill(n)(7),
      "two values" -> Array.tabulate(n)(i => i % 2),
      "organ pipe" -> Array.tabulate(n)(i => math.min(i, n - i)),
      "few values" -> Array.fill(n)(rng.nextInt(5))
    )
    val problems = inputs.flatMap { (name, xs) =>
      val expected = sortedRange(xs, 0, n)
      val actual = xs.clone()
      Parallel.sortInts(actual, 0, n)
      if actual.toSeq == expected.toSeq then None else Some(name)
    }
    problems must beEmpty
  }

  "sortInts on a small range of a big array does not cost the size of the array" >> {
    // 20000 sorts of 20 elements of a 1M array: ~20 ms of work, but seconds if each call allocated or copied the array.
    val big = Array.tabulate(1 << 20)(i => (i * 2654435761L % 1000003).toInt)
    val start = System.nanoTime()
    var i = 0
    while i < 20000 do
      Parallel.sortInts(big, 1000 + i, 1020 + i)
      i += 1
    val seconds = (System.nanoTime() - start) / 1e9
    seconds must be_<(3.0)
  }

  "sortInts rejects a range outside the array" >> {
    Parallel.sortInts(new Array[Int](5), 2, 6) must throwAn[Exception]
  }

  private def checkLongs(xs: Array[Long], from: Int, to: Int) =
    val expected = xs.clone()
    java.util.Arrays.sort(expected, from, to)
    val actual = xs.clone()
    Parallel.sortLongs(actual, from, to)
    actual.toSeq must beEqualTo(expected.toSeq)

  "sortLongs sorts random ranges of random arrays, keys past 2^32 and negative ones included, and the rest stays" >> {
    val problems = for
      size <- Seq(0, 1, 2, 3, 15, 16, 17, 33, 100, 1000)
      round <- 0 until 20
      xs = Array.fill(size)(rng.nextLong() >> rng.nextInt(64))
      from = if size == 0 then 0 else rng.nextInt(size + 1)
      to = from + (if size == from then 0 else rng.nextInt(size - from + 1))
      expected =
        val e = xs.clone(); java.util.Arrays.sort(e, from, to); e
      actual = xs.clone()
      _ = Parallel.sortLongs(actual, from, to)
      if actual.toSeq != expected.toSeq
    yield s"size $size, range $from until $to"
    problems must beEmpty
  }

  "sortLongs takes the extremes of Long and keys that differ only in the high word" >>
    checkLongs(
      Array(
        Long.MaxValue,
        0L,
        Long.MinValue,
        -1L,
        1L,
        1L << 32,
        (1L << 32) + 1,
        1L << 40,
        1L << 53,
        (1L << 53) + 1,
        7L,
        7L,
        7L,
        3L,
        2L,
        1L,
        0L,
        -5L,
        -9L,
        12L,
        11L
      ),
      0,
      21
    )

  "sortLongs is not thrown by sorted, reversed, constant and few-valued inputs" >> {
    val n = 5000
    val inputs = Seq(
      "ascending" -> Array.tabulate(n)(_.toLong << 20),
      "descending" -> Array.tabulate(n)(i => (n - i).toLong << 20),
      "constant" -> Array.fill(n)(7L),
      "two values" -> Array.tabulate(n)(i => (i % 2).toLong << 40),
      "organ pipe" -> Array.tabulate(n)(i => math.min(i, n - i).toLong << 33),
      "few values" -> Array.fill(n)(rng.nextInt(5).toLong << 33)
    )
    val problems = inputs.flatMap { (name, xs) =>
      val expected = xs.clone()
      java.util.Arrays.sort(expected)
      val actual = xs.clone()
      Parallel.sortLongs(actual, 0, n)
      if actual.toSeq == expected.toSeq then None else Some(name)
    }
    problems must beEmpty
  }

  "sortLongs on a small range of a big array does not cost the size of the array" >> {
    val big = Array.tabulate(1 << 20)(i => i * 2654435761L % 1000003)
    val start = System.nanoTime()
    var i = 0
    while i < 20000 do
      Parallel.sortLongs(big, 1000 + i, 1020 + i)
      i += 1
    val seconds = (System.nanoTime() - start) / 1e9
    seconds must be_<(3.0)
  }

  "sortLongs rejects a range outside the array" >> {
    Parallel.sortLongs(new Array[Long](5), 2, 6) must throwAn[Exception]
  }
