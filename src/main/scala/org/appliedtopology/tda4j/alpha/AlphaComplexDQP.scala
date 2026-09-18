package org.appliedtopology.tda4j
package alpha

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/*
 * Mikael Vejdemo-Johansson with Claude Opus 5 at Extra effort
 * Partially refactored with GitHub Copilot
 * Debugged with Claude Sonnet 5
 * ===========================================================================
 *  Weighted alpha complexes via dual active-set quadratic programming
 *
 *  Erik Carlsson & John Carlsson, "Computing the alpha complex using dual
 *  active set quadratic programming", Scientific Reports 14:19824 (2024).
 *  https://doi.org/10.1038/s41598-024-63971-3
 *
 *  Equation numbers in the comments below refer to that paper.
 *
 *  The point of the method is that it never builds the Delaunay complex.
 *  For each candidate simplex it asks a *feasibility* question -- "is this
 *  Voronoi/power face nonempty, and does it meet the ball of radius r?" --
 *  posed as the convex QP (13), and answers it by working in the Lagrangian
 *  dual (10).  Two things follow:
 *
 *    1. Any dual-feasible point gives a lower bound on the primal optimum
 *       (weak duality), so as soon as the running dual objective exceeds the
 *       cutoff c1 the simplex can be discarded without ever solving the QP.
 *       lambda = 0 is always dual feasible, so there is no phase-1 cost.
 *
 *    2. The dual depends on the data only through the Gram matrix B = A A^t.
 *       Ambient dimension therefore enters only when B is assembled, not in
 *       the search itself -- which is why this scales to the 2352-dimensional
 *       example in the paper where Delaunay is hopeless.
 *
 *  The QP solver below is a self-contained dual active-set method in the
 *  style of Goldfarb-Idnani / DAQP (Arnstrom, Bemporad & Axehill, IEEE TAC
 *  67(8):4362-4369, 2022 -- reference [27] of the paper, https://github.com/
 *  darnstrom/daqp).  Our problem (9) is already in DAQP's canonical inner
 *  form: H = I, i.e. a *least-distance problem*, so none of DAQP's
 *  factorization of H is needed and the recursive LDL^T updates reduce to
 *  Cholesky update/downdate of B_W.  That is what CholeskyWorkspace does.
 *
 *  USAGE
 *
 *    val pts: Array[Array[Double]] = ...
 *    val ac  = AlphaComplexDQP.euclidean(pts, maxRadius = 0.35, maxDimension = 3)
 *    ac.sizeByDimension            // the (|X_k|) tuples of the paper
 *    ac.barcodeInput               // (simplex, filtration value), faces first
 *    ac.witness(ac.cells.head)     // the witness map Phi
 *
 *    // weighted / power distance, and a non-coordinate input:
 *    AlphaComplexDQP.weighted(pts, weights, maxPower = 0.12, maxDimension = 3)
 *    AlphaComplexDQP(PowerDistance.fromSquaredDistances(d2), 0.12, 3, AlphaDQPSettings())
 *
 *    // AlphaShapeDQP (what Alpha(pts, "DQP") constructs) always uses
 *    // maxRadius = Infinity to match HelixDelaunay's always-untruncated
 *    // behaviour -- call AlphaComplexDQP.euclidean/.weighted/.apply directly,
 *    // as above, for an actually radius-truncated complex.
 *
 *  CAVEAT ON maxDimension.  In degenerate position the alpha complex is NOT a
 *  triangulation: k cospherical sites share a Voronoi vertex and contribute a
 *  (k-1)-simplex, so a unit grid in the plane produces 3-simplices and a unit
 *  grid in R^3 produces 7-simplices. If you truncate at the ambient dimension
 *  you will get a complex with the wrong homotopy type, not just a truncated
 *  one. Perturb the input or raise maxDimension.
 *
 *  NOTE ON UNITS.  The filtration value returned is the *power*
 *  w(sigma) = ||y - x||^2 - p(x), matching Definition 10.  In the unweighted
 *  case (p = 0) this is the squared circumradius, so the usual alpha/Cech
 *  radius is math.sqrt(w).  See AlphaComplex#radiusOf.
 * ===========================================================================
 */

/** Thrown when the inner QP fails to converge. In practice this means a genuinely near-degenerate configuration (see
  * the `rankTolerance` doc on `AlphaDQPSettings` and `WORKLOG.md` at the repo root for the full derivation) -- not
  * simply "too tight a tolerance", since no fixed tolerance safely eliminates every such case. Loosening
  * `AlphaDQPSettings` may help but isn't guaranteed to; `AlphaComplexDQPBuilder.solveAtVertex` already catches this
  * per-candidate (excluding just that candidate, logged to stderr) so it should only reach a caller from a direct
  * `DualQP.solve` call.
  */
final class AlphaComplexDQPException(message: String) extends RuntimeException(message)

// ===========================================================================
// Input abstraction
// ===========================================================================

/** The data the algorithm needs: a finite set of sites with power weights.
  *
  * Everything in the QP is expressible through *squared distances* alone, because
  *
  * B_ij = (x_i - x)·(x_j - x) = ( d²(i,x) + d²(j,x) - d²(i,j) ) / 2 U_i = ( p(i) - p(x) - d²(i,x) ) / 2
  *
  * so the solver never sees coordinates. Coordinates are optional and used only to materialise the witness map Phi
  * (Definition 11).
  *
  * Caveat: the QP is convex only if B is positive semidefinite, i.e. only if the squared distances come from a
  * Euclidean embedding. Feeding in a non-Euclidean metric (as Vietoris-Rips happily allows) is not supported; the
  * notion of "alpha complex" is not defined there either.
  */
