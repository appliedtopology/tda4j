package org.appliedtopology.tda4j

import scala.collection.{
  mutable,
  SortedIterableFactory,
  SortedSetFactoryDefaults,
  StrictOptimizedSortedSetOps
}
import scala.collection.immutable.{SortedSet, SortedSetOps, TreeSet, SortedMap}
import scala.math.Ordering.IntOrdering
import scala.math.Ordering.Double.IeeeOrdering
import math.Ordering.Implicits.sortedSetOrdering

/** Type alias creating `Simplex` as the type representing
  * `AbstractSimplex[Int]`
  *
  * @todo
  *   Figure out whether it would be better to make `Simplex` a class of its own
  *   and override everything to get the type to print out at `Simplex`
  *   everywhere instead of as the more verbose `AbstractSimplex`
  */
type Simplex = AbstractSimplex[Int]

object Simplex {
  def apply(vertices: Int*) = new Simplex(TreeSet[Int](vertices: _*))
}


/** Class representing an abstract simplex. Abstract simplices are sets (of
  * totally ordered vertices) and thus are represented by a type that implements
  * the interface of a `SortedSet` fully, and also inherits from `Cell` so that
  * the class has a `boundary` method.
  *
  * With this `SortedSet` interface in place, there is scope for using
  * `AbstractSimplex` for combinatorial algebraic topology tasks unrelated to
  * persistence, and possibly for writing persistence algorithms more smoothly.
  *
  * You should never have reason to use the constructor directly (...and if you
  * do, you should make sure to give the internal `SortedSet` yourself) -
  * instead use the factory method in the companion object. In code this means
  * that instead of `new AbstractSimplex[T](a,b,c)` you would write
  * `AbstractSimplex[T](a,b,c)`.
  *
  * @param vertexSet
  *   Vertices of the simplex
  * @param ordering
  *   Ordering of the vertex type
  * @tparam VertexT
  *   Vertex type
  */
class AbstractSimplex[VertexT](protected val vertexSet: SortedSet[VertexT])( //vertexSet variable defined here
  using val ordering: Ordering[VertexT] //ordering definied here
) extends Cell[AbstractSimplex[VertexT]]
    with SortedSet[VertexT]
    with SortedSetOps[VertexT, AbstractSimplex, AbstractSimplex[VertexT]]
    with SortedSetFactoryDefaults[VertexT, AbstractSimplex, Set] {
  self => // gives methods access to the object that's calling it in the first place

  // ***** Simplex specific operations

  /** Implementation of the face enumeration for the simplicial boundary map:
    * exclude one element at a time, and use alternating signs.
    *
    * @return
    *   A sequence of boundary cells.
    * @todo
    *   Change `List` to `Chain` once we have an implementation of `Chain`
    */
  override def boundary[CoefficientT](using fr : Fractional[CoefficientT]): Chain[AbstractSimplex[VertexT], CoefficientT] =
    Chain[AbstractSimplex[VertexT], CoefficientT](
      self.to(Seq).map(vtx => self-vtx).zip(Iterator.unfold(fr.one)(s => Some((s,fr.negate(s))))) : _*
    )

  // ***** Overriding for inheriting and extending standard library constructions
  override def className = "AbstractSimplex"

  override def iterator: Iterator[VertexT] = vertexSet.iterator

  override def excl(elem: VertexT): AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](
      vertexSet.excl(elem)
    )

  override def incl(elem: VertexT): AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](
      vertexSet.incl(elem)
    )

  override def contains(elem: VertexT): Boolean = vertexSet.contains(elem)

  override def rangeImpl(
    from: Option[VertexT],
    until: Option[VertexT]
  ): AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](vertexSet.rangeImpl(from, until))

  override def iteratorFrom(start: VertexT): Iterator[VertexT] =
    vertexSet.iteratorFrom(start)

  override def sortedIterableFactory: SortedIterableFactory[AbstractSimplex] =
    AbstractSimplex
}

/** Simplex companion object with factory methods
  */
object AbstractSimplex extends SortedIterableFactory[AbstractSimplex] {
  override def apply[VertexT: Ordering](vertices: VertexT*) =
    new AbstractSimplex[VertexT](TreeSet[VertexT](vertices: _*))
  override def empty[VertexT: Ordering]: AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](TreeSet[VertexT]())

  override def from[VertexT: Ordering](
    source: IterableOnce[VertexT]
  ): AbstractSimplex[VertexT] =
    (newBuilder[VertexT] ++= source).result()

  override def newBuilder[VertexT: Ordering]
    : mutable.Builder[VertexT, AbstractSimplex[VertexT]] =
    new mutable.ReusableBuilder[VertexT, AbstractSimplex[VertexT]] {
      private val elems = mutable.Set[VertexT]()

      override def clear(): Unit = elems.clear()

      def addOne(elem: VertexT): this.type =
        elems += elem; this

      override def result(): AbstractSimplex[VertexT] =
        new AbstractSimplex[VertexT](TreeSet[VertexT](elems.to(Seq): _*))
    }

}
