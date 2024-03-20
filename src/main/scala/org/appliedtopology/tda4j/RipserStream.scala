package org.appliedtopology.tda4j

import scala.collection.Searching.{given, *}
import org.apache.commons.numbers.combinatorics

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*

def binomial(n: Int, k: Int): Int =
  if (n > 0 && k >= 0 && n >= k)
    combinatorics.BinomialCoefficient.value(n, k).toInt
  else 0

class SimplexIndexing(val vertexCount: Int) {
  given sc: SimplexContext[Int]()
  import sc.*

  /** Table of binomial coefficients for fast lookups.
    *
    * In order to make the simplex <-> index mapping work, this table encodes
    * binomial coefficients (d+s \choose s) so that binary search along each
    * such diagonal works.
    */
  val binomialTable = (0 to vertexCount).map { d =>
    (0 to vertexCount).map { s =>
      binomial(d + s, s)
    }
  }

  @tailrec
  final def apply(n: Int, d: Int, upperAccum: Set[Int] = Set()): Simplex = {
    if (d < 0) return Simplex.from(upperAccum)
    if (n <= 0) return Simplex.from(upperAccum ++ (0 until d).toSet)
    if (d == 0) return Simplex.from(upperAccum + n)
    val searchResult: SearchResult = binomialTable(d).search(n)
    val id: Int = searchResult match {
      case Found(foundIndex)              => foundIndex
      case InsertionPoint(insertionPoint) => insertionPoint - 1
    }
    return apply(n - binomialTable(d)(id), d - 1, upperAccum + (id + d))
  }

  def cofacetIterator(simplex: Simplex): Iterator[Int] =
    cofacetIterator(apply(simplex), simplex.size, true)
  def topCofacetIterator(simplex: Simplex): Iterator[Int] =
    cofacetIterator(apply(simplex), simplex.size, false)
  def cofacetIterator(
    index: Int,
    size: Int,
    allCofacets: Boolean = true
  ): Iterator[Int] =
    Iterator
      .unfold(
        (apply(index, size), index, 0, size, vertexCount - 1): Tuple5[
          Simplex,
          Int,
          Int,
          Int,
          Int
        ]
      ) { (s, iB, iA, k, j) =>
        if (j < 0) {
          None // end iteration when we're done
        } else if (s.contains(j)) {
          if (!allCofacets)
            None
          else
            Some(
              (
                None,
                (s, iB - binomial(j, k), iA + binomial(j, k + 1), k - 1, j - 1)
              )
            )
        } else { // if j is there, skip
          Some((Some(iB + binomial(j, k + 1) + iA), (s, iB, iA, k, j - 1)))
        }
      }
      .filter((os: Option[Int]) => os.isDefined)
      .map((os: Option[Int]) => os.get)

  def facetIterator(index: Int, size: Int): Iterator[Int] =
    Iterator.unfold((apply(index, size).toSeq.sorted, index, 0, size - 1)) {
      (s, iB, iA, k) =>
        if (k < 0) None
        else {
          val j = s(k)
          val iiB = iB - binomial(j, k + 1)
          val iiA = iA + binomial(j, k)
          Some((iiB + iA, (s, iiB, iiA, k - 1)))
        }
    }

  def apply(simplex: Simplex): Int =
    simplex.toSeq.sorted.reverse.zipWithIndex.map { (v, i) =>
      binomial(v, simplex.size - i)
    }.sum
}

class RipserCliqueFinder extends CliqueFinder[Int] {
  override val className: String = "RipserCliqueFinder"

  given sc: SimplexContext[Int]()
  import sc.*

  override def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex] =
    RipserStream(metricSpace, maxFiltrationValue, maxDimension).iterator.toSeq
}

