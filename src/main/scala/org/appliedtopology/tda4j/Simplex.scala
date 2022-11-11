package org.appliedtopology.tda4j

import scala.collection.{SortedIterableFactory, SortedSetFactoryDefaults, StrictOptimizedSortedSetOps, mutable}
import scala.collection.immutable.{SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.Double.IeeeOrdering
import scala.math.Ordering.IntOrdering

/**
 * Lightweight trait to define what it means to be a topological "Cell".
 */
trait Cell {
  /**
   * The boundary map of the cell.
   *
   * @return A sequence of boundary cells.
   * @todo When a `Chain` trait or class has been created, this should change to return an
   *       appropriate `Chain` instead of the current `List`.
   */
  def boundary() : Seq[Cell]
}

/**
 * Type alias creating `Simplex` as the type representing `AbstractSimplex[Int]`
 *
 * @todo Figure out whether it would be better to make `Simplex` a class of its own and override
 *       everything to get the type to print out at `Simplex` everywhere instead of as the more
 *       verbose `AbstractSimplex`
 */
type Simplex = AbstractSimplex[Int]

object Simplex extends AbstractSimplex[Int] {
  def apply(vertices: Int*) = new Simplex(vertices: _*)
}

/**
 * Class representing an abstract simplex. Abstract simplices are sets (of totally ordered vertices)
 * and thus are represented by a type that implements the interface of a `SortedSet` fully, and also
 * inherits from `Cell` so that the class has a `boundary` method.
 *
 * With this `SortedSet` interface in place, there is scope for using `AbstractSimplex` for combinatorial
 * algebraic topology tasks unrelated to persistence, and possibly for writing persistence algorithms
 * more smoothly.
 *
 * @param vertices Vertices of the simplex
 * @param ordering Ordering of the vertex type
 * @tparam VertexT Vertex type
 */
class AbstractSimplex[VertexT](vertices: VertexT*)(implicit val ordering: Ordering[VertexT])
    extends SortedSet[VertexT]
    with SortedSetOps[VertexT, AbstractSimplex, AbstractSimplex[VertexT]]
    with SortedSetFactoryDefaults[VertexT, AbstractSimplex, Set]
    with Cell
    {
  self => //gives methods access to the object that's calling it in the first place

  protected val vertexSet = TreeSet[VertexT](vertices: _*)(ordering)

  // ***** Simplex specific operations

      /**
       * Implementation of the face enumeration for the simplicial boundary map:
       * exclude one element at a time, and use alternating signs.
       *
       *  @return A sequence of boundary cells.
       *  @todo Change `List` to `Chain` once we have an implementation of `Chain`
       */
  override def boundary(): List[AbstractSimplex[VertexT]] =
    self.subsets(self.size - 1).to(List)

  // ***** Overriding for inheriting and extending standard library constructions
  override def className = "AbstractSimplex"

  override def iterator: Iterator[VertexT] = vertexSet.iterator

  override def excl(elem: VertexT): AbstractSimplex[VertexT] = new AbstractSimplex[VertexT](
    vertexSet.excl(elem).toSeq: _*
  )

  override def incl(elem: VertexT): AbstractSimplex[VertexT] = new AbstractSimplex[VertexT](
    vertexSet.incl(elem).toSeq: _*
  )

  override def contains(elem: VertexT): Boolean = vertexSet.contains(elem)

  override def rangeImpl(
                          from: Option[VertexT],
                          until: Option[VertexT]
  ): AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](vertexSet.rangeImpl(from, until).toSeq: _*)

  override def iteratorFrom(start: VertexT): Iterator[VertexT] =
    vertexSet.iteratorFrom(start)

  override def sortedIterableFactory: SortedIterableFactory[AbstractSimplex] =
    AbstractSimplex
}

/** Simplex companion object with factory methods
  */
object AbstractSimplex extends SortedIterableFactory[AbstractSimplex] {
  override def empty[VertexT: Ordering]: AbstractSimplex[VertexT] = new AbstractSimplex[VertexT]()

  override def from[VertexT: Ordering](source: IterableOnce[VertexT]): AbstractSimplex[VertexT] =
    (newBuilder[VertexT] ++= source).result()

  override def newBuilder[VertexT: Ordering]: mutable.Builder[VertexT, AbstractSimplex[VertexT]] =
    new mutable.ImmutableBuilder[VertexT, AbstractSimplex[VertexT]](empty) {
      def addOne(elem: VertexT): this.type =
        elems += elem; this
    }
}
