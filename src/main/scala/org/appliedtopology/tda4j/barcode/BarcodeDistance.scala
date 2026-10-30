package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq

/** Bottleneck and Wasserstein distances between persistence diagrams, by bipartite matching where each point may also
  * be matched to its projection on the diagonal.
  *
  * The parameters follow GUDHI and Hera: `groundNorm` (their `internal_p`) measures one matched pair in the birth-death
  * plane; `order` (their `order`, `q` in Kerber-Morozov-Nigmetov 2017) is the exponent the costs of all pairs are
  * combined with. Wasserstein distance tends to bottleneck distance as `order -> Infinity`; use [[bottleneckDistance]]
  * for that case.
  *
  * '''Essential bars''' are matched only to essential bars, in order of birth (which is optimal for both distances). If
  * the diagrams have different numbers of essential bars, the distance is `Infinity`.
  */
object BarcodeDistance:

  /** The norm on the birth-death plane that measures one matched pair (GUDHI and Hera's `internal_p`). `LInfinity`, the
    * default there and here, is the usual choice.
    */
  enum GroundNorm:
    case LInfinity
    case LP(p: Double)

    /** Throws unless this is a norm: `LP(p)` needs `p >= 1`. */
    def require1(): Unit = this match
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
    * distance to the diagonal, and zeros between the diagonal copies. All entries are finite (no essential points
    * here).
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

  /** Bottleneck distance restricted to already-finite points. A threshold `t` is feasible when the points can be paired
    * (distance at most `t`) or sent to the diagonal (diagonal distance at most `t`); the diagonal takes any number of
    * points. So with `A` the left points and `B` the right points whose diagonal distance exceeds `t`, it is feasible
    * exactly when one matching of the near pairs covers `A` and another covers `B` (Mendelsohn-Dulmage: then one covers
    * both). That is two Hopcroft-Karp runs on the near pairs only, not on the `(nL + nR)^2` augmented graph, and the
    * near pairs of a point are found in a window of the other diagram sorted by birth (every `LP` norm is at least the
    * birth difference), so no matrix of all pairs is built.
    *
    * The answer is one of the pair distances, a diagonal distance, or `0`. Sending everything to the diagonal is
    * feasible, which caps the search at the largest diagonal distance. The search halves `[0, cap]` down to a bracket
    * `(lo, hi]` that holds the answer, then takes the smallest feasible value among the distances inside the bracket.
    */
  private def finiteBottleneck(left: Seq[Point], right: Seq[Point], groundNorm: GroundNorm): Double =
    if left.isEmpty && right.isEmpty then 0.0
    else
      val l = left.sortBy(_.birth).toArray
      val r = right.sortBy(_.birth).toArray
      val diagL = l.map(p => diagonalDistance(p.persistence, groundNorm))
      val diagR = r.map(p => diagonalDistance(p.persistence, groundNorm))
      val birthL = l.map(_.birth)
      val birthR = r.map(_.birth)

      // The first index of `births` (ascending) that is >= `bound`.
      def lowerBound(births: Array[Double], bound: Double): Int =
        var lo = 0
        var hi = births.length
        while lo < hi do
          val mid = (lo + hi) >>> 1
          if births(mid) < bound then lo = mid + 1 else hi = mid
        lo

      // The points of `other` within `t` of `p` (p's birth sorted into `births`), as indices into `other`.
      def near(p: Point, other: Array[Point], births: Array[Double], t: Double): Array[Int] =
        val found = Array.newBuilder[Int]
        var k = lowerBound(births, p.birth - t)
        while k < other.length && births(k) <= p.birth + t do
          if pointDistance(p, other(k), groundNorm) <= t then found += k
          k += 1
        found.result()

      def covers(must: Array[Int], neighbours: Int => Array[Int], others: Int): Boolean =
        val adjacency = must.map(neighbours)
        adjacency.forall(_.nonEmpty) &&
        HopcroftKarp
          .maximumMatching(must.length, others, u => ArraySeq.unsafeWrapArray(adjacency(u)))
          .count(_ != -1) == must.length

      def feasible(t: Double): Boolean =
        covers(l.indices.filter(diagL(_) > t).toArray, i => near(l(i), r, birthR, t), r.length) &&
          covers(r.indices.filter(diagR(_) > t).toArray, j => near(r(j), l, birthL, t), l.length)

      val cap = math.max(diagL.maxOption.getOrElse(0.0), diagR.maxOption.getOrElse(0.0))
      if feasible(0.0) then 0.0
      else
        var lo = 0.0
        var hi = cap
        var steps = 0
        while steps < 100 && math.nextUp(lo) < hi do
          val mid = lo + (hi - lo) / 2
          if mid <= lo || mid >= hi then steps = 100
          else if feasible(mid) then hi = mid
          else lo = mid
          steps += 1
        // The answer is a distance in (lo, hi]: the diagonal distances, and the pair distances found by a window of `hi`.
        val inside = Array.newBuilder[Double]
        (diagL ++ diagR).foreach(d => if d > lo && d <= hi then inside += d)
        for i <- l.indices do
          var k = lowerBound(birthR, birthL(i) - hi)
          while k < r.length && birthR(k) <= birthL(i) + hi do
            val d = pointDistance(l(i), r(k), groundNorm)
            if d > lo && d <= hi then inside += d
            k += 1
        inside += hi
        val candidates = inside.result()
        java.util.Arrays.sort(candidates)
        var a = 0
        var z = candidates.length - 1
        while a < z do
          val mid = a + (z - a) / 2
          if feasible(candidates(mid)) then z = mid else a = mid + 1
        candidates(a)

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

  /** The sliced Wasserstein distance between two persistence diagrams (Carriere, Cuturi, Oudot 2017): project both
    * diagrams, each completed by the orthogonal projections of the other's points onto the diagonal, onto a line,
    * compare the sorted projections in `L1`, and average over the lines. It is a metric on diagrams that is bounded by
    * constant multiples of the 1-Wasserstein distance and costs `O(directions * n log n)`, so it suits kernels and
    * large diagrams, where the Hungarian algorithm does not.
    *
    * The average runs over `directions` evenly-spaced angles in `[-pi/2, pi/2)`. The distance between a diagram of one
    * bar and the empty diagram tends to `persistence * sqrt(2) / pi` as `directions` grows (the spec checks it).
    * Essential bars are dropped; compare them with [[bottleneckDistance]].
    */
  def slicedWassersteinDistance(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    directions: Int = 50
  ): Double =
    require(directions >= 1, s"slicedWassersteinDistance requires directions >= 1, got $directions")
    val pts1 = diagram1.map(toPoint).filterNot(isEssential)
    val pts2 = diagram2.map(toPoint).filterNot(isEssential)
    def onDiagonal(p: Point): Point = DiagramPoint((p.birth + p.death) / 2.0, (p.birth + p.death) / 2.0)
    val a = pts1 ++ pts2.map(onDiagonal)
    val b = pts2 ++ pts1.map(onDiagonal)
    val total = (0 until directions).map { k =>
      val theta = -math.Pi / 2.0 + math.Pi * k / directions
      val (c, s) = (math.cos(theta), math.sin(theta))
      val pa = a.map(p => p.birth * c + p.death * s).sorted
      val pb = b.map(p => p.birth * c + p.death * s).sorted
      pa.zip(pb).map((x, y) => math.abs(x - y)).sum
    }.sum
    total / directions

  /** An optimal matching of two diagrams: bar `i` of the first diagram is matched to bar `j` of the second for each
    * `(i, j)` of `pairs`; the bars in `leftToDiagonal` and `rightToDiagonal` (indices into the diagrams as given) are
    * matched to the diagonal, that is, left unmatched; `cost` is the distance the matching realizes.
    */
  final case class Matching(
    pairs: Vector[(Int, Int)],
    leftToDiagonal: Vector[Int],
    rightToDiagonal: Vector[Int],
    cost: Double
  )

  /** The bars of a diagram split into essential (ascending by birth) and finite, as indices into `diagram`, with the
    * points.
    */
  private def indexedSplit(diagram: Seq[PersistenceBar[Double, ?]]): (Vector[Int], Vector[Int], Vector[Point]) =
    val points = diagram.map(toPoint).toVector
    val (essential, finite) = points.indices.partition(i => isEssential(points(i)))
    (essential.sortBy(points(_).birth).toVector, finite.toVector, points)

  /** A matching that realizes [[wassersteinDistance]] of the same arguments (`None` when the diagrams have different
    * numbers of essential bars, where the distance is `Infinity` and no matching exists). Essential bars are matched in
    * order of birth. The finite bars are matched by the Hungarian algorithm on the augmented cost matrix, so the cost
    * is quadratic in the number of bars in space and cubic in time; for large diagrams use
    * [[slicedWassersteinDistance]].
    */
  def wassersteinMatching(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    order: Double = 1.0,
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Option[Matching] =
    groundNorm.require1()
    require(order.isFinite && order >= 1.0, s"wassersteinMatching requires a finite order >= 1.0, got $order")
    require(diagram1.map(_.dim).distinct.size <= 1, "wassersteinMatching requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "wassersteinMatching requires diagram2 to be single-dimension")
    val (essential1, finite1, points1) = indexedSplit(diagram1)
    val (essential2, finite2, points2) = indexedSplit(diagram2)
    if essential1.size != essential2.size then None
    else
      val essentialPairs = essential1.zip(essential2)
      val essentialPow =
        essentialPairs.map((i, j) => math.pow(math.abs(points1(i).birth - points2(j).birth), order)).sum
      val left = finite1.map(points1)
      val right = finite2.map(points2)
      val (nL, nR) = (left.size, right.size)
      val pairs = Vector.newBuilder[(Int, Int)]
      pairs ++= essentialPairs
      val leftDiagonal = Vector.newBuilder[Int]
      val rightDiagonal = Vector.newBuilder[Int]
      val finitePow =
        if nL + nR == 0 then 0.0
        else
          val matrix = augmentedCostMatrix(left, right, groundNorm).map(_.map(c => math.pow(c, order)))
          val (rowOfColumn, total) = Hungarian.minCostPerfectMatching(matrix)
          for j <- 0 until nL + nR do
            val i = rowOfColumn(j)
            if i < nL && j < nR then pairs += ((finite1(i), finite2(j)))
            else if i < nL then leftDiagonal += finite1(i)
            else if j < nR then rightDiagonal += finite2(j)
          total
      Some(
        Matching(
          pairs.result(),
          leftDiagonal.result().sorted,
          rightDiagonal.result().sorted,
          math.pow(essentialPow + finitePow, 1.0 / order)
        )
      )

  /** A matching that realizes [[bottleneckDistance]] of the same arguments (`None` when the numbers of essential bars
    * differ). It is a perfect matching of the augmented graph that keeps the pairs within the bottleneck distance,
    * found by Hopcroft-Karp, so it takes quadratic space in the number of bars; it is one of many optimal matchings.
    */
  def bottleneckMatching(
    diagram1: Seq[PersistenceBar[Double, ?]],
    diagram2: Seq[PersistenceBar[Double, ?]],
    groundNorm: GroundNorm = GroundNorm.LInfinity
  ): Option[Matching] =
    groundNorm.require1()
    require(diagram1.map(_.dim).distinct.size <= 1, "bottleneckMatching requires diagram1 to be single-dimension")
    require(diagram2.map(_.dim).distinct.size <= 1, "bottleneckMatching requires diagram2 to be single-dimension")
    val (essential1, finite1, points1) = indexedSplit(diagram1)
    val (essential2, finite2, points2) = indexedSplit(diagram2)
    if essential1.size != essential2.size then None
    else
      val essentialPairs = essential1.zip(essential2)
      val essentialCost = essentialPairs.map((i, j) => math.abs(points1(i).birth - points2(j).birth)).maxOption
      val left = finite1.map(points1)
      val right = finite2.map(points2)
      val (nL, nR) = (left.size, right.size)
      val pairs = Vector.newBuilder[(Int, Int)]
      pairs ++= essentialPairs
      val leftDiagonal = Vector.newBuilder[Int]
      val rightDiagonal = Vector.newBuilder[Int]
      val finiteCost =
        if nL + nR == 0 then 0.0
        else
          val threshold = finiteBottleneck(left, right, groundNorm)
          val diagonalLeft = left.map(p => diagonalDistance(p.persistence, groundNorm))
          val diagonalRight = right.map(p => diagonalDistance(p.persistence, groundNorm))
          def adjacent(i: Int, j: Int): Boolean =
            if i < nL && j < nR then pointDistance(left(i), right(j), groundNorm) <= threshold
            else if i < nL then diagonalLeft(i) <= threshold
            else if j < nR then diagonalRight(j) <= threshold
            else true
          val n = nL + nR
          val matched = HopcroftKarp.maximumMatching(
            n,
            n,
            i => ArraySeq.unsafeWrapArray((0 until n).filter(adjacent(i, _)).toArray)
          )
          if matched.contains(-1) then
            throw new IllegalStateException(
              s"bottleneckMatching: no perfect matching within the bottleneck distance $threshold"
            )
          for i <- 0 until n do
            val j = matched(i)
            if i < nL && j < nR then pairs += ((finite1(i), finite2(j)))
            else if i < nL then leftDiagonal += finite1(i)
            else if j < nR then rightDiagonal += finite2(j)
          threshold
      Some(
        Matching(
          pairs.result(),
          leftDiagonal.result().sorted,
          rightDiagonal.result().sorted,
          math.max(essentialCost.getOrElse(0.0), finiteCost)
        )
      )

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
