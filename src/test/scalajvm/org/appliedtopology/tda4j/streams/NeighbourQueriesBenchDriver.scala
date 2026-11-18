package org.appliedtopology.tda4j

import scala.util.Random

/** One run of the edge collection of the packed Ripser engines, for the A/B of the neighbour lists by tree queries
  * (#313): one strategy per JVM. `phase=lists` times only the lists (`how=pass`: the serial pass over all pairs,
  * `how=queries`: one tree query per point on all threads); `phase=engine` times the engine from construction to the
  * bars of degree 0 and 1, whose lists come the same way. Run with a fixed heap and the serial collector:
  * {{{
  * java -Xmx8g -XX:+UseSerialGC -cp $CP org.appliedtopology.tda4j.NeighbourQueriesBenchDriver \
  *   points=50000 radius=0.1 how=pass|queries phase=lists|engine [threads=N] [seed=1] [trial=1]
  * }}}
  * The points are uniform on the Clifford torus in R^4, `(cos a, sin a, cos b, sin b)` for uniform angles.
  */
object NeighbourQueriesBenchDriver:
  def main(args: Array[String]): Unit =
    val options = args.map(_.split("=", 2)).collect { case Array(k, v) => k -> v }.toMap
    val n = options.getOrElse("points", "50000").toInt
    val radius = options.getOrElse("radius", "0.1").toDouble
    val how = options.getOrElse("how", "queries")
    val phase = options.getOrElse("phase", "lists")
    val seed = options.getOrElse("seed", "1").toLong
    require(how == "pass" || how == "queries", s"how must be pass or queries, not $how")
    val rng = Random(seed)
    val points = Array.fill(n) {
      val (a, b) = (2 * math.Pi * rng.nextDouble(), 2 * math.Pi * rng.nextDouble())
      Array(math.cos(a), math.sin(a), math.cos(b), math.sin(b))
    }
    val base = EuclideanMetricSpace(points, cacheDistances = false)
    // the same space with the shortcut switched off: the engine runs the serial pass, everything else is unchanged
    val space: FiniteMetricSpace[Int] =
      if how == "queries" then base
      else
        new EuclideanMetricSpace(points, cacheDistances = false):
          override def neighbourListsWithin(threshold: Double, maxEntries: Long): Option[NeighbourLists] = None
    val start = System.nanoTime()
    def run[T](body: => T): T = options.get("threads").fold(body)(n => Parallel.withThreads(n.toInt)(body))
    def measured(): String = phase match
      case "lists" =>
        val lists =
          if how == "queries" then base.neighbourListsWithin(radius, Long.MaxValue)
          else NeighbourLists.within(n, radius, Long.MaxValue)(base.distance(_, _))
        s"entries=${lists.map(_.entryCount).getOrElse(-1)} checksum=${lists.map(l => l.targets.foldLeft(0L)(_ * 31 + _)).getOrElse(0L)}"
      case "engine" =>
        val f = FiniteField(32003)
        import f.given
        val engine = PackedRipserCohomologyEngine[f.Fp](space, 1, maxFiltrationValue = Some(radius))
        val bars = engine.persistentCohomology()
        s"usesLists=${engine.usesNeighbourLists} bars=${bars.size} checksum=${bars.map(b => (b.dim, b.lower.toString, b.upper.toString).hashCode).foldLeft(0L)(_ * 31 + _)}"
      case other => throw IllegalArgumentException(s"phase must be lists or engine, not $other")
    val summary = run(measured())
    val seconds = (System.nanoTime() - start) / 1e9
    println(f"how=$how phase=$phase points=$n radius=$radius%.3f seconds=$seconds%.2f $summary")
