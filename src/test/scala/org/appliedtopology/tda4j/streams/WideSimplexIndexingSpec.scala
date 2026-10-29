package org.appliedtopology.tda4j

import WideSimplexIndexing.{highOf, lowOf, toBigInt}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.scalacheck.Parameters

class WideSimplexIndexingSpec extends Specification with ScalaCheck:

  // C(n, k) for n <= 2000, k <= 13, by Pascal's rule in BigInt.
  val pascal: Array[Array[BigInt]] =
    val rows = Array.fill(14, 2001)(BigInt(0))
    for n <- 0 to 2000 do rows(0)(n) = BigInt(1)
    for k <- 1 to 13; n <- 1 to 2000 do rows(k)(n) = rows(k)(n - 1) + rows(k - 1)(n - 1)
    rows

  // The combinatorial index of ascending vertices, exactly.
  def oracle(vertices: Seq[Int]): BigInt = vertices.zipWithIndex.map((v, i) => pascal(i + 1)(v)).sum

  def words(index: BigInt): (Long, Long) = (highOf(index), lowOf(index))

  // Sorted distinct vertices of `size` out of `vertexCount`.
  def simplexGen(vertexCount: Int, size: Int): Gen[Array[Int]] =
    Gen.pick(size, 0 until vertexCount).map(_.toArray.sorted)

  // Indices well past 2^63: C(2000, 10) is about 2^88.
  val wideCase: Gen[(Int, Array[Int])] =
    for
      vertexCount <- Gen.chooseNum(1500, 2000)
      size <- Gen.chooseNum(1, 9)
      vertices <- simplexGen(vertexCount, size)
    yield (vertexCount, vertices)

  "Two-word simplex indices" >> {
    "agree with SimplexIndexing where a Long holds them" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(300))
      val smallCase = for
        vertexCount <- Gen.chooseNum(2, 25)
        size <- Gen.chooseNum(1, vertexCount - 1)
        idx <- Gen.chooseNum(0L, SimplexIndexing.binomial(vertexCount, size) - 1)
      yield (vertexCount, size, idx)
      forAll(smallCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        val wsi = WideSimplexIndexing(vertexCount)
        val vertices = si.decodeToArray(idx, size)
        val cof = si.cofacetCursor(idx, size)
        val wcof = wsi.cofacetCursor(0L, idx, size)
        val cofacets = Iterator
          .unfold(())(_ => Option.when(cof.hasNext) { val r = (cof.vertex, 0L, cof.index); cof.advance(); (r, ()) })
          .toList
        val wideCofacets = Iterator
          .unfold(())(_ =>
            Option.when(wcof.hasNext) { val r = (wcof.vertex, wcof.high, wcof.low); wcof.advance(); (r, ()) }
          )
          .toList
        val fac = si.facetCursor(idx, size)
        val wfac = wsi.facetCursor(0L, idx, size)
        val facets = Iterator
          .unfold(())(_ => Option.when(fac.hasNext) { val r = (fac.vertex, 0L, fac.index); fac.advance(); (r, ()) })
          .toList
        val wideFacets = Iterator
          .unfold(())(_ =>
            Option.when(wfac.hasNext) { val r = (wfac.vertex, wfac.high, wfac.low); wfac.advance(); (r, ()) }
          )
          .toList
        (wsi.decodeToArray(0L, idx, size).toSeq must be_==(vertices.toSeq))
          .and(wsi.index(vertices) must be_==((0L, idx)))
          .and(wideCofacets must be_==(cofacets))
          .and(wideFacets must be_==(facets))
      }
    }

    "index and decode simplices whose indices pass 2^63, as BigInt arithmetic does" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(300))
      forAll(wideCase) { case (vertexCount, vertices) =>
        val wsi = WideSimplexIndexing(vertexCount)
        val (h, l) = words(oracle(vertices.toSeq))
        (wsi.index(vertices) must be_==((h, l)))
          .and(wsi.decodeToArray(h, l, vertices.length).toSeq must be_==(vertices.toSeq))
      }
    }

    "reach the BigInt index of every cofacet, in decreasing order, with the added vertex" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(100))
      // Fewer vertices, larger simplices: C(400, 13) is about 2^77.
      val cofacetCase = for
        vertexCount <- Gen.chooseNum(300, 400)
        size <- Gen.chooseNum(1, 12)
        vertices <- simplexGen(vertexCount, size)
      yield (vertexCount, vertices)
      forAll(cofacetCase, Gen.oneOf(true, false)) { case ((vertexCount, vertices), all) =>
        val wsi = WideSimplexIndexing(vertexCount)
        val (h, l) = words(oracle(vertices.toSeq))
        val cur = wsi.cofacetCursor(h, l, vertices.length, allCofacets = all)
        val seen = Iterator
          .unfold(())(_ =>
            Option.when(cur.hasNext) { val r = (cur.vertex, toBigInt(cur.high, cur.low)); cur.advance(); (r, ()) }
          )
          .toList
        val expectedVertices =
          (0 until vertexCount).reverse.filter(v => !vertices.contains(v) && (all || v > vertices.last)).toList
        (seen.map(_._1) must be_==(expectedVertices))
          .and(seen.forall((v, i) => i == oracle((vertices :+ v).sorted.toSeq)) must beTrue)
          .and(seen.map(_._2).zip(seen.map(_._2).drop(1)).forall(_ > _) must beTrue)
      }
    }

    "reach the BigInt index of every facet, in increasing order, with the removed vertex" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(300))
      forAll(wideCase) { case (vertexCount, vertices) =>
        val wsi = WideSimplexIndexing(vertexCount)
        val (h, l) = words(oracle(vertices.toSeq))
        val cur = wsi.facetCursor(h, l, vertices.length)
        val seen = Iterator
          .unfold(())(_ =>
            Option.when(cur.hasNext) { val r = (cur.vertex, toBigInt(cur.high, cur.low)); cur.advance(); (r, ()) }
          )
          .toList
        (seen.map(_._1) must be_==(vertices.reverse.toList))
          .and(seen.forall((v, i) => i == oracle(vertices.filter(_ != v).toSeq)) must beTrue)
          .and(seen.map(_._2).zip(seen.map(_._2).drop(1)).forall(_ < _) must beTrue)
      }
    }

    "walk the cofacets and facets of a decoded simplex as they are walked from its index, and say where a facet's vertex was" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(200))
      forAll(wideCase, Gen.oneOf(true, false)) { case ((vertexCount, vertices), all) =>
        val wsi = WideSimplexIndexing(vertexCount)
        val (h, l) = words(oracle(vertices.toSeq))
        val decoded = wsi.decodeToArray(h, l, vertices.length)
        def cofacets(cur: wsi.CofacetCursor) =
          Iterator
            .unfold(())(_ =>
              Option.when(cur.hasNext) { val r = (cur.vertex, cur.high, cur.low); cur.advance(); (r, ()) }
            )
            .toList
        def facets(cur: wsi.FacetCursor) =
          Iterator
            .unfold(())(_ =>
              Option.when(cur.hasNext) { val r = (cur.vertex, cur.high, cur.low, cur.position); cur.advance(); (r, ()) }
            )
            .toList
        val byIndex = facets(wsi.facetCursor(h, l, vertices.length))
        val byVertices = facets(wsi.facetCursor(h, l, decoded))
        (cofacets(wsi.cofacetCursor(h, l, decoded, all)) must be_==(
          cofacets(wsi.cofacetCursor(h, l, vertices.length, all))
        ))
          .and(byVertices must be_==(byIndex))
          .and(byVertices.forall((v, _, _, position) => decoded(position) == v) must beTrue)
          .and(decoded.toSeq must be_==(vertices.toSeq))
      }
    }

    "find from neighbour lists the cofacets the full cursor finds whose added vertex neighbours every vertex" >> {
      given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(100))
      val n = 300 // C(300, 13) is about 2^70
      val positions = Array.tabulate(n)(i => ((i * 7919L) % 1000).toDouble / 1000.0)
      val distance = (i: Int, j: Int) => math.abs(positions(i) - positions(j))
      val lists = NeighbourLists.within(n, 0.6, Long.MaxValue)(distance).get
      val wsi = WideSimplexIndexing(n)
      val sparseCase = for
        size <- Gen.chooseNum(1, 12)
        vertices <- simplexGen(n, size)
        all <- Gen.oneOf(true, false)
      yield (vertices, all)
      forAll(sparseCase) { case (vertices, all) =>
        val (h, l) = words(oracle(vertices.toSeq))
        val full = wsi.cofacetCursor(h, l, vertices.length, allCofacets = all)
        val expected = Iterator
          .unfold(())(_ =>
            Option.when(full.hasNext) { val r = (full.vertex, full.high, full.low); full.advance(); (r, ()) }
          )
          .filter((v, _, _) => vertices.forall(u => distance(u, v) <= 0.6))
          .toList
        val sparse = wsi.sparseCofacetCursor(h, l, vertices, all, lists)
        val found = Iterator
          .unfold(())(_ =>
            Option.when(sparse.hasNext) {
              val r = (sparse.vertex, sparse.high, sparse.low); sparse.advance(); (r, ())
            }
          )
          .toList
        found must be_==(expected)
      }
    }

    "say which vertex counts and sizes fit one word and two" >>
      (SimplexIndexing.fitsLong(8192, 5) must beTrue)
        .and(SimplexIndexing.fitsLong(20000, 5) must beFalse)
        .and(WideSimplexIndexing.fits(2000, 10) must beTrue)
        .and(WideSimplexIndexing.fits(1000000, 12) must beFalse)
  }
