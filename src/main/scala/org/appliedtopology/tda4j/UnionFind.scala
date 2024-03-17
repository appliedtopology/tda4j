package org.appliedtopology.tda4j

import collection.mutable
import scala.util.Right

class UnionFind[T](vertices: IterableOnce[T]) {
  case class UFSet(val label: T)
  val sets: mutable.Map[UFSet, UFSet] = mutable.Map.from(
    vertices.iterator.map(v => (UFSet(v), UFSet(v)))
  )
  def find(s: UFSet): UFSet = {
    if (sets(s) != s) {
      sets(s) = find(sets(s))
    }
    sets(s)
  }
  def union(x: UFSet, y: UFSet): UFSet = {
    var xr = find(x)
    var yr = find(y)

    if (xr != yr) {
      sets(yr) = xr
    }
    xr
  }
}

/** This implementation of Kruskal's algorithm will return two iterators of vertex pairs:
 * the first iterator is a Minimal Spanning Tree in increasing weight order, while
 * the second iterator gives all the non-included
 */
class Kruskal[T](metricSpace: FiniteMetricSpace[T])(using orderingT: Ordering[T]) {
  val unionFind: UnionFind[T] = UnionFind(metricSpace.elements)

  val sortedEdges: List[(Double, unionFind.UFSet, unionFind.UFSet)] =
    (for
      x <- unionFind.sets.keysIterator
      y <- unionFind.sets.keysIterator
      if orderingT.lt(x.label, y.label)
    yield (metricSpace.distance(x.label, y.label), x, y)).toList.sortWith { (l,r) =>
      l._1 < r._1
    }

  val lrList: (List[(T, T)], List[(T, T)]) = sortedEdges.partitionMap { (d, x, y) =>
    if unionFind.find(x) != unionFind.find(y) then {
      unionFind.union(x, y)
      Left[(T, T), (T, T)]((x.label, y.label))
    } else {
      Right[(T, T), (T, T)]((x.label, y.label))
    }
  }

  val mstIterator: Iterator[(T,T)] = lrList._1.iterator
  val cyclesIterator: Iterator[(T,T)] = lrList._2.iterator
}

/*
def KruskalF[T](metricSpace: FiniteMetricSpace[T])(using orderingT: Ordering[T]) = {
  val unionFind: UnionFind[T] = UnionFind(metricSpace.elements)
  val sortedEdges: List[(Double, unionFind.UFSet, unionFind.UFSet)] =
    (for
      x <- unionFind.sets.keysIterator
      y <- unionFind.sets.keysIterator
      if orderingT.lt(x.label, y.label)
    yield (metricSpace.distance(x.label, y.label), x, y)).toList.sortWith { (l,r) =>
      l._1 < r._1
    }
    
    def process(dxy: (Double,unionFind.UFSet,unionFind.UFSet)): Either[(T,T),(T,T)] =
      if unionFind.find(dxy._2) != unionFind.find(dxy._3) then {
        unionFind.union(dxy._2, dxy._3)
        Left[(T, T), (T, T)]((dxy._2.label, dxy._3.label))
      } else {
        Right[(T, T), (T, T)]((dxy._2.label, dxy._3.label))
      }

    val lrList: (List[(T,T)],List[(T,T)]) = sortedEdges.partitionMap(process)
}
*/