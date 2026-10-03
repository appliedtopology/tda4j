package org.appliedtopology.tda4j

import scala.collection.concurrent.TrieMap

/** The sparse Vietoris-Rips filtration of Cavanna, Jahanseir and Sheehy, "A Geometric Perspective on Sparse
  * Filtrations" (arXiv:1506.03797, CCCG 2015): the greedy-permutation form of Sheehy's linear-size approximation
  * ("Linear-Size Approximations to the Vietoris-Rips Filtration", DCG 2013, arXiv:1203.6786). Its barcode is a
  * `(1 + epsilon)`-multiplicative approximation of the Vietoris-Rips barcode (Theorem 5), from a complex of size linear
  * in the number of points for data of bounded doubling dimension. (Sheehy 2013's `epsilon` is a different parameter:
  * its factor is `1/(1 - 2 epsilon)`.) The edges are computed from all pairs, `O(n^2)`, rather than by the paper's
  * `O(n log n)` neighbour search: the complex to reduce is small, its construction is not.
  *
  * ==The construction==
  *
  * Given a greedy permutation with insertion radii `lambda_p` (`Infinity` for the first point) and `epsilon` in `(0, 1)`:
  *
  *   - the ball of `p` at scale `alpha` has radius `min(alpha, lambda_p (1 + epsilon) / epsilon)`;
  *   - it is empty from `alpha > lambda_p (1 + epsilon)^2 / epsilon` on (`vanish(p)`): no new simplex uses `p` after
  *     that, though nothing already present is removed (the filtration is the running union of the nerves);
  *   - an edge `{p, q}` is born at the smallest `alpha` at which the two balls meet (Algorithm 3, `edgeBirth`);
  *   - a simplex is born at the latest birth of its edges, if that is at most the earliest `vanish` of its vertices
  *     (Section 5.3), and never otherwise.
  *
  * Algorithm 3 computes an edge's birth without the `vanish` check that Section 5.3 requires of every simplex (for
  * `epsilon = 1`, `lambda_p = 1`, `lambda_q = 10`, `d = 10` it gives 8, while `p` vanishes at 4); `edgeBirth` applies
  * the check to edges too.
  *
  * Units: the paper's `alpha` is a radius; every value here is doubled, so that values are diameters like every other
  * Vietoris-Rips filtration (an unsparsified edge gets exactly its length).
  *
  * `maxFiltrationValue` is always clamped to `maxFiniteFiltrationValue`, the largest finite edge birth: the enclosing
  * radius is no bound here, and an unclamped `Infinity` threshold would admit simplices that should never appear.
  *
  * The value of a simplex is not its diameter, so the Ripser engines do not apply; the naive, chunks and cohomology
  * engines do.
  */
private[tda4j] class SheehyRipsSimplexStream(
  val ambientMetricSpace: FiniteMetricSpace[Int],
  val permutation: GreedyPermutation,
  val epsilon: Double,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None,
  parallelFiltrationValue: Boolean = false
) extends RipserCofaceSimplexStream(
      ambientMetricSpace,
      keepCriterion,
      // ALWAYS clamped to maxFiniteFiltrationValue, not just when maxFiltrationValue is unset: nothing finite
      // ever lies past it (see that method's own doc), so this is exact for any caller-supplied threshold, and
      // it is what keeps an explicit `Some(Double.PositiveInfinity)` safe -- without this clamp, that literal
      // threshold would compare equal (IEEE-754) to every excluded pair's own `Double.PositiveInfinity`
      // filtration value and silently readmit it. See the class doc's own note on this hazard.
      Some(
        math.min(
          maxFiltrationValue.getOrElse(Double.PositiveInfinity),
          SheehyRipsSimplexStream.maxFiniteFiltrationValue(ambientMetricSpace, permutation, epsilon)
        )
      ),
      Some(SheehyRipsSimplexStream.filtrationValueOverride(ambientMetricSpace, permutation, epsilon)),
      parallelFiltrationValue
    ):
  require(epsilon > 0.0 && epsilon < 1.0, s"epsilon must be in (0,1), got $epsilon")
  require(
    permutation.order.size == ambientMetricSpace.size && permutation.order.toSet == ambientMetricSpace.elements.toSet,
    "permutation must be a full greedy permutation of every point in ambientMetricSpace"
  )

