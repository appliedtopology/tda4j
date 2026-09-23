package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import collection.mutable

class UnionFind[T](vertices: IterableOnce[T]):
  case class UFSet(label: T)
  val sets: mutable.Map[UFSet, UFSet] = mutable.Map.from(
    vertices.iterator.map(v => (UFSet(v), UFSet(v)))
  )
  private val rank: mutable.Map[UFSet, Int] = mutable.Map.from(sets.keys.map(_ -> 0))

  /** Path-compressing: every node on the way to the root is repointed directly at it, so repeated `find` calls into the
    * same subtree are O(1) after the first.
    */
  def find(s: UFSet): UFSet =
    val root = sets(s)
    if root == s then s
    else
      val r = find(root)
      sets(s) = r
      r

  /** Union by rank, root-to-root: the shallower tree's root is repointed at the deeper one's, keeping both bounded at
    * O(log n) depth. Does not preserve the original edge `(x, y)` as a parent link -- `pathsFrom`/`cycleToChain` below
    * reconstruct tree paths from `lrList._1` directly, not from these pointers.
    */
  def union(x: UFSet, y: UFSet): UFSet =
    val xr = find(x)
    val yr = find(y)
    if xr == yr then xr
    else
      val (child, newRoot) = if rank(xr) < rank(yr) then (xr, yr) else (yr, xr)
      sets(child) = newRoot
      if rank(xr) == rank(yr) then rank(newRoot) += 1
      newRoot

/** This implementation of Kruskal's algorithm will return two iterators of vertex pairs: the first iterator is a
  * Minimal Spanning Tree in increasing weight order, while the second iterator gives all the non-included
  */

class Kruskal[T](elements: Seq[T], distance: (T, T) => Double, maxDistance: Double = Double.PositiveInfinity)(using
  orderingT: Ordering[T]
):
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
    if unionFind.find(x) != unionFind.find(y) then
      unionFind.union(x, y)
      Left[(T, T), (T, T)]((x.label, y.label))
    else Right[(T, T), (T, T)]((x.label, y.label))
  }

  def mstIterator: Iterator[(T, T)] = lrList._1.iterator
  def cyclesIterator: Iterator[(T, T)] = lrList._2.iterator

  private lazy val adjacency: Map[T, List[(T, (T, T))]] =
    lrList._1.foldLeft(Map.empty[T, List[(T, (T, T))]].withDefaultValue(Nil)) { case (acc, e @ (a, b)) =>
      acc.updated(a, (b, e) :: acc(a)).updated(b, (a, e) :: acc(b))
    }

  /** The path-chain from `s` to every vertex reachable from it along Kruskal's own accepted tree edges
    * (`mstIterator`/`lrList._1`) -- NOT `unionFind`'s own internal pointers, which no longer represent actual tree
    * edges once a union links a root under a shorter tree rather than under the specific element that triggered it (see
    * `UnionFind.union`'s own doc). `boundary(pathsFrom(s)(v)) = Simplex(v) - Simplex(s)` for every `v` in the result.
    * Mirrors `CellularPersistenceInChunksContext.unionFindDim01`'s BFS derivation (`Homology.scala`), specialized to
    * `Simplex[T]`'s 1-skeleton.
    */
  private def pathsFrom[CoefficientT: Field](s: T): Map[T, Chain[Simplex[T], CoefficientT]] =
    given Ordering[Simplex[T]] = simplexOrdering[T](using orderingT)
    val fld = summon[CoefficientT is Field]
    val rm = summon[Chain[Simplex[T], CoefficientT] is RingModule { type R = CoefficientT }]
    val paths = mutable.Map(s -> rm.zero)
    val stack = mutable.Stack(s)
    while stack.nonEmpty do
      val u = stack.pop()
      adjacency(u).foreach { case (v, (a, b)) =>
        if !paths.contains(v) then
          val edgeCell = if orderingT.lt(a, b) then Simplex(a, b) else Simplex(b, a)
          val cv = edgeCell.boundary[CoefficientT].find(_._1 == Simplex(v)).get._2
          paths(v) = rm.plus(paths(u), rm.scale(fld.invert(cv), Chain(edgeCell)))
          stack.push(v)
      }
    paths.toMap

  /** The fundamental cycle of a non-tree edge `(s, t)` (as returned by `cyclesIterator`): the edge itself plus the tree
    * path connecting its endpoints, oriented so `boundary(cycleToChain(edge)) = 0`. `s`/`t` are guaranteed to lie in
    * the same component: `lrList`'s own `partitionMap` only routes an edge to `cyclesIterator` once `find` has
    * confirmed its endpoints already share a root.
    */
  def cycleToChain[CoefficientT: Field](edge: (T, T)): Chain[Simplex[T], CoefficientT] =
    given Ordering[Simplex[T]] = simplexOrdering[T](using orderingT)
    val rm = summon[Chain[Simplex[T], CoefficientT] is RingModule { type R = CoefficientT }]
    val (s, t) = edge
    val edgeCell = if orderingT.lt(s, t) then Simplex(s, t) else Simplex(t, s)
    val ct = edgeCell.boundary[CoefficientT].find(_._1 == Simplex(t)).get._2
    rm.minus(Chain[Simplex[T], CoefficientT](edgeCell), rm.scale(ct, pathsFrom[CoefficientT](s)(t)))

object Kruskal:
  def apply[T: Ordering](metricSpace: FiniteMetricSpace[T]): Kruskal[T] =
    new Kruskal(metricSpace.elements.toSeq, metricSpace.distance)
