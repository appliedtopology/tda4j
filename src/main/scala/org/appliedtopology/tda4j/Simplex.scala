package org.appliedtopology.tda4j

import scala.collection.{mutable, SortedIterableFactory, SortedSetFactoryDefaults, StrictOptimizedSortedSetOps}
import scala.collection.immutable.{Set, SortedMap, SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.IntOrdering
import scala.math.Ordering.Double.IeeeOrdering
import math.Ordering.Implicits.sortedSetOrdering

given simplexOrdering[VertexT: Ordering]: Ordering[Simplex[VertexT]] =
  Ordering.by{(spx:Simplex[VertexT]) => spx.vertices}(sortedSetOrdering[SortedSet, VertexT])

/** Class representing an abstract simplex. Abstract simplices are given by sets (of totally ordered vertices)
 * and inherit from `Cell` so that the class has a `boundary` and a `dim` method.
 *
 * You should never have reason to use the constructor directly (...and if you do, you should make sure to give the
 * internal `SortedSet` yourself) - instead use the factory method in the companion object. In code this means that
 * instead of `new Simplex[T](a,b,c)` you would write `Simplex[T](a,b,c)`.
 *
 * @param vertices
 * Vertices of the simplex
 * @param ordering
 * Ordering of the vertex type
 * @tparam VertexT
 * Vertex type
 */
case class Simplex[VertexT : Ordering] private (vertices : SortedSet[VertexT]) {
  override def equals(obj: Any): Boolean = obj match {
    case other : Simplex[VertexT] => vertices == other.vertices
    case _ => super.equals(obj)
  }

  override def toString(): String =
    vertices.mkString(s"∆(", ",", ")")

  def union(other: Simplex[VertexT]) =
    new Simplex(vertices.union(other.vertices))
}

given [VertexT : Ordering] : OrderedCell[Simplex[VertexT]] with {
  given Ordering[Simplex[VertexT]] = simplexOrdering
  extension (t: Simplex[VertexT]) {
    override def boundary[CoefficientT](using fr: Fractional[CoefficientT]): Chain[Simplex[VertexT], CoefficientT] =
      if(t.dim <= 0) Chain()
      else Chain.from(
        t.vertices
          .to(Seq)
          .zipWithIndex
          .map((vtx,i) => Simplex.from(t.vertices.toSeq.patch(i,Seq.empty,1)))
          .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
      )
    override def dim: Int = t.vertices.size - 1
  }
  override def compare(x: Simplex[VertexT], y: Simplex[VertexT]): Int = simplexOrdering[VertexT].compare(x,y)
}

/** Simplex companion object with factory methods
 */
object Simplex {
  def apply[VertexT: Ordering](vertices: VertexT*) =
    new Simplex[VertexT](SortedSet.from(vertices))

  def empty[VertexT: Ordering]: Simplex[VertexT] =
    new Simplex[VertexT](SortedSet.empty)

  def from[VertexT: Ordering](source: IterableOnce[VertexT]): Simplex[VertexT] =
    new Simplex(SortedSet.from(source.iterator))
}

/** Convenience method for defining simplices
  *
  * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
  */
def ∆[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)
