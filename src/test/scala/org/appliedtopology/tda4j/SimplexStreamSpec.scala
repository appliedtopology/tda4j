package org.appliedtopology.tda4j

import org.specs2.{mutable, Specification}
import org.specs2.execute.Result

import scala.collection.mutable as cmutable

class SimplexStreamSpec extends mutable.Specification {
  "Test suite for the abstraction and implementations of `SimplexStream`".txt

  var esb : ExplicitStreamBuilder[Int, Double] = ExplicitStreamBuilder[Int, Double]()

  val simplexSeq : Seq[(Double, Simplex)] = Seq(
    (0.0, Simplex(1)),
    (0.0, Simplex(2)),
    (0.0, Simplex(3)),
    (5.0, Simplex(2,4)),
    (0.0, Simplex(4)),
    (1.0, Simplex(1,2)),
    (3.0, Simplex(2,3)),
    (2.0, Simplex(1,3)),
    (4.0, Simplex(1,4)),
    (6.0, Simplex(1,2,3)),
  )

  esb ++= simplexSeq

  val simplexStream : ExplicitStream[Int, Double] = esb.result()

  "An ExplicitStream should" >> {
    "have simplices" >> {
      simplexStream.iterator.size must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      given Ordering[Int] = Ordering.Int
      simplexStream.iterator.map(simplexStream.filtrationValue).to(Seq) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[AbstractSimplex[Int]] = cmutable.Set(AbstractSimplex())
      simplexStream.iterator.foreach(spx => {
        seen += spx
        spx.to(AbstractSimplex).subsets().foreach(face => seen must contain(face))
      })
    }
    "have all simplices, in order" >> {
      simplexStream.iterator.to(Seq) must contain(exactly(
        === (new Simplex(1)),
        === (new Simplex(2)),
        === (new Simplex(3)),
        === (new Simplex(4)),
        === (new Simplex(1,2)),
        === (new Simplex(1,3)),
        === (new Simplex(2,3)),
        === (new Simplex(1,4)),
        === (new Simplex(2,4)),
        === (new Simplex(1,2,3))
      ).inOrder)
    }
    "be able to compute filtration values" >> {
      simplexStream.filtrationValue(Simplex(1,2)) must be_==(1.0)
      simplexStream.filtrationValue(Simplex(1,2,3)) must be_==(6.0)
    }
  }

  "A SimplexStream induced FilteredSimplexOrdering should" >> {
    given filteredSimplexOrdering : Ordering[AbstractSimplex[Int]] =
      new FilteredSimplexOrdering[Int, Double](simplexStream)
    val sortedSimplexSeq = simplexSeq.map((f,s) => s).sorted
    "have filtration values in ascending order" >> {
      sortedSimplexSeq.map(simplexStream.filtrationValue) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[AbstractSimplex[Int]] = cmutable.Set(AbstractSimplex())
      sortedSimplexSeq.foreach(spx => {
        seen += spx
        spx.to(AbstractSimplex).subsets().foreach(face => seen must contain(face))
      })
    }
  }
}
