package org.appliedtopology.tda4j

import com.dreizak.miniball.model.{ArrayPointSet, PointSet}
import com.dreizak.miniball.highdim.Miniball

class ScalaPointSet(points : Array[Array[Double]]) extends PointSet {
  override def size : Int = points.size
  override def dimension : Int = points(0).size
  override def coord(i : Int, j : Int) : Double = points(i)(j)
}

class AlphaShapes(val points : Array[Array[Double]]) extends StratifiedSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]() {
  def isDelaunay(pts: Array[Array[Double]], points: Array[Array[Double]]): Boolean = {
    val mb = Miniball(ScalaPointSet(pts))
    
    def sqdist(x : Array[Double], y : Array[Double]): Double = 
      x.zip(y).map((a, b) => (a-b)*(a-b)).sum

    points.filter(sqdist(mb.center(), _) < mb.squaredRadius()).isEmpty
  }

  def isDelaunaySimplex(spx: Simplex[Int], points: Array[Array[Double]]): Boolean = {
    isDelaunay(spx.vertices.toArray.map(points(_)), points)
  }
  
  val pointSet : ScalaPointSet = ScalaPointSet(points)
  val metricSpace : EuclideanMetricSpace = EuclideanMetricSpace(points)
  
  var simplexCache : Seq[Simplex[Int]] = metricSpace.elements.toSeq.map(Simplex(_))
  var cacheDimension : Int = 0
  
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if(d == cacheDimension) => simplexCache.iterator
    case 0 => metricSpace.elements.toSeq.map(Simplex(_)).iterator
    case 1 => {
      simplexCache = (for 
        i <- metricSpace.elements
        j <- metricSpace.elements
        if(i < j)
      yield
        Simplex(i,j)).toSeq.sortBy(filtrationValue)
      cacheDimension = 1
      simplexCache.iterator
    }
    case d if(d == cacheDimension+1) => {
      val newSimplexCache = for 
        spx <- simplexCache
        i <- metricSpace.elements.takeWhile(_ < spx.vertices.min)
        coface = spx.union(Simplex(i))
        if(isDelaunaySimplex(coface, points))
      yield
        coface
      simplexCache = newSimplexCache
      cacheDimension += 1
      simplexCache.iterator
    }
    case d => {
      // this may be a time sink
      simplexCache = metricSpace.elements
        .toSeq
        .combinations(d+1)
        .map(Simplex.from(_))
        .toSeq
        .sortBy(filtrationValue)
      cacheDimension = d
      simplexCache.iterator
    }
  }

  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FilteredSimplexOrdering[Int,Double](this)

  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)
}

