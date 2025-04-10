package org.appliedtopology.tda4s

import scala.collection.mutable
import scala.util.Sorting

type WeightedSimplex = (Simplex, Double)

trait VietorisRipsComplexGenerator(val metricSpace: MetricSpace[Int]) {
  /**
   * Generate the Vietoris-Rips simplices for a given dimension, in filtration order.
   *
   * @param dimension   The dimension of the simplices to generate.
   *                  - dim = 0 returns vertices (0-dimensional simplices).
   *                  - dim = 1 returns edges (1-dimensional simplices).
   *                  - dim > 1 recursively computes higher-dimensional simplices.
   * @return A sequence of simplices (dimension, vertices, filtration value).
   */
  def generateSimplices(dimension: Int): Seq[WeightedSimplex]
}

class MemoryEfficientVietorisRipsComplex(metricSpace: MetricSpace[Int]) extends VietorisRipsComplexGenerator(metricSpace) {
  // Cache for storing only the current dimension's simplices
  private var cachedLayer: Option[(Int, Seq[WeightedSimplex])] = None

  /**
   * Generate the Vietoris–Rips simplices for a given dimension, in filtration order.
   * 
   * @param dimension The dimension of the simplices to generate.
   * @param metricSpace A metric space instance implementing distance operations.
   * @return A sequence of simplices (vertices, filtration value).
   */
  def generateSimplices(dimension: Int): Seq[WeightedSimplex] = {
    cachedLayer match {
      case Some((cachedDim, simplices)) if cachedDim == dimension =>
        simplices // Return cached simplices if they are from the requested dimension
      case _ =>
        // Compute simplices for the given dimension
        val simplices = dimension match {
          case 0 => generateVertices()
          case 1 => generateEdges()
          case _ => generateHigherSimplices(dimension)
        }
        // Update the cache to store only the current dimension
        cachedLayer = Some((dimension, simplices))
        simplices
    }
  }

  /**
   * 0-dimensional simplices: Just the points as simplices with filtration value 0.
   */
  private def generateVertices(): Seq[WeightedSimplex] = {
    metricSpace.elements.map(i => (Simplex(Set(i)), 0.0))
  }

  /**
   * 1-dimensional simplices: Minimal spanning tree edges sorted by length (filtration value).
   */
  private def generateEdges(): Seq[WeightedSimplex] = (for
  u <- metricSpace.elements
  v <- metricSpace.elements
  if u < v
  yield (Simplex(Set(u, v)), metricSpace.distance(u, v))
    ).sortBy(_._2)


  {
    val edges = mutable.ArrayBuffer[WeightedSimplex]()
    val visited = mutable.Set[Int]()
    val pq = mutable.PriorityQueue.empty[(Double, Int, Int)](Ordering.by(-_._1)) // Min-heap based on edge length

    val vertices = mutable.ArrayBuffer.from(metricSpace.elements)
    if(vertices.size < 2) Seq() else {
      val headVertex = vertices.remove(0)
      visited.add(headVertex)

      for (j <- vertices) pq.enqueue((metricSpace.distance(headVertex, j), headVertex, j))

      while (visited.size < metricSpace.elements.size) {
        val (dist, u, v) = pq.dequeue()
        if (!visited.contains(v)) {
          edges.append((Simplex(Set(u, v)), dist))
          visited.add(v)

          // Add edges from the new vertex
          for (j <- 0 until metricSpace.elements.size if !visited.contains(j)) {
            pq.enqueue((metricSpace.distance(v, j), v, j))
          }
        }
      }

      Sorting.stableSort(edges, (a: WeightedSimplex, b: WeightedSimplex) => a._2 < b._2)
      edges.toSeq
    }
  }

  /**
   * Higher-dimensional simplices: Generate cofaces of simplices from the lower dimension.
   */
  private def generateHigherSimplices(dimension: Int): Seq[WeightedSimplex] = {
    // Ensure we can use the lower-dimensional simplices (reuse cache or recompute if needed)
    val lowerSimplices = generateSimplices(dimension - 1)
    val higherSimplices = mutable.ArrayBuffer[WeightedSimplex]()

    for ((lowerSimplex, lowerFiltration) <- lowerSimplices) {
      val maxVertex = lowerSimplex.vertices.min // Smallest vertex in the lower simplex

      for (newVertex <- 0 until maxVertex) {
        val newSimplex = Simplex(lowerSimplex.vertices + newVertex) // Add new vertex and sort
        val filtrationValue = (lowerSimplex.vertices.toSeq.filter(_ != newVertex).map {
          case i => metricSpace.distance(i, newVertex)
        } appended lowerFiltration).max[Double] // Filtration value = either previous filtration value, or max new edge length

        higherSimplices.append((newSimplex, filtrationValue))
      }
    }

    Sorting.stableSort(higherSimplices, (a: WeightedSimplex, b: WeightedSimplex) => a._2 < b._2)
    higherSimplices.toSeq
  }
}


class BruteForceVietorisRipsComplex(metricSpace: MetricSpace[Int]) extends VietorisRipsComplexGenerator(metricSpace) {
  /**
   * Generate the Vietoris-Rips simplices for a given dimension using a brute force approach.
   * Directly processes subsets to form all (d+1)-combinations of the elements of the metric space
   * and sorts them based on the longest pairwise distance.
   *
   * @param dimension The dimension of the simplices to generate.
   * @return A sequence of simplices (vertices, filtration value).
   */
  def generateSimplices(dimension: Int): Seq[WeightedSimplex] =
    if metricSpace.elements.isEmpty then Seq()
    else {
    // Helper function to compute the longest pairwise distance in a set of vertices
    def longestPairwiseDistance(vertices: Set[Int]): Double =
    if vertices.size < 2 then 0.0
    else {
      vertices.toSeq.combinations(2).map { case Seq(u, v) =>
        metricSpace.distance(u, v)
      }.max
    }

    // Process subsets directly and map them to WeightedSimplex
    metricSpace.elements.toSet.subsets(dimension + 1)
      .map(vertices => (Simplex(vertices), longestPairwiseDistance(vertices)))
      .toSeq
      .sortBy(_._2)
  }
}
