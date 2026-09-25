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

  /** @param requireValidTriangulation
    *   OFF by default. Only meaningful for the `"helix"`/`"default"` backend -- threaded straight through to
    *   `HelixDelaunay`'s own constructor parameter of the same name (`.claude/DESIGN-helix-triangulation-repair.md`).
    *   `require`d `false` for `dispatch="dqp"`: `AlphaShapeDQP` has no facet-multiplicity precondition to repair in the
    *   first place (it is not `FastAlphaHomologyContext`'s own backend), so a caller passing `true` there almost
    *   certainly mis-set the option rather than intending a silent no-op.
    */
  def apply(pts: Seq[Array[Double]], dispatch: String = "default", requireValidTriangulation: Boolean = false)(using
    epsilon: Epsilon = Epsilon(1e-5)
  ): AlphaShapes =
    dispatch.toLowerCase match
      case "default" =>
        // These three branches all resolve to "helix" today: no regime (point count / ambient dimension) has been
        // measured yet to pick a backend by. They're placeholders for that dispatch, not dead code -- keep them
        // distinct rather than collapsing to a single case.
        pts match
          case pts if pts.isEmpty         => apply(pts, dispatch = "helix", requireValidTriangulation)
          case pts if pts.head.length > 7 => apply(pts, dispatch = "helix", requireValidTriangulation)
          case _                          => apply(pts, dispatch = "helix", requireValidTriangulation)
      case "helix" =>
        HelixDelaunay(
          pts.toArray,
          requireValidTriangulation = requireValidTriangulation
        ) // Helix should be faster for dim: 7 - 17. Adjust this check when additional impl exists.
      case "dqp" =>
        require(
          !requireValidTriangulation,
          "requireValidTriangulation=true is not valid for dispatch=\"dqp\": AlphaShapeDQP has no facet-" +
            "multiplicity precondition to repair (FastAlphaHomologyContext is specialized to HelixDelaunay's own " +
            "triangulation and never consumes AlphaShapeDQP's output) -- this option would be a silent no-op there."
        )
        AlphaShapeDQP(pts.toArray)
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

/** Rank of `matrix`'s own row space, at THIS codebase's own `epsilon` tolerance -- not `SingularValueDecomposition`'s
  * default `getRank`, whose internal tolerance (machine-epsilon-scale, relative to the matrix's own norm) is far
  * tighter than `epsilon.epsilon` (typically `1e-5`) and so treats near-but-not-exactly-degenerate input (e.g. points
  * jittered at the `1e-7` scale) as full rank when every OTHER degeneracy check in this file -- `Hyperplane. from`'s
  * own `nullSV` filter included -- would already call that same input degenerate. Used everywhere this file needs "how
  * many affinely-independent directions does this set of points actually span," so all of them agree on where the line
  * is.
  */
