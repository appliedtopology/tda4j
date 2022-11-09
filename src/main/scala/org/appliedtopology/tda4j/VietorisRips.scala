package org.appliedtopology.tda4j

import scala.collection.immutable.{LazyList, SortedSet}
import scala.math.Ordering.Implicits.*
import Simplex.*
import scalax.collection.{Graph, edge}
import scalax.collection.edge.Implicits.*
import scalax.collection.mutable as gmutable
import scala.collection.mutable
import scala.util.Sorting

/**
 * Convenience definition to allow us to choose a specific implementation.
 *
 * @return A function-like object with the signature
 *         `VietorisRips : (MetricSpace[V], Double) => Seq[FilteredAbstractSimplex[V,Double]]`
 */
def VietorisRips = BronKerbosch

/**
 * `BronKerbosch` implements the creation of a Vietoris-Rips complex by running the
 * Bron-Kerbosch clique finder algorithm and then sorting the resulting cliques before
 * returning them in a `Seq[FilteredAbstractSimplex[V,Double]]`.
 *
 * Implements `apply` so that you call the object with an appropriate metric space and
 * optional maximum filtration value and receive a sequence of simplices back.
 */
object BronKerbosch {
  /**
   * Creates a Vietoris-Rips simplex sequence using the Bron-Kerbosch algorithm for
   * clique finding.
   *
   * @param metricSpace The [[MetricSpace]] instance that carries the metric information about the data.
   * @param maxFiltrationValue Optional: when to stop generating simplices. Default is ∞.
   * @tparam VertexT Vertex type for the simplicial complex
   * @return Vietoris-Rips complex in increasing order of filtration values, and increasing order of dimension,
   *         with lexicographic sorting for equal filtration value and dimension.
   */
  def apply[VertexT](
                      metricSpace: FiniteMetricSpace[VertexT],
                      maxFiltrationValue: Double = Double.PositiveInfinity
  )(implicit ord : Ordering[VertexT]): Seq[FilteredAbstractSimplex[VertexT, Double]] = {
    /**
     * First, construct all the edges with edge length less than the maxFiltrationValue
     */
    val edges : Graph[VertexT, edge.WUnDiEdge] = Graph.from(metricSpace.elements,
      metricSpace.elements.flatMap(v =>
        metricSpace.elements
          .filter(_ > v)
          .flatMap(w => {
            val d_vw : Double = metricSpace.distance(v, w)
            if(d_vw < maxFiltrationValue)
              Some(edge.WUnDiEdge(v,w)(d_vw))
            else
              None
          })
      )
    )

    /**
     * Now that we have the a graph we want to run BronKerbosch to find the cliques and sort them by
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
      cliqueSet += R.to(Set)
      while(!P.isEmpty) {
        val v = P.head
        val Nv = (edges get v).neighbors.map(_.toOuter)
        bkTaskStack.push((R + v, P & Nv, X & Nv))
        P -= v
        X += v
      }
    }

    // Well, we have our simplices generated and ready for us.
    // Let's look up maximum edge weights and sort them to create a stream
    def maxFiltrationValueOfSimplex(spx : Set[VertexT]) : Double = (edges filter (edges having
        (node = (n: edges.NodeT) =>
          spx.contains(n.toOuter)))).edges.map(_.toOuter.weight).maxOption.getOrElse(0)

    val weightedSimplices = Sorting.stableSort(cliqueSet filter (spx => !spx.isEmpty) map
      ((spx : Set[VertexT]) => (maxFiltrationValueOfSimplex(spx),spx.size,(spx to Seq).sorted)) to Seq)

    weightedSimplices.map((w,_,spx) => new FilteredAbstractSimplex[VertexT,Double](w, spx.toSeq : _*))
  }
}
