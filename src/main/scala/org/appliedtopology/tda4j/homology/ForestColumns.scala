package org.appliedtopology.tda4j

import scala.collection.mutable

/** The columns of `ParallelPackedCohomology`'s coboundary matrices, found without listing the simplices: the leaves of
  * the maximal-facet forest that are not minimal children.
  *
  * The simplices filed under one edge `e` (its block, a step) are `e` together with a clique `W` of the lune graph of
  * `e`: the vertices joined to both ends of `e` by earlier edges, adjacent when the edge between them is earlier too.
  * Adding to `e ∪ W` a vertex `x` of the common neighbours of `W` in that graph, earlier in the vertex order than every
  * vertex of `W`, gives a child; the smallest such `x` is the minimal child, and a simplex with a child is the facet of
  * an apparent pair with it. A column of degree `d` is therefore a `d`-simplex with no child, which is not the minimal
  * child of its parent, and the search visits only the simplices with a child below degree `d`. A column is still
  * cleared by a pivot of the degree below that is not an apparent pair: that is checked where the column is reduced.
  *
  * The vertex order is the label order for `BlockOrder.SmallerIndexFirst` and the reverse for `LargerIndexFirst` (the
  * simplex that adds the smaller index is older, so the earlier vertex of the forest's order is the one with the larger
  * label). The adjacency of the earlier edges is a bit matrix that grows as the steps are taken in the order of the
  * edges, so the lune of an edge is the intersection of two rows, and every step is independent of the others: tasks
  * take ranges of edges and each builds the matrix of its first edge.
  */
