package org.appliedtopology.tda4j

/** Bottleneck and Wasserstein distances between persistence diagrams, by bipartite matching where each point may also
  * be matched to its projection on the diagonal.
  *
  * The parameters follow GUDHI and Hera: `groundNorm` (their `internal_p`) measures one matched pair in the
  * birth-death plane; `order` (their `order`, `q` in Kerber-Morozov-Nigmetov 2017) is the exponent the costs of all
  * pairs are combined with. Wasserstein distance tends to bottleneck distance as `order -> Infinity`; use
  * [[bottleneckDistance]] for that case.
  *
  * '''Essential bars''' are matched only to essential bars, in order of birth (which is optimal for both distances).
  * If the diagrams have different numbers of essential bars, the distance is `Infinity`.
  */
object BarcodeDistance:

  /** The norm on the birth-death plane that measures one matched pair (GUDHI and Hera's `internal_p`). `LInfinity`,
    * the default there and here, is the usual choice.
    */
  enum GroundNorm:
    case LInfinity
    case LP(p: Double)

    private[tda4j] def require1(): Unit = this match
      case LP(p) => require(p >= 1.0, s"GroundNorm.LP requires p >= 1.0, got $p")
      case _     => ()

  private type Point = DiagramPoint
  private def toPoint[A](bar: PersistenceBar[Double, A]): Point = DiagramPoint.of(bar)
  private def isEssential(p: Point): Boolean = DiagramPoint.isEssential(p)

  /** Distance from a finite point to the diagonal, under `groundNorm`. Persistence `pi = death - birth` splits evenly
    * between the two coordinates at the orthogonal projection, so the L^p distance to that projection is
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

  /** Splits into (essential, finite) points and requires equal essential counts (else there is no finite matching);
    * returns the sorted-by-birth essential pairing costs (see the class doc for why sorted pairing is optimal) plus the
    * two finite-point lists left for the caller to solve with the general bipartite machinery.
    */
  private def essentialAndFinite(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]]
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

  /** The square cost matrix of the matching problem, size `left.size + right.size`: point-to-point costs, each point's
    * distance to the diagonal, and zeros between the diagonal copies. All entries are finite (no essential points here).
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
    * candidate distance is achievable with SOME perfect matching (the largest one trivially, since at that threshold
    * the bipartite graph is complete), so the search always succeeds once it starts.
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

  /** Bottleneck distance between two persistence diagrams of a single homological dimension. `diagram1`/ `diagram2`
    * must each be internally homogeneous in `.dim` (a mismatch usually means an unfiltered multi-dimensional barcode
    * was passed by mistake -- use [[bottleneckDistanceByDimension]] for that case); the two diagrams' own dimensions
    * are not required to agree with each other, since asking that question directly (rather than through
    * [[bottleneckDistanceByDimension]]) is treated as a deliberate choice, not a mistake to guard against. See the
    * class doc for the essential-bar policy and the ground-metric/aggregation convention.
    */
  def bottleneckDistance(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Double =
    groundNorm.require1()
    require(diagram1.map(_.dim).distinct.size <= 1, "bottleneckDistance requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "bottleneckDistance requires diagram2 to be single-dimension")
    essentialAndFinite(diagram1, diagram2) match
      case None                               => Double.PositiveInfinity
      case Some((essentialCosts, fin1, fin2)) =>
        val essentialCost = if essentialCosts.isEmpty then 0.0 else essentialCosts.max
        math.max(essentialCost, finiteBottleneck(fin1, fin2, groundNorm))

  /** Wasserstein distance restricted to already-finite points, via the Hungarian algorithm on the augmented cost matrix
    * with entries raised to `order` (so minimizing total cost there minimizes `sum cost^order`, per the standard
    * order-th-root-of-sum-of-powers construction); the `order`-th root is taken by the caller once this is combined
    * with the essential-pair contribution.
    */
  private def finiteWassersteinPow(left: Seq[Point], right: Seq[Point], order: Double, groundNorm: GroundNorm): Double =
    val n = left.size + right.size
    if n == 0 then 0.0
    else
      val matrix = augmentedCostMatrix(left, right, groundNorm).map(_.map(c => math.pow(c, order)))
      Hungarian.minCostPerfectMatching(matrix)._2

  /** The Wasserstein distance of order `order` (finite, at least `1.0`) between the diagrams of one homological
    * dimension, essential bars handled as in [[bottleneckDistance]].
    */
  def wassersteinDistance(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    order: Double = 1.0,
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Double =
    groundNorm.require1()
    require(order.isFinite && order >= 1.0, s"wassersteinDistance requires a finite order >= 1.0, got $order")
    require(diagram1.map(_.dim).distinct.size <= 1, "wassersteinDistance requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "wassersteinDistance requires diagram2 to be single-dimension")
    essentialAndFinite(diagram1, diagram2) match
      case None                               => Double.PositiveInfinity
      case Some((essentialCosts, fin1, fin2)) =>
        val essentialPow = essentialCosts.map(c => math.pow(c, order)).sum
        val finitePow = finiteWassersteinPow(fin1, fin2, order, groundNorm)
        math.pow(essentialPow + finitePow, 1.0 / order)

  private def byDimension[R](
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]]
  )(f: (Seq[PersistenceBar[Double, ?]], Seq[PersistenceBar[Double, ?]]) => R): Map[Int, R] =
    val dims = (diagram1.map(_.dim) ++ diagram2.map(_.dim)).distinct
    val byDim1 = diagram1.groupBy(_.dim).withDefaultValue(Seq.empty)
    val byDim2 = diagram2.groupBy(_.dim).withDefaultValue(Seq.empty)
    dims.map(d => d -> f(byDim1(d), byDim2(d))).toMap

  /** Convenience wrapper: groups two (possibly multi-dimensional) barcodes by `.dim` and computes
    * [[bottleneckDistance]] within each dimension present in either one (a dimension missing from one side is treated
    * as the empty diagram on that side, i.e. every bar on the other side must die to the diagonal, or the comparison is
    * `Infinity` if any of them is essential).
    */
  def bottleneckDistanceByDimension(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Map[Int, Double] =
    byDimension(diagram1, diagram2)((d1, d2) => bottleneckDistance(d1, d2, groundNorm))

  /** As [[bottleneckDistanceByDimension]], but for [[wassersteinDistance]]. */
  def wassersteinDistanceByDimension(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    order: Double = 1.0,
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Map[Int, Double] =
    byDimension(diagram1, diagram2)((d1, d2) => wassersteinDistance(d1, d2, order, groundNorm))
