package org.appliedtopology.tda4j

import scala.util.Random

/** Test-scope driver for an A/B of the chunks engine's dimension-0/1 union-find (`unionFindDim01`), kept with the other
  * profile drivers. One engine, one JVM process:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.UnionFindEdgeDriver <vr|tree> <size> [warmups] [trials] [seed] \
  *   [query] [maxDim]
  * }}}
  *
  * The workload is MATERIALIZED first into a plain stream (no coface enumeration or filtration lookups timed). With the
  * default `maxDim = 0` it is all union-find (cells up to dimension 1 only, so the general reduction has nothing to
  * do); `maxDim = 1` adds the triangles, so degree-1 bars are reported and the general reduction runs:
  *   - `vr`: the vertices and edges of a Vietoris-Rips complex of `size` seeded random points in the unit cube (every
  *     edge up to the default threshold) -- mostly cycle-closing edges;
  *   - `tree`: a random tree on `size` vertices (no cycles), the bare edge loop.
  * `query` is `diagram` (default: `diagramAt`, bars only) or `barcode` (`barcodeAt`, bars with representatives). Prints
  * the number of edges and bars and the median of `trials` timed runs of the query at `+Infinity` (after `warmups`),
  * each on a fresh engine state. History: `.claude/WORKLOG-union-find-robustness.md`.
  */
object UnionFindEdgeDriver:
  def main(args: Array[String]): Unit =
    val workload = args(0)
    val size = args(1).toInt
    val warmups = args.lift(2).fold(3)(_.toInt)
    val trials = args.lift(3).fold(7)(_.toInt)
    val seed = args.lift(4).fold(17L)(_.toLong)
    val query = args.lift(5).getOrElse("diagram")
    val maxDim = args.lift(6).fold(0)(_.toInt)
    val rng = new Random(seed)
    val (byDim, values, ordering) = workload match
      case "vr" =>
        val cloud = Seq.fill(size)(Seq.fill(3)(rng.nextDouble()))
        val vr = VietorisRips(EuclideanMetricSpace(cloud), maxDimension = maxDim)
        val cells = (0 to maxDim + 1).map(d => d -> vr.iterateDimension(d).toVector).toMap
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
      val state = PersistenceInChunksEngine[Int, field.Fp](maxDim).persistentHomology(stream)
      val t0 = System.nanoTime()
      val bars = query match
        case "diagram" => state.diagramAt(Double.PositiveInfinity).size
        case "barcode" => state.barcodeAt(Double.PositiveInfinity).size
      ((System.nanoTime() - t0) / 1e6, bars)
    (1 to warmups).foreach(_ => once())
    val runs = (1 to trials).map(_ => once())
    val times = runs.map(_._1).sorted
    println(
      f"cells=${byDim.values.map(_.size).sum} edges=${byDim(1).size} bars=${runs.head._2} " +
        f"median_ms=${times(times.size / 2)}%.1f min_ms=${times.head}%.1f max_ms=${times.last}%.1f"
    )
