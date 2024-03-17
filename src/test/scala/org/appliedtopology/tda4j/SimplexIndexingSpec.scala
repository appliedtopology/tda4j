package org.appliedtopology.tda4j

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

class SimplexIndexingSpec extends Specification {
  "Testing the simplex indexing code against Ulrich Bauer's paper examples" >> {

    given sc: SimplexContext[Int]()
    import sc.*

    "Error Case" >> {
      val si: SimplexIndexing = SimplexIndexing(15)
      si(72, 5) must be_==(s(8, 6, 3, 1, 0))
    }

    "Examples from the Ripser paper, d=2, d=3" >> {
      val si: SimplexIndexing = SimplexIndexing(15)

      "idx[0,1,2]" ==> (si(s(0, 1, 2)) must be_==(0))
      "idx[0,1,3]" ==> (si(s(0, 1, 3)) must be_==(1))
      "idx[0,2,3]" ==> (si(s(0, 2, 3)) must be_==(2))
      "idx[1,2,3]" ==> (si(s(1, 2, 3)) must be_==(3))
      "idx[0,1,4]" ==> (si(s(0, 1, 4)) must be_==(4))
      "idx[0,3,5]" ==> (si(s(0, 3, 5)) must be_==(13))

      "idx[0,3,5,6]" ==> (si(s(0, 3, 5, 6)) must be_==(28))
      "idx[0,3,4,5]" ==> (si(s(0, 3, 4, 5)) must be_==(12))
      "idx[0,2,3,5]" ==> (si(s(0, 2, 3, 5)) must be_==(7))
      "idx[0,1,3,5]" ==> (si(s(0, 1, 3, 5)) must be_==(6))

      "spx(72, 5)" ==> (si(72, 5) must be_==(s(8, 6, 3, 1, 0)))

      "spx(0, 3)" ==> (si(0, 3) must be_==(s(0, 1, 2)))
      "spx(1, 3)" ==> (si(1, 3) must be_==(s(0, 1, 3)))
      "spx(2, 3)" ==> (si(2, 3) must be_==(s(0, 2, 3)))
      "spx(3, 3)" ==> (si(3, 3) must be_==(s(1, 2, 3)))
      "spx(4, 3)" ==> (si(4, 3) must be_==(s(0, 1, 4)))

      "spx(13, 3)" ==> (si(13, 3) must be_==(s(0, 3, 5)))
      "spx(28, 4)" ==> (si(28, 4) must be_==(s(0, 3, 5, 6)))
      "spx(12, 4)" ==> (si(12, 4) must be_==(s(0, 3, 4, 5)))
      "spx(7, 4)" ==> (si(7, 4) must be_==(s(0, 2, 3, 5)))
      "spx(6, 4)" ==> (si(6, 4) must be_==(s(0, 1, 3, 5)))
    }

    "Simplex facet-iterator requirements" >> {
      val si: SimplexIndexing = SimplexIndexing(5)

      "all cofacets of [1,3]" ==> (
        si.cofacetIterator(si(s(1, 3)), 2, true).toSeq must contain(
          exactly(
            si(s(0, 1, 3)),
            si(s(1, 2, 3)),
            si(s(1, 3, 4))
          )
        )
      )

      "top cofacets of [1,3]" ==> (
        si.cofacetIterator(si(s(1, 3)), 2, false).toSeq must contain(
          exactly(
            si(s(1, 3, 4))
          )
        )
      )

      "facets of [1,2,4,5]" ==> (si
        .facetIterator(si(s(1, 2, 4, 5)), 4)
        .toSeq must contain(
        exactly(
          si(s(1, 2, 4)),
          si(s(1, 2, 5)),
          si(s(1, 4, 5)),
          si(s(2, 4, 5))
        )
      ))
    }
  }
}

class RipserStreamSpec extends Specification {
  "RipserStream interface testing" >> {
    given sc: SimplexContext[Int]()
    import sc.*

    val hc2: HyperCube = HyperCube(2)

    val rs: RipserStream = RipserStream(hc2, 5.0, 5)

    "0-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(0)
        .toSeq must contain(s(0), s(1), s(2), s(3)))
    }
    "1-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(1)
        .toSeq must contain(
        s(0, 1),
        s(0, 2),
        s(0, 3),
        s(1, 2),
        s(1, 3),
        s(2, 3)
      ))
    }
    "2-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(2)
        .toSeq must contain(s(0, 1, 2), s(0, 1, 3), s(0, 2, 3), s(1, 2, 3)))
    }
    "3-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(3)
        .toSeq must contain(s(0, 1, 2, 3)))
    }
    "4-dimensional" >> {
      "contains the right simplices" ==> (rs
        .iteratorByDimension(4)
        .toSeq must beEmpty)
    }
    "Check total orders of dimensions" >> {
      Fragment.foreach(0 to hc2.size) { d =>
        s"$d is sorted" ! {
          (rs
            .iteratorByDimension(d)
            .map(s => rs.filtrationValue(s))
            .toSeq must beSorted)
        }
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
  }

  "Apparent facets, cofacets, pairs" >> {
    val ms = HyperCube(2) // work with the square
    val rs = RipserStream(ms, ms.minimumEnclosingRadius, 5)

    "[0,1,2] has zero pivot cofacet [0,1,2,3]" ==>
      (rs.zeroPivotCofacet(rs.si(rs.sc.s(0, 1, 2)), 3) 
        must beSome(rs.si(rs.sc.s(0, 1, 2, 3))))

    "[0,1,3] has zero pivot facet [0,3]" ==>
      (rs.zeroPivotFacet(rs.si(rs.sc.s(0, 1, 3)), 3) 
        must beSome(rs.si(rs.sc.s(0, 3))))
  }
}
