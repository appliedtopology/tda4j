package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue

import scala.collection.immutable.{LazyList, SortedSet}
import scala.math.Ordering.Implicits.*
import scalax.collection.{edge, mutable as gmutable, Graph}
import scalax.collection.edge.Implicits.*
import scalax.collection.edge.WUnDiEdge

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.util.Sorting
import scala.util.control.*
import scala.util.chaining.*

/** Convenience definition to allow us to choose a specific implementation.
  *
  * @return
  *   A function-like object with the signature
  * {{{
  *     VietorisRips : (MetricSpace[VertexT], Double) =>
  *   Seq[FilteredAbstractSimplex[VertexT,Double]]
  * }}}
  */
class VietorisRips[VertexT](using ordering: Ordering[VertexT])(
  val metricSpace: FiniteMetricSpace[VertexT],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2,
  val cliqueFinder: CliqueFinder[VertexT] = new ZomorodianIncremental[VertexT](using ordering)
) extends SimplexStream[VertexT, Double] {
  self =>

  val simplices: Seq[Simplex[VertexT]] =
    cliqueFinder(metricSpace, maxFiltrationValue, maxDimension)

  val filtrationValue: PartialFunction[Simplex[VertexT], Double] =
    new FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)

  // Members declared in scala.collection.IterableOnce
  def iterator: Iterator[org.appliedtopology.tda4j.Simplex[VertexT]] =
    simplices.iterator

  // Members declared in scala.collection.SeqOps
  // def apply(i: Int): org.appliedtopology.tda4j.Simplex[VertexT] = simplices(i)

  // def length: Int = simplices.length
}

abstract class CliqueFinder[VertexT: Ordering]
    extends (
      (
        FiniteMetricSpace[VertexT],
        Double,
        Int
      ) => Seq[Simplex[VertexT]]
    ) {
  val className: String
  override def apply(
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex[VertexT]]
}

object CliqueFinder {

  /** First, construct all the edges with edge length less than the maxFiltrationValue
    */
  def weightedEdges[VertexT: Ordering](
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double
  ): Graph[VertexT, edge.WUnDiEdge] =
    Graph.from(
      metricSpace.elements,
      metricSpace.elements.flatMap(v =>
        metricSpace.elements
          .filter(_ > v)
          .flatMap { w =>
            val d_vw: Double = metricSpace.distance(v, w)
            if (d_vw < maxFiltrationValue)
              Some(edge.WUnDiEdge(v, w)(d_vw))
            else
              None
          }
      )
    )

  def simplexOrdering[VertexT: Ordering](
    metricSpace: FiniteMetricSpace[VertexT]
  ): Ordering[Simplex[VertexT]] =
    FilteredSimplexOrdering[VertexT, Double](new DoubleFiltration[Simplex[VertexT]] {
      def filtrationValue: PartialFunction[Simplex[VertexT], Double] =
        new FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](
          metricSpace
        )
    })

  def BronKerboschAlgorithm[VertexT: Ordering](
    maxDimension: Int,
    edges: Graph[VertexT, WUnDiEdge]
  ): mutable.Set[Set[VertexT]] = {
    // Now, recursive calls to Bron-Kerbosch:
    val cliqueSet = mutable.Set[Set[VertexT]]()
    val bkTaskStack: mutable.Stack[
      (Set[VertexT], mutable.Set[VertexT], mutable.Set[VertexT])
    ] =
      mutable.Stack(
        (
          Set[VertexT](),
          edges.nodes.map(_.toOuter).to(mutable.Set),
          mutable.Set[VertexT]()
        )
      )

    while (bkTaskStack.nonEmpty) {
      val task = bkTaskStack.pop()
      val R = task._1
      val P = task._2
      val X = task._3
      if (R.size <= maxDimension + 1) {
        cliqueSet += R.to(Set)
        while (P.nonEmpty) {
          val v = P.head
          val Nv = edges.get(v).neighbors.map(_.toOuter)
          bkTaskStack.push((R + v, P & Nv, X & Nv))
          P -= v
          X += v
        }
      }
    }
    cliqueSet
  }

}

