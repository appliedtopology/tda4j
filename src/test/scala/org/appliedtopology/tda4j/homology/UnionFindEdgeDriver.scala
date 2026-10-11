package org.appliedtopology.tda4j

import scala.util.Random

/** Test-scope driver for an A/B of the chunks engine's dimension-0/1 union-find (`unionFindDim01`), kept with the other
  * profile drivers. One engine, one JVM process:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.UnionFindEdgeDriver <vr|tree> <size> [warmups] [trials] [seed]
  * }}}
  *
  * The workload is all union-find, MATERIALIZED first into a plain stream (no coface enumeration or filtration lookups
  * timed), run with `maxDim = 0` (cells up to dimension 1 only, so the general reduction has nothing to do):
  *   - `vr`: the vertices and edges of a Vietoris-Rips complex of `size` seeded random points in the unit cube (every
  *     edge up to the default threshold) -- mostly cycle-closing edges;
  *   - `tree`: a random tree on `size` vertices (no cycles), the bare edge loop.
  * Prints the number of edges and the median of `trials` timed `diagramAt` runs (after `warmups`), each on a fresh
  * engine state. History: `.claude/WORKLOG-union-find-robustness.md`.
  */
object UnionFindEdgeDriver:
  def main(args: Array[String]): Unit =
    val workload = args(0)
    val size = args(1).toInt
    val warmups = args.lift(2).fold(3)(_.toInt)
    val trials = args.lift(3).fold(7)(_.toInt)
    val seed = args.lift(4).fold(17L)(_.toLong)
    val rng = new Random(seed)
    val (byDim, values, ordering) = workload match
      case "vr" =>
        val cloud = Seq.fill(size)(Seq.fill(3)(rng.nextDouble()))
        val vr = VietorisRips(EuclideanMetricSpace(cloud), maxDimension = 0)
        val cells = Map(0 -> vr.iterateDimension(0).toVector, 1 -> vr.iterateDimension(1).toVector)
        (cells, cells.values.flatten.map(s => s -> vr.filtrationValue(s)).toMap, vr.filtrationOrdering)
      case "tree" =>
        val edges = (1 until size).map(i => Simplex(rng.nextInt(i), i))
        val value: Map[Simplex[Int], Double] =
          ((0 until size).map(i => Simplex(i) -> 0.0) ++ edges.zipWithIndex.map((e, i) => e -> (i + 1).toDouble)).toMap
        val order = FiltrationOrdering.canonical(value, (s: Simplex[Int]) => s.dim, simplexOrdering[Int])
        val cells = Map(0 -> (0 until size).map(i => Simplex(i)).toVector, 1 -> edges.toVector)
        (cells.view.mapValues(_.sorted(using order.reverse)).toMap, value, order)
    val stream = new StratifiedCellStream[Simplex[Int], Double]:
      val filtrationValue: PartialFunction[Simplex[Int], Double] = values
      val filtrationOrdering: Ordering[Simplex[Int]] = ordering
      val smallest = Double.NegativeInfinity
      val largest = Double.PositiveInfinity
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if byDim.contains(d) => byDim(d).iterator
      }
    val field = FiniteField(2)
    import field.given
    def once(): (Double, Int) =
      val state = PersistenceInChunksEngine[Int, field.Fp](0).persistentHomology(stream)
      val t0 = System.nanoTime()
      val bars = state.diagramAt(Double.PositiveInfinity).size
      ((System.nanoTime() - t0) / 1e6, bars)
    (1 to warmups).foreach(_ => once())
    val runs = (1 to trials).map(_ => once())
    val times = runs.map(_._1).sorted
    println(
      f"edges=${byDim(1).size} bars=${runs.head._2} median_ms=${times(times.size / 2)}%.1f " +
        f"min_ms=${times.head}%.1f max_ms=${times.last}%.1f"
    )
