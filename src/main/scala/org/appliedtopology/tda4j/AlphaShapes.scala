package org.appliedtopology.tda4j

import collection.mutable
import scala.math.{pow, sqrt}
import org.apache.commons.math3.linear.{
  MatrixUtils,
  QRDecomposition,
  RealMatrix,
  RealVector,
  SingularValueDecomposition
}
import org.apache.commons.math3.linear.MatrixUtils.createRealVector
import scala.util.Random
import scala.util.chaining.*

import scala.jdk.CollectionConverters.*

import java.util.concurrent.*

import com.dreizak.miniball.model.{ArrayPointSet, PointSet}
import com.dreizak.miniball.highdim.Miniball

final case class Epsilon(epsilon: Double)
given Epsilon = Epsilon(1e-5)

def Alpha(pts: Seq[Array[Double]], dispatch: String = "default")(using epsilon: Epsilon): AlphaShapes = dispatch match
  case "default" =>
    pts match
      case pts if pts.isEmpty         => Alpha(pts, dispatch = "miniball")
      case pts if pts.head.length > 7 => Alpha(pts, dispatch = "helix")
      case _                          => Alpha(pts, dispatch = "miniball")
  case "helix" =>
    HelixDelaunay(pts.toArray) // Helix should be faster for dim: 7 - 17. Adjust this check when additional impl exists.
  case "miniball" => MiniballDelaunay(pts.toArray)

abstract class AlphaShapes extends StratifiedSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]():
  val metricSpace: FiniteMetricSpace[Int]

class ScalaPointSet(points: Array[Array[Double]]) extends PointSet:
  override def size: Int = points.size
  override def dimension: Int = points(0).size
  override def coord(i: Int, j: Int): Double = points(i)(j)

class MiniballDelaunay(val points: Array[Array[Double]]) extends AlphaShapes:

  val pointSet: ScalaPointSet = ScalaPointSet(points)
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(points)

  var simplexCache: Seq[Simplex[Int]] = metricSpace.elements.toSeq.map(Simplex(_))
  var cacheDimension: Int = 0

  def isDelaunay(pts: Array[Array[Double]]): Boolean = pts.size match
    case 0 => true
    case 1 => true
    case 2 =>
      val c = pts(0)
        .zip(pts(1))
        .map((x, y) => (x + y) / 2)
      val sqd = metricSpace.pointSqDistance(c, pts(0))
      !points.exists(metricSpace.pointSqDistance(c, _) < sqd)
    case _ =>
      val mb = Miniball(ScalaPointSet(pts))

      !points.exists(metricSpace.pointSqDistance(mb.center(), _) < mb.squaredRadius())

  def isDelaunaySimplex(spx: Simplex[Int]): Boolean =
    isDelaunay(spx.toArray.map(points(_)))

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if d == cacheDimension => simplexCache.iterator
    case 0                        => metricSpace.elements.toSeq.map(Simplex(_)).iterator
    case 1                        =>
      simplexCache = (for
        i <- metricSpace.elements
        j <- metricSpace.elements
        if i < j
        spx = Simplex(i, j)
        if isDelaunaySimplex(spx)
      yield spx).toSeq.sortBy(filtrationValue)
      cacheDimension = 1
      simplexCache.iterator
    case d if d == cacheDimension + 1 =>
      val newSimplexCache = for
        spx <- simplexCache
        i <- metricSpace.elements.takeWhile(_ < spx.min)
        coface: Simplex[Int] = spx.union(Simplex(i))
        if isDelaunaySimplex(coface)
      yield coface
      simplexCache = newSimplexCache.sortBy(filtrationValue)
      cacheDimension += 1
      simplexCache.iterator
    case d =>
      // this may be a time sink
      simplexCache = metricSpace.elements.toSeq
        .combinations(d + 1)
        .map(Simplex.from(_))
        .filter(isDelaunaySimplex)
        .toSeq
        .sortBy(filtrationValue)
      cacheDimension = d
      simplexCache.iterator
  }

  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FilteredSimplexOrdering[Int, Double](this)

  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

// utilities for Delaunay computations
type Point = RealVector
object Point:
  def apply(coords: Array[Double]): Point = MatrixUtils.createRealVector(coords)

case class Hyperplane(normal: Point, offset: Double):
  def isLight(point: Point): Boolean = normal.dotProduct(point) >= offset

  def dist(point: Point): Double = normal.dotProduct(point) - offset

  def reverse: Hyperplane = Hyperplane(normal.mapMultiply(-1.0), -offset)

