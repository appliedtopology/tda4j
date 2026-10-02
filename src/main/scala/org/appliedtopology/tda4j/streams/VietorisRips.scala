package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import org.appliedtopology.tda4j.streams.FiniteMetricSpace.MaximumDistanceFiltrationValue

import scala.collection.immutable.{LazyList, SortedSet}
import scala.math.Ordering.Implicits.*

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.util.Sorting
import scala.util.control.*
import scala.util.chaining.*

case class SimplexEdge(simplex: Simplex[Int], edge: Simplex[Int], diameter: Double)
object SimplexEdge:
  def from(simplex: Simplex[Int])(using metricSpace: FiniteMetricSpace[Int]) =
    val edges = for
      i <- simplex.toSortedSet
      j <- simplex.toSortedSet
      if i > j
    yield (metricSpace.distance(i, j), i, j)
    val maxedge = edges.max
    SimplexEdge(simplex, Simplex(maxedge._2, maxedge._3), maxedge._1)

class TopCofacetEnumerator(val simplex: SimplexEdge, val neighbors: SortedSet[Int])(using
  val metricSpace: FiniteMetricSpace[Int]
):
  val neighborIt: collection.BufferedIterator[Int] = neighbors.iterator.buffered

  val case2: Boolean = simplex.edge.last == simplex.simplex.last
  val case3: Boolean = case2 && (simplex.edge.firstKey == simplex.simplex.takeRight(2).firstKey)

  def isValid(head: Option[Int]): Boolean = head match
    case None    => false
    case Some(w) =>
      val distances = simplex.simplex.toSeq.map(v => metricSpace.distance(v, w))
      if distances.exists(d => d > simplex.diameter) then false
      else
        val Seq(s2, s1, s0) = simplex.simplex
          .takeRight(3)
          .toSeq
          .reverse
          .padTo(3, metricSpace.elements.min - 1)
        if w > s2 then true
        else if case2 && (w > s1) && (distances.last <= simplex.diameter) && (distances
            .dropRight(1)
            .forall(d => d < simplex.diameter))
        then true
        else if case3 && (w > s0) && (distances.forall(d => d < simplex.diameter)) then true
        else false

  def nonValid(head: Option[Int]): Boolean = !isValid(head)

  def hasNext: Boolean =
    while nonValid(neighborIt.headOption) && neighborIt.hasNext do neighborIt.next()
    neighborIt.hasNext
  def next(): Int = neighborIt.next()

class RecursiveStackSimplexEnumerator(val metricSpace: FiniteMetricSpace[Int], val targetDimension: Int = 2)(
  val query: SpatialQuery[Int] = BruteForce(metricSpace)
) extends Iterator[Simplex[Int]]:
  given FiniteMetricSpace[Int] = metricSpace
  val filtrationValue = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
  lazy val edges = (
    for
      i <- metricSpace.elements
      j <- metricSpace.elements
      if i < j
    yield Simplex(i, j)
  ).toSeq.sorted(using Ordering.by(filtrationValue).orElse(simplexOrdering))

  val enumeratorStack: mutable.Stack[TopCofacetEnumerator] = mutable.Stack.empty
  lazy val edgeIt: Iterator[Simplex[Int]] = edges.iterator

  def nextEdge(): Unit =
    if enumeratorStack.isEmpty then
      if edgeIt.hasNext then
        val edge = edgeIt.next()
        val Simplex(i, j) = edge: @unchecked
        val simplexedge: SimplexEdge = SimplexEdge.from(edge)
        val neighbors: SortedSet[Int] =
          ((query.neighbors(i, simplexedge.diameter) - i) &
            (query.neighbors(j, simplexedge.diameter) - j)).to(SortedSet)
        enumeratorStack.push(TopCofacetEnumerator(simplexedge, neighbors))

  // Grows enumeratorStack (pulling a new edge, or a new candidate vertex from the current top) until it reaches
  // targetDimension, backtracking (popping) whenever the current top is exhausted; once at depth, reports whether
  // the top level has a next candidate, backtracking and retrying if not.
  @tailrec
  final def hasNext: Boolean =
    if enumeratorStack.size < targetDimension then
      if enumeratorStack.isEmpty then
        nextEdge()
        if enumeratorStack.isEmpty then false else hasNext
      else if enumeratorStack.top.hasNext then
        // build up the next iterator up top
        val w = enumeratorStack.top.next()
        val simplexedge = enumeratorStack.top.simplex.copy(simplex = enumeratorStack.top.simplex.simplex + w)
        val neighbors = enumeratorStack.top.neighbors & (query.neighbors(w, enumeratorStack.top.simplex.diameter) - w)
        enumeratorStack.push(TopCofacetEnumerator(simplexedge, neighbors))
        hasNext
      else
        enumeratorStack.pop()
        hasNext
    else if enumeratorStack.top.hasNext then true
    else
      enumeratorStack.pop()
      hasNext

  def next(): Simplex[Int] = enumeratorStack.top.simplex.simplex + enumeratorStack.top.next()

/** A third, independent Vietoris-Rips coface-enumeration strategy, built on a recursive stack and spatial (VP-tree)
  * neighbor query rather than `SimplexIndexing`'s combinatorial-number-system enumeration. A cross-validation baseline
  * for the canonical VR streams, not a speed-competitive production engine in its own right.
  */
