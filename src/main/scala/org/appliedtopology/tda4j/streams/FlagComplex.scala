package org.appliedtopology.tda4j

import scala.collection.mutable

/** The flag (clique) complex of a weighted graph: a set of vertices is a simplex when every two of them are joined by
  * an edge, and it enters at the largest weight among those edges, so the filtration by weight is the Vietoris-Rips
  * filtration of the graph read as a distance matrix with `Infinity` where there is no edge. Unlike
  * `VietorisRips(graph)`, it never forms the `n x n` matrix or its bit tables: cliques are grown from the sorted
  * neighbour lists, so the cost is the number of simplices and the size of the graph, which is what makes a sparse
  * network with a hundred thousand vertices feasible.
  *
  * {{{
  * val graph = WeightedGraph.unweighted(5, Seq((0, 1), (1, 2), (2, 3), (3, 4), (4, 0)))
  * Persistence(graph).bettiNumbers   // Vector(1, 1): the pentagon is a loop
  * }}}
  *
  * The stream holds the simplices up to dimension `maxDimension + 1`, all in memory: cliques of a dense graph are many.
  *
  * @param maxFiltrationValue
  *   simplices entering later than this are left out; by default the graph's longest edge, past which nothing enters
  */
open class FlagComplex(
  val graph: WeightedGraph,
  val maxDimension: Int,
  maxFiltrationValue: Optional[Double] = Optional.empty
) extends StratifiedCellStream[Simplex[Int], Double]
    with DoubleFiltration[Simplex[Int]]():

  require(maxDimension >= 0, s"FlagComplex: maxDimension must be at least 0, got $maxDimension")

  /** The value past which simplices are left out. */
  val threshold: Double = maxFiltrationValue.toOption.getOrElse(graph.minimumEnclosingRadius)

  override val filtrationValue: PartialFunction[Simplex[Int], Double] = new PartialFunction[Simplex[Int], Double]:
    def isDefinedAt(s: Simplex[Int]): Boolean = values.contains(s)
    def apply(s: Simplex[Int]): Double = values(s)

  /** Filtration value reversed, then size, then the colexicographic order of the vertices ([[SimplexIndexing]]). */
  override val filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, SimplexIndexing.colexOrdering)

  override def homologyDegreeLimit: Option[Int] = Some(maxDimension)

  // Every simplex with its value, by dimension. A clique grows by one vertex above its largest, chosen among the common
  // neighbours of all its vertices; `candidates` are those, ascending, with `reach` the largest weight from each to
  // the clique, so a new simplex's value is `max(value so far, reach)`.
  private lazy val (values, unsorted) =
    val value = mutable.HashMap.empty[Simplex[Int], Double]
    val cells = Array.fill(maxDimension + 2)(mutable.ArrayBuffer.empty[Simplex[Int]])
    def grow(prefix: Array[Int], valueSoFar: Double, candidates: Array[Int], reach: Array[Double]): Unit =
      val cell = Simplex.from(prefix.toSeq)
      value(cell) = valueSoFar
      cells(prefix.length - 1) += cell
      if prefix.length < maxDimension + 2 then
        var j = 0
        while j < candidates.length do
          val entry = math.max(valueSoFar, reach(j))
          if entry <= threshold then
            val next = candidates(j)
            // The candidates above `next` that `next` is also joined to, with the heavier of the two weights.
            val keptCandidates = new Array[Int](candidates.length - j - 1)
            val keptReach = new Array[Double](keptCandidates.length)
            var kept = 0
            var k = j + 1
            var at = graph.rowRange(next)._1
            val end = graph.rowRange(next)._2
            while k < candidates.length && at < end do
              val c = candidates(k)
              val neighbour = graph.columnAt(at)
              if neighbour < c then at += 1
              else if neighbour > c then k += 1
              else
                keptCandidates(kept) = c
                keptReach(kept) = math.max(reach(k), graph.weightAt(at))
                kept += 1
                at += 1
                k += 1
            grow(
              prefix :+ next,
              entry,
              java.util.Arrays.copyOf(keptCandidates, kept),
              java.util.Arrays.copyOf(keptReach, kept)
            )
          j += 1
    for v <- 0 until graph.n if 0.0 <= threshold do
      val (first, end) = graph.rowRange(v)
      var above = first
      while above < end && graph.columnAt(above) < v do above += 1
      grow(
        Array(v),
        0.0,
        Array.tabulate(end - above)(i => graph.columnAt(above + i)),
        Array.tabulate(end - above)(i => graph.weightAt(above + i))
      )
    (value.toMap, cells.map(_.toVector).toVector)

  private lazy val byDimension: Vector[Vector[Simplex[Int]]] =
    unsorted.map(_.sorted(using filtrationOrdering.reverse))

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d >= 0 && d < byDimension.length && byDimension(d).nonEmpty => byDimension(d).iterator
  }
end FlagComplex