// Restricted: a public top-level `ForestColumns` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object ForestColumns:
  /** Whether the columns of `space` can be found this way: one-word keys, the dense table of edge ranks (the bit matrix
    * has `n^2` bits, a quarter of a megabyte at 1,024 points and two at 4,096), and apparent pairs found from the
    * block's edge (Ripser's way is the other path's own check).
    */
  def applies(space: ParallelPackedCohomology.Space): Boolean =
    !space.wide && !space.sparseRanks && !space.ripserApparent && space.maxDim >= 1

  /** The keys of the columns of each degree `1 .. maxDim`, ascending (the filtration order, oldest first): index 0 is
    * unused. Apparent pairs and their minimal children are not among them, and the columns cleared by a pivot of the
    * degree below are.
    */
  def compute(
    space: ParallelPackedCohomology.Space,
    threads: Int,
    // Fewer edges than this are one task: each task builds the adjacency of its first edge, which costs one pass.
    splitAt: Int = 4096
  ): Array[Array[Long]] =
    val edges = space.edgeA.length
    val tasks = if threads <= 1 || edges < splitAt then 1 else math.min(edges, threads * 4)
    val parts = new Array[Array[Array[Long]]](tasks)
    val run = (t: Int) =>
      parts(t) = sweep(space, (t.toLong * edges / tasks).toInt, ((t + 1).toLong * edges / tasks).toInt)
    if tasks == 1 then run(0) else Parallel.tasks(tasks)(run)
    Array.tabulate(space.maxDim + 1) { d =>
      if d == 0 then Array.emptyLongArray
      else
        val merged = new Array[Long](parts.map(_(d).length).sum)
        var at = 0
        for part <- parts do
          System.arraycopy(part(d), 0, merged, at, part(d).length)
          at += part(d).length
        java.util.Arrays.sort(merged)
        merged
    }

  // The columns of the steps of the edges of rank `from` until `until`, by degree.
  private def sweep(space: ParallelPackedCohomology.Space, from: Int, until: Int): Array[Array[Long]] =
    import space.*
    val words = (n + 63) >>> 6
    val adj = new Array[Long](n * words)
    def join(r: Int): Unit =
      val (a, b) = (edgeA(r), edgeB(r))
      adj(a * words + (b >>> 6)) |= 1L << (b & 63)
      adj(b * words + (a >>> 6)) |= 1L << (a & 63)
    var q = 0
    while q < from do
      join(q)
      q += 1
    val out = Array.fill(maxDim + 1)(mutable.ArrayBuilder.ofLong())
    // The candidates for children of the node at depth t: the common neighbours, in the lune graph, of its residual
    // vertices that precede all of them.
    val sets = Array.fill(maxDim + 1)(new Array[Long](words))
    val residual = new Array[Int](maxDim + 1)
    val vs = new Array[Int](maxDim + 3)
    // The first vertex of `set` in the vertex order.
    def first(set: Array[Long]): Int =
      if smallerFirst then
        var i = 0
        while set(i) == 0L do i += 1
        (i << 6) + java.lang.Long.numberOfTrailingZeros(set(i))
      else
        var i = words - 1
        while set(i) == 0L do i -= 1
        (i << 6) + 63 - java.lang.Long.numberOfLeadingZeros(set(i))
    // `into = from & row` restricted to the vertices before `x` in the vertex order; whether it is non-empty.
    def meetBefore(from: Array[Long], row: Int, x: Int, into: Array[Long]): Boolean =
      var any = 0L
      val xi = x >>> 6
      val xb = x & 63
      var i = 0
      while i < words do
        val mask =
          if smallerFirst then if i < xi then -1L else if i == xi then (1L << xb) - 1L else 0L
          else if i > xi then -1L
          else if i == xi then if xb == 63 then 0L else -1L << (xb + 1)
          else 0L
        val w = from(i) & adj(row * words + i) & mask
        if into != null then into(i) = w
        any |= w
        i += 1
      any != 0L
    def emit(degree: Int, a: Int, b: Int, block: Int, t: Int, x: Int): Unit =
      var size = 0
      def add(v: Int): Unit =
        var j = size
        while j > 0 && vs(j - 1) > v do
          vs(j) = vs(j - 1)
          j -= 1
        vs(j) = v
        size += 1
      add(a); add(b); add(x)
      var j = 0
      while j < t do
        add(residual(j))
        j += 1
      out(degree) += key(block, indexOf(vs, size))
    // The node with `t` residual vertices, whose candidate set `sets(t)` is not empty.
    def node(t: Int, a: Int, b: Int, block: Int): Unit =
      val candidates = sets(t)
      val childDim = t + 2
      val x0 = first(candidates)
      if childDim == maxDim then
        // The children joined to the minimal child have a child of their own: only the others can be columns.
        var i = 0
        while i < words do
          var bits = candidates(i) & ~adj(x0 * words + i)
          if i == (x0 >>> 6) then bits &= ~(1L << (x0 & 63))
          while bits != 0L do
            val x = (i << 6) + java.lang.Long.numberOfTrailingZeros(bits)
            bits &= bits - 1
            if !meetBefore(candidates, x, x, null) then emit(childDim, a, b, block, t, x)
          i += 1
      else
        val next = sets(t + 1)
        var i = 0
        while i < words do
          var bits = candidates(i)
          while bits != 0L do
            val x = (i << 6) + java.lang.Long.numberOfTrailingZeros(bits)
            bits &= bits - 1
            if x != x0 then
              if meetBefore(candidates, x, x, next) then
                residual(t) = x
                node(t + 1, a, b, block)
              else emit(childDim, a, b, block, t, x)
          i += 1
    var r = from
    while r < until do
      val (a, b) = (edgeA(r), edgeB(r))
      val lune = sets(0)
      var any = 0L
      var i = 0
      while i < words do
        val w = adj(a * words + i) & adj(b * words + i)
        lune(i) = w
        any |= w
        i += 1
      if any == 0L then
        vs(0) = a
        vs(1) = b
        out(1) += key(r + 1, indexOf(vs, 2))
      else if maxDim >= 2 then node(0, a, b, r + 1)
      join(r)
      r += 1
    Array.tabulate(maxDim + 1)(d => if d == 0 then Array.emptyLongArray else out(d).result())