private[tda4j] class RecursiveStackVietorisRipsSimplexStream(val metricSpace: FiniteMetricSpace[Int])
    extends StratifiedSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]:
  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  // The shared FiltrationOrdering.canonical shape: fv reversed, then dimension, then simplexOrdering. This used
  // to be a hand-built `Ordering.by(filtrationValue).reverse.orElse(simplexOrdering[Int])` with NO dimension key
  // at all -- self-consistent within one dimension (all that VietorisRipsSpec's sortedness check or this class's
  // own bucket-by-bucket iteration ever compares), but wrong for the CROSS-dimension comparisons
  // `Chain.reduceBy`'s SortedMap/PriorityQueue actually performs during reduction, where two cells of different
  // dimension can tie on filtration value and must not compare equal.
  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, simplexOrdering[Int])

  // Bounded at metricSpace.size (a d-simplex needs d+1 distinct vertices) -- see
  // EnumeratingCofaceSimplexStream's own iterateDimension and StratifiedCellStream's doc for why an unbounded
  // catch-all here is the actual bug behind ".iterator hangs/eventually crashes", not just a missing guard.
  //
  // Each bucket is explicitly re-sorted by filtrationOrdering.reverse before being handed out, even though
  // `edges` (case 1) is already ascending by filtrationValue and the d >= 2 case's DFS-over-neighbors walk
  // (RecursiveStackSimplexEnumerator, via TopCofacetEnumerator) is already non-decreasing overall (pinned by
  // VietorisRipsSpec's "have sorted layers" test). Neither of those matches filtrationOrdering's tie-break on
  // cells that tie exactly -- `edges` tie-breaks ascending via simplexOrdering, and the DFS walk's tie order
  // comes from SortedSet[Int] neighbor traversal (ascending vertex id), not any filtration-aware order at
  // all. PersistenceInChunksEngine's chunk-boundary logic (Homology.scala's
  // PersistenceInChunksEngine.allCells) relies on this bucket's own position standing in for
  // filtrationOrdering position, so an inconsistent tie-break there silently breaks it even though this
  // class's own crash (the un-reversed filtrationOrdering primary key, fixed separately above) is gone --
  // found via EngineComparisonBenchmarkSpec / the regression test below.
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case 0 => metricSpace.elements.iterator.map(v => Simplex(v))
    case 1 =>
      RecursiveStackSimplexEnumerator(metricSpace, 1)().edges.sorted(using filtrationOrdering.reverse).iterator
    case d if d >= 2 && d < metricSpace.size =>
      RecursiveStackSimplexEnumerator(metricSpace, d - 1)().toVector.sorted(using filtrationOrdering.reverse).iterator
  }

/** The one place to ask for a Vietoris-Rips filtration, whichever of the several constructions does the work.
  *
  * Unlike the constructions' own constructors -- where `maxDimension` is sometimes the top SIMPLEX dimension
  * (`IncrementalVietorisRipsSimplexStream`) and sometimes absent (the coface streams are unbounded and wrapped in
  * `LimitedCofaceSimplexStream`) -- `maxDimension` here is, as everywhere user-facing, the top HOMOLOGICAL degree: you
  * get what is needed to compute `H_0 .. H_maxDimension` with ANY engine, so one dimension higher gets built internally
  * -- so an engine run directly on the stream also reports incomplete classes in dimension `maxDimension + 1` (the
  * stream stops there): drop them (`dim <= maxDimension`), as `matlab.TDA4j` does. Pass the stream to an engine that
  * wants it as-is (`SimplicialHomologyEngine`, `PersistenceInChunksEngine`, `CellularCohomologyEngine`);
  * `RipserCohomologyEngine`/`PackedRipserCohomologyEngine` take the metric space directly and do not need a stream at
  * all.
  *
  * `maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius` (Ripser's enclosing radius: beyond it nothing
  * new is born); `Some(Double.PositiveInfinity)` for the untruncated complex.
  *
  * The default implementation is [[Implementation.Enumerating]], what `matlab.TDA4j` itself builds for the naive and
  * chunks engines; the constructions agree cell for cell (they are cross-validated in the test suite) and differ in
  * speed by factors of ~1-2 in the benchmarks (`WORKLOG-mst-and-perf.md`). `RecursiveStackVietorisRipsSimplexStream` is
  * deliberately not offered: it cannot truncate by dimension or radius and times out beyond toy sizes.
  */
object VietorisRips:
  enum Implementation:
    /** Breadth-first coface enumeration; the default. */
    case Enumerating

    /** Ripser's coface order, via its combinatorial number system indexing. */
    case RipserCoface

    /** Cofaces generated in filtration order. */
    case Inorder

    /** Rieser's New-VR (arXiv:2301.07191): a cross-validation baseline, not a fast construction. */
    case Incremental

  def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxDimension: Int = 2,
    maxFiltrationValue: Option[Double] = None,
    implementation: Implementation = Implementation.Enumerating
  ): StratifiedSimplexStream[Int, Double] =
    require(maxDimension >= 0, s"maxDimension must be >= 0, got $maxDimension")
    val topSimplexDimension = maxDimension + 1
    implementation match
      case Implementation.Enumerating =>
        LimitedCofaceSimplexStream(
          EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
          topSimplexDimension
        )
      case Implementation.RipserCoface =>
        LimitedCofaceSimplexStream(
          RipserCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
          topSimplexDimension
        )
      case Implementation.Inorder =>
        LimitedCofaceSimplexStream(
          InorderCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
          topSimplexDimension
        )
      case Implementation.Incremental =>
        IncrementalVietorisRipsSimplexStream(metricSpace, topSimplexDimension, maxFiltrationValue)
