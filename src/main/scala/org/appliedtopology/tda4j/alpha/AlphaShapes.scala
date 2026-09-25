package org.appliedtopology.tda4j
package alpha

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import collection.mutable
import scala.math.{pow, sqrt}
import org.apache.commons.math3.linear.{MatrixUtils, RealMatrix, RealVector, SingularValueDecomposition}
import org.apache.commons.math3.linear.MatrixUtils.createRealVector
import scala.util.Random
import scala.util.chaining.*

import java.util.concurrent.*

/** A numerical tolerance, threaded via `using` through `Hyperplane.from`/`Hypersphere`/`HelixDelaunay`'s internal
  * geometric comparisons (near-zero singular values, near-zero circumsphere margins). Deliberately NOT a package- level
  * `given` (the earlier shape here): `Hyperplane`/`Hypersphere`/`HelixDelaunay` are never constructed from outside this
  * file (every external caller goes through `AlphaShapes.apply` below), so the one default value this codebase actually
  * uses only needs to live on `apply`'s own `using` parameter -- an ordinary default value, not an ambient given every
  * file that happens to wildcard-import `alpha.{given, *}` would otherwise pick up silently.
  */
final case class Epsilon(epsilon: Double)

abstract class AlphaShapes extends StratifiedSimplexStream[Int, Double]() with DoubleFiltration[Simplex[Int]]():
  val metricSpace: FiniteMetricSpace[Int]

/** `apply`/`Point` are scoped here rather than as bare top-level `alpha` package defs (a generic name like `Point`, or
  * a dispatch function as central as `Alpha` used to be, is exactly the kind of top-level-name collision hazard
  * documented elsewhere in this codebase) -- callers write `AlphaShapes(points, dispatch)`. `import AlphaShapes.Point`
  * below brings both the type and its factory back into unqualified scope for the rest of this file, where `Point` is
  * used pervasively by `Hyperplane`/`Hypersphere`/`HelixDelaunay`.
  */
object AlphaShapes:
  def apply(pts: Seq[Array[Double]], dispatch: String = "default")(using
    epsilon: Epsilon = Epsilon(1e-5)
  ): AlphaShapes =
    dispatch.toLowerCase match
      case "default" =>
        // These three branches all resolve to "helix" today: no regime (point count / ambient dimension) has been
        // measured yet to pick a backend by. They're placeholders for that dispatch, not dead code -- keep them
        // distinct rather than collapsing to a single case.
        pts match
          case pts if pts.isEmpty         => apply(pts, dispatch = "helix")
          case pts if pts.head.length > 7 => apply(pts, dispatch = "helix")
          case _                          => apply(pts, dispatch = "helix")
      case "helix" =>
        HelixDelaunay(
          pts.toArray
        ) // Helix should be faster for dim: 7 - 17. Adjust this check when additional impl exists.
      case "dqp" => AlphaShapeDQP(pts.toArray)
      case other =>
        throw IllegalArgumentException(s"Unknown alpha complex backend: '$other' (expected default/helix/DQP)")

  // utilities for Delaunay computations
  type Point = RealVector
  object Point:
    def apply(coords: Array[Double]): Point = MatrixUtils.createRealVector(coords)

import AlphaShapes.Point

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

case class Hypersphere(center: Point, radius: Double)(using epsilon: Epsilon):
  def contains(point: Point): Boolean = point.subtract(center).getNorm < radius - epsilon.epsilon

  def onSphere(point: Point): Boolean = math.abs(point.subtract(center).getNorm - radius) < epsilon.epsilon

object Hypersphere:
  def apply(pointSet: Seq[Point])(using epsilon: Epsilon): Hypersphere =
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

/** Runs the frontier-walking bootstrap + main loop that finds every Delaunay simplex of `pts`, holding all of the
  * algorithm's own mutable working state (`validated`, `frontierCases`, `visitedFacets`, `cospherical`) so
  * `HelixDelaunay` itself only ever sees the finished, immutable result of `compute()` -- the same
  * mutable-builder/immutable-result split `AlphaComplexDQPBuilder`/`AlphaComplexDQP` use.
  */
