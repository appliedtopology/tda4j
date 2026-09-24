package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Random

/** Greedy landmark-selection strategies over a `FiniteMetricSpace[Int]` -- the first step of building any witness
  * complex (De Silva & Carlsson, "Topological estimation using witness complexes", 2004; see
  * `.claude/WORKLOG-witness-complex.md`, checked against JavaPlex's own `LandmarkSelector` implementations). Landmarks
  * are always a SUBSET of the ambient point set (their own ambient indices), matching JavaPlex's convention -- not
  * arbitrary points outside it, which the original paper allows but no downstream tool actually uses.
  *
  * Both selectors assume `metricSpace`'s own `elements` are the contiguous range `0 until metricSpace.size` -- the same
  * assumption every other coface stream in this codebase already makes of its own `FiniteMetricSpace[Int]` (via
  * `SimplexIndexing`'s combinatorial-number-system enumeration; see `IntMetricSpace`'s own doc), not a new one
  * introduced here.
  */
object LandmarkSelector:

  /** Sequential maxmin (furthest-point) sampling: start from `firstLandmark`, then repeatedly add the point currently
    * furthest (in the ambient metric) from every landmark chosen so far. Deterministic given `firstLandmark` -- ties
    * are broken by lowest ambient index (iterating `sortedElements` ascending and using `maxBy`, whose "first
    * occurrence wins a tie" behavior does this for free), both for reproducibility and so tests can pin an exact
    * landmark set.
    *
    * Also returns the resulting covering radius `R = max_x min_{l in L} d(x,l)` (JavaPlex's own
    * `getMaxDistanceFromPointsToLandmarks()`) -- free, since the greedy loop already tracks `minDistToLandmarks` for
    * every point; callers use it to pick a `maxFiltrationValue` (e.g. `2R`, as the tutorial does).
    */
  def maxmin(metricSpace: FiniteMetricSpace[Int], numLandmarks: Int, firstLandmark: Int = 0): LandmarkSelection =
    require(
      numLandmarks >= 1 && numLandmarks <= metricSpace.size,
      s"numLandmarks must be between 1 and ${metricSpace.size}, got $numLandmarks"
    )
    require(metricSpace.contains(firstLandmark), s"firstLandmark $firstLandmark is not in the metric space")
    val sortedElements = metricSpace.elements.toIndexedSeq.sorted
    val landmarks = mutable.ArrayBuffer(firstLandmark)
    val minDistToLandmarks = mutable.Map.from(sortedElements.map(x => x -> metricSpace.distance(x, firstLandmark)))
    while landmarks.size < numLandmarks do
      val next = sortedElements.maxBy(minDistToLandmarks(_))
      landmarks += next
      for x <- sortedElements do minDistToLandmarks(x) = math.min(minDistToLandmarks(x), metricSpace.distance(x, next))
    LandmarkSelection(landmarks.toIndexedSeq, sortedElements.map(minDistToLandmarks(_)).max)

  /** Uniform random selection of `numLandmarks` distinct ambient indices, seeded for reproducibility. Cheaper than
    * `maxmin` (`O(size)` vs `O(numLandmarks * size)`) but gives no covering guarantee -- outliers can be missed
    * entirely, unlike maxmin's worst-case coverage bound. The covering radius is still computed and returned (an honest
    * `O(size * numLandmarks)` pass after the fact), for the same `maxFiltrationValue`-picking use as maxmin's.
    */
  def random(metricSpace: FiniteMetricSpace[Int], numLandmarks: Int, seed: Long): LandmarkSelection =
    require(
      numLandmarks >= 1 && numLandmarks <= metricSpace.size,
      s"numLandmarks must be between 1 and ${metricSpace.size}, got $numLandmarks"
    )
    val landmarks = new Random(seed).shuffle(metricSpace.elements.toIndexedSeq.sorted).take(numLandmarks)
    LandmarkSelection(landmarks, coveringRadius(metricSpace, landmarks))

  /** The covering radius of an ARBITRARY landmark set, not necessarily one `maxmin`/`random` chose --
    * `R = max_x min_{l in landmarks} d(x,l)`. `maxmin`/`random` already compute this as part of their own selection
    * loop and return it via `LandmarkSelection`; this standalone version is for a caller who already has a landmark set
    * (hand-picked, or reused from an earlier selection) and wants `R` for it -- e.g. to apply the JavaPlex tutorial's
    * own `2R` threshold recipe to landmarks it didn't just pick. `O(metricSpace.size * landmarks.size)`, the same cost
    * `random`'s own inline version (now just this call) always was.
    */
  def coveringRadius(metricSpace: FiniteMetricSpace[Int], landmarks: IndexedSeq[Int]): Double =
    metricSpace.elements.map(x => landmarks.map(l => metricSpace.distance(x, l)).min).max

