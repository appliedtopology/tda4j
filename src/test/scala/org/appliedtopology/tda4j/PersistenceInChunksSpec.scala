package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.ScalaCheck

class PersistenceInChunksSpec extends mutable.Specification with ScalaCheck {
  given (Double is Field) = Field.DoubleApproximated(1e-25)

  "Homology of a triangle" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3))))
    val rawStream = streamBuilder.result()
    val byDim: Map[Int, Seq[Simplex[Int]]] =
      rawStream.iterator.toSeq.groupBy(_.dim)

    val stream: StratifiedCellStream[Simplex[Int], Double] =
      new StratifiedCellStream[Simplex[Int], Double] {
        def filtrationValue = rawStream.filtrationValue
        def filtrationOrdering = rawStream.filtrationOrdering
        val smallest = Double.NegativeInfinity
        var largest = Double.PositiveInfinity
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }
      }
    val homology = persistentHomology(stream)
    homology.diagramAt(5.0) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
        (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
        (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
        (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3.
      )
    )
  }

  "Homology of a filled tetrahedron" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3, 4).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List(
      (1.0, ∆(1, 2)),
      (2.0, ∆(1, 3)),
      (3.0, ∆(1, 4)),
      (4.0, ∆(2, 3)),
      (5.0, ∆(2, 4)),
      (6.0, ∆(3, 4))
    ))
    streamBuilder.addAll(List(
      (7.0,  ∆(1, 2, 3)),
      (8.0,  ∆(1, 2, 4)),
      (9.0,  ∆(1, 3, 4)),
      (10.0, ∆(2, 3, 4))
    ))
    streamBuilder.addOne((11.0, ∆(1, 2, 3, 4)))

    val rawStream = streamBuilder.result()
    val byDim: Map[Int, Seq[Simplex[Int]]] =
      rawStream.iterator.toSeq.groupBy(_.dim)

    val stream: StratifiedCellStream[Simplex[Int], Double] =
      new StratifiedCellStream[Simplex[Int], Double] {
        def filtrationValue = rawStream.filtrationValue
        def filtrationOrdering = rawStream.filtrationOrdering
        val smallest = Double.NegativeInfinity
        var largest = Double.PositiveInfinity
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }
      }

    val homology = persistentHomology(stream)
    homology.diagramAt(12.0) must containTheSameElementsAs(
      List(
        // H_0: 3 vertices die when connected to vertex 1; one lives forever
        (0, 0.0, 1.0),                          // killed by edge {1,2}
        (0, 0.0, 2.0),                          // killed by edge {1,3}
        (0, 0.0, 3.0),                          // killed by edge {1,4}
        (0, 0.0, Double.PositiveInfinity),      // essential 0-class
        // H_1: 3 independent 1-cycles each filled by a triangle
        (1, 4.0, 7.0),                          // {2,3} born; {1,2,3} kills it
        (1, 5.0, 8.0),                          // {2,4} born; {1,2,4} kills it
        (1, 6.0, 9.0),                          // {3,4} born; {1,3,4} kills it
        // H_2: 2-sphere boundary born by {2,3,4}; filled by the tetrahedron
        (2, 10.0, 11.0)
      )
    )
  }
}
