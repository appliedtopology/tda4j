package org.appliedtopology.tda4j

import scala.collection.mutable

// Replaces a coefficient map per component that was copied on every merge: quadratic when one large component absorbs
// many small ones, as a bright object on a dark background does (`.claude/WORKLOG-fast-cubical-representatives.md`).
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
  * for the bars that are reported.
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

/** [[SignedUnionFind]] with every orientation a sign `±1`, kept in bytes: exact whenever every flip is `±1`, as in the
  * cubical dual, where a flip is a product of boundary coefficients `±1`. The field never enters; the caller maps a
  * sign to its field once, when it writes a representative. Integers map to any field by a ring homomorphism, so this
  * gives the same coefficients as the field arithmetic, and none of its boxing.
  */
private[tda4j] final class UnitSignedUnionFind(size: Int):
  private val parent: Array[Int] = Array.range(0, size)
  // Sign of i relative to parent(i), in the path-compressed tree.
  private val relative: Array[Byte] = Array.fill[Byte](size)(1)
  // The merge forest: children of each root, and the flip each child got when it was merged.
  private val firstChild: Array[Int] = Array.fill(size)(-1)
  private val nextSibling: Array[Int] = Array.fill(size)(-1)
  private val mergeFlip: Array[Byte] = Array.fill[Byte](size)(1)
  private var path: Array[Int] = new Array[Int](64)
  private var stackNodes: Array[Int] = new Array[Int](64)
  private var stackSigns: Array[Byte] = new Array[Byte](64)

  /** The root of `i`'s component. Compresses the path, composing signs so they stay relative to the root. */
  def find(i: Int): Int =
    var length = 0
    var root = i
    while parent(root) != root do
      if length == path.length then path = java.util.Arrays.copyOf(path, 2 * length)
      path(length) = root
      length += 1
      root = parent(root)
    // From the node nearest the root outwards: its parent is already relative to the root.
    var k = length - 1
    while k >= 0 do
      val node = path(k)
      val p = parent(node)
      if p != root then relative(node) = (relative(node) * relative(p)).toByte
      parent(node) = root
      k -= 1
    root

  /** The sign of `i` relative to the root of its component (`1` for the root itself). */
  def orientation(i: Int): Int =
    val root = find(i)
    if i == root then 1 else relative(i)

  /** Merges root `young` into root `old`, multiplying every sign in `young`'s component by `flip` (`±1`). */
  def union(young: Int, old: Int, flip: Int): Unit =
    require(parent(young) == young && parent(old) == old && young != old, "union takes two distinct roots")
    require(flip == 1 || flip == -1, s"a flip is a sign, got $flip")
    parent(young) = old
    relative(young) = flip.toByte
    mergeFlip(young) = flip.toByte
    nextSibling(young) = firstChild(old)
    firstChild(old) = young

  /** `f(member, sign relative to root)` for every element merged (directly or not) into `root`, `root` included, in the
    * order of `SignedUnionFind.members`. Exact for a root that is still a root, or was merged away.
    */
  def foreachMember(root: Int)(f: (Int, Int) => Unit): Unit =
    stackNodes(0) = root
    stackSigns(0) = 1
    var top = 1
    while top > 0 do
      top -= 1
      val node = stackNodes(top)
      val sign = stackSigns(top)
      var child = firstChild(node)
      while child != -1 do
        if top == stackNodes.length then
          stackNodes = java.util.Arrays.copyOf(stackNodes, 2 * top)
          stackSigns = java.util.Arrays.copyOf(stackSigns, 2 * top)
        stackNodes(top) = child
        stackSigns(top) = (sign * mergeFlip(child)).toByte
        top += 1
        child = nextSibling(child)
      f(node, sign)