object Hyperplane:
  def from(pointSet: Seq[Point])(using epsilon: Epsilon): Hyperplane =
    assert(pointSet.size >= pointSet.head.getDimension)
    val points = pointSet.map(p => p.subtract(pointSet.head))
    // now we need to find a basis of orthogonal complement to the space spanned by these
    val A = MatrixUtils.createRealMatrix(points.toSeq.map(_.toArray).toArray)
    val svdA = SingularValueDecomposition(A.transpose)
    val nullSV = svdA.getSingularValues.zipWithIndex.filter(_._1.abs < epsilon.epsilon)
    val n =
      nullSV.map((s, i) => svdA.getU.getColumnVector(i)).head // should leave out head if we want higher codimensions
    val offset = n.dotProduct(pointSet.head)
    Hyperplane(n, offset)

case class Ridge(normals: (Point, Point))
object Ridge:
  def apply(pointSet: Seq[Point], hyperplane: Hyperplane): Ridge =
    assert(pointSet.size == hyperplane.normal.getDimension - 1)
    val Hyperplane(normal1, offset1) = hyperplane
    val points = pointSet.map(p => p.subtract(pointSet.head)).appended(normal1)
    val Hyperplane(normal2, offset) = Hyperplane.from(points)
    Ridge((normal1, normal2))

case class Hypersphere(center: Point, radius: Double)(using epsilon: Epsilon):
  def contains(point: Point): Boolean = point.subtract(center).getNorm < radius - epsilon.epsilon

  def onSphere(point: Point): Boolean = math.abs(point.subtract(center).getNorm - radius) < epsilon.epsilon

object Hypersphere:
  def apply(pointSet: Seq[Point]): Hypersphere =
    val vvs = for
      v1 <- pointSet.indices
      v2 <- pointSet.indices
      if v1 < v2
    yield (pointSet(v1).subtract(pointSet(v2)), math.pow(pointSet(v1).getNorm, 2) - math.pow(pointSet(v2).getNorm, 2))
    val V = MatrixUtils.createRealMatrix(vvs.map(_._1.toArray).toArray)
    val b = MatrixUtils.createRealVector(vvs.map(_._2).toArray).mapMultiply(0.5)
    val c = SingularValueDecomposition(V).getSolver.solve(b)
    new Hypersphere(c, c.getDistance(pointSet.head))

case class DelaunaySimplex(simplex: Simplex[Int], circumsphere: Hypersphere)

/** Based on https://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=10917453&tag=1
  * @param pts - the input points to triangulate
  */