trait PowerDistance:
  pd =>
  def size: Int

  /** Squared distance between sites i and j. */
  def squaredDistance(i: Int, j: Int): Double

  /** The power weight p(x_i) of Eq. (6). Default 0 gives the ordinary (unweighted) alpha complex. Note the sign
    * convention: pi_i(y) = ||y - x_i||^2 - p(i), so a *larger* weight is a *larger* ball.
    */
  def weight(i: Int): Double = 0.0

  /** Ambient dimension if the sites carry coordinates, otherwise -1. Used to bound the working set size and to build
    * the witness map.
    */
  def ambientDimension: Int = -1

  def hasCoordinates: Boolean = ambientDimension >= 0

  /** k-th coordinate of site i. Only called when hasCoordinates. */
  def coordinate(i: Int, k: Int): Double =
    throw new UnsupportedOperationException("this PowerDistance has no coordinates")

  /** B_ij for the quadratic program based at x -- Eq. (11) specialised in the paragraph after Eq. (13).
    */
  final def gram(x: Int, i: Int, j: Int): Double =
    0.5 * (squaredDistance(i, x) + squaredDistance(j, x) - squaredDistance(i, j))

  /** U_i for the quadratic program based at x, same paragraph. */
  final def dualLinear(x: Int, i: Int): Double =
    0.5 * (weight(i) - weight(x) - squaredDistance(i, x))

  /** Radius of the weighted ball U_i at power level a (Definition 7); negative if U_i is empty.
    */
  final def ballRadius(i: Int, a: Double): Double =
    val t = a + weight(i)
    if t < 0.0 then -1.0 else math.sqrt(t)

  def toMetricSpace: FiniteMetricSpace[Int] = new FiniteMetricSpace[Int]:
    override def distance(x: Int, y: Int): Double = math.sqrt(squaredDistance(x, y))
    override def size: Int = pd.size
    override def elements: Iterable[Int] = Range(0, size)
    override def contains(x: Int): Boolean = x >= 0 && x < size
end PowerDistance

object PowerDistance:

  /** Sites given by coordinates; squared distances computed on demand. Memory O(N·m), time O(m) per squared distance.
    */
  def euclidean(points: Array[Array[Double]], weights: Array[Double] = null): PowerDistance =
    require(points.nonEmpty, "empty point set")
    val m = points(0).length
    require(points.forall(_.length == m), "ragged coordinate array")
    require(weights == null || weights.length == points.length, "weights/points length mismatch")
    new PowerDistance:
      def size: Int = points.length
      override def ambientDimension: Int = m
      override def coordinate(i: Int, k: Int): Double = points(i)(k)
      override def weight(i: Int): Double = if weights == null then 0.0 else weights(i)
      def squaredDistance(i: Int, j: Int): Double =
        points(i)
          .zip(points(j))
          .map((a, b) => a - b)
          .map(d => d * d)
          .sum

  /** Same, but caches the full N×N squared-distance matrix. Worth it when the ambient dimension is large and N is
    * moderate (the paper's 24- and 2352-dimensional examples); O(N²) memory.
    */
  def cached(base: PowerDistance): PowerDistance =
    val n = base.size
    val d2 = new Array[Double](n * n)
    var i = 0
    while i < n do
      var j = i + 1
      while j < n do
        val v = base.squaredDistance(i, j)
        d2(i * n + j) = v
        d2(j * n + i) = v
        j += 1
      i += 1
    new PowerDistance:
      def size: Int = n
      override def ambientDimension: Int = base.ambientDimension
      override def coordinate(i: Int, k: Int): Double = base.coordinate(i, k)
      override def weight(i: Int): Double = base.weight(i)
      def squaredDistance(i: Int, j: Int): Double = d2(i * n + j)

  /** Sites given by an explicit squared-distance matrix (no coordinates: the witness map will be unavailable,
    * everything else works).
    */
  def fromSquaredDistances(d2: Array[Array[Double]], weights: Array[Double] = null): PowerDistance =
    new PowerDistance:
      def size: Int = d2.length
      override def weight(i: Int): Double = if weights == null then 0.0 else weights(i)
      def squaredDistance(i: Int, j: Int): Double = d2(i)(j)

end PowerDistance

// ===========================================================================
// Tolerances and options
// ===========================================================================

/** All tolerances are *relative*; absolute ones silently break when the point cloud is rescaled.
  *
  * @param rankTolerance
  *   a candidate constraint is treated as linearly dependent on the working set when its Schur complement drops below
  *   this fraction of B_jj. 1e-12 is too tight to be safe: a commit can clear that bar while still leaving a Cholesky
  *   pivot many orders of magnitude below the problem's natural scale, which silently poisons the factorisation and
  *   manifests later as either non-convergence (degenerate cycling between near-duplicate working sets) or, worse, a
  *   garbage-but-finite answer. 1e-8 (the textbook sqrt-of-machine-epsilon rule of thumb) is *still* not always enough
  *   margin in practice -- found a case where the poisoning commit's ratio was 1.03e-8, clearing that bar by a hair.
  *   1e-6 sits in the middle of the empirically-verified safe range [1e-7, 1e-5]: 1e-4 starts rejecting genuinely
  *   non-degenerate directions and gives a different (wrong) answer.
  * @param feasibilityTolerance
  *   a constraint counts as violated when its slack exceeds this fraction of max|U|.
  * @param zeroTolerance
  *   threshold on multipliers in the ratio tests.
  * @param equalityTolerance
  *   consistency threshold for dependent equality constraints (relative to max|U|).
  * @param maxIterationsPerQP
  *   iteration cap per QP; 0 means 20n + 200.
  * @param workingSetCapacity
  *   max active set size; 0 means "ambient dimension + 2", which is a hard bound since the active rows live in R^m.
  *   Raise it only if you hit the corresponding exception.
  * @param enforceMonotonicity
  *   clamp each w(sigma) up to the max over its facets. Mathematically a no-op (see below) but removes ~1e-16
  *   violations that would upset a persistence algorithm.
  * @param parallel
  *   run the per-vertex loop on the common ForkJoinPool. Output is deterministic.
  */
final case class AlphaDQPSettings(
  rankTolerance: Double = 1e-6,
  feasibilityTolerance: Double = 1e-12,
  zeroTolerance: Double = 1e-12,
  equalityTolerance: Double = 1e-9,
  maxIterationsPerQP: Int = 0,
  workingSetCapacity: Int = 0,
  enforceMonotonicity: Boolean = true,
  parallel: Boolean = false
)

// ===========================================================================
// Cholesky workspace: B_W = L L^t with append / delete
// ===========================================================================

/** Maintains a Cholesky factor of B_W for the current working set W.
  *
  * The working set is kept linearly independent, so B_W stays positive definite and a plain Cholesky suffices; DAQP
  * uses LDL^T so that it can *represent* the singular step, we instead detect it via the Schur complement and
  * restructure the working set immediately (see DualQP).
  *
  * Appending is the standard bordered update; deleting removes a row of L and rotates the resulting extra superdiagonal
  * away with Givens rotations, which is O(|W|²) rather than a refactorisation.
  */