/** `BronKerbosch` implements the creation of a Vietoris-Rips complex by running the Bron-Kerbosch clique finder
  * algorithm and then sorting the resulting cliques before returning them in a
  * `Seq[FilteredAbstractSimplex[V,Double]]`.
  *
  * Implements `apply` so that you call the object with an appropriate metric space and optional maximum filtration
  * value and receive a sequence of simplices back.
  */
class BronKerbosch[VertexT: Ordering] extends CliqueFinder[VertexT] {
  val className = "BronKerbosch"

  /** Creates a Vietoris-Rips simplex sequence using the Bron-Kerbosch algorithm for clique finding.
    *
    * @param metricSpace
    *   The [[MetricSpace]] instance that carries the metric information about the data.
    * @param maxFiltrationValue
    *   Optional: when to stop generating simplices. Default is ∞.
    * @param maxDimension
    *   Optional: what maximal dimension simplices to generate. Default is 2.
    * @return
    *   Vietoris-Rips complex in increasing order of filtration values, and increasing order of dimension, with
    *   lexicographic sorting for equal filtration value and dimension.
    */
  def apply(
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double = Double.PositiveInfinity,
    maxDimension: Int = 2
  ): Seq[Simplex[VertexT]] = {
    val edges = CliqueFinder.weightedEdges(metricSpace, maxFiltrationValue)

    /** Now that we have the a graph we want to run Bron-Kerbosch to find the cliques and sort them by their appearance
      * time/
      *
      * By insertion into a mutable SortedMap we can generate and insert all subsimplices without worrying too much
      * about saving duplicates.
      */

    // First, create a degeneracy ordering of the vertices
    val queryGraph: gmutable.Graph[VertexT, WUnDiEdge] =
      gmutable.Graph.from(edges.nodes, edges.edges)
    val degeneracyOrderedVertices = mutable.Queue[VertexT]()
    while (queryGraph.order > 0) {
      val (_, vs) = queryGraph.degreeNodesMap.head
      val v = vs.head
      degeneracyOrderedVertices += v.toOuter
      queryGraph -= v
    }

    val cliqueSet: mutable.Set[Set[VertexT]] =
      CliqueFinder.BronKerboschAlgorithm[VertexT](maxDimension, edges)

    // Well, we have our simplices generated and ready for us.
    // Let's sort them to create a stream
    val simplices: Seq[Simplex[VertexT]] =
      cliqueSet
        .filter(spx => spx.nonEmpty)
        .map(spx => Simplex[VertexT](spx.to(Seq): _*)) to Seq
    val filtration =
      new MaximumDistanceFiltrationValue[VertexT](metricSpace)
    val simplexOrdering =
      FilteredSimplexOrdering[VertexT, Double](new DoubleFiltration[Simplex[VertexT]] {
        def filtrationValue: PartialFunction[Simplex[VertexT], Double] =
          filtration
      })
    given Ordering[Simplex[VertexT]] = simplexOrdering
    Sorting.stableSort(simplices).toSeq
  }
}

class ZomorodianIncremental[VertexT: Ordering] extends CliqueFinder[VertexT] {
  val className = "ZomorodianIncremental"
  override def apply(
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex[VertexT]] = {
    val edges = CliqueFinder.weightedEdges(metricSpace, maxFiltrationValue)

    def lowerNeighbors(v: VertexT): SortedSet[VertexT] =
      edges.get(v).neighbors.map(_.toOuter).filter(_ < v).to(SortedSet)

    given Ordering[Simplex[VertexT]] =
      CliqueFinder.simplexOrdering(metricSpace)

    val V = mutable.SortedSet[Simplex[VertexT]]()
    val tasks = mutable.Stack[(SortedSet[VertexT], SortedSet[VertexT])]()

    edges.nodes
      .map(_.toOuter)
      .foreach(u => tasks.push((SortedSet[VertexT](u), lowerNeighbors(u))))

    while (tasks.nonEmpty) {
      val task = tasks.pop()
      val tau = task._1
      val N = task._2
      V += Simplex.from(tau)
      if (tau.size <= maxDimension) {
        N.foreach { v =>
          val sigma = tau + v
          val M = N & lowerNeighbors(v)
          tasks.push((sigma, M))
        }
      }
    }

    V.to(Seq)
  }
}