/** `landmarks(i)` is the ambient index of the `i`-th landmark -- the mapping every witness-stream class below needs to
  * translate its own LOCAL `0 until landmarks.size` simplex vertex indices back to the caller's original point cloud
  * (`matlab.TDA4j` does this for `cycleVertices`). `coveringRadius` is `R = max_x min_l d(x,l)`.
  */
case class LandmarkSelection(landmarks: IndexedSeq[Int], coveringRadius: Double)

/** Precomputes and exposes the landmark<->witness distance geometry a witness complex is built from (De Silva &
  * Carlsson 2004; checked against JavaPlex's own `WitnessStream`/`LazyWitnessStream` -- see
  * `.claude/WORKLOG-witness-complex.md`). Every point of `ambientMetricSpace` is a witness (landmarks included,
  * matching JavaPlex's own `plex3Compatible = true` default), and `landmarks` is a subset of `ambientMetricSpace`'s own
  * ambient indices -- LOCAL landmark index `i` (`0 until landmarks.size`) corresponds to ambient index `landmarks(i)`.
  * Assumes `ambientMetricSpace.elements == 0 until ambientMetricSpace.size` (see `LandmarkSelector`'s own doc for why
  * that's not a new assumption).
  *
  * `D(l)(n)` is the distance from landmark `l` (local index) to witness `n` (ambient index) -- built once, eagerly: an
  * `L x N` matrix, exactly JavaPlex's own `D`. `O(L*N)` space/time, unavoidable since the witness-value formula below
  * reads a whole row per candidate landmark.
  */
class WitnessGeometry(val ambientMetricSpace: FiniteMetricSpace[Int], val landmarks: IndexedSeq[Int]):
  val L: Int = landmarks.size
  val N: Int = ambientMetricSpace.size

  require(L >= 1, "witness complex needs at least one landmark")
  require(landmarks.forall(ambientMetricSpace.contains), "every landmark must be a point of the ambient metric space")

  val D: Array[Array[Double]] =
    Array.tabulate(L, N)((l, n) => ambientMetricSpace.distance(landmarks(l), n))

  /** `sortedByWitness(n)` is witness `n`'s own row of `D` (its distance to every landmark), sorted ascending --
    * `sortedByWitness(n)(0)` is the nearest-landmark distance, `sortedByWitness(n)(k)` the `(k+1)`-th nearest.
    * Precomputed once per witness (`O(N*L log L)`, matching JavaPlex's own per-column `Arrays.sort`) since both the
    * lazy stream's single `m_nu` and the general stream's per-dimension `m_k` are just different indices into the SAME
    * sorted row.
    */
  private val sortedByWitness: Array[Array[Double]] =
    Array.tabulate(N)(n => Array.tabulate(L)(l => D(l)(n)).sorted)

  /** The `(k+1)`-th nearest landmark's distance to witness `n` (0-indexed: `mDim(0, n)` is the nearest-landmark
    * distance). Valid for `0 <= k < L`. This is JavaPlex's `WitnessStream.m[k][n]` -- no sentinel zero prepended,
    * unlike `WitnessMetricSpace.mNu` below (that sentinel is a `LazyWitnessStream`-only device).
    */
  def mDim(k: Int, witness: Int): Double = sortedByWitness(witness)(k)

  /** The De Silva-Carlsson witness value for the landmark set `sigma` (local indices), given a per-witness threshold
    * function `m`: `min over witnesses n of max(0, (max over l in sigma of D(l,n)) - m(n))`. Shared by both
    * `WitnessMetricSpace` (edges, `m = m_nu`, one global `nu`) and `WitnessCofaceSimplexStream` (a `k`-dimensional
    * simplex, `m = m_k`) -- JavaPlex's own `getWitnessAndDistance`/`addCofaces_` formula. Clamping `max(0, ...)` PER
    * WITNESS before taking the `min`, rather than clamping the min's own result, matches JavaPlex's code exactly and is
    * provably equivalent (`max(0, *)` is monotone nondecreasing, and a monotone function commutes with `min`) -- see
    * `.claude/WORKLOG-witness-complex.md`.
    */
  def witnessValue(sigma: IndexedSeq[Int], m: Int => Double): Double =
    var best = Double.PositiveInfinity
    var n = 0
    while n < N do
      var dmax = 0.0
      var i = 0
      while i < sigma.size do
        val d = D(sigma(i))(n)
        if d > dmax then dmax = d
        i += 1
      val candidate = math.max(0.0, dmax - m(n))
      if candidate < best then best = candidate
      n += 1
    best

