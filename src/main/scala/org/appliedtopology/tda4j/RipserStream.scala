package org.appliedtopology.tda4j

import scala.collection.Searching.{*, given}
import org.apache.commons.numbers.combinatorics

import scala.annotation.tailrec
import scala.util.boundary

given sc: SimplexContext[Int]()
import sc.*

def binomial(n:Int,k:Int): Int = {
  if(n > 0 && k >= 0 && n >= k) combinatorics.BinomialCoefficient.value(n,k).toInt
  else 0
}

class SimplexIndexing(val vertexCount: Int) {
  /** Table of binomial coefficients for fast lookups.
   *
   * In order to make the simplex <-> index mapping work, this table encodes
   * binomial coefficients (d+s \choose s) so that binary search along each
   * such diagonal works.
   */
  val binomialTable = (0 to vertexCount).map { d =>
    (0 to vertexCount).map { s =>
      binomial(d+s, s)
    }
  }

  @tailrec
  final def apply(n: Int, d: Int, upperAccum: Set[Int] = Set()): Simplex = {
    if (d < 0) return Simplex.from(upperAccum)
    if (n <= 0) return Simplex.from(upperAccum ++ (0 until d).toSet)
    if (d == 0) return Simplex.from(upperAccum + n)
    val searchResult: SearchResult = binomialTable(d).search(n)
    val id: Int = searchResult match {
      case Found(foundIndex) => foundIndex
      case InsertionPoint(insertionPoint) => insertionPoint - 1
    }
    return apply(n - binomialTable(d)(id), d-1, upperAccum + (id+d))
  }

  def cofacetIterator(simplex: Simplex): Iterator[Int] =
    cofacetIterator(apply(simplex), simplex.size, true)
  def topCofacetIterator(simplex: Simplex): Iterator[Int] =
    cofacetIterator(apply(simplex), simplex.size, false)
  def cofacetIterator(index: Int, size: Int, allCofacets: Boolean = true): Iterator[Int] =
    Iterator.unfold((apply(index, size), index, 0, size, vertexCount-1): Tuple5[Simplex,Int,Int,Int,Int]) { (s,iB,iA,k,j) =>
      if (j < 0) {
        None // end iteration when we're done
      } else if (s.contains(j)) {
        if (!allCofacets)
          None
        else
          Some((None, (s, iB-binomial(j,k), iA+binomial(j,k+1), k-1, j-1)))
      } else { // if j is there, skip
        Some((Some(iB + binomial(j, k + 1) + iA), (s, iB, iA, k, j - 1)))
      }
    }.filter { (os: Option[Int]) => os.isDefined }.map { (os: Option[Int]) => os.get }

  def facetIterator(index: Int, size: Int): Iterator[Int] =
    Iterator.unfold((apply(index,size).toSeq.sorted, index, 0, size-1)) { (s,iB,iA,k) =>
      if(k < 0) None
      else {
        val j = s(k)
        val iiB = iB - binomial(j, k+1)
        val iiA = iA + binomial(j,k)
        Some((iiB + iA, (s, iiB, iiA, k-1)))
      }
    }

  def apply(simplex: Simplex): Int =
    simplex
      .toSeq.sorted
      .reverse
      .zipWithIndex
      .map { (v,i) => binomial(v, simplex.size-i) }
      .sum
}

class RipserStream(
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2
) extends SimplexStream[Int, Double] {
  override def iterator: Iterator[Simplex] =
    for
      d <- (0 to maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

  def iteratorByDimension(d: Int): Iterator[Simplex] = if (d > metricSpace.size) Iterator() else {
    val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
    (0 until binomial(metricSpace.size, d+1))
      .toSeq
      .map { i => (filtrationValue(si(i, d+1)), i, d+1) }
      .sortBy { (f,i,s) => f }
      .map { (f,i,s) => si(i,s) }
      .iterator
  }

  override def filtrationValue: PartialFunction[Simplex, Double] = simplex =>
    (for
      i <- simplex.iterator
      j <- simplex.iterator if (i != j)
    yield metricSpace.distance(i,j)
      ).maxOption.getOrElse(Double.NegativeInfinity)
}

object RipserStream {}