final class CholeskyWorkspace(capacity: Int):
  private val cap = math.max(capacity, 1)
  private val l = new Array[Double](cap * cap)
  private var nw = 0

  def size: Int = nw
  def isFull: Boolean = nw >= cap
  def clear(): Unit = nw = 0

  /** Solve L z = rhs. Aliasing rhs eq out is allowed. */
  def solveLower(rhs: Array[Double], out: Array[Double]): Unit =
    var i = 0
    while i < nw do
      var s = rhs(i)
      var j = 0
      while j < i do
        s -= l(i * cap + j) * out(j)
        j += 1
      out(i) = s / l(i * cap + i)
      i += 1

  /** Solve L^t w = rhs. Aliasing rhs eq out is allowed. */
  def solveUpper(rhs: Array[Double], out: Array[Double]): Unit =
    var i = nw - 1
    while i >= 0 do
      var s = rhs(i)
      var j = i + 1
      while j < nw do
        s -= l(j * cap + i) * out(j)
        j += 1
      out(i) = s / l(i * cap + i)
      i -= 1

  /** Solve B_W v = rhs. */
  def solve(rhs: Array[Double], out: Array[Double]): Unit =
    solveLower(rhs, out)
    solveUpper(out, out)

  /** Given bcol = B[W, j] and beta = B[j,j], write lOut = L^{-1} bcol and return the Schur complement s = beta -
    * lOut·lOut.
    *
    * s is exactly the squared norm of the component of A_j orthogonal to the rows already in W, so s <= 0 means linear
    * dependence. Nothing is mutated -- call commit to actually extend the factor.
    */
  def schurComplement(bcol: Array[Double], beta: Double, lOut: Array[Double]): Double =
    solveLower(bcol, lOut)
    var s = beta
    var i = 0
    while i < nw do
      s -= lOut(i) * lOut(i)
      i += 1
    s

  /** Commit the column computed by schurComplement. */
  def commit(lOut: Array[Double], s: Double): Unit =
    if nw >= cap then throw new AlphaComplexDQPException(s"working set exceeded capacity $cap")
    var k = 0
    while k < nw do
      l(nw * cap + k) = lOut(k)
      k += 1
    l(nw * cap + nw) = math.sqrt(s)
    nw += 1

  /** Remove index k from the working set.
    *
    * Deleting *row* k of L (keeping every column) leaves an (nw-1) x nw matrix R with R R^t equal to B_W with
    * row/column k struck out. R is lower triangular except for one extra superdiagonal below row k, which a sweep of
    * Givens rotations on adjacent column pairs removes.
    */
  def delete(k: Int): Unit =
    var i = k
    while i < nw - 1 do
      var j = 0
      while j <= i + 1 do
        l(i * cap + j) = l((i + 1) * cap + j)
        j += 1
      i += 1
    var c = k
    while c < nw - 1 do
      val a = l(c * cap + c)
      val b = l(c * cap + c + 1)
      val rho = math.hypot(a, b)
      if rho != 0.0 then
        val cs = a / rho
        val sn = b / rho
        var r = c
        while r < nw - 1 do
          val x = l(r * cap + c)
          val y = l(r * cap + c + 1)
          l(r * cap + c) = cs * x + sn * y
          l(r * cap + c + 1) = -sn * x + cs * y
          r += 1
      c += 1
    var r = 0
    while r < nw do
      l(r * cap + nw - 1) = 0.0
      r += 1
    nw -= 1
end CholeskyWorkspace

// ===========================================================================
// The dual quadratic program, Eq. (10)
// ===========================================================================

/** Solves
  *
  * maximise d(lambda) = -1/2 lambda^t B lambda + U^t lambda subject to lambda_i >= 0 for i not in J
  *
  * which is the Lagrangian dual of Eq. (9) with H = I. Optimum equals the primal optimum c* = 1/2 ||y* - x||^2; the
  * primal solution is recovered from the KKT conditions (12) as y* = x - sum_i lambda_i (x_i - x).
  *
  * Reused across every candidate simplex sharing the same base vertex x -- only J changes, which is exactly the
  * observation in the paper that lets B and U be assembled once per vertex rather than once per simplex.
  *
  * The instance is stateful and not thread safe; give each worker its own.
  */
