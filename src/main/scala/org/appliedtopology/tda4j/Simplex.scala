package org.appliedtopology.tda4j

import scala.collection.{mutable, SortedIterableFactory, SortedSetFactoryDefaults, StrictOptimizedSortedSetOps}
import scala.collection.immutable.{Set, SortedMap, SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.IntOrdering
import scala.math.Ordering.Double.IeeeOrdering
import math.Ordering.Implicits.sortedSetOrdering

/** Class representing an abstract simplex. Abstract simplices are given by sets (of totally ordered vertices)
 * and inherit from `Cell` so that the class has a `boundary` and a `dim` method.
 *
 * You should never have reason to use the constructor directly (...and if you do, you should make sure to give the
 * internal `SortedSet` yourself) - instead use the factory method in the companion object. In code this means that
 * instead of `new Simplex[Self](a,b,c)` you would write `Simplex[Self](a,b,c)`.
 *
 * @param vertices
 * Vertices of the simplex
 * @param ordering
 * Ordering of the vertex type
 * @tparam VertexT
 * Vertex type
 */
case class Simplex[VertexT : Ordering] private[tda4j] (vertices : SortedSet[VertexT]) {
  override def toString(): String =
    vertices.mkString(s"∆(", ",", ")")

  def union(other: Simplex[VertexT]) =
    new Simplex(vertices.union(other.vertices))
}

def simplexOrdering[VertexT : Ordering as vtxOrdering]: Ordering[Simplex[VertexT]] =
  Ordering.by{(spx: Simplex[VertexT]) => spx.vertices}(sortedSetOrdering[SortedSet, VertexT](vtxOrdering))

given [VertexT : Ordering] => Ordering[Simplex[VertexT]] = simplexOrdering

  //  Ordering.by{(spx: Simplex[VertexT]) => spx.vertices}(sortedSetOrdering[SortedSet, VertexT](vtxOrdering))


/** Simplex companion object with factory methods
 */
object Simplex {
  def apply[VertexT: Ordering](vertices: VertexT*) =
    new Simplex[VertexT](SortedSet.from(vertices))

  def empty[VertexT: Ordering]: Simplex[VertexT] =
    new Simplex[VertexT](SortedSet.empty)

  def from[VertexT: Ordering](source: IterableOnce[VertexT]): Simplex[VertexT] =
    new Simplex(SortedSet.from(source.iterator))

  given [VertexT: Ordering] => Simplex[VertexT] is OrderedCell:
    given Ordering[Simplex[VertexT]] = simplexOrdering

    extension (t: Simplex[VertexT]) {
      def boundary[CoefficientT](using fr: Fractional[CoefficientT]): Chain[Simplex[VertexT], CoefficientT] =
        if (t.dim <= 0) Chain()
        else Chain.from(
          t.vertices
            .to(Seq)
            .zipWithIndex
            .map((vtx, i) => Simplex.from(t.vertices.toSeq.patch(i, Seq.empty, 1)))
            .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
        )
      def dim: Int = t.vertices.size - 1
    }

    def compare(x: Simplex[VertexT], y: Simplex[VertexT]): Int = simplexOrdering[VertexT].compare(x, y)
}

/** Convenience method for defining simplices
  *
  * The character ∆ is typed as Alt+J on Mac GB layout, and has unicode code 0x0394.
  */
def ∆[T: Ordering](ts: T*): Simplex[T] = Simplex.from(ts)
