package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.scalacheck.Parameters
import org.specs2.specification.core.Fragment

class SimplexIndexingSpec extends Specification with ScalaCheck:
  given Parameters = Parameters(minTestsOk = 500)

  "Testing the simplex indexing code against Ulrich Bauer's paper examples" >> {

    "Error Case" >> {
      val si: SimplexIndexing = SimplexIndexing(15)
      si(72, 5) must be_==(∆(8, 6, 3, 1, 0))
    }

    "Examples from the Ripser paper, d=2, d=3" >> {
      val si: SimplexIndexing = SimplexIndexing(15)

      "idx[0,1,2]" ==> (si(∆(0, 1, 2)) must be_==(0))
      "idx[0,1,3]" ==> (si(∆(0, 1, 3)) must be_==(1))
      "idx[0,2,3]" ==> (si(∆(0, 2, 3)) must be_==(2))
      "idx[1,2,3]" ==> (si(∆(1, 2, 3)) must be_==(3))
      "idx[0,1,4]" ==> (si(∆(0, 1, 4)) must be_==(4))
      "idx[0,3,5]" ==> (si(∆(0, 3, 5)) must be_==(13))

      "idx[0,3,5,6]" ==> (si(∆(0, 3, 5, 6)) must be_==(28))
      "idx[0,3,4,5]" ==> (si(∆(0, 3, 4, 5)) must be_==(12))
      "idx[0,2,3,5]" ==> (si(∆(0, 2, 3, 5)) must be_==(7))
      "idx[0,1,3,5]" ==> (si(∆(0, 1, 3, 5)) must be_==(6))

      "spx(72, 5)" ==> (si(72, 5) must be_==(∆(8, 6, 3, 1, 0)))

      "spx(0, 3)" ==> (si(0, 3) must be_==(∆(0, 1, 2)))
      "spx(1, 3)" ==> (si(1, 3) must be_==(∆(0, 1, 3)))
      "spx(2, 3)" ==> (si(2, 3) must be_==(∆(0, 2, 3)))
      "spx(3, 3)" ==> (si(3, 3) must be_==(∆(1, 2, 3)))
      "spx(4, 3)" ==> (si(4, 3) must be_==(∆(0, 1, 4)))

      "spx(13, 3)" ==> (si(13, 3) must be_==(∆(0, 3, 5)))
      "spx(28, 4)" ==> (si(28, 4) must be_==(∆(0, 3, 5, 6)))
      "spx(12, 4)" ==> (si(12, 4) must be_==(∆(0, 3, 4, 5)))
      "spx(7, 4)" ==> (si(7, 4) must be_==(∆(0, 2, 3, 5)))
      "spx(6, 4)" ==> (si(6, 4) must be_==(∆(0, 1, 3, 5)))
    }

    "Simplex facet-iterator requirements" >> {
      val si: SimplexIndexing = SimplexIndexing(5)

      "all cofacets of [1,3]" ==> (
        si.cofacetIterator(si(∆(1, 3)), 2, true).toSeq must contain(
          exactly(
            si(∆(0, 1, 3)),
            si(∆(1, 2, 3)),
            si(∆(1, 3, 4))
          )
        )
      )

      "top cofacets of [1,3]" ==> (
        si.cofacetIterator(si(∆(1, 3)), 2, false).toSeq must contain(
          exactly(
            si(∆(1, 3, 4))
          )
        )
      )

      "facets of [1,2,4,5]" ==> (si
        .facetIterator(si(∆(1, 2, 4, 5)), 4)
        .toSeq must contain(
        exactly(
          si(∆(1, 2, 4)),
          si(∆(1, 2, 5)),
          si(∆(1, 4, 5)),
          si(∆(2, 4, 5))
        )
      ))
    }

    /** Backs two later fixes' correctness (`.claude/WORKLOG-ripser-profiling.md`): `decodeToArray`'s array+sort
      * rewrite of `apply`'s decode, and `RipserCohomologyContext.zeroPivotCofacet`/`zeroPivotFacet` reusing an
      * iterator's own already-known index instead of re-encoding the simplex it just decoded. Neither fix is
      * exercised by the hand-picked paper examples above (those never decode-then-encode the SAME simplex back).
      */
    "decodeToArray and decode-then-encode round-tripping, for arbitrary valid (vertexCount, size, index)" >> {
      val validCase = for
        vertexCount <- Gen.chooseNum(2, 25)
        size <- Gen.chooseNum(1, vertexCount)
        idx <- Gen.chooseNum(0L, math.max(0L, binomial(vertexCount, size) - 1))
      yield (vertexCount, size, idx)

      "decodeToArray agrees with apply's own Simplex[Int] decode" ==> forAll(validCase) {
        case (vertexCount, size, idx) =>
          val si = SimplexIndexing(vertexCount)
          si.decodeToArray(idx, size).toSet must be_==(si(idx, size).underlying)
      }

      "decode then re-encode is the identity on valid indices" ==> forAll(validCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        si(si(idx, size)) must be_==(idx)
      }
    }

    /** Backs the incremental-insert/incremental-remove optimizations `RipserCohomologyContext.coboundaryOf`/
      * `zeroPivotCofacet`/`zeroPivotFacet` build on top of `CofacetCursor`/`FacetCursor` (`.claude/
      * WORKLOG-ripser-profiling.md`'s cursor-redesign session): those callers build a cofacet/facet's vertex set by
      * inserting/removing `cursor.vertex` from an already-materialized set instead of decoding `cursor.index` fresh --
      * only sound if `cursor.vertex` really is the one vertex that distinguishes `cursor.index`'s simplex from the
      * cursor's own starting simplex. Checked directly against `decodeToArray`, independently of any assumption about
      * the cursors' own internal arithmetic.
      */
    "CofacetCursor/FacetCursor's vertex is exactly the vertex inserted/removed to reach their index, for arbitrary valid (vertexCount, size, index)" >> {
      val validCase = for
        vertexCount <- Gen.chooseNum(2, 25)
        size <- Gen.chooseNum(1, vertexCount)
        idx <- Gen.chooseNum(0L, math.max(0L, binomial(vertexCount, size) - 1))
      yield (vertexCount, size, idx)

      "CofacetCursor" ==> forAll(validCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        val startSet = si.decodeToArray(idx, size).toSet
        val cur = si.cofacetCursor(idx, size, allCofacets = true)
        var ok = true
        while cur.hasNext do
          if si.decodeToArray(cur.index, size + 1).toSet != startSet + cur.vertex then ok = false
          cur.advance()
        ok must beTrue
      }

      "FacetCursor" ==> forAll(validCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        val startSet = si.decodeToArray(idx, size).toSet
        val cur = si.facetCursor(idx, size)
        var ok = true
        var count = 0
        while cur.hasNext do
          if si.decodeToArray(cur.index, size - 1).toSet != startSet - cur.vertex then ok = false
          count += 1
          cur.advance()
        (ok must beTrue) and (count must be_==(size))
      }
    }
  }

