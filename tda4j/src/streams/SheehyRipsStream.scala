package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap

/** The linear-size approximate/sparse Vietoris-Rips filtration of Cavanna, Jahanseir & Sheehy, "A Geometric Perspective
  * on Sparse Filtrations" (arXiv:1506.03797, CCCG 2015) -- checked directly against the paper's own
  * Definitions/Algorithm 1-4 and Lemma 1/Corollary 2/Theorem 5 (`.claude/WORKLOG-sheehy-rips.md` has the fetched PDF
  * pages and the derivation). This is the GREEDY-PERMUTATION reformulation of Sheehy's original net-tree-based
  * construction (D.R. Sheehy, "Linear-Size Approximations to the Vietoris-Rips Filtration," Discrete & Computational
  * Geometry 49(4), 2013, arXiv:1203.6786) -- simpler to implement correctly and to verify, at the cost of the two
  * papers' own `epsilon` parameters NOT being directly comparable (Sheehy 2013's approximation factor is
  * `1/(1-2*epsilon)`; this class's, following CJS 2015, is `(1+epsilon)`).
  *
  * '''What this buys you''': a filtration whose persistence barcode is a genuine `(1+epsilon)`-multiplicative
  * approximation (Theorem 5) to the plain Vietoris-Rips barcode, built from a complex whose SIZE is linear in `n` (CJS
  * 2015 Lemma 6/7/Theorem 9/10) for point sets of bounded doubling dimension -- dramatically fewer simplices to reduce
  * than plain VR at the same scale range, in exchange for a controlled, quantified loss of precision.
  *
  * '''What this class does NOT do''': the paper's own `O(n log n)` algorithm (Section 5, Algorithms 1-4) builds the
  * edges of the sparse filtration directly from the greedy permutation's neighbor structure, touching only `kappa^O(d)`
  * candidates per point. This class instead computes every pairwise `edgeBirth` directly (`O(n^2)`, like plain
  * `VietorisRips`/`RipserCofaceSimplexStream`'s own default candidate enumeration) and lets
  * `RipserCofaceSimplexStream`'s ordinary combinatorial coface generation do the rest. The payoff here is a SMALLER
  * complex to reduce, not a faster one to build -- see the class doc's own honest framing on this point in every other
  * "not the fastest construction" case in this codebase (Cech, Witness, alpha).
  *
  * ==The construction==
  *
  * Given a greedy permutation (`streams.GreedyPermutation`, computed by `LandmarkSelector.maxmin` run to
  * `numLandmarks = ambientMetricSpace.size`) with insertion radii `lambda_p` (`lambda` of the very first point is
  * `Double.PositiveInfinity` by convention -- it must never be pruned away, since it anchors the whole construction),
  * and a sparsity parameter `epsilon in (0,1)`:
  *
  *   - each point `p`'s ball radius at scale `alpha` is `r_p(alpha) := min(alpha, lambda_p*(1+epsilon)/epsilon)` (CJS
  *     2015 Section 3) -- grows with `alpha` until it saturates;
  *   - `p`'s ball becomes and stays EMPTY once `alpha > lambda_p*(1+epsilon)^2/epsilon` (`vanish(p)` below) -- no new
  *     simplex may use `p` past that scale, though the filtration itself never removes anything already present
  *     (`S^alpha := union_{delta <= alpha} Q^delta`, CJS 2015 Section 4 -- a running union of nerves, which is what
  *     makes it an honest, monotone filtration despite individual balls disappearing);
  *   - an edge `{p,q}` (`lambda_p <= lambda_q` WLOG) is born at the smallest `alpha` with `d(p,q) <= r_p(alpha) +
  *     r_q(alpha)` -- CJS 2015 Algorithm 3 (`EdgeBirthTime`), reproduced in `edgeBirth` below;
  *   - a `k`-simplex `sigma` (`k >= 1`) is born at `max` over its own edges' birth times, PROVIDED that value is
  *     `<= min_{p in sigma} vanish(p)` -- otherwise it never appears at all (CJS 2015 Section 5.3, "SimplexBirthTime"
  *     -- the max/min intersection is valid because balls are convex and pairwise-intersecting convex sets have a
  *     common intersection, the same Helly-type fact that makes plain Rips itself a flag/nerve complex).
  *
  * '''A real gap in CJS 2015's own Algorithm 3, verified, not just suspected''': that algorithm computes an edge's
  * birth from only the TWO endpoints' own thresholds, with no check against `vanish` -- but Section 5.3's own
  * `SimplexBirthTime` definition (the general `k`-simplex rule above) requires exactly that check, and an edge is
  * simply its `k=1` case, not a special one. Two independent counterexamples confirm this is a real gap in the
  * published algorithm, not an artifact of skipping its neighbor-search prefilter: `epsilon=1, lambda_p=1, lambda_q=10,
  * d=10` (raw formula gives `8`, but `p` vanishes at `4`); and, checked directly against the paper's OWN Lemma 6/7
  * neighbor bound (`kappa = (epsilon^2+3*epsilon+2)/epsilon`, `d(p_i,p_j) <= kappa*2^ceil(lg lambda_i)` puts `p_j` in
  * `p_i`'s own candidate neighbor list), `epsilon=1, lambda_p=1.1, lambda_q=10, d=10` -- this pair passes the paper's
  * own restricted neighbor search AND its Algorithm 3 (returning `7.8`), while `p` vanishes at `4.4`. Whether the
  * paper's full `O(n log n)` pipeline compensates for this some other way was not checked; only the gap itself was
  * verified. `edgeBirth` here applies the `vanish` clamp Section 5.3 describes to every edge, not just higher
  * simplices.
  *
  * '''Units''': every OTHER stream in this codebase records filtration values in "diameter" units (an edge's own value
  * is the raw ambient `distance`, not half of it) -- but CJS 2015's own `alpha` is a RADIUS parameter (`R_alpha := {J :
  * max d(p,q) <= 2*alpha}`; their own Algorithm 3 literally returns `d/2` in its first branch). Every output this class
  * reports -- `edgeBirth`'s return value and `vanish` -- is therefore the paper's own value DOUBLED, never `lambda`
  * itself (an ordinary ambient distance already in diameter-comparable units, entering the formulas unchanged). After
  * doubling, the first (unsparsified) branch collapses to exactly `d`, matching plain VR's own edge value -- a useful
  * internal sanity check, exercised directly by `SheehyRipsStreamSpec`'s "reduces to plain VR" fixture.
  *
  * '''`maxFiltrationValue`''' is always clamped to `maxFiniteFiltrationValue` -- the largest FINITE simplex birth this
  * construction can ever produce (an honest, data-dependent bound: any finite simplex's value is a max over its own
  * edges, so the largest finite EDGE birth bounds every finite simplex) -- REGARDLESS of what the caller passes (`None`
  * resolves to exactly that bound; an explicit `Some(x)` is `min(x, maxFiniteFiltrationValue)`). This is deliberately
  * NOT `metricSpace.minimumEnclosingRadius` (this is not a cone construction -- an edge to the anchor point can be
  * unboundedly large, so no point has a finite max distance to every other point), and the clamp is unconditional
  * rather than only a `None`-default specifically so that an explicit `Some(Double.PositiveInfinity)` -- how every
  * other stream in this codebase spells "untruncated" -- stays safe here too: `keptByThresholdAndCriterion`'s own `<=`
  * comparison is plain IEEE-754 `Double` comparison, under which `Double.PositiveInfinity <= Double.PositiveInfinity`
  * is `true`, so an UNCLAMPED literal infinite threshold would silently readmit every simplex this construction is
  * supposed to exclude forever.
  *
  * '''Not a diameter-only construction''': unlike plain VR (and like the general, non-flag `WitnessCofaceSimplexStream`
  * and `DtmRipsSimplexStream`), a simplex's value here is not simply the maximum ambient pairwise distance among its
  * vertices, so `matlab.TDA4j` refuses `engine=ripser` for this complex (both Ripser engines' incremental
  * `insertionDiameter`/apparent-pairs machinery assume the filtration functional literally IS
  * `MaximumDistanceFiltrationValue` on the metric space handed to them). `naive`/`chunks`/`cohomology` all consume it
  * like any other `CofaceSimplexStream[Int, Double]`; `chunks` is cross-validated fresh against `naive`
  * (`SheehyRipsStreamSpec`), not assumed to carry over.
  */