/** The lazy witness complex's own 1-skeleton (De Silva & Carlsson 2004; JavaPlex's `LazyWitnessStream`), reified as a
  * `FiniteMetricSpace[Int]` over LOCAL landmark indices so it slots directly into `RipserCofaceSimplexStream` unchanged
  * (`LazyWitnessSimplexStream` below) -- the lazy witness complex IS, by definition, the flag/clique complex of this
  * weighted graph (JavaPlex's own `LazyWitnessStream` derives from `FlagComplexStream` for exactly this reason), so
  * `distance(a,b)` here doubles as both the edge filtration value AND (via the inherited
  * `MaximumDistanceFiltrationValue` "max pairwise distance" formula) every higher simplex's filtration value too -- no
  * `filtrationValueOverride` needed anywhere.
  *
  * '''Not a real metric''': `distance` can be zero for two distinct landmarks (whenever some witness sees both within
  * its own `m_nu` threshold) and need not obey the triangle inequality. NEVER hand this to `JVPTree`,
  * `SparseMetricSpace`, `RecursiveStackVietorisRipsSimplexStream`, or anything in the `alpha` package -- only to
  * `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`'s own combinatorial (not spatial) candidate generation,
  * which assumes neither property.
  *
  * `nu` (JavaPlex's own name) selects the per-witness threshold `m_nu`: `0` means no threshold (`m = 0` everywhere --
  * the strictest/smallest complex), `1` the nearest-landmark distance, `2` (JavaPlex's own default) the 2nd-nearest.
  * Capped at `[0, 2]`, matching JavaPlex's own `verifyLessThan(nu, 3)` -- beyond `nu = 2` the per-witness clamp stops
  * being a no-op for every landmark pair, an unstudied regime this class deliberately doesn't offer (see
  * `.claude/WORKLOG-witness-complex.md`).
  */
class WitnessMetricSpace(val geometry: WitnessGeometry, val nu: Int = 2) extends FiniteMetricSpace[Int]:
  require(nu >= 0 && nu <= 2, s"nu must be 0, 1, or 2 (JavaPlex's own range); got $nu")

  def size: Int = geometry.L
  def elements: Iterable[Int] = 0 until geometry.L
  def contains(x: Int): Boolean = 0 <= x && x < geometry.L

  private def mNu(witness: Int): Double = if nu == 0 then 0.0 else geometry.mDim(nu - 1, witness)

  // Lazy, not eager: only ever read by a consumer that genuinely needs pairwise "distance" (this class's own
  // consumer, LazyWitnessSimplexStream, via the inherited MaximumDistanceFiltrationValue/minimumEnclosingRadius).
  // WitnessCofaceSimplexStream (the general/eager variant below) supplies its own filtrationValueOverride and an
  // explicit maxFiltrationValue, so it never triggers this O(L^2 * N) computation at all despite needing a
  // WitnessMetricSpace instance to satisfy RipserCofaceSimplexStream's constructor.
  private lazy val cache: Array[Array[Double]] =
    Array.tabulate(geometry.L, geometry.L) { (i, j) =>
      if i == j then 0.0 else geometry.witnessValue(IndexedSeq(i, j), mNu)
    }

  def distance(x: Int, y: Int): Double = cache(x)(y)

