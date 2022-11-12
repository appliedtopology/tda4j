package org.appliedtopology.tda4j

import scala.collection.immutable.{LazyList, SortedSet}
import scala.math.Ordering.Implicits.*
import Simplex.*
import org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue
import scalax.collection.{Graph, edge}
import scalax.collection.edge.Implicits.*
import scalax.collection.mutable as gmutable

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Sorting
import scala.util.control.*

/**
 * Convenience definition to allow us to choose a specific implementation.
 *
 * @return A function-like object with the signature
 *         `VietorisRips : (MetricSpace[VertexT], Double) => Seq[FilteredAbstractSimplex[VertexT,Double]]`
 */
class VietorisRips[VertexT]
  (using ordering : Ordering[VertexT])
  (val metricSpace: FiniteMetricSpace[VertexT],
   val maxFiltrationValue : Double = Double.PositiveInfinity,
   val maxDimension : Int = 2,
   val cliqueFinder : CliqueFinder[VertexT] = new ZomorodianIncremental[VertexT]()(ordering))
  extends SimplexStream[VertexT, Double] {
  self =>

  val simplices = cliqueFinder(metricSpace, maxFiltrationValue, maxDimension)

  val filtrationValue : PartialFunction[AbstractSimplex[VertexT], Double] =
    new FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)

  // Members declared in scala.collection.IterableOnce
  def iterator: Iterator[org.appliedtopology.tda4j.AbstractSimplex[VertexT]] = simplices.iterator

  // Members declared in scala.collection.SeqOps
  //def apply(i: Int): org.appliedtopology.tda4j.AbstractSimplex[VertexT] = simplices(i)

  //def length: Int = simplices.length
}


abstract class CliqueFinder[VertexT : Ordering]
  extends ((FiniteMetricSpace[VertexT], Double, Int) => IterableOnce[AbstractSimplex[VertexT]]) {
  val className : String
  override def apply(metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double, maxDimension : Int):
    IterableOnce[AbstractSimplex[VertexT]]
}

object CliqueFinder {
  /**
   * First, construct all the edges with edge length less than the maxFiltrationValue
   */
  def weightedEdges[VertexT:Ordering](metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue : Double): Graph[VertexT, edge.WUnDiEdge] =
    Graph.from(metricSpace.elements,
      metricSpace.elements.flatMap(v =>
        metricSpace.elements
          .filter(_ > v)
          .flatMap(w => {
            val d_vw: Double = metricSpace.distance(v, w)
            if (d_vw < maxFiltrationValue)
              Some(edge.WUnDiEdge(v, w)(d_vw))
            else
              None
          })
      )
    )
}

/**
 * `BronKerbosch` implements the creation of a Vietoris-Rips complex by running the
 * Bron-Kerbosch clique finder algorithm and then sorting the resulting cliques before
 * returning them in a `Seq[FilteredAbstractSimplex[V,Double]]`.
 *
 * Implements `apply` so that you call the object with an appropriate metric space and
 * optional maximum filtration value and receive a sequence of simplices back.
 */