final class DualQP(val n: Int, workingSetCapacity: Int, settings: AlphaDQPSettings):
  private val cap = math.max(1, math.min(workingSetCapacity, math.max(n, 1)))
  private val chol = new CholeskyWorkspace(cap)
  private val ws = new Array[Int](cap)
  private val lam = new Array[Double](cap)
  private val lamStar = new Array[Double](cap)
  private val work = new Array[Double](cap)
  private val rvec = new Array[Double](cap)
  private val inW = new Array[Boolean](n)
  private val frozen = new Array[Boolean](n)
  private val grad = new Array[Double](n)

  private var nw = 0
  private var neq = 0

  /** Total active-set iterations, useful for benchmarking against Delaunay. */
  var iterationCount: Long = 0L

  /** Size of the final working set (the "active set" of Fig. 3). */
  def activeSetSize: Int = nw

  /** Index (into 0 until n) of the t-th active constraint. */
  def activeIndex(t: Int): Int = ws(t)

  /** Multiplier of the t-th active constraint. */
  def multiplier(t: Int): Double = lam(t)

  /** @param b
    *   flat n x n Gram matrix, row major
    * @param u
    *   the vector U of Eq. (11)
    * @param eq
    *   indices of the equality constraints, i.e. the vertices of sigma other than the base vertex
    * @param nEq
    *   number of valid entries in eq
    * @param c1
    *   cutoff (a1 + p(x))/2; the solve aborts as soon as the dual objective provably exceeds it
    * @return
    *   c* if c* <= c1, otherwise Double.PositiveInfinity
    */
  def solve(b: Array[Double], u: Array[Double], eq: Array[Int], nEq: Int, c1: Double): Double =
    // Tolerance scale, in units of squared length. Derived from the data, NOT
    // floored at 1: flooring makes these tolerances absolute for point clouds
    // of small diameter and the solver then stops one constraint early.
    var scale = 0.0
    var i = 0
    while i < n do
      val a = math.abs(u(i))
      if a > scale then scale = a
      val d = b(i * n + i)
      if d > scale then scale = d
      i += 1
    if scale <= 0.0 then scale = 1.0
    val gradTol = settings.feasibilityTolerance * scale
    val eqTol = settings.equalityTolerance * scale
    val lamTol = settings.zeroTolerance
    val rankTol = settings.rankTolerance
    val maxIter = if settings.maxIterationsPerQP > 0 then settings.maxIterationsPerQP else 20 * n + 200

    chol.clear()
    nw = 0
    neq = 0
    java.util.Arrays.fill(inW, false)
    java.util.Arrays.fill(frozen, false)
    java.util.Arrays.fill(lam, 0.0)

    // ---- phase 1: the equality constraints -------------------------------
    // These are always active and their multipliers are sign-free, so they
    // are never candidates for the ratio tests below. Geometrically this
    // projects x onto the affine flat where the powers to all vertices of
    // sigma agree, i.e. onto the (weighted) circumcentre of sigma.
    var e = 0
    while e < nEq do
      val q = eq(e)
      var t = 0
      while t < nw do
        work(t) = b(ws(t) * n + q)
        t += 1
      val beta = b(q * n + q)
      val s = chol.schurComplement(work, beta, rvec)
      // If A_q lies in the span of the equalities already present, the system
      // is either inconsistent (empty face: rule the simplex out) or
      // redundant (ignore the constraint).
      if s > rankTol * beta then
        if chol.isFull then throw capacityExceeded()
        chol.commit(rvec, s)
        ws(nw) = q
        inW(q) = true
        nw += 1
        neq += 1
        t = 0
        while t < nw do
          work(t) = u(ws(t))
          t += 1
        chol.solve(work, lam)
      else
        var g = u(q)
        t = 0
        while t < nw do
          g -= b(ws(t) * n + q) * lam(t)
          t += 1
        if math.abs(g) > eqTol then return Double.PositiveInfinity
        frozen(q) = true
      e += 1

    if dualObjective(b, u) > c1 then return Double.PositiveInfinity

    // ---- phase 2: dual active-set iteration on the inequalities ----------
    var iter = 0
    while true do
      iter += 1
      iterationCount += 1
      if iter > maxIter then
        throw new AlphaComplexDQPException(
          s"dual QP failed to converge in $maxIter iterations; try loosening AlphaDQPSettings tolerances"
        )
      // grad_i = U_i - (B lambda)_i = A_i y - V_i, the slack of constraint i.
      // grad_i > 0 means constraint i is violated by the current y, which is
      // the same as saying that raising lambda_i increases the dual.
      var ii = 0
      while ii < n do
        var g = u(ii)
        var t = 0
        while t < nw do
          g -= b(ii * n + ws(t)) * lam(t)
          t += 1
        grad(ii) = g
        ii += 1

      var j = -1
      var best = gradTol
      ii = 0
      while ii < n do
        if !inW(ii) && !frozen(ii) && grad(ii) > best then
          j = ii
          best = grad(ii)
        ii += 1

      // No violated constraint: y is primal feasible and lambda optimal.
      if j < 0 then return dualObjective(b, u)

      var t = 0
      while t < nw do
        work(t) = b(ws(t) * n + j)
        t += 1
      val beta = b(j * n + j)
      var s = chol.schurComplement(work, beta, rvec)

      // When A_j depends on the active rows the dual has no curvature in the
      // direction (lambda_j += t, lambda_W -= t r), r = B_W^{-1} B_{W,j}; it
      // increases linearly at rate grad_j > 0. Either some multiplier blocks,
      // in which case we swap that constraint out for j (the working set stays
      // independent because r_kb != 0), or the dual is unbounded -- which by
      // weak duality means the primal is infeasible: the power face V_sigma is
      // empty and sigma is not a simplex.
      if s <= rankTol * beta then
        chol.solveUpper(rvec, rvec)
        // Anti-cycling (Bland's rule): break ties in the ratio test by the
        // constraint's GLOBAL index ws(t), not its position t in the working
        // set. Working-set position isn't a stable ordering -- constraints
        // get added and dropped continually -- so breaking ties by t instead
        // lets the same pair of global indices repeatedly swap places without
        // ever changing the dual objective, i.e. genuine cycling. A first
        // pass finds the true minimum ratio; a second pass, only entered when
        // some ratio qualified, picks the smallest global index among every
        // ratio within lamTol of that minimum.
        var tmax = Double.PositiveInfinity
        t = neq
        while t < nw do
          if rvec(t) > lamTol then
            val c = lam(t) / rvec(t)
            if c < tmax then tmax = c
          t += 1
        var kb = -1
        if tmax < Double.PositiveInfinity then
          val tieBound = tmax + lamTol * math.max(1.0, tmax)
          t = neq
          while t < nw do
            if rvec(t) > lamTol then
              val c = lam(t) / rvec(t)
              if c <= tieBound && (kb < 0 || ws(t) < ws(kb)) then kb = t
            t += 1
        if kb < 0 then
          // No active inequality blocks this direction (there may not even be
          // one yet -- e.g. right after phase 1, nw == neq and this loop
          // range is empty). In EXACT arithmetic this isn't quite "unbounded"
          // when s > 0: the dual objective along this ray is technically a
          // concave parabola with a small-but-nonzero curvature, with a
          // genuine finite maximum at t* = grad_j / s. BUT tried committing
          // on exactly that basis (see WORKLOG.md) and found it numerically
          // unsound in practice: two counterexamples with near-identical
          // s/beta ratios (~3.4e-7 and ~3.0e-7) went opposite ways -- one
          // legitimate, the other producing |lambda| ~1e5 and genuine
          // non-terminating cycling. There is no fixed ratio that reliably
          // separates "safe to commit" from "will poison the factor" here;
          // it depends on the rest of the working set's conditioning, not
          // just this one Schur complement. So: stay conservative and treat
          // this as infeasible. Known, accepted consequence: a small class of
          // genuinely-valid candidates near this kind of degeneracy get
          // wrongly excluded rather than included (confirmed on one such
          // case: 5 points in R^4, where this excludes the top-dimensional
          // simplex and one of its facets that HelixDelaunay does include) --
          // preferred over the alternative, which is unpredictable cycling.
          return Double.PositiveInfinity
        else
          t = 0
          while t < nw do
            lam(t) -= tmax * rvec(t)
            t += 1
          dropActive(kb)

          t = 0
          while t < nw do
            work(t) = b(ws(t) * n + j)
            t += 1
          s = chol.schurComplement(work, beta, rvec)
          // Cannot happen in exact arithmetic (r_kb != 0 guarantees
          // independence); bail out rather than commit a pivot small enough
          // to risk poisoning the factor (see the kb < 0 case above for why
          // "small but positive" is not safe to treat as committable here).
          if s <= rankTol * beta then return Double.PositiveInfinity
          chol.commit(rvec, s)
          ws(nw) = j
          inW(j) = true
          lam(nw) = tmax
          nw += 1
      else
        if chol.isFull then throw capacityExceeded()
        chol.commit(rvec, s)
        ws(nw) = j
        inW(j) = true
        lam(nw) = 0.0
        nw += 1

      // Solve the equality-constrained subproblem on the enlarged working
      // set. If the solution is not dual feasible, step towards it until a
      // multiplier hits zero and drop that constraint. d is concave and
      // lambda* maximises it over the working-set subspace, so every partial
      // step increases d -- the method is monotone, which is what makes the
      // c1 cutoff below a valid termination test.
      var refining = true
      while refining do
        t = 0
        while t < nw do
          work(t) = u(ws(t))
          t += 1
        chol.solve(work, lamStar)

        // Same anti-cycling tie-break as the singular-step ratio test above:
        // find the true minimum step length first, then break ties by global
        // constraint index rather than working-set position.
        var alpha = 1.0
        t = neq
        while t < nw do
          if lamStar(t) < -lamTol then
            val den = lam(t) - lamStar(t)
            if den > lamTol then
              val c = lam(t) / den
              if c < alpha then alpha = c
          t += 1
        var kb = -1
        if alpha < 1.0 then
          val tieBound = alpha + lamTol * math.max(1.0, alpha)
          t = neq
          while t < nw do
            if lamStar(t) < -lamTol then
              val den = lam(t) - lamStar(t)
              if den > lamTol then
                val c = lam(t) / den
                if c <= tieBound && (kb < 0 || ws(t) < ws(kb)) then kb = t
            t += 1

        if kb < 0 then
          t = 0
          while t < nw do
            lam(t) = lamStar(t)
            t += 1
          refining = false
        else
          t = 0
          while t < nw do
            lam(t) += alpha * (lamStar(t) - lam(t))
            t += 1
          lam(kb) = 0.0
          dropActive(kb)

      // Weak duality: d(lambda) <= c*. This is the early termination of the
      // paper -- most candidate simplices die here, long before the QP would
      // have been solved.
      if dualObjective(b, u) > c1 then return Double.PositiveInfinity
    end while
    Double.NaN // unreachable
  end solve

  private def capacityExceeded(): AlphaComplexDQPException =
    new AlphaComplexDQPException(
      s"active set exceeded capacity $cap; the active rows should span at most the ambient " +
        "dimension, so this indicates severe ill-conditioning. Raise AlphaDQPSettings.workingSetCapacity."
    )

  private def dropActive(k: Int): Unit =
    inW(ws(k)) = false
    chol.delete(k)
    var t = k
    while t < nw - 1 do
      ws(t) = ws(t + 1)
      lam(t) = lam(t + 1)
      t += 1
    lam(nw - 1) = 0.0
    nw -= 1

  private def dualObjective(b: Array[Double], u: Array[Double]): Double =
    var quad = 0.0
    var lin = 0.0
    var a = 0
    while a < nw do
      lin += u(ws(a)) * lam(a)
      val row = ws(a) * n
      var c = 0
      while c < nw do
        quad += lam(a) * b(row + ws(c)) * lam(c)
        c += 1
      a += 1
    -0.5 * quad + lin
