package org.appliedtopology.tda4j

/** The components of a graph as its edges arrive in filtration order, by union-find, with the merge history kept: the
  * elder rule for degree 0 of an engine that reduces the other degrees. When an edge joins two components the caller
  * says which root is older; the younger root dies, paired with that edge, and its component at that moment (its root
  * and everything merged into it before) is the support of the bar's cocycle.
  *
  * `find` compresses paths; `into` and `diedAt` are written once, when a root dies, so the history stays a forest whose
  * trees are the components: `members(v, before)` walks the part of `v`'s tree that merged before position `before`.
  */
// Restricted (reason 3 of the openness rules): a public top-level `MergeForest` would join every user's wildcard import.
private[tda4j] final class MergeForest(n: Int):
  private val up = Array.range(0, n)
  private val into = Array.fill(n)(-1)
  private val at = Array.fill(n)(Int.MaxValue)
  // The children of each vertex (the roots that died into it), as compressed rows, built by `seal`.
  private var firstChild: Array[Int] = null
  private var children: Array[Int] = null

  /** The root of `x`'s component. */
  def find(x: Int): Int =
    var v = x
    while up(v) != v do
      up(v) = up(up(v))
      v = up(v)
    v

  /** Merges root `dead` into root `alive` at `position` (the rank of the edge that joins them). */
  def kill(dead: Int, alive: Int, position: Int): Unit =
    up(dead) = alive
    into(dead) = alive
    at(dead) = position

  /** The position of the merge that killed `v`, `Int.MaxValue` while `v` is a root. */
  def diedAt(v: Int): Int = at(v)

  /** Ends the merging; `members` may be called, from any number of threads, from here on. */
  def seal(): Unit =
    val start = new Array[Int](n + 1)
    var v = 0
    while v < n do
      if into(v) >= 0 then start(into(v) + 1) += 1
      v += 1
    v = 0
    while v < n do
      start(v + 1) += start(v)
      v += 1
    val fill = java.util.Arrays.copyOf(start, n)
    val list = new Array[Int](start(n))
    v = 0
    while v < n do
      if into(v) >= 0 then
        list(fill(into(v))) = v
        fill(into(v)) += 1
      v += 1
    children = list
    firstChild = start

  /** The vertices of `v`'s component just before position `before`: `v` and every root that died into it, and into
    * those, at an earlier position (`Int.MaxValue` for the final component). Breadth first, in one growing array: not a
    * `java.util.ArrayDeque` used as a stack, whose Scala.js version repeats elements once it grows.
    */
  def members(v: Int, before: Int = Int.MaxValue): Array[Int] =
    require(firstChild != null, "MergeForest: members before seal()")
    var out = new Array[Int](16)
    var size = 1
    out(0) = v
    var next = 0
    while next < size do
      val x = out(next)
      next += 1
      var i = firstChild(x)
      while i < firstChild(x + 1) do
        val child = children(i)
        if at(child) < before then
          if size == out.length then out = java.util.Arrays.copyOf(out, 2 * size)
          out(size) = child
          size += 1
        i += 1
    java.util.Arrays.copyOf(out, size)
