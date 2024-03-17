package org.appliedtopology.tda4j

import collection.mutable

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

def Kruskal[T](metricSpace: FiniteMetricSpace[T])(using orderingT: Ordering[T]): Iterator[(T, T)] = {
  val unionFind: UnionFind[T] = UnionFind(metricSpace.elements)
  val sortedEdges: List[(Double, unionFind.UFSet, unionFind.UFSet)] =
    (for
      x <- unionFind.sets.keysIterator
      y <- unionFind.sets.keysIterator
      if orderingT.lt(x.label,y.label)
    yield (metricSpace.distance(x.label, y.label), x, y)).toList.sortBy {
      _._1
    }
  for
    (d, x, y) <- sortedEdges.iterator
    if unionFind.find(x) != unionFind.find(y)
  yield {
    unionFind.union(x, y)
    (x.label, y.label)
  }
}