private class HelixDelaunayBuilder(pts: Array[Array[Double]], seed: Long)(using epsilon: Epsilon):
  // `seed` controls, not merely reproduces: measured directly (varying seed 0-9 on a 3x3 grid and on 6 cospherical
  // points), the bootstrap shuffle changes WHICH of several valid tilings the frontier walk produces whenever the
  // input has cospherical/degenerate structure -- exactly the class doc's own "Accepted limitation" case, since a
  // different starting simplex can land the walk on a different side of an order-dependent tie. A fixed default
  // keeps unseeded call sites deterministic; it does not make the walk order-independent.
  private val rng: Random = new Random(seed)
  val points: Seq[Point] = pts.map(Point.apply).toIndexedSeq
  val ambientDimension: Int = points.head.getDimension
  private val validated: mutable.Set[DelaunaySimplex] = mutable.Set.empty

  private case class FrontierCase(
    facet: Simplex[Int],
    hyperplane: Hyperplane,
    complement: Int,
    cofacetHypersphere: Hypersphere
  )

  private object FrontierCase:
    def apply(cofacet: DelaunaySimplex, complement: Int): FrontierCase =
      val facet = cofacet.simplex - complement
      val hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points(_)))
      if hyperplane.isLight(points(complement)) then
        FrontierCase(facet, hyperplane.reverse, complement, cofacet.circumsphere)
      else FrontierCase(facet, hyperplane, complement, cofacet.circumsphere)

  private val frontierCases: LinkedBlockingDeque[FrontierCase] = LinkedBlockingDeque[FrontierCase]()
  private val visitedFacets: mutable.Set[Simplex[Int]] = mutable.Set.empty
  private val cospherical: mutable.Set[Set[Int]] = mutable.Set.empty

  private def addFrontierCase(simplex: DelaunaySimplex, complement: Int): Unit =
    val newFacet = simplex.simplex - complement
    val removed = frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSortedSet == newFacet.toSortedSet)
    if !removed then frontierCases.put(FrontierCase(simplex, complement))

  private def handleCosphericalPoints(
    cosphericalPoints: Seq[Int],
    frontierCase: FrontierCase,
    newDelaunaySimplex: DelaunaySimplex
  ): Unit =
    val spherepoints: mutable.SortedSet[Int] = cosphericalPoints.to(mutable.SortedSet)
    // all of these work as extra point: every subset of the spherepoints is a valid Delaunay simplex with this empty circumsphere
    // so we need to pick a tiling subset of them. We start a local version of this frontier walking algorithm
    // we also need to make sure we don't come back inside this cospherical point set in a later iteration
    cospherical.add(spherepoints.toSet)
    frontierCases.removeIf((fc: FrontierCase) => fc.facet.toSet.subsetOf(cosphericalPoints.toSet))
    spherepoints.subtractAll(newDelaunaySimplex.simplex.toSeq)
    val facets: mutable.ArrayDeque[(Simplex[Int], Simplex[Int])] =
      mutable.ArrayDeque.from(
        frontierCase.facet.toSeq.toSeq.map(fi => (newDelaunaySimplex.simplex - fi, newDelaunaySimplex.simplex))
      )
    while facets.nonEmpty do
      // take a facet
      val (facet, cofacet) = facets.removeHead()
      val complement = cofacet.toSeq.diff(facet.toSeq).head
      val hyperplane: Hyperplane = Hyperplane.from(facet.toSeq.toSeq.map(points)) match
        case candidateHP if candidateHP.isLight(points(complement)) => candidateHP.reverse
        case candidateHP                                            => candidateHP
      spherepoints.filter(hyperplane.isLight.compose(points)).headOption match
        case Some(pi) =>
          val nds = DelaunaySimplex(facet + pi, newDelaunaySimplex.circumsphere)
          validated.add(nds)
          facets.addAll(facet.toSeq.toSeq.map(pj => (Simplex.from(nds.simplex.toSet.diff(Set(pj)).toSeq), nds.simplex)))
          spherepoints.remove(pi)
        case None =>
          addFrontierCase(
            DelaunaySimplex(cofacet, newDelaunaySimplex.circumsphere),
            cofacet.toSeq.diff(facet.toSeq).head
          )

  /** Runs the full bootstrap + frontier walk once and returns every Delaunay simplex found. */
  def compute(): Set[DelaunaySimplex] =
    var startingSimplex: Set[Int] = points.indices.take(ambientDimension).toSet
    var convexHullHP: Boolean = false
    while !convexHullHP do
      val currentHP: Hyperplane = Hyperplane.from(startingSimplex.map(p => points(p)).toSeq)
      val lightPoints: Set[Int] =
        (points.indices.toSet -- startingSimplex).filter(pi => currentHP.dist(points(pi)) > epsilon.epsilon)
      if lightPoints.isEmpty then convexHullHP = true
      else
        startingSimplex = (lightPoints ++ startingSimplex).toSeq
          .pipe(rng.shuffle)
          .take(ambientDimension)
          .toSet
    startingSimplex = points.indices.filter(pi =>
      Hyperplane.from(startingSimplex.map(points).toSeq).dist(points(pi)).abs < epsilon.epsilon
    ) match
      case vs if vs.size == ambientDimension => vs.toSet
      case vs if vs.size > ambientDimension  =>
        // vs has more points on the supporting hyperplane than needed for a
        // non-degenerate (ambientDimension-1)-simplex within it -- common for
        // grid-like or otherwise partly-degenerate point clouds. Picking just 2
        // of them regardless of ambientDimension leaves startingSimplex
        // undersized for ambientDimension > 2, starving the brute-force
        // bootstrap search below of a well-posed circumsphere (Hypersphere
        // needs ambientDimension+1 points to be uniquely determined) and
        // making it fail to find any candidate, which fails the assertion
        // below. Instead, greedily grow an affinely-independent subset of
        // exactly ambientDimension points: start from vs.head, and add each
        // next candidate only if it strictly increases the rank of the affine
        // span built so far (i.e. isn't already in that span). vs always has
        // at least ambientDimension independent points available, since it's
        // a superset of the already-independent startingSimplex being refined.
        val base = points(vs.head)
        var chosen: Vector[Int] = Vector(vs.head)
        var chosenVecs: Vector[Array[Double]] = Vector.empty
        val remaining = vs.tail.iterator
        while chosen.size < ambientDimension && remaining.hasNext do
          val candidate = remaining.next()
          val trialVecs = chosenVecs :+ points(candidate).subtract(base).toArray
          val rank = new SingularValueDecomposition(MatrixUtils.createRealMatrix(trialVecs.toArray)).getRank
          if rank > chosenVecs.size then
            chosen = chosen :+ candidate
            chosenVecs = trialVecs
        chosen.toSet

    // brute force search for first delaunay simplex
    var done = false
    for pi <- points.indices do
      if !done then
        if !startingSimplex.contains(pi) then
          val circumsphere = Hypersphere((startingSimplex + pi).map(p => points(p)).toSeq)
          val containedPoints = points.indices.toSet.filter(qi => circumsphere.contains(points(qi)))
          if containedPoints.isEmpty then
            done = true
            validated.add(DelaunaySimplex(Simplex.from((startingSimplex + pi).toSeq), circumsphere))
    assert(validated.nonEmpty)

    visitedFacets.add(Simplex.from(startingSimplex.toSeq))

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
            // check whether we have "too many" cospherical points; in that case we have to tile them on our own
            val spherepoints: mutable.SortedSet[Int] = points.indices
              .map(i => (i, newDelaunaySimplex.circumsphere.center.getDistance(points(i))))
              .filter((i, d) => math.abs(d - newDelaunaySimplex.circumsphere.radius) <= epsilon.epsilon)
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

    validated.toSet

