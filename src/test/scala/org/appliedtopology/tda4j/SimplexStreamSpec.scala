package org.appliedtopology.tda4j

import org.specs2.{Specification, mutable}
import org.specs2.execute.Result

import scala.collection.mutable as cmutable

class SimplexStreamSpec extends mutable.Specification {
  "Test suite for the abstraction and implementations of `SimplexStream`".txt

  val esb: ExplicitStreamBuilder[Int, Double] =
    ExplicitStreamBuilder[Int, Double]

  val simplexSeq: Seq[(Double, Simplex[Int])] = Seq(
    (0.0, Simplex(1)),
    (0.0, Simplex(2)),
    (0.0, Simplex(3)),
    (5.0, Simplex(2, 4)),
    (0.0, Simplex(4)),
    (1.0, Simplex(1, 2)),
    (3.0, Simplex(2, 3)),
    (2.0, Simplex(1, 3)),
    (4.0, Simplex(1, 4)),
    (6.0, Simplex(1, 2, 3))
  )

  esb ++= simplexSeq

  val simplexStream: ExplicitStream[Int, Double] = esb.result()

  "An ExplicitStream should" >> {
    "have simplices" >> {
      simplexStream.iterator.size must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      given Ordering[Int] = Ordering.Int
      simplexStream.iterator
        .map(simplexStream.filtrationValue)
        .to(Seq) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[Simplex[Int]] =
        cmutable.Set(Simplex())
      simplexStream.iterator.foreach { spx =>
        seen += spx
        spx.vertices.toSet
          .subsets()
          .foreach(face => seen must contain(Simplex.from(face)))
      }
    }
    "have all simplices, in order" >> {
      simplexStream.iterator.to(Seq) must contain[Simplex[Int]](
        exactly(
          ===(∆(1)),
          ===(∆(2)),
          ===(∆(3)),
          ===(∆(4)),
          ===(∆(1, 2)),
          ===(∆(1, 3)),
          ===(∆(2, 3)),
          ===(∆(1, 4)),
          ===(∆(2, 4)),
          ===(∆(1, 2, 3))
        ).inOrder
      )
    }
    "be able to compute filtration values" >> {
      simplexStream.filtrationValue(∆(1, 2)) must be_==(1.0)
      simplexStream.filtrationValue(∆(1, 2, 3)) must be_==(6.0)
    }
  }

  "A SimplexStream induced FilteredSimplexOrdering should" >> {
    given filteredSimplexOrdering: Ordering[Simplex[Int]] =
      new FilteredSimplexOrdering[Int, Double](simplexStream)
    val sortedSimplexSeq = simplexSeq.map((_, s) => s).sorted
    "have filtration values in ascending order" >> {
      sortedSimplexSeq.map(simplexStream.filtrationValue) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[Simplex[Int]] =
        cmutable.Set(Simplex())
      sortedSimplexSeq.foreach { spx =>
        seen += spx
        spx.vertices
          .toSet
          .subsets()
          .foreach(face => seen must contain(Simplex.from(face)))
      }
    }
  }
}

class GradedSimplexStreamSpec extends mutable.Specification{

  val metricSpace = EuclideanMetricSpace(Seq(Seq(0.0,0.0),Seq(1.0,1.0),Seq(0.0,1.0),Seq(1.0,0.0)))

  val gradedSimplexStream = GradedSimplexStream.from(Seq(
    // Dimension 0
    Seq(
      Simplex(0), Simplex(1), Simplex(2)
    ),
    // Dimension 1
    Seq(
      Simplex(0,1), Simplex(0,2), Simplex(1,2)
    ),
    // Dimension 2
    Seq(
      Simplex(0,1,2)
    ),
  ),metricSpace)

  "Graded Simplex Stream can " >> {
    "give the correct 1-simplices" >> {

      gradedSimplexStream.iterateDimension(1).toSeq must containTheSameElementsAs(
        Seq(
          Simplex(0,1), Simplex(0,2), Simplex(1,2)
        )
      )

    }
    "give the correct 0-simplices" >> {

      gradedSimplexStream.iterateDimension(0).toSeq must containTheSameElementsAs(
        Seq(
          Simplex(1), Simplex(2), Simplex(0)
        )
      )
    }
    "give the correct 2-simplices" >> {

      gradedSimplexStream.iterateDimension(2).toSeq must containTheSameElementsAs(
        Seq(
          Simplex(0,1,2)
        )
      )

    }
  }

}