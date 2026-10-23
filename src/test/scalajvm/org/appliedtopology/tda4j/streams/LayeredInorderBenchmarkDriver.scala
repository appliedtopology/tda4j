package org.appliedtopology.tda4j

import LayeredInorderSimplexStream.{sortIndices, Candidates, DoubleBuffer, Generator, IntBuffer, Layer}

import java.lang.management.ManagementFactory
import scala.util.Random

/** Test-scope driver: the layered in-order Vietoris-Rips generation against generating each dimension Ripser's way (the
  * cofacets that add a vertex above the top one, within the threshold) and sorting it, with the same primitive arrays
  * and the same bitset candidate search, so that the only difference is sorting versus not. Optionally also times the
  * library's existing streams, which use boxed `Simplex` values.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.LayeredInorderBenchmarkDriver <n> <ambientDim> <topSimplexDim> <threshold> <method> [trials] [seed]
  * }}}
  *
  * `method` is `bitsets`, `lists`, `sort`, `countsort` (sorted by a counting sort on the diameter's rank among the edge
  * lengths, then colex within ties), `layered` (the stream, boxed), `enumerating` or `ripser`, one per JVM, or `check`
  * (the layered and sorted dimensions are identical). `threshold` is a number or `mer` (the minimum enclosing radius).
  * Prints one line per dimension: size, median milliseconds, bytes allocated, bytes the dimension's arrays keep.
  */
object LayeredInorderBenchmarkDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val ambient = args(1).toInt
    val top = args(2).toInt
    val method = args(4)
    val trials = if args.length > 5 then args(5).toInt else 5
    val seed = if args.length > 6 then args(6).toLong else 42L
    val rng = Random(seed)
    val pts = Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))
    val metricSpace = EuclideanMetricSpace(pts)
    val threshold = if args(3) == "mer" then metricSpace.minimumEnclosingRadius else args(3).toDouble
    val dist = new Array[Double](n * n)
    for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
    val distance: (Int, Int) => Double = (i, j) => dist(i * n + j)

    val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    def allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().getId)
    val sortTimes = scala.collection.mutable.ArrayBuffer.empty[Map[Int, Long]]

    // One trial: (size, nanoseconds, bytes allocated, bytes kept) per dimension 1..top.
    def trial(): Vector[(Int, Long, Long, Long)] = method match
      case "bitsets" | "lists" =>
        val c = if method == "bitsets" then Candidates.Bitsets else Candidates.NeighbourLists
        var t0 = System.nanoTime()
        var a0 = allocated()
        val g = Generator(n, distance, threshold, c)
        var layer = g.edges
        val out = Vector.newBuilder[(Int, Long, Long, Long)]
        out += ((layer.size, System.nanoTime() - t0, allocated() - a0, kept(layer)))
        for _ <- 2 to top do
          t0 = System.nanoTime()
          a0 = allocated()
          layer = g.cofacets(layer)
          out += ((layer.size, System.nanoTime() - t0, allocated() - a0, kept(layer)))
        if g.tieRunsSorted > 0 then println(s"  tie runs sorted: ${g.tieRunsSorted}")
        out.result()
      case "sort" | "countsort" =>
        var t0 = System.nanoTime()
        var a0 = allocated()
        val s = SortBaseline(n, distance, threshold, counting = method == "countsort")
        var layer = s.edges
        val out = Vector.newBuilder[(Int, Long, Long, Long)]
        out += ((layer.size, System.nanoTime() - t0, allocated() - a0, layer.kept))
        for _ <- 2 to top do
          t0 = System.nanoTime()
          a0 = allocated()
          layer = s.cofacets(layer)
          out += ((layer.size, System.nanoTime() - t0, allocated() - a0, layer.kept))
        sortTimes += s.sortNanos.toMap
        out.result()
      case "enumerating" | "ripser" | "layered" =>
        val stream = method match
          case "layered"     => LayeredInorderSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
          case "enumerating" => EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
          case _             => RipserCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
        stream.iterateDimension(0).size
        (1 to top).toVector.map { d =>
          val t0 = System.nanoTime()
          val a0 = allocated()
          val size = stream.iterateDimension(d).foldLeft(0)((count, s) => count + s.size / s.size)
          (size, System.nanoTime() - t0, allocated() - a0, -1L)
        }

    def kept(layer: Layer): Long = 4L * layer.vertices.length + 4L * layer.root.length

    // The block of simplices whose first longest edge is ab (length alpha) is ab joined with the clique complex of the
    // lens graph (the vertices closer than alpha to a and to b, joined when closer than alpha), so in general position
    // the H1 classes born at alpha number 1 if the lens is empty (and ab joins no two components), 0 otherwise, and
    // H1 deaths plus H2 births at alpha number (components of the lens graph) - 1. Compared with the Ripser engine.
    if method == "lens" then
      var emptyLens = 0
      var componentSum = 0
      def root(parent: Array[Int], x: Int): Int = if parent(x) == x then x else root(parent, parent(x))
      val parent = Array.range(0, n)
      var components = n
      val edgeOrder = (for b <- 0 until n; a <- 0 until b if dist(a * n + b) <= threshold yield (a, b))
        .sortBy((a, b) => dist(a * n + b))
      for (a, b) <- edgeOrder do
        val alpha = dist(a * n + b)
        val lens = (0 until n).filter(w => dist(w * n + a) < alpha && dist(w * n + b) < alpha)
        if lens.isEmpty then emptyLens += 1
        else
          val lensParent = Array.range(0, n)
          var c = lens.size
          for x <- lens; y <- lens if x < y && dist(x * n + y) < alpha && root(lensParent, x) != root(lensParent, y) do
            lensParent(root(lensParent, x)) = root(lensParent, y)
            c -= 1
          componentSum += c - 1
        if root(parent, a) != root(parent, b) then
          parent(root(parent, a)) = root(parent, b)
          components -= 1
      val diagram = Persistence(pts, maxDimension = 2, maxFiltrationValue = threshold)
      val h1 = diagram.dim(1).bars.size
      val h2 = diagram.dim(2).bars.size
      println(
        s"n=$n threshold=$threshold: empty lenses - (n - components) = ${emptyLens - (n - components)}, H1 bars = $h1"
      )
      println(
        s"  sum of (lens graph components - 1) = $componentSum, H1 deaths + H2 bars = ${diagram.dim(1).bars.count(!_.death.isPosInfinity) + h2}"
      )
      return
    // Reduction by edge blocks (F_2, plain homology, no clearing). Cells are ordered by value, then by block (first
    // longest edge, ascending), then dimension, then layer order: a filtration in which every block's faces outside it
    // come earlier. Phase 1 reduces each block's columns using only that block's columns, while the pivot stays in the
    // block (the blocks are independent); a column whose pivot leaves the block is deferred to phase 2, which reduces
    // the deferred columns in order against everything. Compared with one sequential reduction of the whole matrix.
    if method == "blocks" then
      val g = Generator(n, distance, threshold)
      val layers = Iterator.iterate(g.edges)(g.cofacets).take(top).toVector
      case class Cell(dim: Int, vertices: Array[Int], value: Double, a: Int, b: Int, block: Int, pos: Int)
      val vertexCells = (0 until n).map(v => Cell(0, Array(v), 0.0, -1, v, -1 - v, v))
      val higher = for
        layer <- layers
        i <- 0 until layer.size
      yield
        val r = layer.root(i)
        val w = layer.dim + 1
        Cell(layer.dim, layer.vertices.slice(i * w, (i + 1) * w), g.edgeLength(r), g.edgeA(r), g.edgeB(r), r, i)
      val cells = (vertexCells ++ higher).sortBy(c => (c.value, c.a, c.b, c.dim, c.pos)).toArray
      val index = cells.indices.map(i => cells(i).vertices.toSeq -> i).toMap
      val boundary = cells.map(c =>
        if c.dim == 0 then Array.empty[Int]
        else c.vertices.indices.map(j => index(c.vertices.patch(j, Nil, 1).toSeq)).sorted.toArray
      )
      def add(x: Array[Int], y: Array[Int]): Array[Int] =
        val out = IntBuffer()
        var i = 0
        var j = 0
        while i < x.length || j < y.length do
          if j == y.length || (i < x.length && x(i) < y(j)) then
            out += x(i); i += 1
          else if i == x.length || y(j) < x(i) then
            out += y(j); j += 1
          else
            i += 1; j += 1
        out.result
      val N = cells.length
      // One sequential reduction.
      val fullCol = boundary.clone()
      val fullPivot = Array.fill(N)(-1)
      var fullAdds = 0L
      for j <- 0 until N do
        while fullCol(j).nonEmpty && fullPivot(fullCol(j).last) >= 0 do
          fullCol(j) = add(fullCol(j), fullCol(fullPivot(fullCol(j).last)))
          fullAdds += 1
        if fullCol(j).nonEmpty then fullPivot(fullCol(j).last) = j
      // Phase 1: blocks independently.
      val col = boundary.clone()
      val pivot = Array.fill(N)(-1)
      var localAdds = 0L
      var localPairs = 0
      val deferred = scala.collection.mutable.ArrayBuffer.empty[Int]
      for j <- 0 until N do
        val blk = cells(j).block
        while col(j).nonEmpty && cells(col(j).last).block == blk && pivot(col(j).last) >= 0 do
          col(j) = add(col(j), col(pivot(col(j).last)))
          localAdds += 1
        if col(j).nonEmpty then
          if cells(col(j).last).block == blk then
            pivot(col(j).last) = j
            localPairs += 1
          else deferred += j
      // Phase 2: the deferred columns, in order.
      var globalAdds = 0L
      for j <- deferred do
        while col(j).nonEmpty && pivot(col(j).last) >= 0 do
          col(j) = add(col(j), col(pivot(col(j).last)))
          globalAdds += 1
        if col(j).nonEmpty then pivot(col(j).last) = j
      val samePairs = (0 until N).forall(r => pivot(r) == fullPivot(r))
      val pairs = (0 until N).count(pivot(_) >= 0)
      val positive = (0 until N).count(r => pivot(r) >= 0 && cells(pivot(r)).value > cells(r).value)
      val unpaired = N - 2 * pairs
      val blocks = higher.groupBy(_.block).values.map(_.size)
      println(
        s"n=$n ambient=$ambient top simplex dim=$top threshold=$threshold: $N cells, ${blocks.size} edge blocks (largest ${blocks.max})"
      )
      println(
        s"  pairs: $pairs, of which inside one block: $localPairs; positive-length: $positive; unpaired: $unpaired"
      )
      println(s"  deferred to phase 2: ${deferred.size} columns; same pairs as one sequential reduction: $samePairs")
      println(s"  column additions: sequential $fullAdds; phase 1 (parallel by block) $localAdds; phase 2 $globalAdds")
      // The same two phases on the coboundary matrix (cohomology, as Ripser reduces): columns are the cells below the
      // top dimension, in reverse filtration order, so the top dimension is only rows and its cycles cost nothing.
      val rev = (i: Int) => N - 1 - i
      val cob = Array.fill(N)(IntBuffer())
      for j <- 0 until N; i <- boundary(j) do cob(i) += rev(j)
      val coCols = (N - 1 to 0 by -1).filter(i => cells(i).dim < top)
      def coboundary(i: Int): Array[Int] = cob(i).result.sorted
      val coFull = Array.fill(N)(Array.empty[Int])
      val coFullPivot = Array.fill(N)(-1)
      var coFullAdds = 0L
      for i <- coCols do
        var c = coboundary(i)
        while c.nonEmpty && coFullPivot(c.last) >= 0 do
          c = add(c, coFull(coFullPivot(c.last)))
          coFullAdds += 1
        coFull(i) = c
        if c.nonEmpty then coFullPivot(c.last) = i
      val co = Array.fill(N)(Array.empty[Int])
      val coPivot = Array.fill(N)(-1)
      var coLocalAdds = 0L
      var coLocalPairs = 0
      val coDeferred = scala.collection.mutable.ArrayBuffer.empty[Int]
      for i <- coCols do
        val blk = cells(i).block
        var c = coboundary(i)
        while c.nonEmpty && cells(rev(c.last)).block == blk && coPivot(c.last) >= 0 do
          c = add(c, co(coPivot(c.last)))
          coLocalAdds += 1
        co(i) = c
        if c.nonEmpty then
          if cells(rev(c.last)).block == blk then
            coPivot(c.last) = i
            coLocalPairs += 1
          else coDeferred += i
      var coGlobalAdds = 0L
      var coAddsOfLocal = 0L
      val locallyPaired = coPivot.clone()
      // Additions a deferred column makes before it first needs another deferred column depend only on phase 1, so
      // they can run in parallel too; the ones from then on are the sequential remainder.
      var coSequentialAdds = 0L
      for i <- coDeferred do
        var c = co(i)
        var waited = false
        while c.nonEmpty && coPivot(c.last) >= 0 do
          if locallyPaired(c.last) >= 0 then coAddsOfLocal += 1 else waited = true
          if waited then coSequentialAdds += 1
          c = add(c, co(coPivot(c.last)))
          coGlobalAdds += 1
        co(i) = c
        if c.nonEmpty then coPivot(c.last) = i
      val coSame = coCols.forall(i =>
        (fullPivot(i) >= 0 && cells(fullPivot(i)).dim <= top) == (co(i).nonEmpty) &&
          (co(i).isEmpty || rev(co(i).last) == fullPivot(i))
      )
      println(
        s"  cohomology: ${coCols.size} columns, pairs inside one block: $coLocalPairs, deferred: ${coDeferred.size}; same pairs as homology: $coSame"
      )
      println(
        s"  cohomology column additions: sequential $coFullAdds; phase 1 (parallel by block) $coLocalAdds; phase 2 $coGlobalAdds, of which adding a column paired in phase 1: $coAddsOfLocal, after first needing another deferred column: $coSequentialAdds"
      )
      return
    if method == "check" then
      val g = Generator(n, distance, threshold)
      val s = SortBaseline(n, distance, threshold)
      var a = g.edges
      var b = s.edges
      for d <- 1 to top do
        if d > 1 then
          a = g.cofacets(a)
          b = s.cofacets(b)
        val same = java.util.Arrays.equals(a.vertices, b.vertices) &&
          (0 until a.size).forall(i => g.diameter(a, i) == b.diameter(i))
        println(s"dim $d: ${a.size} simplices, identical to the sorted baseline: $same")
      return
    println(s"n=$n ambient=$ambient top=$top threshold=$threshold method=$method trials=$trials")
    val runs = Vector.fill(trials)(trial())
    if sortTimes.nonEmpty then
      println(
        "  of which sorting: " + (1 to top)
          .map(d => f"dim $d ${sortTimes.map(_(d)).sorted.apply(trials / 2) / 1e6}%.1f ms")
          .mkString(", ")
      )
    for d <- 1 to top do
      val rows = runs.map(_(d - 1))
      val ms = rows.map(_._2).sorted.apply(trials / 2) / 1e6
      println(
        f"  dim $d%d  size ${rows.head._1}%,12d  median ${ms}%10.1f ms  alloc ${rows.last._3}%,15d B  kept ${rows.head._4}%,13d B"
      )

  /** Ripser's way, on the same arrays: the cofacets of each simplex that add a vertex above its top vertex and within
    * the threshold of every vertex (a word-wise AND of threshold-adjacency bitsets), each with its diameter computed,
    * then the dimension sorted by diameter and colexicographically descending.
    */
  final class SortBaseline(n: Int, distance: (Int, Int) => Double, threshold: Double, counting: Boolean = false):
    final class SortedLayer(val dim: Int, val vertices: Array[Int], val diameter: Array[Double]):
      def size: Int = diameter.length
      def kept: Long = 4L * vertices.length + 8L * diameter.length

    private val words = (n + 63) >>> 6
    private val rows = new Array[Long](n * words)
    private val binomials = Array.tabulate(n + 1, 6)((m, k) => SimplexIndexing.binomial(m, k))

    def edges: SortedLayer =
      val vs = IntBuffer()
      val ds = DoubleBuffer()
      var b = 1
      while b < n do
        var a = 0
        while a < b do
          val d = distance(a, b)
          if d <= threshold then
            vs += a
            vs += b
            ds += d
            rows(a * words + (b >>> 6)) |= 1L << (b & 63)
            rows(b * words + (a >>> 6)) |= 1L << (a & 63)
          a += 1
        b += 1
      sorted(SortedLayer(1, vs.result, ds.result))

    def cofacets(layer: SortedLayer): SortedLayer =
      val k = layer.dim
      val vs = IntBuffer()
      val ds = DoubleBuffer()
      var i = 0
      while i < layer.size do
        val o = i * (k + 1)
        val topVertex = layer.vertices(o + k)
        var wi = words - 1
        val lowWord = (topVertex + 1) >>> 6
        while wi >= lowWord do
          var m = -1L
          var j = 0
          while j <= k && m != 0L do
            m &= rows(layer.vertices(o + j) * words + wi)
            j += 1
          if wi == lowWord then m &= -1L << ((topVertex + 1) & 63)
          while m != 0L do
            val bit = java.lang.Long.numberOfTrailingZeros(m)
            val w = (wi << 6) + bit
            var diam = layer.diameter(i)
            j = 0
            while j <= k do
              vs += layer.vertices(o + j)
              diam = math.max(diam, distance(w, layer.vertices(o + j)))
              j += 1
            vs += w
            ds += diam
            m &= m - 1
          wi -= 1
        i += 1
      sorted(SortedLayer(k + 1, vs.result, ds.result))

    /** Nanoseconds spent sorting, per dimension. */
    val sortNanos: scala.collection.mutable.Map[Int, Long] = scala.collection.mutable.Map.empty

    // The distinct edge lengths, ascending: every diameter is one of them, so `counting` sorts by its rank here with a
    // counting sort and then sorts each run of equal diameters by colex.
    private var distinctLengths: Array[Double] = Array.empty

    private def sorted(layer: SortedLayer): SortedLayer =
      val t0 = System.nanoTime()
      if layer.dim == 1 then distinctLengths = layer.diameter.distinct.sorted
      val w = layer.dim + 1
      val colex = Array.tabulate(layer.size) { i =>
        var c = 0L
        var j = 0
        while j < w do
          c += binomials(layer.vertices(i * w + j))(j + 1)
          j += 1
        c
      }
      val order =
        if !counting then
          sortIndices(
            layer.size,
            (x, y) =>
              java.lang.Double.compare(layer.diameter(x), layer.diameter(y)) match
                case 0 => java.lang.Long.compare(colex(y), colex(x))
                case c => c
          )
        else
          val rank = layer.diameter.map(d => java.util.Arrays.binarySearch(distinctLengths, d))
          val start = new Array[Int](distinctLengths.length + 1)
          rank.foreach(r => start(r + 1) += 1)
          for r <- 0 until distinctLengths.length do start(r + 1) += start(r)
          val fill = java.util.Arrays.copyOf(start, distinctLengths.length)
          val byRank = new Array[Int](layer.size)
          var i = 0
          while i < layer.size do
            byRank(fill(rank(i))) = i
            fill(rank(i)) += 1
            i += 1
          var r = 0
          while r < distinctLengths.length do
            val lo = start(r)
            val hi = start(r + 1)
            if hi - lo > 1 then
              val run = java.util.Arrays.copyOfRange(byRank, lo, hi)
              val runOrder = sortIndices(hi - lo, (x, y) => java.lang.Long.compare(colex(run(y)), colex(run(x))))
              var t = 0
              while t < runOrder.length do
                byRank(lo + t) = run(runOrder(t))
                t += 1
            r += 1
          byRank
      val vs = new Array[Int](layer.vertices.length)
      var t = 0
      while t < order.length do
        System.arraycopy(layer.vertices, order(t) * w, vs, t * w, w)
        t += 1
      val result = SortedLayer(layer.dim, vs, order.map(layer.diameter))
      sortNanos(layer.dim) = System.nanoTime() - t0
      result