end DualQP

// ===========================================================================
// Result
// ===========================================================================

/** A computed weighted alpha complex.
  *
  * Simplices are `Simplex[Int]` of site indices in increasing order. `cells` is in a valid filtration order: faces
  * always precede cofaces.
  */
final class AlphaComplexDQP(
  val space: PowerDistance,
  val maxPower: Double,
  val maxDimension: Int,
  private val cellsByDim: IndexedSeq[mutable.IndexedBuffer[Simplex[Int]]],
  private val weights: mutable.HashMap[Simplex[Int], Double],
  private val witnesses: mutable.HashMap[Simplex[Int], Array[Double]]
):
  def size: Int = cellsByDim.iterator.map(_.size).sum

  def dimension: Int = cellsByDim.lastIndexWhere(_.nonEmpty)

  /** Number of simplices in each dimension -- the (|X_k|) tuples quoted throughout "Examples and applications".
    */
  def sizeByDimension: IndexedSeq[Int] = cellsByDim.map(_.size).toIndexedSeq

  def cellsOfDimension(k: Int): IndexedSeq[Simplex[Int]] =
    if k < 0 || k >= cellsByDim.length then IndexedSeq.empty
    else cellsByDim(k).toIndexedSeq

  def contains(cell: Simplex[Int]): Boolean = weights.contains(cell)

  /** The weight w(sigma) of Definition 10, i.e. the *power* at the witness. Unweighted: the squared circumradius.
    */
  def filtrationValue(cell: Simplex[Int]): Double = weights.getOrElse(cell, 0.0)

  /** The alpha radius. Only equals the circumradius when p = 0. */
  def radiusOf(cell: Simplex[Int]): Double =
    val w = weights.getOrElse(cell, 0.0)
    if w <= 0.0 then 0.0 else math.sqrt(w)

  /** The witness map Phi of Definition 11: the unique minimiser of the power over the face V_sigma. Empty when the
    * input carried no coordinates.
    */
  def witness(cell: Simplex[Int]): Option[Array[Double]] = witnesses.get(cell)

  def eulerCharacteristic: Long =
    cellsByDim.zipWithIndex.foldLeft(0L) { case (chi, (cells, k)) =>
      chi + (if (k % 2) == 0 then cells.size else -cells.size)
    }

  /** Filtration order: increasing weight, breaking ties by dimension so that faces precede cofaces, then
    * lexicographically for determinism.
    */
  lazy val cells: IndexedSeq[Simplex[Int]] =
    cellsByDim.flatten
      .sortBy(c => (weights.getOrElse(c, 0.0), c.size, c.show))

  /** (simplex, filtration value) pairs in filtration order. */
  def barcodeInput: IndexedSeq[(Simplex[Int], Double)] =
    cells.map(c => (c, weights.getOrElse(c, 0.0)))

  override def toString: String =
    s"AlphaComplex(n=${space.size}, a1=$maxPower, sizes=${sizeByDimension.mkString("(", ", ", ")")})"
end AlphaComplexDQP

// ===========================================================================
// The main algorithm -- Algorithm 1 of the paper
// ===========================================================================