class BronKerbosch[VertexT : Ordering] extends CliqueFinder[VertexT] {
  val className = "BronKerbosch"
  /**
   * Creates a Vietoris-Rips simplex sequence using the Bron-Kerbosch algorithm for
   * clique finding.
   *
   * @param metricSpace The [[MetricSpace]] instance that carries the metric information about the data.
   * @param maxFiltrationValue Optional: when to stop generating simplices. Default is ∞.
   * @param maxDimension Optional: what maximal dimension simplices to generate. Default is 2.
   * @tparam VertexT Vertex type for the simplicial complex
   * @return Vietoris-Rips complex in increasing order of filtration values, and increasing order of dimension,
   *         with lexicographic sorting for equal filtration value and dimension.
   */
  def apply(metricSpace: FiniteMetricSpace[VertexT],
            maxFiltrationValue: Double = Double.PositiveInfinity,
            maxDimension: Int = 2): Seq[AbstractSimplex[VertexT]] = {
    val edges = CliqueFinder.weightedEdges(metricSpace, maxFiltrationValue)

    /**
     * Now that we have the a graph we want to run Bron-Kerbosch to find the cliques and sort them by
     * their appearance time/
     *
     * By insertion into a mutable SortedMap we can generate and insert all subsimplices without worrying
     * too much about saving duplicates.
     */

    // First, create a degeneracy ordering of the vertices
    val queryGraph : gmutable.Graph[VertexT, edge.WUnDiEdge] = gmutable.Graph.from(edges.nodes, edges.edges)
    val degeneracyOrderedVertices = mutable.Queue[VertexT]()
    while(queryGraph.order > 0) {
      val (o, vs) = queryGraph.degreeNodesMap.head
      val v = vs.head
      degeneracyOrderedVertices += v.toOuter
      queryGraph -= v
    }

    // Now, recursive calls to Bron-Kerbosch:
    val cliqueSet = mutable.Set[Set[VertexT]]()
    val bkTaskStack : mutable.Stack[(mutable.Set[VertexT],mutable.Set[VertexT],mutable.Set[VertexT])] =
      mutable.Stack((mutable.Set[VertexT](), edges.nodes.map(_.toOuter).to(mutable.Set), mutable.Set[VertexT]()))



    while(!bkTaskStack.isEmpty) {
      val task = bkTaskStack.pop()
      val R = task._1
      val P = task._2
      val X = task._3
      if(R.size <= maxDimension + 1) {
        cliqueSet += R.to(Set)
        while (!P.isEmpty) {
          val v = P.head
          val Nv = (edges get v).neighbors.map(_.toOuter)
          bkTaskStack.push((R + v, P & Nv, X & Nv))
          P -= v
          X += v
        }
      }
    }

    // Well, we have our simplices generated and ready for us.
    // Let's sort them to create a stream
    val simplices : Seq[AbstractSimplex[VertexT]] =
      cliqueSet filter (spx => spx.nonEmpty) map (spx => AbstractSimplex[VertexT](spx.to(Seq) : _*)) to(Seq)
    val filtration = new FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)
    val simplexOrdering = FilteredSimplexOrdering[VertexT, Double](new Filtration[VertexT, Double]{
      def filtrationValue : PartialFunction[AbstractSimplex[VertexT], Double] = filtration
    })
    given Ordering[AbstractSimplex[VertexT]] = simplexOrdering
    Sorting.stableSort(simplices).toSeq
  }
}

class ZomorodianIncremental[VertexT:Ordering]
  extends CliqueFinder[VertexT] {
  val className = "ZomorodianIncremental"
  override def apply(metricSpace: FiniteMetricSpace[VertexT],
                     maxFiltrationValue: Double,
                     maxDimension : Int): Seq[AbstractSimplex[VertexT]] = {
    val edges = CliqueFinder.weightedEdges(metricSpace, maxFiltrationValue)

    def lowerNeighbors(v : VertexT) : SortedSet[VertexT] =
      (edges get v).neighbors.map(_.toOuter).filter(_ < v).to(SortedSet)

    val filtration = new FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](metricSpace)
    val simplexOrdering = FilteredSimplexOrdering[VertexT, Double](new Filtration[VertexT, Double] {
      def filtrationValue: PartialFunction[AbstractSimplex[VertexT], Double] = filtration
    })

    given Ordering[AbstractSimplex[VertexT]] = simplexOrdering

    var V = mutable.SortedSet[AbstractSimplex[VertexT]]()
    var tasks = mutable.Stack[(SortedSet[VertexT], SortedSet[VertexT])]()

    edges.nodes.map(_.toOuter).foreach(u => tasks.push((SortedSet[VertexT](u), lowerNeighbors(u))))

    while(!tasks.isEmpty) {
      val task = tasks.pop()
      val tau = task._1
      val N = task._2
      V += AbstractSimplex.from(tau)
      if(tau.size < maxDimension) {
        N.foreach(v => {
          val sigma = tau + v
          val M = N & lowerNeighbors(v)
          tasks.push((sigma, M))
        })
      }
    }

    V.to(Seq)
  }
}