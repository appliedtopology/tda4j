package org.appliedtopology.tda4j

import collection.mutable
import math.Ordering.Implicits.infixOrderingOps

class UnionFind[T](vertices: IterableOnce[T]) {
  case class UFSet(val label: T)
  val sets: mutable.Map[UFSet, UFSet] = mutable.Map.from(
    vertices.iterator.map(v => (UFSet(v), UFSet(v)))
  )
  def find(s: UFSet): UFSet =
    if (sets(s) == s) s
    else find(sets(s))
  def union(x: UFSet, y: UFSet): UFSet = {
    var xr = find(x)
    var yr = find(y)

    if (xr != yr) {
      sets(y) = x
    }
    xr
  }
}

/** This implementation of Kruskal's algorithm will return two iterators of vertex pairs: the first iterator is a
  * Minimal Spanning Tree in increasing weight order, while the second iterator gives all the non-included
  */

class Kruskal[T](elements: Seq[T], distance: (T, T) => Double, maxDistance: Double = Double.PositiveInfinity)(using
  orderingT: Ordering[T]
) {
  val unionFind: UnionFind[T] = UnionFind(elements)

  val sortedEdges: List[(Double, unionFind.UFSet, unionFind.UFSet)] =
    (for
      x <- unionFind.sets.keysIterator
      y <- unionFind.sets.keysIterator
      if orderingT.lt(x.label, y.label)
      if distance(x.label, y.label) < maxDistance
    yield (distance(x.label, y.label), x, y)).toList.sortWith { (l, r) =>
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

  val mstIterator: Iterator[(T, T)] = lrList._1.iterator
  val cyclesIterator: Iterator[(T, T)] = lrList._2.iterator

  def cycleToChain[CoefficientT: Fractional](edge: (T, T)): Chain[Simplex[T], CoefficientT] = {
    import unionFind.UFSet
    val (s, t) = edge
    val edgeChain =
      if (s < t) Chain(Simplex(s, t))
      else Chain(Simplex(t, s))
    val sPath = Seq
      .unfold(UFSet(s)) { v =>
        val next = unionFind.sets(v)
        if (next == v) None
        else Some(((v.label, next.label), next))
      }
      .map { (i, j) =>
        if (i < j) Chain[Simplex[T], CoefficientT](Simplex(i, j))
        else -Chain[Simplex[T], CoefficientT](Simplex(j, i))
      }
      .fold(summon[Chain[Simplex[T], CoefficientT] is RingModule].zero)(_ + _)
    val tPath = Seq
      .unfold(UFSet(t)) { v =>
        val next = unionFind.sets(v)
        if (next == v) None
        else Some(((v.label, next.label), next))
      }
      .map { (i, j) =>
        if (i < j) Chain[Simplex[T], CoefficientT](Simplex(i, j))
        else -Chain[Simplex[T], CoefficientT](Simplex(j, i))
      }
      .fold(summon[Chain[Simplex[T], CoefficientT] is RingModule].zero)(_ + _)

    edgeChain + sPath - tPath
  }
}

object Kruskal {
  def apply[T: Ordering](metricSpace: FiniteMetricSpace[T]): Kruskal[T] =
    new Kruskal(metricSpace.elements.toSeq, metricSpace.distance)
}

/*
def KruskalF[Self](metricSpace: FiniteMetricSpace[Self])(using orderingT: Ordering[Self]) = {
  val unionFind: UnionFind[Self] = UnionFind(metricSpace.elements)
  val sortedEdges: List[(Double, unionFind.UFSet, unionFind.UFSet)] =
    (for
      x <- unionFind.sets.keysIterator
      y <- unionFind.sets.keysIterator
      if orderingT.lt(x.label, y.label)
    yield (metricSpace.distance(x.label, y.label), x, y)).toList.sortWith { (l,r) =>
      l._1 < r._1
    }

    def process(dxy: (Double,unionFind.UFSet,unionFind.UFSet)): Either[(Self,Self),(Self,Self)] =
      if unionFind.find(dxy._2) != unionFind.find(dxy._3) then {
        unionFind.union(dxy._2, dxy._3)
        Left[(Self, Self), (Self, Self)]((dxy._2.label, dxy._3.label))
      } else {
        Right[(Self, Self), (Self, Self)]((dxy._2.label, dxy._3.label))
      }

    val lrList: (List[(Self,Self)],List[(Self,Self)]) = sortedEdges.partitionMap(process)
}
 */
