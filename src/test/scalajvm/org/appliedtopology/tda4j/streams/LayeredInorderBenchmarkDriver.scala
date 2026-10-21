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
  * `method` is `bitsets`, `lists`, `sort`, `enumerating`, `ripser` or `inorder`, one per JVM, or `check` (the layered
  * and sorted dimensions are identical). `threshold` is a number or `mer` (the minimum enclosing radius). Prints one
  * line per dimension: size, median milliseconds, bytes allocated, bytes the dimension's arrays keep.
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
      case "sort" =>
        var t0 = System.nanoTime()
        var a0 = allocated()
        val s = SortBaseline(n, distance, threshold)
        var layer = s.edges
        val out = Vector.newBuilder[(Int, Long, Long, Long)]
        out += ((layer.size, System.nanoTime() - t0, allocated() - a0, layer.kept))
        for _ <- 2 to top do
          t0 = System.nanoTime()
          a0 = allocated()
          layer = s.cofacets(layer)
          out += ((layer.size, System.nanoTime() - t0, allocated() - a0, layer.kept))
        out.result()
      case "enumerating" | "ripser" | "inorder" =>
        val stream = method match
          case "enumerating" => EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
          case "ripser"      => RipserCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
          case _             => InorderCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(threshold))
        stream.iterateDimension(0).size
        (1 to top).toVector.map { d =>
          val t0 = System.nanoTime()
          val a0 = allocated()
          val size = stream.iterateDimension(d).size
          (size, System.nanoTime() - t0, allocated() - a0, -1L)
        }

    def kept(layer: Layer): Long = 4L * layer.vertices.length + 4L * layer.root.length

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
  final class SortBaseline(n: Int, distance: (Int, Int) => Double, threshold: Double):
    final class SortedLayer(val dim: Int, val vertices: Array[Int], val diameter: Array[Double]):
      def size: Int = diameter.length
      def kept: Long = 4L * vertices.length + 8L * diameter.length

    private val words = (n + 63) >>> 6
    private val rows = new Array[Long](n * words)
    private val binomials = Array.tabulate(n + 1, 8)((m, k) => SimplexIndexing.binomial(m, k))

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

    private def sorted(layer: SortedLayer): SortedLayer =
      val w = layer.dim + 1
      val colex = Array.tabulate(layer.size) { i =>
        var c = 0L
        var j = 0
        while j < w do
          c += binomials(layer.vertices(i * w + j))(j + 1)
          j += 1
        c
      }
      val order = sortIndices(
        layer.size,
        (x, y) =>
          java.lang.Double.compare(layer.diameter(x), layer.diameter(y)) match
            case 0 => java.lang.Long.compare(colex(y), colex(x))
            case c => c
      )
      val vs = new Array[Int](layer.vertices.length)
      var t = 0
      while t < order.length do
        System.arraycopy(layer.vertices, order(t) * w, vs, t * w, w)
        t += 1
      SortedLayer(layer.dim, vs, order.map(layer.diameter))
