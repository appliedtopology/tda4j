package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class SimplexIndexingSpec extends Specification {
  "Testing the simplex indexing code against Ulrich Bauer's paper examples" >> {

    given sc: SimplexContext[Int]()
    import sc.*

    "Error Case" >> {
      val si: SimplexIndexing = SimplexIndexing(15)
      si(72, 5) must be_==(s(8,6,3,1,0))
    }

    "Examples from the Ripser paper, d=2, d=3" >> {
      val si: SimplexIndexing = SimplexIndexing(15)

      "idx[0,1,2]" ==> (si(s(0,1,2)) must be_==(0))
      "idx[0,1,3]" ==> (si(s(0,1,3)) must be_==(1))
      "idx[0,2,3]" ==> (si(s(0,2,3)) must be_==(2))
      "idx[1,2,3]" ==> (si(s(1,2,3)) must be_==(3))
      "idx[0,1,4]" ==> (si(s(0,1,4)) must be_==(4))
      "idx[0,3,5]" ==> (si(s(0,3,5)) must be_==(13))

      "idx[0,3,5,6]" ==> (si(s(0,3,5,6)) must be_==(28))
      "idx[0,3,4,5]" ==> (si(s(0,3,4,5)) must be_==(12))
      "idx[0,2,3,5]" ==> (si(s(0,2,3,5)) must be_==(7))
      "idx[0,1,3,5]" ==> (si(s(0,1,3,5)) must be_==(6))

      "spx(72, 5)" ==> (si(72, 5) must be_==(s(8,6,3,1,0)))

      "spx(0, 3)" ==> (si(0, 3) must be_==(s(0,1,2)))
      "spx(1, 3)" ==> (si(1, 3) must be_==(s(0,1,3)))
      "spx(2, 3)" ==> (si(2, 3) must be_==(s(0,2,3)))
      "spx(3, 3)" ==> (si(3, 3) must be_==(s(1,2,3)))
      "spx(4, 3)" ==> (si(4, 3) must be_==(s(0,1,4)))

      "spx(13, 3)" ==> (si(13, 3) must be_==(s(0,3,5)))
      "spx(28, 4)" ==> (si(28, 4) must be_==(s(0,3,5,6)))
      "spx(12, 4)" ==> (si(12, 4) must be_==(s(0,3,4,5)))
      "spx(7, 4)" ==> (si(7, 4) must be_==(s(0,2,3,5)))
      "spx(6, 4)" ==> (si(6, 4) must be_==(s(0,1,3,5)))
    }

    "Simplex facet-iterator requirements" >> {
      val si: SimplexIndexing = SimplexIndexing(5)

      "all cofacets of [1,3]" ==> (
        si.cofacetIterator(si(s(1,3)), 2, true).toSeq must contain(exactly(
          si(s(0,1,3)),
          si(s(1,2,3)),
          si(s(1,3,4)),
        ))
      )

      "top cofacets of [1,3]" ==> (
        si.cofacetIterator(si(s(1,3)), 2, false).toSeq must contain(exactly(
          si(s(1,3,4)),
        ))
      )

      "facets of [1,2,4,5]" ==> (
        si.facetIterator(si(s(1, 2, 4, 5)), 4).toSeq must contain(exactly(
          si(s(1, 2, 4)),
          si(s(1, 2, 5)),
          si(s(1, 4, 5)),
          si(s(2, 4, 5)),
        )))
    }
  }
}

class RipserStreamSpec extends Specification {
  "RipserStream interface testing" >> {

  }
}
