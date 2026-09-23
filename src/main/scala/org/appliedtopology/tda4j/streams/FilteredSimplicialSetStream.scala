package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

/** Checks the one precondition every persistence engine in this codebase needs from a filtration: a face's value is
  * never larger than its coface's. Only BARE (`word = Nil`) direct faces matter here -- those are the only faces
  * `finiteSimplicialSetIsOrderedCell.boundary` (hence every engine consuming this complex) ever looks at; a degenerate
  * face contributes nothing to the boundary and is never separately filtration-tested. Lives here, not on
  * `FiniteSimplicialSet` itself: filtration is entirely an adapter-layer concern, per the architecture note in
  * CLAUDE.md's "Simplicial sets" section (a `FiniteSimplicialSet`'s own structure has no notion of filtration at all).
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

/** A `FiniteSimplicialSet` with a real, caller-supplied filtration -- unlike `SimplicialSetStream` (every generator at
  * filtration `0`, ordinary homology only), this is a genuine `StratifiedCellStream[G, Double]`, so it plugs into
  * `CellularPersistenceInChunksContext`/`PersistenceInChunksContext` as well as `CellularHomologyContext`.
  * `filtrationValue` is defined only on generators (never on arbitrary, possibly degenerate `SSetElement`s) -- correct
  * because every engine here only ever queries a stream's `filtrationValue` on the actual `CellT` values it iterates,
  * and `SimplicialSetStream`/this class both only ever iterate generators, never degenerate elements
  * (`finiteSimplicialSetIsOrderedCell.boundary` resolves degeneracy internally via `faces`, without the engine ever
  * seeing an `SSetElement` directly).
  *
  * `iterateDimension` sorts each dimension's bucket by `filtrationOrdering.reverse` -- oldest first, the SAME
  * `Ordering` object reversed, not an independently-built comparator -- matching the established convention this
  * codebase has broken and fixed three separate times when two independently-tie-broken orders disagreed (see
  * CLAUDE.md). Positional index within `PersistenceInChunksContext`'s `allCells` (built by dimension-major
  * concatenation of `iterateDimension`'s buckets) stands in for chunk-boundary/local-reduction "how old is this cell"
  * logic, so a bucket sorted any other way corrupts chunking even though it would still look like a valid total order
  * in isolation -- `FilteredSimplicialSetStreamSpec` asserts this directly rather than only checking the resulting
  * barcode.
  */
class FilteredSimplicialSetStream[G](
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
