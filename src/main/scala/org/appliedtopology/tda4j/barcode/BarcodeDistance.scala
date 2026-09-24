package org.appliedtopology.tda4j
package barcode

/** Bottleneck and Wasserstein distance between two persistence diagrams, plus the ground-metric convention they
  * share. Hand-rolled per `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 4: pure bipartite matching over
  * diagram points (each also matchable to its own projection onto the diagonal `birth = death`), not a boundary-
  * matrix optimization like the circular-coordinates/optimal-cycles items -- no relation to [[Barcode]]'s
  * map/kernel/cokernel algebra, hence its own file/object.
  *
  * Parameter naming and the ground-metric/aggregation split follow Hera (`anigmetov/hera`) and GUDHI's own
  * `wasserstein_distance`, cross-checked against both (not recalled from memory, and independently re-derived
  * below) rather than assumed: `groundNorm` (GUDHI/Hera's `internal_p`) is the norm on the birth-death plane used
  * to cost a single matched pair; `order` (GUDHI/Hera's `order`/`wasserstein_power`, called `q` in
  * Kerber-Morozov-Nigmetov 2017) is the exponent used to aggregate all matched pairs' costs into one number. As
  * `order -> Infinity` this aggregation becomes a max, i.e. Wasserstein converges to bottleneck -- but
  * `wassersteinDistance` below only accepts finite `order` (seeding a Hungarian cost matrix with `cost^Infinity`
  * is not meaningful); call `bottleneckDistance` directly for that case, matching how GUDHI/Hera expose them as
  * separate entry points rather than one function with an infinite default.
  *
  * '''Essential (never-dying) bars''': matched only to other essential bars, never to the diagonal (infinite
  * persistence means infinite distance to the diagonal under any ground norm) and never to a finite bar (infinite
  * vs. finite death is likewise infinitely bad). If the two diagrams have different numbers of essential bars,
  * the distance is `Double.PositiveInfinity` -- there is no finite matching. When the counts agree, essential
  * bars carry no usable death coordinate (both are "the same" point at infinity, contributing nothing to the
  * cost), so they are matched purely by ascending birth value; this is provably cost-minimal for both the sum-of-
  * powers (Wasserstein) and max (bottleneck) aggregations, by the standard line-matching exchange argument (for
  * any `x1 < x2`, `y1 < y2`: `max/sum` of the sorted pairing `(x1,y1),(x2,y2)` never exceeds the crossed pairing
  * `(x1,y2),(x2,y1)`), so it can be resolved directly instead of routed through the general bipartite solvers
  * below. This also keeps `Infinity` values out of the Hungarian/Hopcroft-Karp cost matrices entirely (an
  * `Infinity - Infinity = NaN` landmine in the Hungarian potential updates, avoided by construction rather than
  * guarded against).
  */