private def rankAtEpsilon(matrix: org.apache.commons.math3.linear.RealMatrix)(using epsilon: Epsilon): Int =
  new SingularValueDecomposition(matrix).getSingularValues.count(_.abs > epsilon.epsilon)

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
  // Greedily picks `ambientDimension` affinely-independent points from `candidates` (in the given order), by
  // rank -- the SAME technique already used below for refining an over-sized `vs`, applied here to the hull-
  // hyperplane-search loop's OWN candidate simplex too. This matters for a genuinely different reason than the
  // `vs` refinement: `Hyperplane.from` assumes its input spans a genuine (ambientDimension-1)-dimensional
  // hyperplane (rank exactly `ambientDimension - 1`). If the `ambientDimension` points handed to it are
  // THEMSELVES more degenerate than that (rank strictly less -- e.g. they lie in some lower-dimensional flat
  // within the ambient space, not just "coplanar" the way a genuine facet's points are), its SVD-based normal-
  // vector extraction is under-determined: there is an entire FAMILY of hyperplanes containing such a point
  // set, not one, and the specific one the code happens to compute can be an arbitrary, non-hull-supporting
  // plane -- one that can have points strictly on BOTH sides of it, yet still spuriously satisfy the loop's own
  // `lightPoints.isEmpty` termination check, converging the whole bootstrap onto a bogus, non-facet
  // `startingSimplex`. Confirmed as a real, distinct root cause (not the same one the `vs`-refinement fix
  // above addresses) via direct reproduction: a real point cloud's 5-point candidate simplex had rank 3, not
  // the required 4, and its resulting "hyperplane" had points with `dist` both well above and well below
  // `epsilon` -- see `.claude/WORKLOG-helix-bootstrap-fix.md`.
  private def affinelyIndependentPick(candidates: Seq[Int]): Set[Int] =
    val base = points(candidates.head)
    var chosen: Vector[Int] = Vector(candidates.head)
    var chosenVecs: Vector[Array[Double]] = Vector.empty
    val remaining = candidates.tail.iterator
    while chosen.size < ambientDimension && remaining.hasNext do
      val candidate = remaining.next()
      val trialVecs = chosenVecs :+ points(candidate).subtract(base).toArray
      val rank = rankAtEpsilon(MatrixUtils.createRealMatrix(trialVecs.toArray))
      if rank > chosenVecs.size then
        chosen = chosen :+ candidate
        chosenVecs = trialVecs
    chosen.toSet

  def compute(): Set[DelaunaySimplex] =
    var startingSimplex: Set[Int] = affinelyIndependentPick(points.indices)
    var convexHullHP: Boolean = false
    while !convexHullHP do
      val currentHP: Hyperplane = Hyperplane.from(startingSimplex.map(p => points(p)).toSeq)
      val lightPoints: Set[Int] =
        (points.indices.toSet -- startingSimplex).filter(pi => currentHP.dist(points(pi)) > epsilon.epsilon)
      if lightPoints.isEmpty then convexHullHP = true
      else startingSimplex = affinelyIndependentPick((lightPoints ++ startingSimplex).toSeq.pipe(rng.shuffle))
    val vs = points.indices.filter(pi =>
      Hyperplane.from(startingSimplex.map(points).toSeq).dist(points(pi)).abs < epsilon.epsilon
    )

    // vs has more points on the supporting hyperplane than needed for a non-degenerate
    // (ambientDimension-1)-simplex within it -- common for grid-like or otherwise partly-degenerate point
    // clouds. Picking just 2 of them regardless of ambientDimension leaves startingSimplex undersized for
    // ambientDimension > 2, starving the brute-force bootstrap search below of a well-posed circumsphere
    // (Hypersphere needs ambientDimension+1 points to be uniquely determined) and making it fail to find any
    // candidate. So instead, greedily grow an affinely-independent subset of exactly ambientDimension points,
    // rooted at `base`: start from `base`, and add each next candidate (by increasing distance from `base`,
    // NOT raw point-index order) only if it strictly increases the rank of the affine span built so far.
    //
    // Distance-based ordering matters, not just correctness of the final rank: a candidate subset that spans a
    // "long" edge/facet skipping over some OTHER vs point actually lying BETWEEN the chosen vertices dooms the
    // brute-force seed search below regardless of which third point it tries next -- that skipped-over point
    // ends up unconditionally inside every candidate circumsphere (a direct geometric fact: for any two points
    // p, q and a third point r on the segment between them, r's distance to the center of ANY circle through p
    // and q is always strictly less than that circle's own radius, since r sits on the foot of the
    // perpendicular from the center to the chord p-q). Confirmed as a real root cause via a grid-point
    // reproduction (a plain 3x3 integer grid, zero jitter: `vs = {(0,0),(1,0),(2,0)}`, and picking
    // `{(0,0),(2,0)}` skips over `(1,0)`, which then sits inside every circumcircle through the chosen pair
    // and any third grid point) -- see `.claude/WORKLOG-helix-bootstrap-fix.md`.
    //
    // A single greedy pass rooted at one fixed base (`vs.head`) is not a complete fix at higher ambient
    // dimension: nearest-to-ONE-base doesn't guarantee no OTHER vs point ends up inside the resulting
    // higher-dimensional simplex's own affine hull. Measured directly, not assumed, across three successive
    // attempts: the raw index-order pick failed a targeted grid-heavy stress sweep at 2226/30000; sorting one
    // greedy pass by distance from `vs.head` cut that to 744/30000 (fixed the 2D case outright, not the higher-
    // dimensional one); trying a nearest-neighbor candidate rooted at every vs point in turn cut it further to
    // 70/30000, but still not zero. So this instead enumerates every affinely-independent `ambientDimension`-
    // subset of `vs` (bounded -- `vs` is typically a handful of points sharing one exact hyperplane even in the
    // pathological cases that trigger this branch at all; capped at `maxCandidates` as a safety valve against a
    // pathological `vs` this reasoning doesn't anticipate), ordered by increasing total pairwise span so the
    // candidates least likely to skip over an interior point are tried first, and the brute-force search below
    // retries across ALL of them (not just the first) until one succeeds.
    val maxCandidates = 20000
    val candidateStartingSimplices: Seq[Set[Int]] =
      if vs.size == ambientDimension then Seq(vs.toSet)
      else
        vs
          .combinations(ambientDimension)
          .filter { combo =>
            val base = points(combo.head)
            val vecs = combo.tail.map(pi => points(pi).subtract(base).toArray)
            rankAtEpsilon(MatrixUtils.createRealMatrix(vecs.toArray)) == ambientDimension - 1
          }
          .take(maxCandidates)
          .map(_.toSet)
          .toSeq
          .sortBy(combo =>
            (for
              i <- combo.toSeq.indices
              j <- (i + 1) until combo.size
            yield points(combo.toSeq(i)).getDistance(points(combo.toSeq(j)))).sum
          )

    // brute force search for first delaunay simplex -- retried across every candidate starting simplex above,
    // not just the first, stopping at the first (candidate, third point) pair whose circumsphere is empty.
    var done = false
    val candidateIter = candidateStartingSimplices.iterator
    while !done && candidateIter.hasNext do
      val candidate = candidateIter.next()
      for pi <- points.indices do
        if !done then
          if !candidate.contains(pi) then
            val circumsphere = Hypersphere((candidate + pi).map(p => points(p)).toSeq)
            val containedPoints = points.indices.toSet.filter(qi => circumsphere.contains(points(qi)))
            if containedPoints.isEmpty then
              done = true
              startingSimplex = candidate
              validated.add(DelaunaySimplex(Simplex.from((candidate + pi).toSeq), circumsphere))
    assert(
      validated.nonEmpty,
      s"HelixDelaunayBuilder: no empty-circumsphere seed simplex found across ${candidateStartingSimplices.size} " +
        s"candidate starting simplices (hull-supporting hyperplane has ${vs.size} coincident points) -- this is a " +
        "genuine construction failure, not user error; please report it with the exact point cloud, per " +
        ".claude/WORKLOG-helix-bootstrap-fix.md."
    )

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
  * @param requireValidTriangulation
  *   OFF by default -- changes nothing above when `false`. When `true`, and `compute()` produces a facet- multiplicity
  *   violation (`FastAlphaHomologyContext`'s own precondition), runs `HelixDelaunay.repairByJitterRetriangulation`
  *   (`.claude/DESIGN-helix-triangulation-repair.md`): a "simulation of simplicity"-style repair that nudges exactly
  *   the offending, near-tied points by a tiny random perturbation and re-runs this SAME `HelixDelaunayBuilder` on the
  *   full (mostly unperturbed) point set, then recomputes every resulting simplex's own circumsphere from the ORIGINAL,
  *   un-nudged coordinates so the perturbation never leaks into a real filtration value -- only into the combinatorial
  *   tie-break, plus a direct check that the repaired result has no interior gap (see that method's own doc for why the
  *   facet-count check alone was found insufficient). Two other designs (discarding the conflicting region and
  *   re-filling it via coning from an arbitrary apex; discarding the extra claimants outright with no replacement) were
  *   tried first and rejected after being checked against the actual failing fixture -- see the design note's own
  *   "First"/"Second design attempt (rejected)" sections. Validated by targeted stress sweep at `d=2` and `d=3`
  *   (`FastAlphaHomologyContext`'s own primary use case); `d>=4` is untested -- `HelixDelaunayBuilder` itself is
  *   already "not reliable ground truth" there for unrelated reasons (this class's own doc above), so this repair
  *   inherits that pre-existing limitation rather than introducing a new one.
  */
class HelixDelaunay(pts: Array[Array[Double]], seed: Long = 0L, requireValidTriangulation: Boolean = false)(using
  epsilon: Epsilon
) extends AlphaShapes:
  // If `pts` is globally coplanar -- its own affine rank is strictly less than the declared ambient dimension
  // (the array width) -- there is no genuine full-ambient-dimensional Delaunay simplex to find at all: every
  // point lies in some lower-dimensional flat, so the bootstrap's search for a supporting hyperplane plus one
  // more "inside" point can never succeed no matter which candidate subset it tries (a structurally different
  // failure from a merely locally-bad subset choice -- no amount of subset selection fixes it). Rather than let
  // that surface as a crash, project onto an orthonormal basis of the point set's own actual affine span first
  // and run the whole construction there -- a no-op (identity, modulo re-centering) whenever the input already
  // has full rank, and exact (not approximate) when it doesn't: an orthogonal projection onto the affine span
  // containing every point changes no pairwise Euclidean distance among them, so the resulting triangulation is
  // the genuine Delaunay triangulation of the true (lower-dimensional) point configuration, not an approximation
  // of it. `ambientDimension` (below, via `builder`) then correctly reflects the data's own true dimensionality
  // -- e.g. 2D points accidentally stored with a spurious constant third coordinate behave exactly like a 2D
  // alpha complex, not a degenerate "3D" one. See `.claude/WORKLOG-helix-bootstrap-fix.md`.
  private val reducedPts: Array[Array[Double]] = HelixDelaunay.projectToAffineRank(pts)
  private val builder = HelixDelaunayBuilder(reducedPts, seed)
  val points: Seq[Point] = builder.points
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(reducedPts)
  val ambientDimension: Int = builder.ambientDimension
  val validated: Set[DelaunaySimplex] =
    val raw = builder.compute()
    if requireValidTriangulation then HelixDelaunay.repairByJitterRetriangulation(pts, raw, seed) else raw

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

object HelixDelaunay:

  /** If `pts` is globally coplanar -- its own affine rank (via SVD of the points centered at `pts.head`) is strictly
    * less than the declared ambient dimension (`pts.head.length`) -- re-expresses every point in an orthonormal basis
    * of that actual affine span, dropping the genuinely-unused extra coordinates; returns `pts` completely unchanged
    * (not even re-centered) when the input already has full rank, so this is a no-op for every ordinary, non-degenerate
    * point cloud. An orthogonal projection onto the affine span containing every input point preserves every pairwise
    * Euclidean distance among them EXACTLY (nothing is discarded that any of the points actually extend into), so the
    * returned points' own Delaunay triangulation is the genuine one for the true (lower-dimensional) point
    * configuration, not an approximation -- see this class's own constructor doc and
    * `.claude/WORKLOG-helix-bootstrap-fix.md`.
    */
  private def projectToAffineRank(pts: Array[Array[Double]])(using epsilon: Epsilon): Array[Array[Double]] =
    if pts.length < 2 then pts
    else
      val dim = pts.head.length
      val base = pts.head
      val centered = pts.map(p => p.zip(base).map(_ - _))
      val matrix = MatrixUtils.createRealMatrix(centered)
      val rank = rankAtEpsilon(matrix)
      if rank >= dim then pts
      else
        val v = new SingularValueDecomposition(matrix).getV
        centered.map { row =>
          Array.tabulate(rank)(k => (0 until dim).map(j => row(j) * v.getEntry(j, k)).sum)
        }

  private def facetsOf(ds: DelaunaySimplex): Seq[Simplex[Int]] = ds.simplex.toSeq.toSeq.map(v => ds.simplex - v)

  private def facetToSimplices(simps: Set[DelaunaySimplex]): Map[Simplex[Int], Vector[DelaunaySimplex]] =
    simps.toVector
      .flatMap(ds => facetsOf(ds).map(f => (f, ds)))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toVector)
      .toMap

  private def badFacetsOf(simps: Set[DelaunaySimplex]): Map[Simplex[Int], Vector[DelaunaySimplex]] =
    facetToSimplices(simps).filter { case (_, claimants) => claimants.size > 2 }

  /** A genuine Delaunay triangulation always fully tetrahedralizes its own convex hull -- the hull is convex, hence
    * contractible, so the STATIC (unfiltered, every cell at once) complex's own `H_{d-1}` must be trivial. "Every facet
    * has `<=2` claimants" (`badFacetsOf`) is necessary for that but NOT sufficient: a facet can end up with exactly 1
    * claimant not because it is genuinely on the outer hull, but because the builder's own search, run on jittered
    * coordinates, simply failed to place its second coface -- indistinguishable from a real hull facet by the local
    * claimant-count check alone, but detectable by this global one. Confirmed empirically, not assumed: a real failing
    * case measured a 33% total-tetrahedra-volume shortfall against the (violation-inflated) unrepaired triangulation,
    * alongside exactly one spurious essential `H_2` bar -- direct evidence of a genuine gap, not a
    * structurally-clean-but-topologically-wrong result (`.claude/DESIGN-helix-triangulation-repair.md`).
    */
  private def hasNoInteriorVoid(simps: Set[DelaunaySimplex], ambientDimension: Int): Boolean =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val builder = ExplicitStreamBuilder[Int, Double]()
    simps
      .flatMap(ds => ds.simplex.toSet.subsets().filter(_.nonEmpty).map(s => Simplex.from(s.toSeq)))
      .foreach(s => builder.addOne((0.0, s)))
    val bars = SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(builder.result())
      .diagramAt(Double.PositiveInfinity)
    !bars.exists { case (dim, _, death) => dim == ambientDimension - 1 && death.isInfinite }

  /** The `requireValidTriangulation = true` repair pass (`.claude/DESIGN-helix-triangulation-repair.md`) -- strictly a
    * post-processing step over an already-`compute()`d result. Rather than trying to hand-patch the conflicting region
    * (both a coning re-fill and a discard-without-replacement prune were tried and rejected -- see the design note),
    * this reruns `HelixDelaunayBuilder` -- the SAME already-tested global algorithm -- on the full point set with only
    * the vertices actually involved in a violation nudged by a small random perturbation, so it never needs to manually
    * reconstruct or "glue" a local patch: the builder's own global frontier walk does that implicitly, correctly,
    * exactly as it does for any other input. The perturbation is discarded once it has done its job of breaking the
    * combinatorial tie -- every simplex in the final result gets its own circumsphere recomputed from the ORIGINAL,
    * un-nudged coordinates, so no filtration value is ever contaminated by jitter (the classic "simulation of
    * simplicity" discipline: perturb only to choose a combinatorial structure, then discard the perturbation for every
    * numeric output).
    *
    * Retries with a fresh seed (and a widened jitter set, folding in any newly-implicated vertices) if a retry still
    * has EITHER problem -- rare, but not assumed away: only a direct re-check, not the fix's own optimism, decides
    * success. Gives up after `maxAttempts` and throws a named, actionable exception rather than ever returning an
    * unrepaired or partially-repaired result silently.
    *
    * '''`hasNoInteriorVoid` exists because the facet-count check alone was measured to be insufficient''' -- an earlier
    * version of this repair, checking only "no facet has `>2` claimants," passed its own self-check but had a real
    * ~10.5% barcode-disagreement rate against the naive engine at `d=3` (656/6272 hit violations in a stress sweep).
    * Root-caused, not just patched around: `HelixDelaunayBuilder`, re-run on jittered coordinates, can silently fail to
    * place a tetrahedron's second coface, leaving a facet with exactly 1 claimant that looks like an ordinary hull
    * facet but is actually a gap -- and a genuine Delaunay triangulation can never have a real interior gap (the convex
    * hull is convex, hence contractible, so `H_{d-1}` of the complete, unfiltered triangulation must be trivial; a
    * nonzero `H_{d-1}` is not a legitimate feature there, it is direct evidence of a missed simplex). Confirmed on the
    * actual failing case before implementing the fix: the repaired complex's own total tetrahedra volume was measurably
    * short (a real ~33% shortfall) and its naive-engine barcode carried exactly one spurious essential `H_2` bar that
    * the facet-count check could not see. Adding `hasNoInteriorVoid` to the retry condition (widening the jitter set to
    * the result's own boundary vertices on failure) resolved it completely: re-running the same two stress sweeps with
    * the added check found ZERO barcode disagreements across 316 hit violations at `d=2` and 6272 at `d=3` (20000
    * trials each, near-cospherical point clouds, the SAME `d=3` sweep that previously found 656 disagreements) -- see
    * `.claude/DESIGN-helix-triangulation-repair.md`.
    */
  private def repairByJitterRetriangulation(
    pts: Array[Array[Double]],
    validatedIn: Set[DelaunaySimplex],
    seed: Long
  )(using epsilon: Epsilon): Set[DelaunaySimplex] =
    val initialBad = badFacetsOf(validatedIn)
    if initialBad.isEmpty then validatedIn
    else
      var jitterVertices: Set[Int] = initialBad.values.flatten.toSet.flatMap(_.simplex.toSet)
      var result: Option[Set[DelaunaySimplex]] = None
      var attempt = 0
      val maxAttempts = 8
      while result.isEmpty && attempt < maxAttempts do
        val localSeed = seed * 1000003L + attempt + 1
        val rng = new Random(localSeed)
        val jitterPoints = jitterVertices.toVector.map(i => Point(pts(i)))
        val minPairwiseSpacing =
          if jitterPoints.size < 2 then 1.0
          else
            (for
              i <- jitterPoints.indices
              j <- (i + 1) until jitterPoints.size
            yield jitterPoints(i).getDistance(jitterPoints(j))).min
        val jitterMagnitude = math.max(epsilon.epsilon * 100, minPairwiseSpacing * 1e-6)
        val perturbedPts: Array[Array[Double]] = pts.zipWithIndex.map { case (p, i) =>
          if jitterVertices.contains(i) then p.map(_ + (rng.nextDouble() - 0.5) * 2 * jitterMagnitude)
          else p
        }

        val perturbedValidated = HelixDelaunayBuilder(perturbedPts, localSeed).compute()
        val realized: Set[DelaunaySimplex] = perturbedValidated.map { ds =>
          DelaunaySimplex(ds.simplex, Hypersphere(ds.simplex.toSeq.toSeq.map(v => Point(pts(v)))))
        }
        val stillBad = badFacetsOf(realized)
        if stillBad.isEmpty && hasNoInteriorVoid(realized, pts.head.length) then result = Some(realized)
        else
          val extraJitterVertices =
            if stillBad.nonEmpty then stillBad.values.flatten.toSet.flatMap(_.simplex.toSet)
            else
              // No facet-multiplicity violation remains, but the void check caught a gap: the builder silently
              // failed to place some tetrahedron's second coface. The gap's own location isn't directly known,
              // so widen to every vertex touching the result's current boundary (coface count 1) -- a real hull
              // facet's vertices are harmless to re-jitter, and a spurious one (the actual gap site) is exactly
              // what needs to move to get a fresh chance at the builder placing it correctly.
              facetToSimplices(realized).collect { case (f, cs) if cs.size == 1 => f }.flatMap(_.toSet).toSet
          jitterVertices = jitterVertices ++ extraJitterVertices
        attempt += 1

      result.getOrElse(
        throw new IllegalStateException(
          s"HelixDelaunay.repairByJitterRetriangulation: could not resolve the facet-multiplicity violation " +
            s"after $maxAttempts jitter attempts on vertex set $jitterVertices; this may indicate a genuinely " +
            "higher-order degeneracy this repair pass isn't designed for -- please report it with the exact " +
            "point cloud that triggered this, per .claude/DESIGN-helix-triangulation-repair.md."
        )
      )

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
