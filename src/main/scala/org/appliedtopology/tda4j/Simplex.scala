package org.appliedtopology.tda4j

import scala.collection.immutable.{SortedSet, TreeSet}
import scala.math.Ordering.IntOrdering

class Simplex(vertices: Int*) extends SortedSet[Int] {
  private val vertexSet = TreeSet[Int](vertices: _*)

  def boundary(): List[Simplex] =
    vertexSet.toList.map[Simplex](elem =>
      new Simplex((vertexSet - elem).toSeq: _*)
    )

  override def iterator: Iterator[Int] = vertexSet.iterator

  override def ordering: Ordering[Int] = vertexSet.ordering

  override def excl(elem: Int): Simplex = new Simplex(
    vertexSet.excl(elem).toSeq: _*
  )

  override def incl(elem: Int): Simplex = new Simplex(
    vertexSet.excl(elem).toSeq: _*
  )

  override def contains(elem: Int): Boolean = vertexSet.contains(elem)

  override def rangeImpl(from: Option[Int], until: Option[Int]): Simplex =
    new Simplex()

  override def iteratorFrom(start: Int): Iterator[Int] =
    vertexSet.iteratorFrom(start)
}
