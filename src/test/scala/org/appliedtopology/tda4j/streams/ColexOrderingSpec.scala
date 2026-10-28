package org.appliedtopology.tda4j

import Persistence.Engine

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

/** `SimplexIndexing.colexOrdering`, the tie-break of every Vietoris-Rips stream, is the order of the combinatorial
  * index on sets of one size, and has no width limit: the generic engines (which order cells by it) run on a cloud
  * whose indices do not fit a `Long`.
  */
class ColexOrderingSpec extends Specification with ScalaCheck:
  given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(500))

  // `size` distinct vertices below `n` (`Gen.pick` would materialize all of `0 until n`).
  private def vertexSet(n: Int, size: Int): Gen[Set[Int]] =
    Gen.listOfN(3 * size, Gen.chooseNum(0, n - 1)).map(_.distinct).suchThat(_.size >= size).map(_.take(size).toSet)

  private def subsets(vertexCount: Gen[Int]): Gen[(Int, Int, Set[Int], Set[Int])] =
    for
      n <- vertexCount
      size <- Gen.chooseNum(1, math.min(n / 3, 12))
      a <- vertexSet(n, size)
      b <- vertexSet(n, size)
    yield (n, size, a, b)

  // The index with `BigInt` arithmetic: a sum of binomials, one per vertex, however many vertices there are.
  private def bigIndex(vertices: Set[Int]): BigInt =
    vertices.toList.sorted.zipWithIndex.map { case (v, i) => binomialBig(v, i + 1) }.sum

  private def binomialBig(n: Int, k: Int): BigInt =
    if k < 0 || n < k then BigInt(0) else (1 to k).foldLeft(BigInt(1))((acc, i) => acc * (n - k + i) / i)

  "colexOrdering" should {
    "agree with the order of the combinatorial index on sets of one size" in
      forAll(subsets(Gen.chooseNum(3, 40))) { case (n, size, a, b) =>
        val si = SimplexIndexing(n)
        val bySet = SimplexIndexing.colexOrdering.compare(Simplex.from(a.toSeq.sorted), Simplex.from(b.toSeq.sorted))
        Integer.signum(bySet) must beEqualTo(
          java.lang.Long.signum(
            java.lang.Long.compare(
              si(Simplex.from(a.toSeq.sorted)),
              si(Simplex.from(b.toSeq.sorted))
            )
          )
        )
      }

    "agree with the index where the index passes a Long (a million vertices, twelve to a set)" in
      forAll(subsets(Gen.const(1000000))) { case (_, _, a, b) =>
        val bySet = SimplexIndexing.colexOrdering.compare(Simplex.from(a.toSeq.sorted), Simplex.from(b.toSeq.sorted))
        Integer.signum(bySet) must beEqualTo(bigIndex(a).compare(bigIndex(b)).sign)
      }

    "put a set below one that shares its top vertices and has more" in {
      val order = SimplexIndexing.colexOrdering
      order.compare(∆(5, 9), ∆(2, 5, 9)) must beLessThan(0)
      order.compare(∆(2, 5, 9), ∆(5, 9)) must beGreaterThan(0)
      order.compare(∆(1, 2, 9), ∆(3, 4, 8)) must beGreaterThan(0)
      order.compare(∆(1, 2, 9), ∆(1, 2, 9)) must beEqualTo(0)
    }
  }

  // Nine points, the last nine vertices, at mutual distance 1 among 991 isolated ones: the 8-simplex's cells tie in value
  // and dimension, so their order is the tie-break, and its top cell's index is C(999, 9) + ... = 2e21, past a Long.
  private val cluster = new FiniteMetricSpace[Int]:
    def distance(x: Int, y: Int): Double = if x == y then 0.0 else if x >= 991 && y >= 991 then 1.0 else 100.0
    def size: Int = 1000
    def elements: Iterable[Int] = 0 until 1000
    def contains(x: Int): Boolean = x >= 0 && x < 1000

  "the generic engines" should {
    "give the barcode of an 8-simplex among 991 isolated points, though the index of 8 of 1000 points passes a Long" in {
      val expected = List.fill(8)((0, 0.0, 1.0)) ++ List.fill(992)((0, 0.0, Double.PositiveInfinity))
      val failures = for
        engine <- List(Engine.Chunks, Engine.Naive, Engine.Cohomology, Engine.Ripser)
        triples = Persistence(cluster, maxDimension = 7, maxFiltrationValue = 1.5, engine = engine).triples.sorted
        if triples != expected
      yield s"$engine: ${triples.size} bars, first $triples".take(200)
      failures must beEmpty
    }
  }
