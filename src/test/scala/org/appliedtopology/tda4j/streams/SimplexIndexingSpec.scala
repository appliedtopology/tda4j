package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import SimplexIndexing.binomial

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.scalacheck.Parameters

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

    /** Backs two later fixes' correctness (`.claude/WORKLOG-ripser-profiling.md`): `decodeToArray`'s array+sort rewrite
      * of `apply`'s decode, and `RipserCohomologyContext.zeroPivotCofacet`/`zeroPivotFacet` reusing an iterator's own
      * already-known index instead of re-encoding the simplex it just decoded. Neither fix is exercised by the
      * hand-picked paper examples above (those never decode-then-encode the SAME simplex back).
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

    /** Backs an early-exit optimization in `zeroPivotCofacet`/`zeroPivotFacet` (both engines,
      * `Homology.scala`/`PackedRipserCohomology.scala`): those methods want the cofacet with the MAXIMUM index
      * (resp. facet with the MINIMUM index) among candidates tied at the target diameter, and previously found
      * it by sweeping every candidate and tracking a running best -- wasted work if the cursor's own
      * enumeration order already visits candidates in strict index order, since the FIRST tied match would then
      * already be the extremal one. Confirmed here, not assumed: `CofacetCursor`'s `.index` is strictly
      * DECREASING across successive `advance()` calls (so first tied match = max index) and `FacetCursor`'s is
      * strictly INCREASING (so first tied match = min index) -- matching real `ripser.cpp`'s own
      * `get_zero_pivot_cofacet`, which returns on the first diameter-tied cofacet with no further scan, relying
      * on the same combinatorial-number-system property.
      */
    "CofacetCursor's index is strictly decreasing and FacetCursor's is strictly increasing across successive advance() calls" >> {
      val validCase = for
        vertexCount <- Gen.chooseNum(2, 25)
        size <- Gen.chooseNum(1, vertexCount - 1)
        idx <- Gen.chooseNum(0L, math.max(0L, binomial(vertexCount, size) - 1))
      yield (vertexCount, size, idx)

      "CofacetCursor" ==> forAll(validCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        val cur = si.cofacetCursor(idx, size, allCofacets = true)
        var prev: Long = Long.MaxValue
        var ok = true
        while cur.hasNext do
          if cur.index >= prev then ok = false
          prev = cur.index
          cur.advance()
        ok must beTrue
      }

      "FacetCursor" ==> forAll(validCase) { case (vertexCount, size, idx) =>
        val si = SimplexIndexing(vertexCount)
        val cur = si.facetCursor(idx, size)
        var prev: Long = Long.MinValue
        var ok = true
        while cur.hasNext do
          if cur.index <= prev then ok = false
          prev = cur.index
          cur.advance()
        ok must beTrue
      }
    }
  }