object BarcodeDistance:

  /** The ground metric on the birth-death plane used to cost one matched pair of diagram points (GUDHI/Hera's
    * `internal_p`). `LInfinity` (the default in both) is the usual TDA convention.
    */
  enum GroundNorm:
    case LInfinity
    case LP(p: Double)

    private[barcode] def require1(): Unit = this match
      case LP(p) => require(p >= 1.0, s"GroundNorm.LP requires p >= 1.0, got $p")
      case _     => ()

  private type Point = DiagramPoint
  private def toPoint[A](bar: PersistenceBar[Double, A]): Point = DiagramPoint.of(bar)
  private def isEssential(p: Point): Boolean = DiagramPoint.isEssential(p)

  /** Distance from a finite point to the diagonal, under `groundNorm`. Persistence `pi = death - birth` splits
    * evenly between the two coordinates at the orthogonal projection, so the L^p distance to that projection is
    * `(pi/2) * 2^(1/p)` for finite `p` (which is `(d-b) * 2^(1/p - 1)`, cross-checked against GUDHI's own
    * `_perstools.py`) and exactly `pi/2` at `p = Infinity`, matching the `p -> Infinity` limit of that formula.
    */
  private def diagonalDistance(persistence: Double, groundNorm: GroundNorm): Double =
    groundNorm match
      case GroundNorm.LInfinity => persistence / 2.0
      case GroundNorm.LP(p)     => persistence * math.pow(2.0, 1.0 / p - 1.0)

  /** Ground distance between two finite off-diagonal points under `groundNorm`. */
  private def pointDistance(x: Point, y: Point, groundNorm: GroundNorm): Double =
    val db = math.abs(x.birth - y.birth)
    val dd = math.abs(x.death - y.death)
    groundNorm match
      case GroundNorm.LInfinity => math.max(db, dd)
      case GroundNorm.LP(p)     => math.pow(math.pow(db, p) + math.pow(dd, p), 1.0 / p)

  /** Splits into (essential, finite) points and requires equal essential counts (else there is no finite
    * matching); returns the sorted-by-birth essential pairing costs (see the class doc for why sorted pairing is
    * optimal) plus the two finite-point lists left for the caller to solve with the general bipartite machinery.
    */
  private def essentialAndFinite[A, B](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]]
  ): Option[(Seq[Double], Seq[Point], Seq[Point])] =
    val pts1 = diagram1.map(toPoint)
    val pts2 = diagram2.map(toPoint)
    val (ess1, fin1) = pts1.partition(isEssential)
    val (ess2, fin2) = pts2.partition(isEssential)
    if ess1.size != ess2.size then None
    else
      val essentialCosts =
        ess1.map(_.birth).sorted.zip(ess2.map(_.birth).sorted).map((a, b) => math.abs(a - b))
      Some((essentialCosts, fin1, fin2))

  /** The augmented square cost matrix for matching `left` against `right` allowing either side to die on the
    * diagonal instead: size `(left.size + right.size)`, real-real entries under `groundNorm`, real-diagonal
    * entries each point's own `diagonalDistance`, and `0` for the diagonal-vs-diagonal padding needed to balance
    * the bipartite sizes into a square (standard reduction from "partial matching with a diagonal of infinite
    * capacity" to plain perfect bipartite matching -- see class doc / worklog for the construction). All entries
    * are finite because essential points are never passed in here.
    */
  private def augmentedCostMatrix(left: Seq[Point], right: Seq[Point], groundNorm: GroundNorm): Array[Array[Double]] =
    val nL = left.size
    val nR = right.size
    val n = nL + nR
    Array.tabulate(n, n) { (i, j) =>
      if i < nL && j < nR then pointDistance(left(i), right(j), groundNorm)
      else if i < nL && j >= nR then diagonalDistance(left(i).persistence, groundNorm)
      else if i >= nL && j < nR then diagonalDistance(right(j).persistence, groundNorm)
      else 0.0
    }

  /** Bottleneck distance restricted to already-finite points, via binary search over candidate distances (every
    * distinct entry of the augmented cost matrix) plus a Hopcroft-Karp feasibility check at each candidate. Every
    * candidate distance is achievable with SOME perfect matching (the largest one trivially, since at that
    * threshold the bipartite graph is complete), so the search always succeeds once it starts.
    */
  private def finiteBottleneck(left: Seq[Point], right: Seq[Point], groundNorm: GroundNorm): Double =
    val n = left.size + right.size
    if n == 0 then 0.0
    else
      val matrix = augmentedCostMatrix(left, right, groundNorm)
      val candidates = matrix.flatten.distinct.sorted
      def feasible(threshold: Double): Boolean =
        val adjacency: Array[Array[Int]] =
          Array.tabulate(n)(i => (0 until n).filter(j => matrix(i)(j) <= threshold).toArray)
        HopcroftKarp.maximumMatching(n, n, adjacency(_).toIndexedSeq).count(_ != -1) == n
      var lo = 0
      var hi = candidates.length - 1
      while lo < hi do
        val mid = lo + (hi - lo) / 2
        if feasible(candidates(mid)) then hi = mid else lo = mid + 1
      candidates(lo)

  /** Bottleneck distance between two persistence diagrams of a single homological dimension. `diagram1`/
    * `diagram2` must each be internally homogeneous in `.dim` (a mismatch usually means an unfiltered
    * multi-dimensional barcode was passed by mistake -- use [[bottleneckDistanceByDimension]] for that case); the
    * two diagrams' own dimensions are not required to agree with each other, since asking that question directly
    * (rather than through [[bottleneckDistanceByDimension]]) is treated as a deliberate choice, not a mistake to
    * guard against. See the class doc for the essential-bar policy and the ground-metric/aggregation convention.
    */
  def bottleneckDistance[A, B](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]],
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Double =
    groundNorm.require1()
    require(diagram1.map(_.dim).distinct.size <= 1, "bottleneckDistance requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "bottleneckDistance requires diagram2 to be single-dimension")
    essentialAndFinite(diagram1, diagram2) match
      case None => Double.PositiveInfinity
      case Some((essentialCosts, fin1, fin2)) =>
        val essentialCost = if essentialCosts.isEmpty then 0.0 else essentialCosts.max
        math.max(essentialCost, finiteBottleneck(fin1, fin2, groundNorm))

  /** Wasserstein distance restricted to already-finite points, via the Hungarian algorithm on the augmented cost
    * matrix with entries raised to `order` (so minimizing total cost there minimizes `sum cost^order`, per the
    * standard order-th-root-of-sum-of-powers construction); the `order`-th root is taken by the caller once this
    * is combined with the essential-pair contribution.
    */
  private def finiteWassersteinPow(left: Seq[Point], right: Seq[Point], order: Double, groundNorm: GroundNorm): Double =
    val n = left.size + right.size
    if n == 0 then 0.0
    else
      val matrix = augmentedCostMatrix(left, right, groundNorm).map(_.map(c => math.pow(c, order)))
      Hungarian.minCostPerfectMatching(matrix)._2

  /** Wasserstein distance (order `order`, default `1.0`) between two persistence diagrams of a single homological
    * dimension. Same single-dimension-per-list requirement, essential-bar policy, and ground-metric convention as
    * [[bottleneckDistance]] -- see the class doc. `order` must be finite and `>= 1.0`; call [[bottleneckDistance]]
    * directly for the `order = Infinity` case.
    */
  def wassersteinDistance[A, B](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]],
    order: Double = 1.0,
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Double =
    groundNorm.require1()
    require(order.isFinite && order >= 1.0, s"wassersteinDistance requires a finite order >= 1.0, got $order")
    require(diagram1.map(_.dim).distinct.size <= 1, "wassersteinDistance requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "wassersteinDistance requires diagram2 to be single-dimension")
    essentialAndFinite(diagram1, diagram2) match
      case None => Double.PositiveInfinity
      case Some((essentialCosts, fin1, fin2)) =>
        val essentialPow = essentialCosts.map(c => math.pow(c, order)).sum
        val finitePow = finiteWassersteinPow(fin1, fin2, order, groundNorm)
        math.pow(essentialPow + finitePow, 1.0 / order)

  private def byDimension[A, B, R](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]]
  )(f: (Seq[PersistenceBar[Double, A]], Seq[PersistenceBar[Double, B]]) => R): Map[Int, R] =
    val dims = (diagram1.map(_.dim) ++ diagram2.map(_.dim)).distinct
    val byDim1 = diagram1.groupBy(_.dim).withDefaultValue(Seq.empty)
    val byDim2 = diagram2.groupBy(_.dim).withDefaultValue(Seq.empty)
    dims.map(d => d -> f(byDim1(d), byDim2(d))).toMap

  /** Convenience wrapper: groups two (possibly multi-dimensional) barcodes by `.dim` and computes
    * [[bottleneckDistance]] within each dimension present in either one (a dimension missing from one side is
    * treated as the empty diagram on that side, i.e. every bar on the other side must die to the diagonal, or the
    * comparison is `Infinity` if any of them is essential).
    */
  def bottleneckDistanceByDimension[A, B](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]],
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Map[Int, Double] =
    byDimension(diagram1, diagram2)((d1, d2) => bottleneckDistance(d1, d2, groundNorm))

  /** As [[bottleneckDistanceByDimension]], but for [[wassersteinDistance]]. */
  def wassersteinDistanceByDimension[A, B](
    diagram1: Seq[PersistenceBar[Double, A]],
    diagram2: Seq[PersistenceBar[Double, B]],
    order: Double = 1.0,
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Map[Int, Double] =
    byDimension(diagram1, diagram2)((d1, d2) => wassersteinDistance(d1, d2, order, groundNorm))