object LazyVietorisRips {
  self =>

  def apply[VertexT: Ordering](
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): LazyList[Simplex[VertexT]] = {
    given Ordering[Simplex[VertexT]] =
      CliqueFinder.simplexOrdering(metricSpace)

    val edges = CliqueFinder.weightedEdges(metricSpace, Double.PositiveInfinity)

    case class FoldState(
      G: Graph[VertexT, WUnDiEdge],
      retLL: LazyList[Simplex[VertexT]],
      knownSize: Int
    )

    def processNextEdge(
      state: FoldState,
      nextEdge: WUnDiEdge[VertexT]
    ): FoldState = state match {
      case FoldState(g, retLL, n) =>
        val endpoints: Seq[VertexT] = nextEdge.to(Seq)
        // val neighbors: Seq[Set[VertexT]] =
        //  endpoints.map[Set[VertexT]]((v: VertexT) => (g get v).neighbors map (_.toOuter) filter (_ < v))
        // val NN = neighbors.reduce(_ & _)

        /*        val cliqueSet: mutable.Set[Set[VertexT]] =
          CliqueFinder.BronKerboschAlgorithm[VertexT](
            maxDimension - 2,
            (g filter edges.having(node = NN.contains(_)))
          )
         */

        def lowerNeighbors(v: VertexT): SortedSet[VertexT] =
          g.get(v).neighbors.map(_.toOuter).filter(_ < v).to(SortedSet)

        val neighbors: Seq[SortedSet[VertexT]] =
          endpoints.map[SortedSet[VertexT]]((v: VertexT) => lowerNeighbors(v))

        val V = mutable.SortedSet[Simplex[VertexT]]()
        val tasks = mutable.Stack[(SortedSet[VertexT], SortedSet[VertexT])](
          (SortedSet[VertexT](endpoints: _*), neighbors.reduce(_ & _))
        )

        while (tasks.nonEmpty) {
          val task = tasks.pop()
          val tau = task._1
          val N = task._2
          V += Simplex.from(tau)
          if (tau.size <= maxDimension) {
            N.foreach { v =>
              val sigma = tau + v
              val M = N & lowerNeighbors(v)
              tasks.push((sigma, M))
            }
          }
        }

        val newSimplices: SortedSet[Simplex[VertexT]] =
          V.map(spx => spx.vertices)
            .map(spx => Simplex.from(spx ++ endpoints))
            .to(SortedSet)

        FoldState(
          g + nextEdge,
          retLL #::: newSimplices.to(LazyList),
          V.size
        )
    }

    val simplices = edges.nodes
      .to(Seq)
      .map(_.toOuter)
      .map(spx => Simplex(spx))
      .sorted
      .to(LazyList) #::: {
      val edgesLL: LazyList[WUnDiEdge[VertexT]] =
        edges
          .filter(edges.having(edge = _.weight < maxFiltrationValue))
          .edges
          .toSeq
          .map(_.toOuter)
          .sortBy(_.weight)
          .to(LazyList)
      val startingState = FoldState(
        edges.filter(edges.having(edge = _ => false, node = _ => true)),
        LazyList[Simplex[VertexT]](),
        edges.order // because we seeded `simplices` with the vertices
      )
      val foldedState =
        edgesLL.foldLeft[FoldState](startingState)(processNextEdge)

      foldedState match {
        case FoldState(_, retLL, _) => retLL
      }
    }
    simplices
  }
}

object LazyStratifiedVietorisRips {
  def apply[VertexT: Ordering](
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double,
    maxDimension: Int = 2
  ): Array[LazyList[Simplex[VertexT]]] =
    this(metricSpace, Some(maxFiltrationValue), maxDimension)

