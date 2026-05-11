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
}
