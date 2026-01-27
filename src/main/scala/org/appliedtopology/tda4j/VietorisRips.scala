package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue

import scala.collection.immutable.{LazyList, SortedSet}
import scala.math.Ordering.Implicits.*
import scalax.collection.{edge, mutable as gmutable, Graph}
import scalax.collection.edge.Implicits.*
import scalax.collection.edge.WUnDiEdge

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.util.Sorting
import scala.util.control.*
import scala.util.chaining.*

case class SimplexEdge(simplex : Simplex[Int], edge: Simplex[Int], diameter: Double)
object SimplexEdge:
  def from(simplex : Simplex[Int])(using metricSpace : FiniteMetricSpace[Int]) = {
    val edges = for
      i <- simplex.toSortedSet
      j <- simplex.toSortedSet
      if(i > j)
    yield
      (metricSpace.distance(i,j),i,j)
    val maxedge = edges.max
    SimplexEdge(simplex, Simplex(maxedge._2, maxedge._3), maxedge._1)
  }

case class TopCofacetEnumerator(val simplex : SimplexEdge,
                           val neighbors : SortedSet[Int])
                          (using val metricSpace: FiniteMetricSpace[Int]) {
  val neighbor_it : collection.BufferedIterator[Int] = neighbors.iterator.buffered

  val case2 : Boolean = simplex.edge.last == simplex.simplex.last
  val case3 : Boolean = case2 && (simplex.edge.firstKey == simplex.simplex.takeRight(2).firstKey)

  def isValid(head : Option[Int]): Boolean = head match {
    case None => false
    case Some(w) => {
      val distances = simplex.simplex.toSeq.map((v) => metricSpace.distance(v,w))
      if(distances.exists((d) => d > simplex.diameter)) false
      else {
        val Seq(s2,s1,s0) = simplex.simplex
          .takeRight(3)
          .toSeq
          .reverse
          .padTo(3,metricSpace.elements.min-1)
        if(w > s2) true
        else if (case2 && (w > s1) && (distances.last <= simplex.diameter) && (distances.dropRight(1).forall((d) => d < simplex.diameter))) true
        else if (case3 && (w > s0) && (distances.forall((d) => d < simplex.diameter))) true
        else false
      }
    }
  }

  def nonValid(head : Option[Int]): Boolean = !isValid(head)

  def hasNext(): Boolean = {
    while(nonValid(neighbor_it.headOption) && neighbor_it.hasNext)
      neighbor_it.next()
    neighbor_it.hasNext
  }
  def next(): Int = neighbor_it.next()
}

case class RecursiveStackSimplexEnumerator(val metricSpace: FiniteMetricSpace[Int],
                                      val targetDimension : Int = 2)
                                     (val query : SpatialQuery[Int] = BruteForce(metricSpace))
  extends Iterator[Simplex[Int]] {
  given FiniteMetricSpace[Int] = metricSpace
  val filtrationValue = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
  lazy val edges = (
    for
      i <- metricSpace.elements
      j <- metricSpace.elements
      if(i < j)
    yield
      Simplex(i,j)
  ).toSeq.sorted(using Ordering.by(filtrationValue).orElse(simplexOrdering))

  val enumeratorStack : mutable.Stack[TopCofacetEnumerator] = mutable.Stack.empty
  lazy val edge_it : Iterator[Simplex[Int]] = edges.iterator

  def nextEdge(): Unit =
    if(enumeratorStack.isEmpty) {
      if(edge_it.hasNext) {
        val edge = edge_it.next()
        val Simplex(i,j) = edge : @unchecked
        val simplexedge : SimplexEdge = SimplexEdge.from(edge)
        val neighbors : SortedSet[Int] =
          ((query.neighbors(i,simplexedge.diameter)-i) &
            (query.neighbors(j,simplexedge.diameter)-j)).to(SortedSet)
        enumeratorStack.push(TopCofacetEnumerator(simplexedge, neighbors))
      }
    }

  @tailrec
  final def hasNext(): Boolean = {
    while(enumeratorStack.size < targetDimension) {
      if(enumeratorStack.isEmpty) {
        nextEdge()
        if(enumeratorStack.isEmpty) return false
      } else {
        if (enumeratorStack.top.hasNext()) {
          // build up the next iterator up top
          val w = enumeratorStack.top.next()
          val simplexedge = enumeratorStack.top.simplex.copy(simplex = enumeratorStack.top.simplex.simplex + w)
          val neighbors = enumeratorStack.top.neighbors & (query.neighbors(w, enumeratorStack.top.simplex.diameter)-w)
          enumeratorStack.push(TopCofacetEnumerator(simplexedge, neighbors))
        } else {
          enumeratorStack.pop()
        }
      }
    }
    if(enumeratorStack.top.hasNext()) return true
    else {
      enumeratorStack.pop()
      return hasNext()
    }
    return false
  }

  def next(): Simplex[Int] = enumeratorStack.top.simplex.simplex + enumeratorStack.top.next()
}

class RecursiveStackVietorisRipsSimplexStream(val metricSpace: FiniteMetricSpace[Int])
  extends StratifiedSimplexStream[Int,Double] with DoubleFiltration[Simplex[Int]] {
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  override def filtrationOrdering: Ordering[Simplex[Int]] =
    Ordering.by(filtrationValue).orElse(simplexOrdering[Int])

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 => metricSpace.elements.iterator.map((v) => Simplex(v))
    case 1 => RecursiveStackSimplexEnumerator(metricSpace, 1)().edges.iterator
    case d => RecursiveStackSimplexEnumerator(metricSpace, d-1)()
  }
}