/** The lazy witness complex (De Silva & Carlsson 2004; JavaPlex's `LazyWitnessStream`): the flag/clique complex of
  * `WitnessMetricSpace`'s own weighted 1-skeleton. A thin `RipserCofaceSimplexStream` subclass -- exactly the same
  * relationship `CechCofaceSimplexStream` has to `RipserCofaceSimplexStream`, except no `filtrationValueOverride` is
  * needed here at all: `WitnessMetricSpace.distance` already IS the edge filtration value, and the inherited default
  * ("max pairwise distance") is exactly the flag-complex extension to higher dimensions this construction wants.
  *
  * `maxFiltrationValue` inherits `EnumeratingCofaceSimplexStream`'s own default: `metricSpace.minimumEnclosingRadius`
  * under `WitnessMetricSpace.distance`. This IS a valid truncation here (unlike for `WitnessCofaceSimplexStream`
  * below): any flag complex is a cone past `min_x max_y d'(x,y)` regardless of whether `d'` is a genuine metric -- the
  * cone argument (Ripser paper p.412) only needs symmetry of `d'` and the flag property, both of which hold here -- see
  * `.claude/WORKLOG-witness-complex.md`.
  *
  * Vertex ids in every emitted `Simplex[Int]` are LOCAL landmark indices (`0 until landmarks.size`) -- translate back
  * through `landmarks(i)` for the caller's own ambient point cloud (`matlab.TDA4j` does this for `cycleVertices`).
  */
class LazyWitnessSimplexStream(
  ambientMetricSpace: FiniteMetricSpace[Int],
  val landmarks: IndexedSeq[Int],
  nu: Int = 2,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None,
  parallelFiltrationValue: Boolean = false
) extends RipserCofaceSimplexStream(
      WitnessMetricSpace(WitnessGeometry(ambientMetricSpace, landmarks), nu),
      keepCriterion,
      maxFiltrationValue,
      None,
      parallelFiltrationValue
    )

/** The general witness complex (De Silva & Carlsson 2004; JavaPlex's plain `WitnessStream`): unlike the lazy variant
  * above, NOT a flag complex -- a higher simplex's own witness condition uses a DIMENSION-SPECIFIC threshold `m_k` (the
  * `(k+1)`-th nearest landmark, `k` = the simplex's own dimension) that need not be monotone facet-to-coface on its
  * own, so every simplex's recorded filtration value is `max(own_k(sigma), max over its own facets' filtration values)`
  * (JavaPlex's own `addCofaces_`: `filtrationIndex = max(filtrationIndex, ...)` over the boundary, then maxed again
  * with the simplex's own witness value). This recursive max is what makes "the complex at threshold R" automatically
  * downward-closed for every `R` -- the same way VR's own "max pairwise distance" does -- and it also makes JavaPlex's
  * separate `containsElement(face)` gate redundant here (any facet whose own value exceeds a threshold forces its
  * coface's value above that threshold too, via the max): see `.claude/WORKLOG-witness-complex.md` for the proof. So,
  * unlike the eager reference implementation, `RipserCofaceSimplexStream`'s plain "generate from the canonical
  * (min-vertex-removed) facet, filter by filtrationValue <= threshold" shape is already correct once fed this recursive
  * filtration value -- no extra "are all my facets already accepted" check needed.
  *
  * `nu` plays no role here (each dimension has its own fixed `m_k`, not a caller-chosen parameter) -- the
  * `WitnessMetricSpace` handed to the superclass exists only to satisfy `RipserCofaceSimplexStream`'s constructor; its
  * `distance` is never actually read (see that class's own "lazy, not eager" note), since `filtrationValueOverride`
  * replaces `filtrationValue` for every dimension including edges, and `maxFiltrationValue` is always supplied
  * explicitly (default `+Infinity`, i.e. untruncated) rather than left `None` -- `None` would fall back to
  * `minimumEnclosingRadius`, NOT a valid truncation for a non-flag complex like this one (see
  * `.claude/WORKLOG-witness-complex.md`).
  *
  * '''Fact used to cross-validate against the lazy stream''' (`WitnessStreamSpec`): this class's own 1-skeleton is
  * IDENTICAL to `LazyWitnessSimplexStream(..., nu = 2)`'s -- both use the 2nd-nearest-landmark threshold for edges
  * (`m_1` here, `m_nu` there with its sentinel-shifted index), reached via different code paths.
  *
  * Built via the companion `apply` (below), not `new`, so the shared `WitnessGeometry` (`O(L*N)` to build) is computed
  * exactly once and reused both for the superclass's `WitnessMetricSpace` and for `recursiveFiltrationValue` --
  * constructing it twice from raw `(ambientMetricSpace, landmarks)` would silently duplicate that work.
  *
  * '''Performance hazard, standing for any unbounded coface stream, not specific to this one''': iterating this stream
  * at its default `maxFiltrationValue = +Infinity` enumerates EVERY dimension up to `landmarks.size - 1` -- the full
  * `2^L` power set for `L` landmarks, since nothing about the recursive filtration value ever prunes a candidate at an
  * unbounded threshold. `matlab.TDA4j`'s own facade avoids this by always wrapping in `LimitedCofaceSimplexStream` (its
  * `maxDimension` option defaults to reporting `H_0..H_2`, i.e. simplices up to 4 vertices); a caller driving this
  * class directly should do the same, or pass a finite `maxFiltrationValue` -- see `.claude/WORKLOG-witness-complex.md`
  * for tutorial-scale timing measurements (machine-specific, kept there rather than here).
  */
