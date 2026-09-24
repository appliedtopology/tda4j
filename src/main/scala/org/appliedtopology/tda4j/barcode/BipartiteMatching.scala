package org.appliedtopology.tda4j
package barcode

import scala.collection.mutable

/** Maximum-cardinality bipartite matching (Hopcroft-Karp). Used by [[BarcodeDistance.bottleneckDistance]] as the
  * feasibility check inside a binary search over candidate distances: "does a perfect matching exist using only
  * edges of cost <= threshold". Package-private -- [[BarcodeDistance]] is the public surface.
  */
private[barcode] object HopcroftKarp:

  /** `nLeft`/`nRight` left/right vertices, numbered `0 until nLeft`/`0 until nRight`. `adjacency(u)` lists `u`'s
    * right-neighbours. Returns `matchLeft` where `matchLeft(u)` is `u`'s matched right-vertex, or `-1` if `u` is
    * unmatched.
    */
  def maximumMatching(nLeft: Int, nRight: Int, adjacency: Int => Seq[Int]): Array[Int] =
    val matchLeft = Array.fill(nLeft)(-1)
    val matchRight = Array.fill(nRight)(-1)
    val dist = Array.fill(nLeft)(0)
    val Inf = Int.MaxValue

    // BFS layers the unmatched left vertices by distance to an unmatched right vertex along alternating paths.
    def bfs(): Boolean =
      val queue = mutable.Queue.empty[Int]
      for u <- 0 until nLeft do
        if matchLeft(u) == -1 then
          dist(u) = 0
          queue.enqueue(u)
        else dist(u) = Inf
      var foundAugmentingPath = false
      while queue.nonEmpty do
        val u = queue.dequeue()
        for v <- adjacency(u) do
          val w = matchRight(v)
          if w == -1 then foundAugmentingPath = true
          else if dist(w) == Inf then
            dist(w) = dist(u) + 1
            queue.enqueue(w)
      foundAugmentingPath

    // DFS greedily extends along BFS-shortest alternating paths, flipping matched/unmatched edges as it goes.
    def dfs(u: Int): Boolean =
      var found = false
      val it = adjacency(u).iterator
      while !found && it.hasNext do
        val v = it.next()
        val w = matchRight(v)
        if w == -1 || (dist(w) == dist(u) + 1 && dfs(w)) then
          matchLeft(u) = v
          matchRight(v) = u
          found = true
      if !found then dist(u) = Inf
      found

    while bfs() do
      for u <- 0 until nLeft do
        if matchLeft(u) == -1 then dfs(u)

    matchLeft

/** Minimum-cost perfect matching on a square cost matrix (the Hungarian / Kuhn-Munkres algorithm, O(n^3) via
  * shortest augmenting paths with vertex potentials). Used by [[BarcodeDistance.wassersteinDistance]]. All matrix
  * entries must be finite -- see `BarcodeDistance`'s essential/finite split for why the caller never has to hand
  * this an `Infinity` entry (that would poison the potential-update arithmetic with `Infinity - Infinity = NaN`).
  * Package-private -- [[BarcodeDistance]] is the public surface.
  */
private[barcode] object Hungarian:

  /** `cost` must be square (`n x n`), all entries finite. Returns `(assignment, totalCost)` where `assignment(j)`
    * is the row matched to column `j`, and `totalCost = sum_j cost(assignment(j))(j)`.
    *
    * Classic 1-indexed potential formulation (row/column `0` are sentinels): `u`/`v` are the row/column
    * potentials, `p(j)` is the row currently assigned to column `j` (`0` = none yet), `way(j)` records the
    * augmenting path taken to reach column `j` so the final augmentation can be replayed.
    */
  def minCostPerfectMatching(cost: Array[Array[Double]]): (Array[Int], Double) =
    val n = cost.length
    require(cost.forall(_.length == n), "Hungarian.minCostPerfectMatching requires a square cost matrix")
    require(cost.forall(_.forall(_.isFinite)), "Hungarian.minCostPerfectMatching requires all-finite entries")
    if n == 0 then return (Array.empty[Int], 0.0)

    val INF = Double.PositiveInfinity
    val u = Array.fill(n + 1)(0.0)
    val v = Array.fill(n + 1)(0.0)
    val p = Array.fill(n + 1)(0) // p(j) = 1-indexed row matched to column j; p(0) is a working sentinel
    val way = Array.fill(n + 1)(0)

    def a(i: Int, j: Int): Double = cost(i - 1)(j - 1) // 1-indexed accessor into the 0-indexed matrix

    for i <- 1 to n do
      p(0) = i
      var j0 = 0
      val minv = Array.fill(n + 1)(INF)
      val used = Array.fill(n + 1)(false)
      var loop = true
      while loop do
        used(j0) = true
        val i0 = p(j0)
        var delta = INF
        var j1 = -1
        for j <- 1 to n do
          if !used(j) then
            val cur = a(i0, j) - u(i0) - v(j)
            if cur < minv(j) then
              minv(j) = cur
              way(j) = j0
            if minv(j) < delta then
              delta = minv(j)
              j1 = j
        for j <- 0 to n do
          if used(j) then
            u(p(j)) += delta
            v(j) -= delta
          else
            minv(j) -= delta
        j0 = j1
        loop = p(j0) != 0
      // Replay the augmenting path found above, flipping matches column-by-column back to the root.
      var j0b = j0
      while j0b != 0 do
        val j1 = way(j0b)
        p(j0b) = p(j1)
        j0b = j1

    val assignment = Array.fill(n)(-1) // assignment(j) = 0-indexed row matched to 0-indexed column j
    for j <- 1 to n do assignment(j - 1) = p(j) - 1
    val totalCost = (0 until n).map(j => cost(assignment(j))(j)).sum
    (assignment, totalCost)
