package org.appliedtopology.tda4j

import scala.collection.{
  mutable,
  SortedIterableFactory,
  SortedSetFactoryDefaults,
  StrictOptimizedSortedSetOps
}
import scala.collection.immutable.{SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.IntOrdering

type Simplex = AbstractSimplex[Int]

object Simplex extends AbstractSimplex[Int] {
  def apply(vertices: Int*) = new Simplex(vertices: _*)
}

class AbstractSimplex[A](vertices: A*)(implicit val ordering: Ordering[A])
    extends SortedSet[A]
    with SortedSetOps[A, AbstractSimplex, AbstractSimplex[A]]
    with SortedSetFactoryDefaults[A, AbstractSimplex, Set]
    {
  self =>

  private val vertexSet = TreeSet[A](vertices: _*)(ordering)

  /** Simplex specific operations */
  def boundary(): List[AbstractSimplex[A]] =
    self.subsets(self.size - 1).to(List)

  /** Overriding for inheriting and extending standard library constructions */
  override def className = "AbstractSimplex"

  override def iterator: Iterator[A] = vertexSet.iterator

  override def excl(elem: A): AbstractSimplex[A] = new AbstractSimplex[A](
    vertexSet.excl(elem).toSeq: _*
  )

  override def incl(elem: A): AbstractSimplex[A] = new AbstractSimplex[A](
    vertexSet.excl(elem).toSeq: _*
  )

  override def contains(elem: A): Boolean = vertexSet.contains(elem)

  override def rangeImpl(
    from: Option[A],
    until: Option[A]
  ): AbstractSimplex[A] =
    new AbstractSimplex[A](vertexSet.rangeImpl(from, until).toSeq: _*)

  override def iteratorFrom(start: A): Iterator[A] =
    vertexSet.iteratorFrom(start)

  override def sortedIterableFactory: SortedIterableFactory[AbstractSimplex] =
    AbstractSimplex
}

/** Simplex companion object with factory methods
  */
object AbstractSimplex extends SortedIterableFactory[AbstractSimplex] {
  override def empty[A: Ordering]: AbstractSimplex[A] = new AbstractSimplex[A]()

  override def from[A: Ordering](source: IterableOnce[A]): AbstractSimplex[A] =
    (newBuilder[A] ++= source).result()

  override def newBuilder[A: Ordering]: mutable.Builder[A, AbstractSimplex[A]] =
    new mutable.ImmutableBuilder[A, AbstractSimplex[A]](empty) {
      def addOne(elem: A): this.type =
        elems += elem; this
    }
}
