package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq

/** A vantage-point tree over `elements`: a static index for range and nearest-neighbour queries in any metric.
  *
  * Each inner node holds one element (its vantage point) and splits the rest by distance to it, the nearer half to one
  * child and the farther half to the other; the triangle inequality then lets a query skip a child without measuring
  * its elements. Nodes with at most `leafSize` elements are scanned directly.
  *
  * What a query returns does not depend on the tree's shape:
  *
  *   - `withinDistance(query, radius)` is every element `e` with `distance(query, e) <= radius`, the boundary included
  *     (the comparison is on the computed distance, so an element exactly at the radius is found), in the order of
  *     `elements`.
  *   - `nearest(query, k)` is the `k` elements nearest to `query`, nearest first, the first `k` of the stable sort of
  *     `elements` by distance: among elements at equal distance the one earlier in `elements` comes first. `k` larger
  *     than [[size]] returns all of them.
  *
  * Both equal a linear scan whenever `distance` is a metric. Pruning compares distances with a relative slack of
  * `1e-12`, so the rounding of a computed metric (Euclidean distances violate the triangle inequality in the last bits)
  * never hides an element. A `distance` that violates the triangle inequality by more than that (a correlation-derived
  * dissimilarity, say) may make a query miss elements; use [[BruteForce]] for those.
  *
  * The tree is built deterministically (a fixed pseudo-random choice of vantage points, a split at the median), is
  * balanced whatever the data, and handles coincident elements: it builds in `O(n log n)` distance evaluations, a query
  * costs `O(log n)` distance evaluations in low dimension. `distance` must be symmetric, non-negative and not `NaN`;
  * infinite distances are allowed.
  *
  * @param elements
  *   the indexed elements; positions in this sequence are the indices the `...Indices` queries return
  * @param distance
  *   a metric on the elements
  * @param leafSize
  *   the largest number of elements scanned without further splitting (at least 1)
  * @tparam V
  *   the element type, which is also the type of a query
  */
open class VantagePointTree[V](
  elements: Seq[V],
  val distance: (V, V) => Double,
  val leafSize: Int = VantagePointTree.DefaultLeafSize
):
  require(leafSize >= 1, s"leafSize must be at least 1, got $leafSize")

  private val items: IndexedSeq[V] = elements.toIndexedSeq
  private val layout: VantagePointTree.Layout = VantagePointTree.build(items, distance, leafSize)

  /** The number of indexed elements. */
  def size: Int = items.length

  /** The indices (positions in `elements`, ascending) of the elements at distance at most `radius` from `query`. A
    * negative or `NaN` radius finds nothing, an infinite one everything.
    */
  def indicesWithinDistance(query: V, radius: Double): IndexedSeq[Int] =
    if size == 0 || !(radius >= 0.0) then IndexedSeq.empty
    else
      val found = new VantagePointTree.IntList(8)
      collect(0, query, radius, found)
      ArraySeq.unsafeWrapArray(found.sortedArray())

  /** The elements at distance at most `radius` from `query`, in the order of `elements`. */
  def withinDistance(query: V, radius: Double): IndexedSeq[V] = indicesWithinDistance(query, radius).map(items)

  /** The indices of the `k` elements nearest to `query`, nearest first, ties by ascending index. Fewer than `k` when
    * the tree holds fewer elements. Throws `IllegalArgumentException` for a negative `k`.
    */
  def nearestIndices(query: V, k: Int): IndexedSeq[Int] =
    require(k >= 0, s"k must not be negative, got $k")
    val wanted = math.min(k, size)
    if wanted == 0 then IndexedSeq.empty
    else
      val best = new VantagePointTree.Nearest(wanted)
      search(0, query, best)
      ArraySeq.unsafeWrapArray(best.sortedIndices())

  /** The `k` elements nearest to `query`, nearest first, ties in the order of `elements`. */
  def nearest(query: V, k: Int): IndexedSeq[V] = nearestIndices(query, k).map(items)

  private def measure(query: V, slot: Int): Double =
    val d = distance(query, items(layout.order(slot)))
    if d.isNaN then throw new IllegalArgumentException("a distance in a VantagePointTree query was NaN")
    d

  private def collect(node: Int, query: V, radius: Double, found: VantagePointTree.IntList): Unit =
    val lo = layout.lo(node)
    val hi = layout.hi(node)
    if layout.mid(node) < 0 then
      var slot = lo
      while slot < hi do
        if measure(query, slot) <= radius then found.add(layout.order(slot))
        slot += 1
    else
      val d = measure(query, lo)
      if d <= radius then found.add(layout.order(lo))
      val threshold = layout.threshold(node)
      val slack = VantagePointTree.RelativeSlack * (d + radius + threshold)
      // every element of the nearer child is at distance <= threshold from the vantage point, of the farther one >=
      if !(d - radius > threshold + slack) then collect(node + 1, query, radius, found)
      if !(d + radius < threshold - slack) then collect(layout.farther(node), query, radius, found)

  private def search(node: Int, query: V, best: VantagePointTree.Nearest): Unit =
    val lo = layout.lo(node)
    val hi = layout.hi(node)
    if layout.mid(node) < 0 then
      var slot = lo
      while slot < hi do
        best.offer(measure(query, slot), layout.order(slot))
        slot += 1
    else
      val d = measure(query, lo)
      best.offer(d, layout.order(lo))
      val threshold = layout.threshold(node)
      val nearerFirst = d <= threshold
      val nearerNode = node + 1
      val fartherNode = layout.farther(node)
      // the bound is read when the child is about to be visited: the first child may have tightened it
      def visitCloser(): Unit =
        val tau = best.bound
        if !(d - tau > threshold + VantagePointTree.RelativeSlack * (d + tau + threshold)) then
          search(nearerNode, query, best)
      def visitFarther(): Unit =
        val tau = best.bound
        if !(threshold - d > tau + VantagePointTree.RelativeSlack * (d + tau + threshold)) then
          search(fartherNode, query, best)
      if nearerFirst then
        visitCloser()
        visitFarther()
      else
        visitFarther()
        visitCloser()

