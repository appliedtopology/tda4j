package org.appliedtopology.tda4j

import ParallelPackedCohomology.{EdgeTies, Space}
import scala.util.Random

/** An experiment, not an engine: the sequential packed Ripser engine on the RANKS of the edges (`Space`'s order, so an
  * `EdgeTies` order) in place of their lengths, which is how ripser.cpp's edge-lexicographic order is computed. Every
  * edge then has its own "diameter", the engine's same-diameter tests see the edge blocks, and the bars come back in
  * lengths through the table of the ranks.
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.EdgeRankMetricDriver <dataset> <size> <maxDim> <threshold> <trials> <lengths|ties-name> [p]
  * }}}
  * `lengths` runs the engine on the lengths (its own order); a tie order name runs it on ranks of that order. Prints
  * the engine's substitution (column addition) and apparent pair counts and a digest of the positive-length bars in
  * lengths, equal across runs.
  */
object EdgeRankMetricDriver:
  def main(args: Array[String]): Unit =
    val pts = BlockParallelReduction.points(args(0), args(1).toInt, Random(42))
    val maxDim = args(2).toInt
    val threshold = args(3).toDouble
    val trials = args(4).toInt
    val mode = args(5)
    val p = if args.length > 6 then args(6).toInt else 17
    val base = EuclideanMetricSpace(pts)
    val n = base.size
    val field = FiniteField(p)
    import field.given
    var counts = ""
    var digest = ""
    val times = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      val (metric, limit, toLength) =
        if mode == "lengths" then (base: FiniteMetricSpace[Int], threshold, (x: Double) => x)
        else
          val space = Space(n, base.distance(_, _), threshold, maxDim, p, edgeTies = EdgeTies.parse(mode))
          val ranked = new FiniteMetricSpace[Int]:
            def distance(x: Int, y: Int): Double =
              if x == y then 0.0
              else
                val r = space.rankOf(x min y, x max y)
                if r < 0 then Double.PositiveInfinity else r + 1.0
            def size: Int = n
            def elements: Iterable[Int] = Range(0, n)
            def contains(x: Int): Boolean = 0 <= x && x < n
          (ranked, space.edgeA.length + 1.0, (x: Double) => if x.isInfinite then x else space.value(x.toInt))
      val engine = PackedRipserCohomologyEngine[field.Fp](metric, maxDim, maxFiltrationValue = Some(limit))
      val bars = engine
        .persistentCohomology()
        .map(b =>
          (
            b.dim,
            toLength(BlockParallelReduction.endpoint(b.lower)),
            toLength(BlockParallelReduction.endpoint(b.upper))
          )
        )
        .filter(b => b._2 != b._3)
        .sorted
      val elapsed = (System.nanoTime() - t0) / 1e6
      counts = s"totalSimplexCount=${engine.totalSimplexCount} substitutionCount=${engine.substitutionCount} " +
        s"apparentPairCount=${engine.apparentPairCount} emergentPairCount=${engine.emergentPairCount}"
      digest = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bars.map(b => s"${b._1}\t${b._2}\t${b._3}").mkString("\n").getBytes("UTF-8"))
        .take(8)
        .map(b => f"$b%02x")
        .mkString + s" (${bars.size} bars)"
      elapsed
    }
    val sorted = times.sorted
    println(
      f"${args(0)} ${args(1)} maxDim=$maxDim threshold=$threshold mode=$mode: median ${sorted(sorted.size / 2)}%.1f ms, " +
        s"times ${times.map(t => f"$t%.0f").mkString(",")}"
    )
    println(s"  $counts barsDigest=$digest")