/** Based on https://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=10917453&tag=1
  *
  * '''Accepted limitation''': near-cospherical clusters make the frontier walk order-dependent (~1/170 at ambient
  * dimension 4, 20-30 points) -- a real fix needs joint near-tie detection, not attempted here. So Helix is not
  * reliable ground truth for dimension >= 4 fuzzing (`AlphaCrossValidationSpec`'s comparisons stay as
  * `unsafeCompare`/`unsafeFuzzCompare` diagnostics, not wired into `sbt test`). `seed` makes a given `(pts, seed)` pair
  * deterministic, but does not remove this order-dependency -- see `HelixDelaunayBuilder`'s own doc.
  *
  * @param pts
  *   the input points to triangulate
  * @param seed
  *   seeds the bootstrap frontier-selection shuffle (`HelixDelaunayBuilder`); same `pts` and `seed` always produce the
  *   same triangulation.
  */
class HelixDelaunay(pts: Array[Array[Double]], seed: Long = 0L)(using epsilon: Epsilon) extends AlphaShapes:
  private val builder = HelixDelaunayBuilder(pts, seed)
  val points: Seq[Point] = builder.points
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(pts)
  val ambientDimension: Int = builder.ambientDimension
  val validated: Set[DelaunaySimplex] = builder.compute()

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

  // Must be the exact reverse of filtrationOrdering below, not merely "ascending by filtrationValue" --
  // sortBy(filtrationValue) alone has no explicit tie-break (falls back to simplicesMap's own insertion
  // order among ties), which doesn't match filtrationOrdering's simplexOrdering[Int] tie-break. This
  // passes VietorisRipsSpec-style sortedness checks (value-only) but breaks PersistenceInChunksContext,
  // whose chunk-boundary logic (Homology.scala's PersistenceInChunksContext.allCells) relies on
  // iterateDimension's own emission order standing in for filtrationOrdering position -- found via
  // EngineComparisonBenchmarkSpec / AlphaFiltrationOrderingRegressionSpec (see CLAUDE.md).
  val simplicesSortedMap: Map[Int, Seq[Simplex[Int]]] =
    simplicesMap.map((d, v) => (d, v.sorted(using filtrationOrdering.reverse)))

  def simplicesInDimension(d: Int): Iterator[Simplex[Int]] = simplicesSortedMap(d).iterator

  def simplices(): Iterator[Simplex[Int]] = (0 to ambientDimension).iterator.flatMap(simplicesInDimension)

  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d if simplicesSortedMap.contains(d) => simplicesSortedMap(d).iterator
  }

  // The shared FiltrationOrdering.canonical shape: fv reversed, then dimension, then simplexOrdering.
  // `simplicesSortedMap` (above) is built as `.sorted(using filtrationOrdering.reverse)` specifically so it
  // stays the exact reverse of this ordering, tie-break included -- see RecursiveStackVietorisRipsSimplexStream's
  // identical fix (VietorisRips.scala) and CLAUDE.md for the full root-cause writeup. This used to have no
  // dimension key at all (`Ordering.by(filtrationValue).reverse.orElse(simplexOrdering[Int])`), which is wrong
  // for the cross-dimension comparisons `Chain.reduceBy` performs during reduction (see the identical fix's own
  // comment). Kept a `def`, not a `val`: `simplicesSortedMap` above uses it during construction, before a `val`
  // declared this late in the class body would be initialized (see CLAUDE.md's `ExplicitStreamBuilder` NPE note
  // for the general hazard).
  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FiltrationOrdering.canonical(filtrationValue, _.size, simplexOrdering[Int])