object AlphaComplexDQP:

  /** Unweighted alpha complex Alpha(S, r) up to dimension d. Filtration values come back as *squared* radii; see
    * radiusOf.
    */
  def euclidean(
    points: Array[Array[Double]],
    maxRadius: Double,
    maxDimension: Int,
    settings: AlphaDQPSettings = AlphaDQPSettings()
  ): AlphaComplexDQP =
    apply(PowerDistance.euclidean(points), maxRadius * maxRadius, maxDimension, settings)

  /** Weighted alpha complex Alpha(S, p, a1) up to dimension d. */
  def weighted(
    points: Array[Array[Double]],
    powerWeights: Array[Double],
    maxPower: Double,
    maxDimension: Int,
    settings: AlphaDQPSettings = AlphaDQPSettings()
  ): AlphaComplexDQP =
    apply(PowerDistance.euclidean(points, powerWeights), maxPower, maxDimension, settings)

  def apply(
    space: PowerDistance,
    maxPower: Double,
    maxDimension: Int,
    settings: AlphaDQPSettings
  ): AlphaComplexDQP =
    new AlphaComplexDQPBuilder(space, maxPower, maxDimension, settings).compute()

end AlphaComplexDQP

/** Algorithm 1: compute the d-skeleton of Alpha(S, p, a1).
  *
  * Outer loop over dimension, inner loop over base vertex. B and U are built once per (dimension, vertex) rather than
  * once per simplex, which is the only place ambient dimension enters the cost at all.
  */
