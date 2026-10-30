package org.appliedtopology.tda4j

import cats.Show

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** The Euler characteristic of a filtered complex as a function of the scale: the number of vertices, minus the edges,
  * plus the triangles, ... of the cells with filtration value at most `t`. It is a step function, so the curve keeps
  * the scales where it steps and the value from there on.
  *
  * {{{
  * val curve = EulerCharacteristicCurve(points)               // Vietoris-Rips, simplices up to dimension 2
  * curve(0.3)                                                 // the Euler characteristic at scale 0.3
  * curve.sample(0.0, 1.0, 50)                                 // 50 evenly spaced samples
  * }}}
  *
  * A vectorization that needs no persistence: [[EulerCharacteristicCurve.apply]] counts the cliques of every edge's
  * lens graph, so it stores no simplices and sorts nothing.
  *
  * @param scales
  *   The scales where the curve changes, strictly increasing; for a point cloud the first is `0.0`, where the vertices
  *   appear.
  * @param values
  *   `values(i)` is the Euler characteristic from `scales(i)` up to (not including) `scales(i + 1)`.
  */
final case class EulerCharacteristicCurve(scales: IndexedSeq[Double], values: IndexedSeq[Long]):
  require(scales.length == values.length, s"EulerCharacteristicCurve: ${scales.length} scales, ${values.length} values")
  require(
    scales.indices.drop(1).forall(i => scales(i - 1) < scales(i)),
    "EulerCharacteristicCurve: the scales must be strictly increasing"
  )

  /** The Euler characteristic at scale `t`: the complex of all cells with filtration value at most `t`. `0` below the
    * first scale, where the complex is empty.
    */
  def apply(t: Double): Long =
    import scala.collection.Searching.*
    scales.search(t) match
      case Found(i)          => values(i)
      case InsertionPoint(0) => 0L
      case InsertionPoint(i) => values(i - 1)

  /** The curve at each of `ts`. */
  def sample(ts: Seq[Double]): Array[Long] = ts.map(apply).toArray

  /** The curve at `resolution` evenly spaced points across `[tMin, tMax]`, both ends included (as
    * `Vectorization.landscape` samples).
    */
  def sample(tMin: Double, tMax: Double, resolution: Int): Array[Long] =
    require(resolution >= 2, s"EulerCharacteristicCurve.sample requires resolution >= 2, got $resolution")
    require(tMax > tMin, s"EulerCharacteristicCurve.sample requires tMax > tMin, got tMin=$tMin, tMax=$tMax")
    sample(Seq.tabulate(resolution)(j => tMin + j * (tMax - tMin) / (resolution - 1)))