class WitnessCofaceSimplexStream(
  val geometry: WitnessGeometry,
  maxFiltrationValue: Double = Double.PositiveInfinity,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true }
) extends RipserCofaceSimplexStream(
      WitnessMetricSpace(geometry, nu = 2),
      keepCriterion,
      Some(maxFiltrationValue),
      Some(WitnessCofaceSimplexStream.recursiveFiltrationValue(geometry))
    ):
  def landmarks: IndexedSeq[Int] = geometry.landmarks

object WitnessCofaceSimplexStream:
  def apply(
    ambientMetricSpace: FiniteMetricSpace[Int],
    landmarks: IndexedSeq[Int],
    maxFiltrationValue: Double = Double.PositiveInfinity,
    keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true }
  ): WitnessCofaceSimplexStream =
    new WitnessCofaceSimplexStream(WitnessGeometry(ambientMetricSpace, landmarks), maxFiltrationValue, keepCriterion)

  /** Overloads taking an already-built `WitnessGeometry` directly (e.g. one a caller is also handing to
    * `recursiveFiltrationValue` separately, for cross-validation) -- avoids rebuilding it a second time. Scala forbids
    * default arguments on more than one overloaded `apply` variant, so these mirror the primary constructor's own
    * defaults (`Double.PositiveInfinity` / accept-everything) by hand instead of sharing them.
    */
  def apply(geometry: WitnessGeometry): WitnessCofaceSimplexStream =
    new WitnessCofaceSimplexStream(geometry, Double.PositiveInfinity, { case _ => true })

  def apply(geometry: WitnessGeometry, maxFiltrationValue: Double): WitnessCofaceSimplexStream =
    new WitnessCofaceSimplexStream(geometry, maxFiltrationValue, { case _ => true })

  def apply(
    geometry: WitnessGeometry,
    maxFiltrationValue: Double,
    keepCriterion: PartialFunction[Simplex[Int], Boolean]
  ): WitnessCofaceSimplexStream =
    new WitnessCofaceSimplexStream(geometry, maxFiltrationValue, keepCriterion)

  /** `max(own_k(sigma), max over sigma's own facets)`, memoized -- unlike `EnumeratingCofaceSimplexStream`'s own
    * `filtrationValueCache` (which only memoizes the DEFAULT `MaximumDistanceFiltrationValue` fallback), a
    * caller-supplied `filtrationValueOverride` is NOT memoized by the base class itself, and `Chain`'s reduction
    * consults `filtrationValue` on every pivot comparison -- so a genuinely expensive override (this one: O(N) per
    * call, before recursion) must cache itself, exactly like `CechFiltration` does.
    *
    * '''Not `cache.getOrElse(facet, 0.0)`''' (the shortcut `CechFiltration` uses for its own facet floor): unlike Cech,
    * this stream's candidate generation touches only ONE canonical facet per candidate (the one obtained by removing
    * the minimum vertex) before this function is ever asked about the candidate at all -- a facet other than that one
    * is generally NOT already cached, and defaulting it to `0.0` would silently drop it from the max, corrupting
    * monotonicity for exactly the simplices this recursion exists to get right. Recursing (`apply` calling itself on
    * every facet) computes it instead of assuming it, at the cost JavaPlex's own `containsElement` short-circuit exists
    * to avoid -- see the class doc for why that short-circuit isn't a correctness requirement here.
    */
  def recursiveFiltrationValue(geometry: WitnessGeometry): PartialFunction[Simplex[Int], Double] =
    val cache = TrieMap.empty[Simplex[Int], Double]
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = spx.forall(v => 0 <= v && v < geometry.L)
      def apply(spx: Simplex[Int]): Double =
        cache.getOrElseUpdate(
          spx,
          if spx.dim <= 0 then 0.0
          else
            val k = spx.dim
            val own = geometry.witnessValue(spx.toIndexedSeq, n => geometry.mDim(k, n))
            val facetsMax = spx.iterator.map(v => apply((spx.underlying - v).asSimplex)).max
            math.max(own, facetsMax)
        )