/** Hides every simplex of dimension `> maxDim` from `helix` -- the `HelixDelaunay` analogue of
  * `streams.LimitedCubicalGridStream` (itself needed because `streams.LimitedCofaceSimplexStream` is hardcoded to
  * `CofaceSimplexStream[Int, Double]`, which `AlphaShapes`/`HelixDelaunay` is not -- it's the smaller
  * `StratifiedSimplexStream[Int, Double]`, with no `currentDimension`/`keepCriterion`/etc. to forward). Used by
  * `homology.FastAlphaHomologyContext`'s own `d >= 3` path (`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`)
  * to hand `CellularPersistenceInChunksContext` a view of the triangulation that never contains a real top-dimensional
  * simplex, so that engine's own general `Chain` reduction never touches them -- the whole point being to let the
  * (cheaper) dual union-find handle the top dimension instead.
  *
  * Delegates `filtrationOrdering`/`filtrationValue` to `helix` unchanged (removing higher-dimensional simplices from
  * the DOMAIN doesn't change either), and preserves `StratifiedCellStream.iterator`'s own contiguous-from-0 contract
  * for free: truncating a contiguous `0..helix.ambientDimension` domain to `0..maxDim` is still contiguous from 0.
  */
class LimitedAlphaShapesStream(helix: HelixDelaunay, maxDim: Int)
    extends StratifiedSimplexStream[Int, Double]
    with DoubleFiltration[Simplex[Int]]():
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case d: Int if d >= 0 && d <= maxDim && helix.iterateDimension.isDefinedAt(d) => helix.iterateDimension(d)
  }
  override def filtrationOrdering: Ordering[Simplex[Int]] = helix.filtrationOrdering
  override def filtrationValue: PartialFunction[Simplex[Int], Double] = helix.filtrationValue