class AlphaComplexDQPBuilder(
  val space: PowerDistance,
  val maxPower: Double,
  val maxDimension: Int,
  val settings: AlphaDQPSettings = AlphaDQPSettings()
):
  require(maxDimension >= 0, "maxDimension must be nonnegative")

  val n = space.size

  /** One record produced by a single successful QP solve. */
  final case class Found(cell: Simplex[Int], weight: Double, witness: Array[Double])

  /** Line 1-2: the one-skeleton of the Cech complex of the weighted ball cover, Cech(S, p, a1).
    *
    * Only Cech neighbours can constrain the part of V_x that lies inside U_x: if U_x and U_z are disjoint then any y
    * with pi_x(y) <= a1 already satisfies pi_x(y) <= pi_z(y). So restricting the QP's inequality constraints to N_G(x)
    * is exact, not an approximation -- provided the optimum really is <= a1, which is precisely when we keep the
    * simplex.
    *
    * Uses the VP-tree spatial index already in FiniteMetricSpace.scala (`JVPTree`, via `PowerDistance.toMetricSpace`)
    * rather than the naive O(N^2) all-pairs scan, when maxPower is finite -- see WORKLOG.md for why this only helps a
    * genuinely-truncated complex: AlphaShapeDQP's default (maxRadius = Infinity, to match HelixDelaunay) makes every
    * alive point trivially everyone's neighbour, where a spatial index buys nothing, so that case is still handled
    * directly rather than via a VP-tree query with an infinite radius.
    *
    * The per-point query radius radius(i) + maxRadiusOverAll is a *conservative superset* bound, not the exact pairwise
    * one (weights make each point's true radius(i)+radius(j) threshold vary per pair) -- so every candidate the tree
    * returns still goes through the same exact check as before. Correctness therefore doesn't depend on the spatial
    * index at all, only its performance does; see AlphaComplexDQPSpatialIndexSpec for a brute-force cross-check across
    * many random weighted and unweighted configurations.
    */
  def cechNeighbours(): IndexedSeq[IndexedSeq[Int]] =
    val radius = Array.tabulate(n)(space.ballRadius(_, maxPower))
    val alive = radius.map(_ >= 0.0)
    val adj = IndexedSeq.fill(n)(mutable.SortedSet[Int]())

    def exactCheck(i: Int, j: Int): Boolean =
      val sum = radius(i) + radius(j)
      // Generous by a relative epsilon: a superset of the true Cech
      // graph is always safe (extra candidates get rejected by the QP,
      // extra constraints are genuine constraints), a subset is not.
      val bound = sum * sum
      space.squaredDistance(i, j) <= bound * (1.0 + 1e-12)

    if maxPower.isInfinite then
      for
        i <- 0 until n
        if alive(i)
        j <- i + 1 until n
        if alive(j)
      do
        adj(i) += j
        adj(j) += i
    else
      val aliveRadii = (0 until n).filter(alive).map(radius)
      if aliveRadii.nonEmpty then
        val maxRadiusOverAll = aliveRadii.max
        val spatialQuery: SpatialQuery[Int] = JVPTree(space.toMetricSpace)
        for i <- 0 until n if alive(i) do
          val queryRadius = (radius(i) + maxRadiusOverAll) * (1.0 + 1e-12)
          for
            j <- spatialQuery.neighbors(i, queryRadius)
            if j > i && alive(j) && exactCheck(i, j)
          do
            adj(i) += j
            adj(j) += i

    adj.map(_.toIndexedSeq)

  def compute(): AlphaComplexDQP =
    val nbrs = cechNeighbours()
    val alive = IndexedSeq.tabulate[Boolean](n)(space.ballRadius(_, maxPower) >= 0.0)

    val present = mutable.HashSet[Simplex[Int]]()
    val byDim = IndexedSeq.fill(maxDimension + 1)(mutable.IndexedBuffer[Simplex[Int]]())
    val weights = mutable.HashMap[Simplex[Int], Double]()
    val witnesses = mutable.HashMap[Simplex[Int], Array[Double]]()

    // Bound on the working set: rank(A) <= ambient dimension, with slack.
    val wsCap =
      if settings.workingSetCapacity > 0 then math.min(n, settings.workingSetCapacity)
      else if space.ambientDimension > 0 then math.min(n, space.ambientDimension + 2)
      else n
    // k == 0
    for // because dim 0, we know that the candidates are just the vertices
      (x, _) <- buildCandidates(0, nbrs, alive, byDim, present)
      f = Simplex(x)
    do
      byDim(0) += f
      present.add(f)
      // Definition 10 evaluated at a vertex: the unconstrained minimiser of ||y-x||^2 is
      // trivially y*=x, so w({x}) = 0 - p(x) = -weight(x). This is only ever 0 in the
      // unweighted case; leaving no entry here (falling back to filtrationValue's
      // getOrElse(cell, 0.0) default) is silently wrong whenever weight(x) != 0 -- it
      // breaks monotonicity against every edge incident to a nonzero-weight vertex.
      // Confirmed by AlphaComplexDQPWeightedSpec.
      weights(f) = -space.weight(x)
      witnesses(f) = coordsOf(x)

    for k <- 1 to maxDimension do
      val candidates: mutable.Map[Int, mutable.IndexedBuffer[Simplex[Int]]] =
        buildCandidates(k, nbrs, alive, byDim, present)

      val perVertex: IndexedSeq[mutable.IndexedBuffer[Found]] = candidates.collect {
        case (x, cs) if cs.nonEmpty =>
          solveAtVertex(x, cs, nbrs(x), wsCap).to(mutable.IndexedBuffer)
      }.toIndexedSeq

      // Merge in vertex order so the output does not depend on scheduling.
      perVertex.foreach { founds =>
        founds.foreach { (f: Found) =>
          byDim(k) += f.cell
          weights(f.cell) = f.weight
          witnesses(f.cell) = f.witness
        }
      }

      for f <- byDim(k)
      do present.add(f)

    if settings.enforceMonotonicity then clampMonotone(byDim, weights)

    // cellsOfDimension/iterateDimension hand out byDim(k) directly, and this order must be the exact
    // reverse of AlphaShapeDQP.filtrationOrdering (ascending weight -- monotonic in radius via radiusOf,
    // same as before -- but tie-broken by *descending* simplexOrdering[Int], not c.show). Getting only
    // the primary key right and leaving an inconsistent tie-break (previously: c.show, a string) is
    // exactly the bug class documented in CLAUDE.md's "Bug found while cross-validating" section: it self
    // passes VietorisRipsSpec-style sortedness checks (those only look at filtration VALUES), but breaks
    // PersistenceInChunksContext, whose chunk-boundary/local-reduction logic (Homology.scala's
    // PersistenceInChunksContext.allCells) relies on positional index in iterateDimension's own emission
    // order standing in for filtrationOrdering position -- found via EngineComparisonBenchmarkSpec /
    // AlphaFiltrationOrderingRegressionSpec once the primary-key-direction half of this bug was fixed
    // and the crash it caused went away but disagreement with the naive engine remained. byDim(k) is
    // populated per base vertex via a mutable.Map, whose iteration order is unspecified, so it needs an
    // explicit sort regardless; do this after clampMonotone so the order reflects final, possibly-clamped
    // weights.
    val cellOrdering: Ordering[Simplex[Int]] =
      Ordering.by[Simplex[Int], Double](c => weights.getOrElse(c, 0.0)).orElse(simplexOrdering[Int].reverse)
    for k <- byDim.indices do byDim(k).sortInPlace()(using cellOrdering)

    new AlphaComplexDQP(space, maxPower, maxDimension, byDim, weights, witnesses)
  end compute

  // -------------------------------------------------------------------------

  /** Candidate k-simplices, bucketed by their minimal vertex.
    *
    * k = 0: the vertices of G (line 6). k = 1: the edges of G (line 6). k >= 2: the k-simplices of Lazy_{k-1}(X) (line
    * 8), i.e. every (k+1)-set all of whose facets are already known to be in the complex.
    *
    * Each candidate is stored as its vertices *other than* the minimal one, because the base vertex is the bucket key.
    * Considering only simplices whose minimum is x is the "x <= x_j1 <= ... <= x_jk" reduction of step 2(b): every
    * simplex is then tested exactly once.
    */
  /* lines 1 and 4-8 of Algorithm 1 */
  def buildCandidates(
    k: Int,
    nbrs: IndexedSeq[IndexedSeq[Int]],
    alive: IndexedSeq[Boolean],
    byDim: IndexedSeq[mutable.IndexedBuffer[Simplex[Int]]],
    present: mutable.HashSet[Simplex[Int]]
  ): mutable.Map[Int, mutable.IndexedBuffer[Simplex[Int]]] = k match
    case 0 =>
      mutable.Map.from(
        (0 until n)
          .filter(alive)
          .map(x => (x, mutable.IndexedBuffer[Simplex[Int]]()))
      )
    case 1 =>
      mutable.Map.from(
        (0 until n)
          .filter(alive)
          .map(x =>
            (
              x,
              nbrs(x)
                .filter(_ > x)
                .map(Simplex[Int](_))
                .to(mutable.IndexedBuffer)
            )
          )
      )
    case _ =>
      byDim(k - 1)
        .foldLeft(mutable.Map[Int, mutable.IndexedBuffer[Simplex[Int]]]()) { (acc, tau) =>
          val x = tau.first
          val last = tau.last
          val rest = tau.tail
          // allFacetsPresent must see the true candidate (including x): it
          // checks all k+1 facets of a k-dimensional simplex, one of which
          // is `rest` itself (obtained by dropping x). Checking it against
          // `rest + w` alone silently skips every facet that keeps x and
          // drops something else, which only matters once k >= 3 -- for
          // triangles (k=2) those "extra" facets are single vertices and
          // always present, so the bug is invisible there.
          acc.getOrElseUpdate(x, mutable.IndexedBuffer()) ++= nbrs(x)
            .filter(_ > last)
            .filter(w => allFacetsPresent(tau + w, present))
            .map(w => rest + w)
          acc
        }

  def allFacetsPresent(sigma: Simplex[Int], present: mutable.HashSet[Simplex[Int]]): Boolean =
    sigma
      .map(v => sigma - v)
      .forall(present.contains)

  /** Steps 2(a)-(b): assemble the dual data for the cell V_x once, then run one QP per candidate simplex based at x.
    */
  def solveAtVertex(
    x: Int,
    candidates: mutable.IndexedBuffer[Simplex[Int]],
    nb: IndexedSeq[Int],
    wsCap: Int
  ): mutable.IndexedBuffer[Found] =
    val found = mutable.IndexedBuffer[Found]()
    val m = nb.length
    val c1 = 0.5 * (maxPower + space.weight(x))

    // Isolated site: V_x is everything, the QP is trivially solved by
    // lambda = 0, and only the vertex itself can be a simplex.
    if m == 0 then
      if candidates.exists(_.isEmpty) then found += Found(Simplex.from(Array(x)), -space.weight(x), coordsOf(x))
      return found

    // line 11-12: B and U, once per vertex per dimension
    val b = new Array[Double](m * m)
    val u = new Array[Double](m)
    var i = 0
    while i < m do
      u(i) = space.dualLinear(x, nb(i))
      var j = i
      while j < m do
        val g = space.gram(x, nb(i), nb(j))
        b(i * m + j) = g
        b(j * m + i) = g
        j += 1
      i += 1

    val qp = new DualQP(m, wsCap, settings)
    val eq = new Array[Int](maxDimension + 1)
    val neighbourPositions =
      scala.collection.immutable.SortedSet.from(nb).zipWithIndex.toMap

    candidates.foreach { rest =>
      rest.toSeq.flatMap(neighbourPositions.get) match
        case positions if positions.size == rest.size =>
          positions.toArray.copyToArray(eq)
          val cStar =
            try qp.solve(b, u, eq, rest.size, c1)
            catch
              case e: AlphaComplexDQPException =>
                // Defense in depth, not the primary correctness mechanism:
                // a handful of concrete near-degenerate configurations are
                // known to make the active-set method fail to converge (see
                // WORKLOG.md). Rather than let one candidate's non-convergence
                // abort the entire complex, exclude just that candidate --
                // the same outcome as a genuine (if conservatively wrong)
                // infeasibility determination -- and keep going. This is
                // meant to catch configurations *beyond* the ones already
                // characterised, not to paper over a known, fixable bug.
                System.err.println(
                  s"AlphaComplexDQP: dropping candidate ${rest + x} (base vertex $x) after non-convergence: ${e.getMessage}"
                )
                Double.PositiveInfinity
          // cStar.isFinite (not just !cStar.isNaN): DualQP.solve uses
          // Double.PositiveInfinity as its sentinel for "infeasible, discard".
          // When c1 is also infinite (AlphaShapeDQP's untruncated mode),
          // `cStar <= c1` alone is true for that sentinel too (Infinity <=
          // Infinity), so an isFinite check is required to actually reject it.
          if cStar <= c1 && cStar.isFinite then
            val cell = rest + x
            val w = 2.0 * cStar - space.weight(x)
            found += Found(cell, math.min(w, maxPower), witnessOf(x, nb.toArray, qp))
        case _ => ()
    }
    found
  end solveAtVertex

  /** KKT conditions (12): Phi(sigma) = y* = x - sum_i lambda_i (x_i - x). */
  def witnessOf(x: Int, nb: Array[Int], qp: DualQP): Array[Double] =
    if !space.hasCoordinates then null
    else
      val d = space.ambientDimension
      val y = Array.tabulate(d)(space.coordinate(x, _))
      for t <- 0 until qp.activeSetSize do
        val lamT = qp.multiplier(t)
        if lamT != 0.0 then
          val site = nb(qp.activeIndex(t))
          for k <- 0 until d do y(k) -= lamT * (space.coordinate(site, k) - space.coordinate(x, k))
      y

  def coordsOf(x: Int): Array[Double] =
    if !space.hasCoordinates then null
    else
      val d = space.ambientDimension
      Array.tabulate(d)(space.coordinate(x, _))

  /** w is monotone along faces by construction -- the QP for a face has strictly fewer equality constraints, hence a
    * larger feasible set and a smaller optimum -- but floating point can violate it by an ulp or two, which some
    * persistence algorithms will not forgive.
    */
  def clampMonotone(
    byDim: IndexedSeq[mutable.IndexedBuffer[Simplex[Int]]],
    weights: mutable.HashMap[Simplex[Int], Double]
  ): Unit =
    for k <- 1 until byDim.length do
      val cells = byDim(k)
      cells.foreach { sigma =>
        var w = weights(sigma)
        sigma
          .map(v => sigma - v)
          .foreach(facet => weights.get(facet).foreach(fw => if fw > w then w = fw))
        weights(sigma) = w
      }