  def apply[VertexT: Ordering](
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Option[Double],
    maxDimension: Int
  ): Array[LazyList[Simplex[VertexT]]] = {

    val filtrationValue = FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)

    inline def maxSize: Int = maxDimension + 1
    val maxFVal = maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

    val lazyLists: Array[LazyList[Simplex[VertexT]]] = Array.ofDim(maxDimension + 1)
    lazyLists(0) = LazyList.from(metricSpace.elements.map(Simplex(_)))
    (1 to maxDimension).foreach(lazyLists(_) = LazyList.empty)

    def initStack: Vector[(Simplex[VertexT], (VertexT, VertexT))] =
      metricSpace.elements.toList
        .combinations(2)
        .toVector
        .filter { xys =>
          val List(x, y) = xys; metricSpace.distance(x, y) <= maxFVal
        }
        .sortBy { xys =>
          val List(x, y) = xys; metricSpace.distance(x, y)
        }
        .map { xys =>
          val List(src, tgt) = xys; (Simplex(src, tgt), (src, tgt))
        }

    def cofacets(
      simplex: => Simplex[VertexT],
      neighbors: => Map[VertexT, Set[VertexT]]
    ): LazyList[Simplex[VertexT]] =
      LazyList
        .from(
          simplex.vertices.tail
            .foldRight(neighbors(simplex.vertices.head))((v, N) => N.intersect(neighbors(v)))
        )
        .map(v => Simplex.from(simplex.vertices + v))

    case class FoldState(
      outputLists: Array[LazyList[Simplex[VertexT]]],
      taskStack: Vector[(Simplex[VertexT], (VertexT, VertexT))],
      neighbors: Map[VertexT, Set[VertexT]]
    )
    @tailrec def oneStep(foldState: FoldState): Array[LazyList[Simplex[VertexT]]] =
      if (foldState.taskStack.isEmpty) foldState.outputLists
      else if (foldState.taskStack.head._1.dim > maxSize - 1)
        oneStep(foldState.copy(taskStack = foldState.taskStack.tail))
      else {
        val (simplex, edge) = foldState.taskStack.head
        val List(src, tgt): List[VertexT] = List(edge._1, edge._2).sorted // ensure src < tgt
        val neighbors: Map[VertexT, Set[VertexT]] = if (simplex.dim == 1) { // new edge enters
          foldState.neighbors
            .updated(tgt, foldState.neighbors.getOrElse(tgt, Set()) + src)
            .updated(src, foldState.neighbors.getOrElse(src, Set()) + tgt)
        } else foldState.neighbors
        val candidateCofacets = cofacets(simplex, neighbors)

        // a cofacet stays if the edge is the last edge of its length
        val newCofacets =
          for
            cofacet <- candidateCofacets
            others = cofacet.vertices.toList
              .combinations(simplex.dim + 1)
              .filter(spx => filtrationValue(Simplex.from(spx)) == metricSpace.distance(src, tgt))
              .toList
              .sorted(math.Ordering.Implicits.seqOrdering)
            if simplex.vertices.toSeq == others.last
          yield cofacet

        oneStep(
          FoldState(
            foldState.outputLists.updated(simplex.dim, foldState.outputLists(simplex.dim).appended(simplex)),
            foldState.taskStack.tail.prependedAll(newCofacets.map((_, edge))),
            neighbors
          )
        )
      }

    val startState = FoldState(lazyLists, initStack, Map.empty)

    oneStep(startState)
  }
}

class LazyStratifiedCliqueFinder[VertexT: Ordering]() extends CliqueFinder[VertexT] {
  override val className: String = "LazyStratifiedCliqueFinder"
  def apply(
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double = Double.PositiveInfinity,
    maxDimension: Int = 2
  ): Seq[Simplex[VertexT]] =
    LazyStratifiedVietorisRips(metricSpace, maxFiltrationValue, maxDimension)
      .foldRight(LazyList[Simplex[VertexT]]())((lz, sq) => sq #::: lz)
}
