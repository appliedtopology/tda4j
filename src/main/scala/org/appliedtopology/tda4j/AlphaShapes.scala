package org.appliedtopology.tda4j

import com.dreizak.miniball.model.{ArrayPointSet, PointSet}
import com.dreizak.miniball.highdim.Miniball

class ScalaPointSet(points : Array[Array[Double]]) extends PointSet {
  override def size : Int = points.size
  override def dimension : Int = points(0).size
  override def coord(i : Int, j : Int) : Double = points(i)(j)
}

class AlphaShapes(val points : Array[Array[Double]]) extends StratifiedSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]() {
  
  val pointSet : ScalaPointSet = ScalaPointSet(points)
  val metricSpace : EuclideanMetricSpace = EuclideanMetricSpace(points)
  
  var simplexCache : Seq[Simplex[Int]] = metricSpace.elements.toSeq.map(Simplex(_))
  var cacheDimension : Int = 0


  def isDelaunay(pts: Array[Array[Double]]): Boolean = pts.size match {
    case 0 => true
    case 1 => true
    case 2 => {
      val c = pts(0)
        .zip(pts(1))
        .map{(x,y) => (x+y)/2}
      val sqd = metricSpace.pointSqDistance(c, pts(0))
      !points.exists(metricSpace.pointSqDistance(c, _) < sqd)
    }
    case _ => {
      val mb = Miniball(ScalaPointSet(pts))

      !points.exists(metricSpace.pointSqDistance(mb.center(), _) < mb.squaredRadius())
    }
  }

  def isDelaunaySimplex(spx: Simplex[Int]): Boolean = {
    isDelaunay(spx.vertices.toArray.map(points(_)))
  }
  
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if(d == cacheDimension) => simplexCache.iterator
    case 0 => metricSpace.elements.toSeq.map(Simplex(_)).iterator
    case 1 => {
      simplexCache = (for 
        i <- metricSpace.elements
        j <- metricSpace.elements
        if(i < j)
        spx = Simplex(i,j)
        if(isDelaunaySimplex(spx))
      yield
        spx).toSeq.sortBy(filtrationValue)
      cacheDimension = 1
      simplexCache.iterator
    }
    case d if(d == cacheDimension+1) => {
      val newSimplexCache = for 
        spx <- simplexCache
        i <- metricSpace.elements.takeWhile(_ < spx.vertices.min)
        coface = spx.union(Simplex(i))
        if(isDelaunaySimplex(coface))
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
        .filter(isDelaunaySimplex)
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