private[tda4j] object SheehyRipsSimplexStream:

  /** The sparse Rips filtration of `ambientMetricSpace`, computing the greedy permutation (with `epsilon` checked first). */
  def apply(
    ambientMetricSpace: FiniteMetricSpace[Int],
    epsilon: Double,
    firstPoint: Int = 0,
    keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
    maxFiltrationValue: Option[Double] = None,
    parallelFiltrationValue: Boolean = false
  ): SheehyRipsSimplexStream =
    require(epsilon > 0.0 && epsilon < 1.0, s"epsilon must be in (0,1), got $epsilon")
    val selection = LandmarkSelector.maxmin(ambientMetricSpace, ambientMetricSpace.size, firstPoint)
    new SheehyRipsSimplexStream(
      ambientMetricSpace,
      GreedyPermutation(selection.landmarks, selection.insertionRadius),
      epsilon,
      keepCriterion,
      maxFiltrationValue,
      parallelFiltrationValue
    )

  /** CJS 2015 Algorithm 3 (`EdgeBirthTime`), doubled into diameter units and extended with the `vanish` clamp described
    * in the class doc. `lambdaP`/`lambdaQ` are the two endpoints' own insertion radii (order doesn't matter -- the
    * smaller is found internally, exactly like the paper's own leading swap step), `d` their ambient (undoubled)
    * distance. Returns `Double.PositiveInfinity` for a pair that never appears.
    */
  def edgeBirth(lambdaP: Double, lambdaQ: Double, d: Double, epsilon: Double): Double =
    val lo = math.min(lambdaP, lambdaQ)
    val hi = math.max(lambdaP, lambdaQ)
    val raw =
      if d <= 2.0 * lo * (1.0 + epsilon) / epsilon then d / 2.0
      else if d <= (lo + hi) * (1.0 + epsilon) / epsilon then d - lo * (1.0 + epsilon) / epsilon
      else Double.PositiveInfinity
    if raw > vanish(lo, epsilon) then Double.PositiveInfinity else 2.0 * raw

  /** `lambda (1 + epsilon)^2 / epsilon`, in the paper's (radius) units; `vanishDoubled` is the doubled version. */
  private def vanish(lambda: Double, epsilon: Double): Double =
    lambda * (1.0 + epsilon) * (1.0 + epsilon) / epsilon

  /** `vanish`, doubled (in diameter units): after it, `p` enters no new simplex. */
  def vanishDoubled(lambda: Double, epsilon: Double): Double = 2.0 * vanish(lambda, epsilon)

  /** The value of a simplex: `0` for a vertex; otherwise the largest `edgeBirth` of its edges, or `Infinity` if that
    * exceeds the earliest `vanishDoubled` of its vertices (Section 5.3). Memoized.
    */
  def filtrationValueOverride(
    ambientMetricSpace: FiniteMetricSpace[Int],
    permutation: GreedyPermutation,
    epsilon: Double
  ): PartialFunction[Simplex[Int], Double] =
    val lambda = permutation.insertionRadius
    val cache = TrieMap.empty[Simplex[Int], Double]
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = spx.forall(ambientMetricSpace.contains)
      def apply(spx: Simplex[Int]): Double =
        cache.getOrElseUpdate(
          spx,
          if spx.dim <= 0 then 0.0
          else
            val vertices = spx.toSeq
            val edgeBirths =
              for
                i <- vertices.indices
                j <- (i + 1) until vertices.size
              yield edgeBirth(
                lambda(vertices(i)),
                lambda(vertices(j)),
                ambientMetricSpace.distance(vertices(i), vertices(j)),
                epsilon
              )
            if edgeBirths.exists(_.isPosInfinity) then Double.PositiveInfinity
            else
              val raw = edgeBirths.max
              val vanishMin = vertices.map(v => vanishDoubled(lambda(v), epsilon)).min
              if raw > vanishMin then Double.PositiveInfinity else raw
        )

  /** The largest FINITE edge birth this construction produces on `ambientMetricSpace` -- a valid `maxFiltrationValue`
    * default because every finite simplex's own value is a `max` over its edges (see the class doc). Materializes every
    * pairwise `edgeBirth` once, `O(n^2)` like the rest of this reference implementation. `0.0` if every pair is
    * excluded (degenerate: a single point, or an `epsilon` too small for any two points to ever connect).
    */
  def maxFiniteFiltrationValue(
    ambientMetricSpace: FiniteMetricSpace[Int],
    permutation: GreedyPermutation,
    epsilon: Double
  ): Double =
    val lambda = permutation.insertionRadius
    val vs = ambientMetricSpace.elements.toIndexedSeq
    val finiteBirths =
      for
        i <- vs.indices
        j <- (i + 1) until vs.size
        b = edgeBirth(lambda(vs(i)), lambda(vs(j)), ambientMetricSpace.distance(vs(i), vs(j)), epsilon)
        if b.isFinite
      yield b
    if finiteBirths.isEmpty then 0.0 else finiteBirths.max

/** A greedy (farthest-point) permutation of a whole metric space: `order` lists every point (`order(0)` the seed), and
  * `insertionRadius(p)` is `p`'s distance to the points before it (`Infinity` for the seed). Computed by
  * `LandmarkSelector.maxmin` with as many landmarks as points.
  */
case class GreedyPermutation(order: IndexedSeq[Int], insertionRadius: Map[Int, Double])
