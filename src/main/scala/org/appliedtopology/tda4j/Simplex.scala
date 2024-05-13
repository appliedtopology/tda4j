package org.appliedtopology.tda4j

import scala.collection.{mutable, SortedIterableFactory, SortedSetFactoryDefaults, StrictOptimizedSortedSetOps}
import scala.collection.immutable.{Set, SortedMap, SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.IntOrdering
import scala.math.Ordering.Double.IeeeOrdering
import math.Ordering.Implicits.sortedSetOrdering

given simplexOrdering[VertexT: Ordering]: Ordering[Simplex[VertexT]] = sortedSetOrdering[Simplex, VertexT]

/** Class representing an abstract simplex. Abstract simplices are sets (of totally ordered vertices) and thus are
  * represented by a type that implements the interface of a `SortedSet` fully, and also inherits from `Cell` so that
  * the class has a `boundary` method.
  *
  * With this `SortedSet` interface in place, there is scope for using `Simplex` for combinatorial algebraic topology
  * tasks unrelated to persistence, and possibly for writing persistence algorithms more smoothly.
  *
  * You should never have reason to use the constructor directly (...and if you do, you should make sure to give the
  * internal `SortedSet` yourself) - instead use the factory method in the companion object. In code this means that
  * instead of `new Simplex[T](a,b,c)` you would write `Simplex[T](a,b,c)`.
  *
  * @param vertexSet
  *   Vertices of the simplex
  * @param ordering
  *   Ordering of the vertex type
  * @tparam VertexT
  *   Vertex type
  */
class Simplex[VertexT](protected val vertexSet: SortedSet[VertexT])( //vertexSet variable defined here
  using val ordering: Ordering[VertexT] // ordering definied here
) extends Cell[Simplex[VertexT]]
    with SortedSet[VertexT]
    with SortedSetOps[VertexT, Simplex, Simplex[VertexT]]
    with SortedSetFactoryDefaults[VertexT, Simplex, Set] {
  self => // gives methods access to the object that's calling it in the first place

  override def toString(): String =
    vertexSet.mkString(s"∆(", ",", ")")

  // ***** Simplex specific operations

  /** Implementation of the face enumeration for the simplicial boundary map: exclude one element at a time, and use
    * alternating signs.
    *
    * @return
    *   A sequence of boundary cells.
    * @todo
    *   Change `List` to `Chain` once we have an implementation of `Chain`
    */
  override def boundary[CoefficientT](using
    fr: Fractional[CoefficientT]
  ): Chain[Simplex[VertexT], CoefficientT] =
    if (dim == 0) Chain()
    else
      Chain.from(
        self
          .to(Seq)
          .map(vtx => self - vtx)
          .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
      )

  def dim: Int = size - 1

  // ***** Overriding for inheriting and extending standard library constructions
  override def className = "Simplex"

  override def iterator: Iterator[VertexT] = vertexSet.iterator

  override def excl(elem: VertexT): Simplex[VertexT] =
    new Simplex[VertexT](
      vertexSet.excl(elem)
    )

  override def incl(elem: VertexT): Simplex[VertexT] =
    new Simplex[VertexT](
      vertexSet.incl(elem)
    )

  override def contains(elem: VertexT): Boolean = vertexSet.contains(elem)

  override def rangeImpl(
    from: Option[VertexT],
    until: Option[VertexT]
  ): Simplex[VertexT] =
    new Simplex[VertexT](vertexSet.rangeImpl(from, until))

  override def iteratorFrom(start: VertexT): Iterator[VertexT] =
    vertexSet.iteratorFrom(start)

  override def sortedIterableFactory: SortedIterableFactory[Simplex] =
    Simplex
}

/** Convenience method for defining simplices
  *
  * The character ◊, used to avoid hogging `s`, is typed as Alt+Shift+V on Mac GB layout, and has Unicode code 0x25CA.
  *
  * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
  *
  * The character σ has Windows Alt-code 229, and unicode code 0x03c3.
  */
def s[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)
def ◊[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)
def ∆[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)
def σ[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)

/** Simplex companion object with factory methods
  */
object Simplex extends SortedIterableFactory[Simplex] {
  override def apply[VertexT: Ordering](vertices: VertexT*) =
    new Simplex[VertexT](TreeSet[VertexT](vertices: _*))
  override def empty[VertexT: Ordering]: Simplex[VertexT] =
    new Simplex[VertexT](TreeSet[VertexT]())

  override def from[VertexT: Ordering](
    source: IterableOnce[VertexT]
  ): Simplex[VertexT] =
    (newBuilder[VertexT] ++= source).result()

  override def newBuilder[VertexT: Ordering]: mutable.Builder[VertexT, Simplex[VertexT]] =
    new mutable.ReusableBuilder[VertexT, Simplex[VertexT]] {
      private val elems = mutable.Set[VertexT]()

      override def clear(): Unit = elems.clear()

      def addOne(elem: VertexT): this.type =
        elems += elem; this

      override def result(): Simplex[VertexT] =
        new Simplex[VertexT](TreeSet[VertexT](elems.to(Seq): _*))
    }

}