end AlphaComplexDQPBuilder

class AlphaShapeDQP(val points: Array[Array[Double]]) extends AlphaShapes:
  override val metricSpace = EuclideanMetricSpace(points)
  // No radius cutoff: matches HelixDelaunay, which always returns the full,
  // untruncated Delaunay complex. A degenerate/near-collinear configuration
  // can produce simplices with arbitrarily large circumradius (see the
  // maxDimension caveat in the file header), so metricSpace.minimumEnclosingRadius
  // is not a safe bound here -- it can and does exclude genuine Delaunay
  // simplices. Callers who want an actually-truncated alpha complex should go
  // through AlphaComplexDQP.euclidean(points, maxRadius, maxDimension, ...) directly.
  val alphaComplexDQP = AlphaComplexDQP
    .euclidean(points, Double.PositiveInfinity, points.headOption.map(_.length).getOrElse(0))

  // Bounded at alphaComplexDQP.sizeByDimension.length (== the private cellsByDim.length, maxDimension + 1),
  // mirroring cellsOfDimension's own internal `k < 0 || k >= cellsByDim.length` bound exactly -- for any k
  // already in that range this changes nothing (cellsOfDimension never hit its own empty-fallback there
  // anyway), it only turns "silently returns an empty iterator forever" into "undefined" for k past it. That
  // matters because an eta-expanded function assigned to a PartialFunction (what this used to be) has
  // isDefinedAt always true, which is the same unbounded-domain shape StratifiedCellStream's own doc warns
  // about: `.iterator` would hang past the last real dimension exactly like the coface streams used to (see
  // that doc for the full mechanism). No existing caller is affected -- every one of them (AlphaComplexSpec,
  // AlphaValidationSpec, AlphaCrossValidationSpec) loops iterateDimension(d) for d up to the point cloud's own
  // ambient dimension, which is always < cellsByDim.length by construction (maxDimension is set to the
  // ambient dimension two lines up, so cellsByDim.length = ambientDimension + 1).
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
    case k if k >= 0 && k < alphaComplexDQP.sizeByDimension.length => alphaComplexDQP.cellsOfDimension(k).iterator
  }

  // `FilteredSimplexOrdering` compares filtration values using whatever `Ordering[Double]` using-param it's
  // given -- called with none, it silently defaulted to ascending `Ordering[Double]`, the same wrong-direction
  // bug `RecursiveStackVietorisRipsSimplexStream`/`HelixDelaunay` had (see their fixes and CLAUDE.md for the
  // full root-cause writeup). Supplying `.reverse` explicitly here is exactly `SimplexStream`'s own default
  // `val filtrationOrdering` pattern (`SimplexStream.scala`), not a bespoke construction.
  override def filtrationOrdering: Ordering[Simplex[Int]] =
    FilteredSimplexOrdering[Int, Double](this)(using vertexOrdering = summon[Ordering[Int]])(using
      filtrationOrdering = summon[Ordering[Double]].reverse
    )

  // NOTE: alphaComplexDQP.filtrationValue is the *squared* radius (the paper's
  // "power", Definition 10) -- see radiusOf. HelixDelaunay.filtrationValue,
  // its sibling under the shared AlphaShapes contract, returns the actual
  // (unsquared) circumradius, and Alpha(pts, dispatch) is designed to be
  // dispatch-interchangeable (see AlphaComplexSpec, which runs identical
  // property checks against both). Go through radiusOf here so both
  // implementations report the same quantity in the same units.
  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    alphaComplexDQP.radiusOf(_)
