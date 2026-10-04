package org.appliedtopology.tda4j

import scala.collection.mutable

/** Union-find over `0 until size` in which every element carries an orientation, a unit of the field `C`, relative to
  * the root of its component. It is the bookkeeping behind the representatives of the dual union-find engines
  * ([[FastCubicalHomologyEngine]], [[FastAlphaHomologyEngine]]): an element is a top cell, a component a region of top
  * cells, and the region's coherently oriented sum is `sum of orientation(c) * c` over its members.
  *
  *   - `find` compresses paths and composes orientations along them, so `orientation(i)` is amortised near-constant.
  *   - `union(young, old, flip)` hangs root `young` under root `old`, multiplying the whole young component's
  *     orientations by `flip` (a unit, `±1` in practice). It never touches the members.
  *   - The merges also form a forest that path compression does not change: `members(root)` walks the subtree of a
  *     root, which is frozen once that root has been merged away, and returns every member with its orientation
  *     relative to that root.
  *
  * A merge therefore costs nearly constant time, and a representative costs time proportional to its region, paid only
  * for the bars that are reported. The engines used to keep a coefficient map per component and copy the surviving one
  * on every merge, which is quadratic when one large component absorbs many small ones -- the common case of a bright
  * object on a dark background (`.claude/WORKLOG-fast-cubical-representatives.md`).
  */
private[tda4j] final class SignedUnionFind[C](size: Int)(using fr: C is Field):
  private val parent: Array[Int] = Array.range(0, size)
  // Orientation of i relative to parent(i), in the path-compressed tree.
  private val relative: mutable.ArrayBuffer[C] = mutable.ArrayBuffer.fill(size)(fr.one)
  // The merge forest: children of each root, and the flip each child got when it was merged.
  private val firstChild: Array[Int] = Array.fill(size)(-1)
  private val nextSibling: Array[Int] = Array.fill(size)(-1)
  private val mergeFlip: mutable.ArrayBuffer[C] = mutable.ArrayBuffer.fill(size)(fr.one)
  private val path: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty

  /** The root of `i`'s component. Compresses the path, composing orientations so they stay relative to the root. */
  def find(i: Int): Int =
    path.clear()
    var root = i
    while parent(root) != root do
      path += root
      root = parent(root)
    // Walk from the node nearest the root outwards: its parent is already relative to the root.
    var k = path.size - 1
    while k >= 0 do
      val node = path(k)
      val p = parent(node)
      if p != root then relative(node) = fr.times(relative(node), relative(p))
      parent(node) = root
      k -= 1
    root

  /** The orientation of `i` relative to the root of its component (`one` for the root itself). */
  def orientation(i: Int): C =
    val root = find(i)
    if i == root then fr.one else relative(i)

  /** Merges root `young` into root `old`, multiplying every orientation in `young`'s component by `flip`. */
  def union(young: Int, old: Int, flip: C): Unit =
    require(parent(young) == young && parent(old) == old && young != old, "union takes two distinct roots")
    parent(young) = old
    relative(young) = flip
    mergeFlip(young) = flip
    nextSibling(young) = firstChild(old)
    firstChild(old) = young

  /** Every element merged (directly or not) into `root`, `root` included, with its orientation relative to `root`.
    * Exact for a root that is still a root, or was merged away (its subtree no longer changes).
    */
  def members(root: Int): Iterator[(Int, C)] =
    val stack = mutable.Stack((root, fr.one))
    Iterator.unfold(stack) { s =>
      if s.isEmpty then None
      else
        val (node, sign) = s.pop()
        var child = firstChild(node)
        while child != -1 do
          s.push((child, fr.times(sign, mergeFlip(child))))
          child = nextSibling(child)
        Some(((node, sign), s))
    }