class HelixDelaunay(pts: Array[Array[Double]])(using epsilon: Epsilon) extends AlphaShapes:
  val points: Seq[Point] = pts.map(Point.apply)
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(pts)
  val ambientDimension: Int = points.head.getDimension
  val validated: mutable.Set[DelaunaySimplex] = mutable.Set.empty
  val candidate: mutable.ArrayDeque[Simplex[Int]] = mutable.ArrayDeque.empty

  var startingSimplex: Set[Int] = points.indices.take(ambientDimension).toSet
  {
    var convexHullHP: Boolean = false
    while !convexHullHP do
      val currentHP: Hyperplane = Hyperplane.from(startingSimplex.map(p => points(p)).toSeq)
      val lightPoints: Set[Int] =
        (points.indices.toSet -- startingSimplex).filter(pi => currentHP.dist(points(pi)) > epsilon.epsilon)
      if lightPoints.isEmpty then convexHullHP = true
      else
        startingSimplex = (lightPoints ++ startingSimplex).toSeq
          .pipe(Random.shuffle)
          .take(ambientDimension)
          .toSet
    startingSimplex = points.indices.filter(pi =>
      Hyperplane.from(startingSimplex.map(points).toSeq).dist(points(pi)).abs < epsilon.epsilon
    ) match
      case vs if vs.size == ambientDimension => vs.toSet
      case vs if vs.size > ambientDimension  =>
        Set(vs.head, vs.tail.minBy(vi => points(vs.head).getDistance(points(vi))))

    // brute force search for first delaunay simplex
    var done = false
    for pi <- points.indices do
      if !done then
        var valid = true
        if !startingSimplex.contains(pi) then
          val circumsphere = Hypersphere((startingSimplex + pi).map(p => points(p)).toSeq)
          val containedPoints = points.indices.toSet.filter(qi => circumsphere.contains(points(qi)))
          if containedPoints.isEmpty then
            done = true
            validated.add(DelaunaySimplex(Simplex.from((startingSimplex + pi).toSeq), circumsphere))
    assert(validated.nonEmpty)
  }
  // println(s"Starting simplex: $startingSimplex\n\tDelaunay simplex: ${validated.head}")

  case class FrontierCase(facet: Simplex[Int], hyperplane: Hyperplane, complement: Int, cofacetHypersphere: Hypersphere)

  object FrontierCase:
    def apply(cofacet: DelaunaySimplex, complement: Int): FrontierCase =
      val facet = cofacet.simplex - complement
      val hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points(_)))
      if hyperplane.isLight(points(complement)) then
        FrontierCase(facet, hyperplane.reverse, complement, cofacet.circumsphere)
      else FrontierCase(facet, hyperplane, complement, cofacet.circumsphere)

  // val frontierCases: mutable.ArrayDeque[FrontierCase] = mutable.ArrayDeque.empty
  val frontierCases: LinkedBlockingDeque[FrontierCase] = LinkedBlockingDeque[FrontierCase]()
  val visitedFacets: mutable.Set[Simplex[Int]] = mutable.Set(Simplex.from(startingSimplex.toSeq))
  val cospherical: mutable.Set[Set[Int]] = mutable.Set.empty

  def addFrontierCase(simplex: DelaunaySimplex, complement: Int): Unit =
    val removed = frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSortedSet == simplex.simplex.toSortedSet)
    if !removed then frontierCases.put(FrontierCase(simplex, complement))

  def handleCosphericalPoints(
    cosphericalPoints: Seq[Int],
    frontierCase: FrontierCase,
    newDelaunaySimplex: DelaunaySimplex
  ): Unit =
    // println(s"Handling cospherical points by tiling: $cosphericalPoints")
    val spherepoints: mutable.SortedSet[Int] = cosphericalPoints.to(mutable.SortedSet)
    // all of these work as extra point: every subset of the spherepoints is a valid Delaunay simplex with this empty circumsphere
    // so we need to pick a tiling subset of them. We start a local version of this frontier walking algorithm
    // we also need to make sure we don't come back inside this cospherical point set in a later iteration
    cospherical.add(spherepoints.toSet)
    frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSet.subsetOf(cosphericalPoints.toSet))
    spherepoints.subtractAll(newDelaunaySimplex.simplex.toSeq)
    // println(s"\tRemaining spherepoints: $spherepoints")
    val facets: mutable.ArrayDeque[(Simplex[Int], Simplex[Int])] =
      mutable.ArrayDeque.from(
        frontierCase.facet.toSeq.toSeq.map(fi => (newDelaunaySimplex.simplex - fi, newDelaunaySimplex.simplex))
      )
    while facets.nonEmpty do
      // take a facet
      val (facet, cofacet) = facets.removeHead()
      val complement = cofacet.toSeq.diff(facet.toSeq).head
      // println(s"\tFacet: $facet, cofacet: $cofacet")
      val hyperplane: Hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points)) match
        case candidateHP if candidateHP.isLight(points(complement)) => candidateHP.reverse
        case candidateHP                                            => candidateHP
      spherepoints.filter(hyperplane.isLight.compose(points)).headOption match
        case Some(pi) =>
          // println(s"\tFound light point: $pi creating Delaunay simplex: ${facet+pi}")
          val nds = DelaunaySimplex(facet + pi, newDelaunaySimplex.circumsphere)
          validated.add(nds)
          facets.addAll(facet.toSeq.toSeq.map(pj => (Simplex.from(nds.simplex.toSet.diff(Set(pj)).toSeq), nds.simplex)))
          spherepoints.remove(pi)
        case None =>
          // println(s"\tNo light points, the facet $facet points outwards")
          addFrontierCase(
            DelaunaySimplex(cofacet, newDelaunaySimplex.circumsphere),
            cofacet.toSeq.diff(facet.toSeq).head
          )

  val seedDelaunaySimplex: DelaunaySimplex = validated.head
  points.indices
    .map(i => (i, seedDelaunaySimplex.circumsphere.center.getDistance(points(i))))
    .filter((i, d) => math.abs(d - seedDelaunaySimplex.circumsphere.radius) <= epsilon.epsilon)
    .map(_._1)
    .to(mutable.SortedSet) match
    case spherepoints if spherepoints.size > ambientDimension + 1 =>
      handleCosphericalPoints(
        spherepoints.toSeq,
        FrontierCase(seedDelaunaySimplex, seedDelaunaySimplex.simplex.toSet.diff(startingSimplex).head),
        seedDelaunaySimplex
      )
    case spherepoints => startingSimplex.foreach(pi => frontierCases.put(FrontierCase(seedDelaunaySimplex, pi)))

  // handle a frontier case
  while !frontierCases.isEmpty do
    val frontierCase = frontierCases.take()
    // println(s"Handling frontier case: $frontierCase")
    if !visitedFacets.contains(frontierCase.facet) then
      visitedFacets.add(frontierCase.facet)
      // "light points" (in front of the facet) split into inside and outside a small circumsphere of the facet
      val circumsphere = Hypersphere(frontierCase.facet.toSeq.toSeq.map(points))
      (points.indices.toSet -- frontierCase.facet.toSeq).toSeq
        .filter(pi => frontierCase.hyperplane.isLight(points(pi)))
        .sortBy(pi => circumsphere.center.getDistance(points(pi)))
        .view
        .map(pi =>
          DelaunaySimplex(frontierCase.facet + pi, Hypersphere((frontierCase.facet + pi).toSeq.toSeq.map(points)))
        )
        .collectFirst { case ds if !points.exists(ds.circumsphere.contains) => ds } match
        case Some(newDelaunaySimplex) if !validated.exists(ds => newDelaunaySimplex.simplex == ds.simplex) =>
          // println(s"Adding new Delaunay simplex: $newDelaunaySimplex")
          // check whether we have "too many" cospherical points; in that case we have to tile them on our own
          val spherepoints: mutable.SortedSet[Int] = points.indices
            .map(i => (i, newDelaunaySimplex.circumsphere.center.getDistance(points(i))))
            .filter((i, d) => math.abs(d - newDelaunaySimplex.circumsphere.radius) <= 1e-5)
            .map(_._1)
            .to(mutable.SortedSet)
          if spherepoints.size > ambientDimension + 1 then
            if !cospherical.contains(spherepoints.toSet) then
              validated.add(newDelaunaySimplex)
              handleCosphericalPoints(spherepoints.toSeq, frontierCase, newDelaunaySimplex)
          else
            validated.add(newDelaunaySimplex)
            frontierCase.facet.toSeq.toSeq
              .foreach(vi => addFrontierCase(newDelaunaySimplex, vi))
        case Some(newDelaunaySimplex) => ()
        case None                     => ()

  val simplicesMap: Map[Int, Seq[Simplex[Int]]] = Map.from(
    (0 to ambientDimension).map(d =>
      d -> validated
        .flatMap((ds: DelaunaySimplex) => ds.simplex.toSet.subsets(d + 1))
        .map((s: Set[Int]) => Simplex.from(s.toSeq))
        .toSeq
    )
  )

  def edgeIsDelaunay(s: Simplex[Int]): Option[Double] =
    val Seq(src, tgt) = s.toSeq.toSeq
    val circumcenter: Point = points(src).add(points(tgt)).mapMultiply(0.5)
    val circumsphere = Hypersphere(circumcenter, circumcenter.getDistance(points(src)))
    if points.filter(p => circumcenter.getDistance(p) < circumsphere.radius - epsilon.epsilon).size > 0 then None
    else Some(circumsphere.radius)

  def computeFVal(s: Simplex[Int]): Double = s.dim match
    case 0 => 0.0
    case 1 =>
      edgeIsDelaunay(s) match
        case Some(alpha) => alpha
        case None        =>
          validated
            .filter(v => s.toSet.subsetOf(v.simplex.toSet))
            .map(ds => ds.circumsphere.radius)
            .min
    case _ =>
      validated
        .filter(v => s.toSet.subsetOf(v.simplex.toSet))
        .map(ds => ds.circumsphere.radius)
        .min
  val filtrationValuesMemo: mutable.Map[Simplex[Int], Double] = mutable.Map.empty

  override def filtrationValue: PartialFunction[Simplex[Int], Double] = { case spx =>
    filtrationValuesMemo.getOrElseUpdate(spx, computeFVal(spx))
  }

  val simplicesSortedMap: Map[Int, Seq[Simplex[Int]]] = simplicesMap.map((d, v) => (d, v.sortBy(filtrationValue)))

  def simplicesInDimension(d: Int): Iterator[Simplex[Int]] = simplicesSortedMap(d).iterator

  def simplices(): Iterator[Simplex[Int]] = (0 to ambientDimension).iterator.flatMap(simplicesInDimension)

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if simplicesSortedMap.contains(d) => simplicesSortedMap(d).iterator
  }

  override def filtrationOrdering: Ordering[Simplex[Int]] =
    Ordering.by[Simplex[Int], Double](s => filtrationValue(s)).orElse(simplexOrdering[Int])
