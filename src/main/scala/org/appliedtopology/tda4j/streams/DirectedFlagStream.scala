package org.appliedtopology.tda4j

import scala.collection.mutable

/** The directed flag complex of a weighted directed graph (the construction of Flagser, Luetgehetmann, Govc, Smith and
  * Levi 2020; Reimann et al. 2017 for the clique complex of a neural network): its cells are the ordered tuples of
  * vertices `(v0, ..., vk)` such that every edge `vi -> vj` with `i < j` is present, and a cell enters at the largest
  * weight of those edges (or of its vertices, which enter at `vertexValue`, `0` by default).
  *
  * A directed cycle `a -> b -> c -> a` is a hole: no tuple contains all three, so the three edges bound nothing. The
  * same three vertices with `a -> c` in place of `c -> a` span the filled triangle `(a, b, c)`. An undirected graph
  * (both directions present) gives many more cells than its clique complex, one for every ordering of every clique.
  *
  * {{{
  * val stream = DirectedFlagStream(4, Seq((0, 1, 1.0), (1, 2, 1.0), (2, 0, 1.0)), maxDimension = 1)
  * Persistence(stream).bettiNumbers   // Vector(1, 1): a loop
  * }}}
  *
  * The complex is built for homological degrees `0 .. maxDimension` (it holds the cells up to dimension `maxDimension +
  * 1`), and every cell is held in memory.
  *
  * @param edges
  *   `(from, to, weight)`, each ordered pair once; a pair and its reverse may both be present
  * @param maxFiltrationValue
  *   cells entering later than this are left out
  */
open class DirectedFlagStream(
  val n: Int,
  edges: Iterable[(Int, Int, Double)],
  val maxDimension: Int,
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  vertexValue: Int => Double = _ => 0.0
) extends StratifiedCellStream[DirectedSimplex, Double]
    with DoubleFiltration[DirectedSimplex]():

  require(n >= 0, s"DirectedFlagStream: the number of vertices must be at least 0, got $n")
  require(maxDimension >= 0, s"DirectedFlagStream: maxDimension must be at least 0, got $maxDimension")

  private val outgoing: Array[mutable.Map[Int, Double]] = Array.fill(n)(mutable.HashMap.empty[Int, Double])
  for (from, to, weight) <- edges do
    require(0 <= from && from < n && 0 <= to && to < n, s"DirectedFlagStream: edge ($from, $to) is outside 0 until $n")
    require(from != to, s"DirectedFlagStream: edge ($from, $to) is a loop")
    require(!weight.isNaN, s"DirectedFlagStream: edge ($from, $to) has weight NaN")
    require(outgoing(from).put(to, weight).isEmpty, s"DirectedFlagStream: edge ($from, $to) is listed twice")

  override val filtrationValue: PartialFunction[DirectedSimplex, Double] = new PartialFunction[DirectedSimplex, Double]:
    def isDefinedAt(s: DirectedSimplex): Boolean = values.contains(s)
    def apply(s: DirectedSimplex): Double = values(s)

  /** `FiltrationOrdering.canonical` with the cells' own order as tie-break. */
  override val filtrationOrdering: Ordering[DirectedSimplex] =
    FiltrationOrdering.canonical(filtrationValue, _.dim, DirectedSimplex.intrinsicOrdering)

  override def homologyDegreeLimit: Option[Int] = Some(maxDimension)

  // Every cell with its value, by dimension. A cell grows by appending a vertex that every earlier vertex points to.
  // The sort needs `values` through `filtrationOrdering`, so it is a lazy val of its own: sorting inside this one would
  // read `values` while it is still being built, and never return.
  private lazy val (values, unsorted) =
    val value = mutable.HashMap.empty[DirectedSimplex, Double]
    val cells = Array.fill(maxDimension + 2)(mutable.ArrayBuffer.empty[DirectedSimplex])
    def grow(prefix: Vector[Int], valueSoFar: Double, candidates: Set[Int]): Unit =
      val cell = DirectedSimplex(prefix)
      value(cell) = valueSoFar
      cells(cell.dim) += cell
      if cell.dim < maxDimension + 1 then
        for next <- candidates do
          val entry = math.max(valueSoFar, math.max(vertexValue(next), prefix.map(outgoing(_)(next)).max))
          if entry <= maxFiltrationValue then
            grow(prefix :+ next, entry, (candidates - next).intersect(outgoing(next).keySet.toSet))
    for v <- 0 until n if vertexValue(v) <= maxFiltrationValue do
      require(!vertexValue(v).isNaN, s"DirectedFlagStream: vertex $v has value NaN")
      grow(Vector(v), vertexValue(v), outgoing(v).keySet.toSet)
    (value.toMap, cells.map(_.toVector).toVector)

  private lazy val byDimension: Vector[Vector[DirectedSimplex]] =
    unsorted.map(_.sorted(using filtrationOrdering.reverse))

  override def iterateDimension: PartialFunction[Int, Iterator[DirectedSimplex]] = {
    case d if d >= 0 && d < byDimension.length && byDimension(d).nonEmpty => byDimension(d).iterator
  }
end DirectedFlagStream