class SheehyRipsSimplexStream(
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

object SheehyRipsSimplexStream:

  /** Builds the greedy permutation itself (via `LandmarkSelector.maxmin` run to full size, see that method's own doc)
    * before delegating to the primary constructor -- the convenience entry point most callers want. Validates `epsilon`
    * FIRST, before doing any of that `O(n^2)` work: the primary constructor's own `require` runs only after its
    * superclass's constructor arguments (including `maxFiniteFiltrationValue`) are already evaluated, so relying on
    * that alone would waste a full pairwise pass on an input that was always going to be rejected.
    */
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

  /** `lambda*(1+epsilon)^2/epsilon`, in the SAME (undoubled, radius) units `edgeBirth`'s own internal `raw` is computed
    * in -- deliberately not doubled here, since `edgeBirth` compares against it before doubling its own result.
    * `vanishDoubled` below is the doubled, public version higher-dimensional simplices compare against.
    */
  private def vanish(lambda: Double, epsilon: Double): Double =
    lambda * (1.0 + epsilon) * (1.0 + epsilon) / epsilon

  /** The doubled (diameter-units) vanish time -- the scale beyond which `p`'s ball is empty and it can no longer
    * participate in any newly-appearing simplex.
    */
  def vanishDoubled(lambda: Double, epsilon: Double): Double = 2.0 * vanish(lambda, epsilon)

  /** Dimension 0 => 0.0 (every point is born at scale 0, exactly like plain VR -- `b_p(0) = ball(p,0) = {p}`, always
    * nonempty); dimension >= 1 => `max` over the simplex's own pairwise `edgeBirth`s, `Double.PositiveInfinity` if any
    * pairwise value already is one OR if that max exceeds the smallest `vanishDoubled` among the simplex's own vertices
    * (CJS 2015 Section 5.3's `SimplexBirthTime`, applied uniformly from dimension 1 up -- see the class doc's note on
    * why `edgeBirth` alone is not sufficient at dimension 1 either). Memoized: `Chain`'s reduction consults
    * `filtrationValue` on every pivot comparison, and this is an `O(k^2)` pairwise scan per simplex.
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

/** A full greedy permutation (farthest-point / "maxmin" sampling) of an ENTIRE finite metric space -- as opposed to
  * `streams.LandmarkSelection`, which picks a SUBSET. `order` is every ambient index in selection order (`order(0)` is
  * the seed point); `insertionRadius(p)` is `p`'s own `lambda_p = d(p, {points ordered before p})`, with
  * `insertionRadius(order(0)) = Double.PositiveInfinity` by convention (there is no "distance to the empty set", and
  * the seed point must never be pruned away by any downstream sparsification). Built by `LandmarkSelector.maxmin` run
  * to `numLandmarks = metricSpace.size` (see that method's own doc) -- this type just names the result's intended use
  * (`SheehyRipsSimplexStream`) distinctly from a landmark subset.
  */
case class GreedyPermutation(order: IndexedSeq[Int], insertionRadius: Map[Int, Double])