abstract class RipserStreamBase(
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2
) extends SimplexStream[Int, Double] {
  given sc: SimplexContext[Int]()
  import sc.*

  def retain(index: Int, size: Int): Boolean = true
  def expand(filtrationValue: Double, index: Int, size: Int): Seq[Simplex] =
    Seq(si(index, size))

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  override def iterator: Iterator[AbstractSimplex[Int]] =
    for
      d <- (0 to maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

  def iteratorByDimension(d: Int): Iterator[AbstractSimplex[Int]] = if (
    d > metricSpace.size
  ) Iterator()
  else {
    (0 until binomial(metricSpace.size, d + 1)).iterator
      .filter(i => retain(i, d + 1))
      .map(i => (filtrationValue(si(i, d + 1)), i, d + 1))
      .toSeq
      .sortBy((f, i, s) => f)
      .flatMap(expand)
      .iterator
  }

  override def filtrationValue: PartialFunction[AbstractSimplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  def zeroPivotCofacet(index: Int, size: Int): Option[Int] = (
    for {
      cofacet <- si.cofacetIterator(index, size, false)
      if(filtrationValue(si(cofacet, size+1)) == filtrationValue(si(index,size)))
    } yield cofacet).maxOption

  def zeroPivotFacet(index: Int, size: Int): Option[Int] = (
    for {
      facet <- si.facetIterator(index, size)
      if(filtrationValue(si(facet, size-1)) == filtrationValue(si(index,size)))
    } yield facet).maxOption

  def zeroApparentCofacet(index: Int, size: Int): Option[Int] =
    for {
      cofacet <- zeroPivotCofacet(index,size)
      facet <- zeroPivotFacet(cofacet, size+1)
      if(facet == index)
    } yield cofacet

  def zeroApparentFacet(index: Int, size: Int): Option[Int] =
    for {
      facet <- zeroPivotFacet(index,size)
      cofacet <- zeroPivotCofacet(facet, size-1)
      if(facet == index)
    } yield cofacet

  def zeroPersistence[CoefficientT](): List[PersistenceBar[Double,Chain[Simplex,CoefficientT]]] = 
    Kruskal(metricSpace)
      .mstIterator
      .map { (b,d) => PersistenceBar[Double,Chain[Simplex,CoefficientT]](0, ClosedEndpoint(0.0), OpenEndpoint(metricSpace.distance(b,d))) }
      .toList
}

class RipserStream(
  metricSpace: FiniteMetricSpace[Int],
  maxFiltrationValue: Double,
  maxDimension: Int
) extends RipserStreamBase(metricSpace, maxFiltrationValue, maxDimension) {
  val className: String = "RipserStream"
}
object RipserStream {}

class RipserStreamOf[VertexT: Ordering](
  val metricSpace: FiniteMetricSpace[VertexT],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2
) extends SimplexStream[VertexT, Double] {
  protected val vertices: List[VertexT] = metricSpace.elements.toList
  protected val intMetricSpace = new FiniteMetricSpace[Int] {
    override def contains(x: Int): Boolean = metricSpace.contains(vertices(x))

    override def size: Int = metricSpace.size

    override def elements: Iterable[Int] = 0 until size

    override def distance(x: Int, y: Int): Double =
      metricSpace.distance(vertices(x), vertices(y))
  }
  protected val rs: RipserStream =
    RipserStream(intMetricSpace, maxFiltrationValue, maxDimension)

  override def iterator: Iterator[AbstractSimplex[VertexT]] =
    rs.iterator.map(s => s.map(v => vertices(v)))

  override def filtrationValue
    : PartialFunction[AbstractSimplex[VertexT], Double] =
    rs.filtrationValue.compose(s => s.map(v => vertices.indexOf(v)))
}

class SymmetricRipserCliqueFinder[KeyT](
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends CliqueFinder[Int] {
  given sc: SimplexContext[Int]()
  import sc.*

  override val className: String = "SymmetricRipserCliqueFinder"

  override def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex] =
    SymmetricRipserStream(
      metricSpace,
      maxFiltrationValue,
      maxDimension,
      symmetryGroup
    ).iterator.toSeq
}

class SymmetricRipserStream[KeyT](
  metricSpace: FiniteMetricSpace[Int],
  maxFiltrationValue: Double = Double.PositiveInfinity,
  maxDimension: Int = 2,
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends RipserStream(metricSpace, maxFiltrationValue, maxDimension) {
  import sc.*

  override def retain(index: Int, size: Int): Boolean =
    symmetryGroup.isRepresentative(si(index, size))

  override def expand(
    filtrationValue: Double,
    index: Int,
    size: Int
  ): Seq[Simplex] =
    symmetryGroup.orbit(si(index, size)).toSeq
}

class MaskedSymmetricRipserVR[KeyT: Ordering](
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends CliqueFinder[Int] {

  override val className: String = "MaskedSymmetricRipserVR"

  override def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[AbstractSimplex[Int]] =
    MaskedSymmetricRipserStream[KeyT](
      metricSpace,
      maxFiltrationValue,
      maxDimension,
      symmetryGroup
    ).iterator.toSeq
}

class MaskedSymmetricRipserStream[KeyT](
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double,
  val maxDimension: Int,
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends SimplexStream[Int, Double] {
  given sc: SimplexContext[Int]()
  import sc.*
  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
  val distances: Set[Double] = Set.from(
    for
      x <- metricSpace.elements
      y <- metricSpace.elements
    yield metricSpace.distance(x,y)
  )

  override def iterator: Iterator[Simplex] =
    for
      d <- (0 to maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

  def iteratorByDimension(d: Int): Iterator[Simplex] = if (d > metricSpace.size)
    Iterator()
  else {
    val repmap: Map[Double, List[Simplex]] = List.from(
      for {
        i <- (0 until binomial(metricSpace.size, d + 1)).iterator
        spx <- Seq(si(i, d + 1))
        if (symmetryGroup.isRepresentative(spx))
      } yield (filtrationValue(spx) -> spx)
    ).groupMap(_._1)(_._2)
    for {
       dist <- repmap.keys.toSeq.sorted.iterator
       spx <- repmap(dist).iterator
       out <- symmetryGroup.orbit(spx).iterator
    } yield out
  }

  override def filtrationValue: PartialFunction[Simplex, Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)
}