class RipserStreamSpec extends Specification:
  "RipserStream interface testing" >> {
    val hc2: HyperCube = HyperCube(2)

    val rs: RipserStream = RipserStream(hc2, 5.0, 5)

    "0-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(0)
        .toSeq must contain(∆(0), ∆(1), ∆(2), ∆(3)))
    }
    "1-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(1)
        .toSeq must contain(
        ∆(0, 1),
        ∆(0, 2),
        ∆(0, 3),
        ∆(1, 2),
        ∆(1, 3),
        ∆(2, 3)
      ))
    }
    "2-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(2)
        .toSeq must contain(∆(0, 1, 2), ∆(0, 1, 3), ∆(0, 2, 3), ∆(1, 2, 3)))
    }
    "3-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(3)
        .toSeq must contain(∆(0, 1, 2, 3)))
    }
    "4-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(4)
        .toSeq must beEmpty)
    }
    "Check total orders of dimensions" >>
      Fragment.foreach(0 to hc2.size) { d =>
        s"$d is sorted" ! {
          rs
            .iteratorByDimension(d)
            .map(s => rs.filtrationValue(s))
            .toSeq must beSorted
        }
      }
    "Full simplex stream gives the right number of elements" >> {
      rs.iterator.toSeq must haveSize((1 << hc2.size) - 1)
    }
  }

  "Ripser and Vietoris-Rips find the same simplices" >> {
    val sG = HyperCubeSymmetryGenerators(3)

    val vr = SymmetricZomorodianIncremental[Int, Int](sG)
    val ss = vr(sG.hypercube, 2.0, 5)
    val rs = MaskedSymmetricRipserStream[Int](sG.hypercube, 2.0, 5, sG)
    pp(s"Ripser finds ${rs.iterator.size} simplices")
    pp(s"Zomorodian finds ${ss.size} simplices")
    rs.iterator.toSeq must containAllOf(ss)
    ss.iterator.toSeq must containAllOf(rs.iterator.toSeq)
    rs.iterator.size === ss.iterator.size
  }.pendingUntilFixed

  "Apparent facets, cofacets, pairs" >> {
    val ms = HyperCube(2) // work with the square
    val rs = RipserStream(ms, ms.minimumEnclosingRadius, 5)

    "[0,1,2] has zero pivot cofacet [0,1,2,3]" ==>
      (rs.zeroPivotCofacet(rs.si(∆(0, 1, 2)), 3)
        must beSome(rs.si(∆(0, 1, 2, 3))))

    "[0,1,3] has zero pivot facet [0,3]" ==>
      (rs.zeroPivotFacet(rs.si(∆(0, 1, 3)), 3)
        must beSome(rs.si(∆(0, 3))))
  }