object EulerCharacteristicCurve:
  given curveShow: Show[EulerCharacteristicCurve] = Show.show { c =>
    val steps = c.scales.zip(c.values).take(6).map((s, v) => s"$s -> $v").mkString(", ")
    s"EulerCharacteristicCurve(${c.scales.size} steps: $steps${if c.scales.size > 6 then ", ..." else ""})"
  }

  /** The curve of the Vietoris-Rips complex of `points`, counting the simplices of dimension at most
    * `maxSimplexDimension`, up to `maxFiltrationValue` (the minimum enclosing radius by default).
    *
    * Order the edges by length, ties in any fixed order. A simplex belongs to its last edge in that order, and the
    * simplices that belong to the edge `ab` are `ab` joined with the cliques of its ''lens graph'': the points joined
    * to both `a` and `b` by earlier edges, joined to each other when their edge is earlier. So the curve steps at the
    * length of `ab` by `-1 + c_1 - c_2 + ...`, where `c_j` is the number of `j`-vertex cliques of the lens graph. The
    * edges are independent given the edges before them, so they are shared between threads (one fork-join chunk of the
    * sorted edges each); the answer does not depend on the number of threads.
    */
  def apply(
    points: PointCloud,
    maxSimplexDimension: Int = 2,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): EulerCharacteristicCurve =
    vietorisRips(points.metricSpace, maxSimplexDimension, maxFiltrationValue)

  /** [[apply]] on a finite metric space. */
  def vietorisRips(
    metricSpace: FiniteMetricSpace[Int],
    maxSimplexDimension: Int = 2,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): EulerCharacteristicCurve =
    require(maxSimplexDimension >= 0, s"maxSimplexDimension must be >= 0, got $maxSimplexDimension")
    val n = metricSpace.size
    val threshold = maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)
    val edges = LayeredInorderSimplexStream.Generator(n, LayeredInorderSimplexStream.distanceOf(metricSpace), threshold)
    val jumps = new Array[Long](edges.edgeCount)
    if maxSimplexDimension >= 1 && edges.edgeCount > 0 then
      val chunks = math.max(1, math.min(Parallel.threads * 8, edges.edgeCount / 256))
      // The later edges have the larger lens graphs, so the tasks start from the last chunk.
      Parallel.tasks(chunks) { t =>
        val c = chunks - 1 - t
        val from = (edges.edgeCount.toLong * c / chunks).toInt
        val until = (edges.edgeCount.toLong * (c + 1) / chunks).toInt
        LensSweep(n, edges, maxSimplexDimension).run(from, until, jumps)
      }
    val steps = mutable.ArrayBuffer.empty[(Double, Long)]
    if n > 0 then steps += ((0.0, n.toLong))
    var e = 0
    while e < jumps.length do
      val length = edges.edgeLength(e)
      if steps.nonEmpty && steps.last._1 == length then steps(steps.size - 1) = (length, steps.last._2 + jumps(e))
      else steps += ((length, steps.lastOption.fold(0L)(_._2) + jumps(e)))
      e += 1
    of(steps)

  /** The curve of any stream, by counting its cells: the reference [[vietorisRips]] is checked against, and the curve
    * of complexes it does not cover (alpha, cubical, a stream you built). A truncated stream (`VietorisRips` with
    * `maxDimension = k` holds cells up to dimension `k + 1`) is counted as it is.
    */
  def ofStream[CellT: Cell](stream: CellStream[CellT, Double]): EulerCharacteristicCurve =
    val jumps = mutable.TreeMap.empty[Double, Long]
    for cell <- stream.iterator do
      val sign = if cell.dim % 2 == 0 then 1L else -1L
      val value = stream.filtrationValue(cell)
      jumps(value) = jumps.getOrElse(value, 0L) + sign
    var total = 0L
    of(jumps.toSeq.map((scale, jump) =>
      total += jump
      (scale, total)
    ))

  // Only the scales where the value changes are steps.
  private def of(steps: scala.collection.Seq[(Double, Long)]): EulerCharacteristicCurve =
    val changes = steps.zipWithIndex.collect {
      case ((scale, value), i) if i == 0 || steps(i - 1)._2 != value => (scale, value)
    }
    EulerCharacteristicCurve(
      ArraySeq.unsafeWrapArray(changes.map(_._1).toArray),
      ArraySeq.unsafeWrapArray(changes.map(_._2).toArray)
    )

  /** The sweep over a range of the sorted edges `edges`: bit rows of the edges before the current one, so an edge's
    * lens is the AND of its endpoints' rows and the lens graph's adjacency the rows themselves.
    */
  private final class LensSweep(n: Int, edges: LayeredInorderSimplexStream.Generator, maxSimplexDimension: Int):
    private val words = (n + 63) >>> 6
    private val rows = new Array[Long](n * words)
    // The largest clique of a lens graph that still gives a simplex of dimension at most `maxSimplexDimension`.
    private val maxClique = maxSimplexDimension - 1
    // `candidates(level)`: the vertices that extend the clique of `level` vertices found so far, all of them adjacent to
    // each other, to the edge's ends and larger than the clique's last vertex.
    private val candidates = Array.fill(math.max(maxClique, 0) + 1)(new Array[Long](words))
    // `cliques(j)`: the `j`-vertex cliques of the current lens graph.
    private val cliques = new Array[Long](math.max(maxClique, 0) + 1)

    private def add(e: Int): Unit =
      val a = edges.edgeA(e)
      val b = edges.edgeB(e)
      rows(a * words + (b >>> 6)) |= 1L << (b & 63)
      rows(b * words + (a >>> 6)) |= 1L << (a & 63)

    def run(from: Int, until: Int, jumps: Array[Long]): Unit =
      var e = 0
      while e < from do
        add(e)
        e += 1
      while e < until do
        jumps(e) = jump(e)
        add(e)
        e += 1

    private def jump(e: Int): Long =
      var result = -1L
      if maxClique >= 1 then
        val lens = candidates(0)
        val oa = edges.edgeA(e) * words
        val ob = edges.edgeB(e) * words
        var wi = 0
        while wi < words do
          lens(wi) = rows(oa + wi) & rows(ob + wi)
          wi += 1
        java.util.Arrays.fill(cliques, 0L)
        extend(0)
        var j = 1
        while j <= maxClique do
          // A j-vertex clique and the edge's two ends make a simplex of dimension j + 1.
          result += (if j % 2 == 1 then cliques(j) else -cliques(j))
          j += 1
      result

    // The cliques one vertex larger than the one `candidates(level)` extends; those are `level + 1` vertices.
    private def extend(level: Int): Unit =
      val cand = candidates(level)
      var count = 0L
      var wi = 0
      while wi < words do
        count += java.lang.Long.bitCount(cand(wi))
        wi += 1
      cliques(level + 1) += count
      if level + 2 <= maxClique && count > 1 then
        val next = candidates(level + 1)
        wi = 0
        while wi < words do
          var m = cand(wi)
          while m != 0L do
            val bit = java.lang.Long.numberOfTrailingZeros(m)
            m &= m - 1
            val v = (wi << 6) + bit
            // The vertices of `cand` above v that are adjacent to v.
            var wj = 0
            var any = 0L
            while wj < wi do
              next(wj) = 0L
              wj += 1
            val above = if bit == 63 then 0L else -1L << (bit + 1)
            next(wi) = cand(wi) & rows(v * words + wi) & above
            any |= next(wi)
            wj = wi + 1
            while wj < words do
              next(wj) = cand(wj) & rows(v * words + wj)
              any |= next(wj)
              wj += 1
            if any != 0L then extend(level + 1)
          wi += 1