object VantagePointTree:
  /** The default `leafSize`. */
  val DefaultLeafSize: Int = 16

  // Plain private: the arrays of the built tree and the construction scratch space are the algorithm's own.
  private val RelativeSlack: Double = 1e-12

  /** The built tree as arrays. Nodes are numbered in preorder: a node's nearer child is the next node, its farther
    * child is `farther(node)`. A node covers the slots `lo until hi` of `order`; an inner node's vantage point is the
    * element at slot `lo`, its nearer child covers `lo + 1 until mid` and its farther child `mid until hi`. A leaf has
    * `mid < 0`.
    */
  private final class Layout(
    val order: Array[Int],
    val lo: Array[Int],
    val hi: Array[Int],
    val mid: Array[Int],
    val farther: Array[Int],
    val threshold: Array[Double]
  )

  /** A growable `Int` array. */
  private final class IntList(initial: Int):
    private var data = new Array[Int](math.max(initial, 4))
    private var count = 0
    def add(x: Int): Unit =
      if count == data.length then data = java.util.Arrays.copyOf(data, count * 2)
      data(count) = x
      count += 1
    def sortedArray(): Array[Int] =
      val result = java.util.Arrays.copyOf(data, count)
      java.util.Arrays.sort(result)
      result

  /** The `capacity` best `(distance, index)` pairs seen so far, as a max-heap on that pair: the root is the worst kept
    * candidate, `bound` its distance (infinite while the heap is not full).
    */
  private final class Nearest(capacity: Int):
    private val distances = new Array[Double](capacity)
    private val indices = new Array[Int](capacity)
    private var count = 0

    def bound: Double = if count < capacity then Double.PositiveInfinity else distances(0)

    private def worse(d1: Double, i1: Int, d2: Double, i2: Int): Boolean = d1 > d2 || (d1 == d2 && i1 > i2)

    private def swap(a: Int, b: Int): Unit =
      val d = distances(a); distances(a) = distances(b); distances(b) = d
      val i = indices(a); indices(a) = indices(b); indices(b) = i

    private def siftUp(from: Int): Unit =
      var child = from
      while child > 0 && worse(distances(child), indices(child), distances((child - 1) / 2), indices((child - 1) / 2))
      do
        swap(child, (child - 1) / 2)
        child = (child - 1) / 2

    private def siftDown(from: Int): Unit =
      var parent = from
      var done = false
      while !done do
        val left = 2 * parent + 1
        val right = left + 1
        var top = parent
        if left < count && worse(distances(left), indices(left), distances(top), indices(top)) then top = left
        if right < count && worse(distances(right), indices(right), distances(top), indices(top)) then top = right
        if top == parent then done = true
        else
          swap(parent, top)
          parent = top

    def offer(d: Double, index: Int): Unit =
      if count < capacity then
        distances(count) = d
        indices(count) = index
        count += 1
        siftUp(count - 1)
      else if worse(distances(0), indices(0), d, index) then
        distances(0) = d
        indices(0) = index
        siftDown(0)

    /** The kept indices, best first. */
    def sortedIndices(): Array[Int] =
      val result = new Array[Int](count)
      var at = count - 1
      while count > 0 do
        result(at) = indices(0)
        at -= 1
        count -= 1
        distances(0) = distances(count)
        indices(0) = indices(count)
        siftDown(0)
      result

  private def build[V](items: IndexedSeq[V], distance: (V, V) => Double, leafSize: Int): Layout =
    val n = items.length
    val order = Array.range(0, n)
    val scratch = new Array[Double](n) // scratch(slot): distance from the current vantage point
    var capacity = 4 * (n / leafSize + 1) + 8
    var lo = new Array[Int](capacity)
    var hi = new Array[Int](capacity)
    var mid = new Array[Int](capacity)
    var farther = new Array[Int](capacity)
    var threshold = new Array[Double](capacity)
    var nodes = 0
    // xorshift64: fixed seed, so the same elements always give the same tree
    var state = 0x9e3779b97f4a7c15L
    def nextBelow(bound: Int): Int =
      state ^= state << 13
      state ^= state >>> 7
      state ^= state << 17
      ((state >>> 1) % bound).toInt

    def newNode(): Int =
      if nodes == capacity then
        capacity *= 2
        lo = java.util.Arrays.copyOf(lo, capacity)
        hi = java.util.Arrays.copyOf(hi, capacity)
        mid = java.util.Arrays.copyOf(mid, capacity)
        farther = java.util.Arrays.copyOf(farther, capacity)
        threshold = java.util.Arrays.copyOf(threshold, capacity)
      nodes += 1
      nodes - 1

    def swapSlots(a: Int, b: Int): Unit =
      val o = order(a); order(a) = order(b); order(b) = o
      val d = scratch(a); scratch(a) = scratch(b); scratch(b) = d

    // Rearranges slots `from until until` so that slot `k` holds the element of rank `k - from` by `scratch`, everything
    // before it is not larger and everything after it not smaller (Wirth's selection, median-of-three pivot).
    def select(from: Int, until: Int, k: Int): Unit =
      var left = from
      var right = until - 1
      while left < right do
        val a = scratch(left)
        val b = scratch(k)
        val c = scratch(right)
        val pivot =
          if a < b then if b < c then b else if a < c then c else a else if a < c then a else if b < c then c else b
        var i = left
        var j = right
        while i <= j do
          while scratch(i) < pivot do i += 1
          while scratch(j) > pivot do j -= 1
          if i <= j then
            swapSlots(i, j)
            i += 1
            j -= 1
        if j < k then left = i
        if k < i then right = j

    def make(from: Int, until: Int): Int =
      val node = newNode()
      lo(node) = from
      hi(node) = until
      if until - from <= leafSize then
        mid(node) = -1
        farther(node) = -1
        threshold(node) = 0.0
      else
        swapSlots(from, from + nextBelow(until - from)) // the vantage point goes to the first slot
        val vantage = items(order(from))
        var slot = from + 1
        while slot < until do
          val d = distance(vantage, items(order(slot)))
          if d.isNaN then throw new IllegalArgumentException("a distance in a VantagePointTree build was NaN")
          scratch(slot) = d
          slot += 1
        val rest = until - from - 1
        val nearerCount = (rest + 1) / 2
        val nearerLast = from + nearerCount // the slot holding the largest distance of the nearer half
        select(from + 1, until, nearerLast)
        mid(node) = nearerLast + 1
        threshold(node) = scratch(nearerLast)
        make(from + 1, nearerLast + 1)
        // a separate val: building the child may reallocate `farther`, and the store must go to the current array
        val fartherNode = make(nearerLast + 1, until)
        farther(node) = fartherNode
      node

    make(0, n)
    new Layout(order, lo, hi, mid, farther, threshold)
end VantagePointTree
