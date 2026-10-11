package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** The violations of monotonicity in `filtrationValue` (a non-degenerate face with a larger value than its coface), as
  * messages; empty if there are none. Degenerate faces do not enter boundaries and are not checked.
  */
def validateMonotoneFiltration[G](sset: FiniteSimplicialSet[G], filtrationValue: G => Double): Seq[String] =
  sset.generatorsByDim.zipWithIndex.flatMap { case (gens, n) =>
    gens.toSeq.flatMap { g =>
      sset.faces(g).zipWithIndex.collect {
        case (SSetElement(Nil, target), i) if filtrationValue(target) > filtrationValue(g) =>
          s"$g (dim $n): d_$i target $target has filtration ${filtrationValue(target)} > ${filtrationValue(g)}"
      }
    }
  }

/** A finite simplicial set filtered by `filtrationValue` on its generators, as a stream that every engine accepts
  * (unlike [[SimplicialSetStream]], which puts everything at `0`). The filtration must be monotone
  * ([[validateMonotoneFiltration]]).
  */
open class FilteredSimplicialSetStream[G](
  sset: FiniteSimplicialSet[G],
  val filtrationValue: PartialFunction[G, Double]
)(using G is OrderedCell)
    extends StratifiedCellStream[G, Double]:
  override val filtrationOrdering: Ordering[G] =
    FiltrationOrdering.canonical(filtrationValue, sset.dimOf, sset.ord)
  override def iterateDimension: PartialFunction[Int, Iterator[G]] =
    case d if d >= 0 && d < sset.generatorsByDim.length =>
      sset.generatorsByDim(d).toVector.sorted(using filtrationOrdering.reverse).iterator
  export Filterable.DoubleIsFilterable.{largest, smallest}

object FilteredSimplicialSetStream:
  def apply[G](
    sset: FiniteSimplicialSet[G],
    filtrationValue: PartialFunction[G, Double]
  ): FilteredSimplicialSetStream[G] =
    given (G is OrderedCell) = sset.cellInstance
    new FilteredSimplicialSetStream(sset, filtrationValue)
