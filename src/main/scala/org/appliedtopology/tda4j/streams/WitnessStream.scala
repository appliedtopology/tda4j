package org.appliedtopology.tda4j

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Random

/** Choosing the landmarks of a witness complex (de Silva and Carlsson, "Topological estimation using witness complexes",
  * 2004), as JavaPlex does: landmarks are a subset of the points, given by their numbers. Assumes the metric space's
  * elements are `0 until size`.
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
    *
    * Also returns each chosen landmark's OWN insertion radius (`LandmarkSelection.insertionRadius`), lambda in the
    * greedy-permutation literature: `lambda_p = d(p, {landmarks already chosen when p was added})`, i.e. exactly
    * `minDistToLandmarks(next)` read just before that iteration's update -- free for the same reason the covering
    * radius is. `firstLandmark`'s own lambda is `Double.PositiveInfinity` (there is no "distance to the empty set");
    * every later entry is a real, finite, non-increasing (in selection order) value. `numLandmarks = metricSpace.size`
    * gives the FULL greedy permutation of the whole space, not just a landmark subset -- this is how
    * `SheehyRipsSimplexStream` (Cavanna-Jahanseir-Sheehy 2015's sparse-filtration construction) gets its own greedy
    * permutation, reusing this loop rather than a second copy of it.
    */
  def maxmin(metricSpace: FiniteMetricSpace[Int], numLandmarks: Int, firstLandmark: Int = 0): LandmarkSelection =
    require(
      numLandmarks >= 1 && numLandmarks <= metricSpace.size,
      s"numLandmarks must be between 1 and ${metricSpace.size}, got $numLandmarks"
    )
    require(metricSpace.contains(firstLandmark), s"firstLandmark $firstLandmark is not in the metric space")
    val sortedElements = metricSpace.elements.toIndexedSeq.sorted
    val landmarks = mutable.ArrayBuffer(firstLandmark)
    // Tracked separately from `landmarks` and excluded from the maxBy candidates below -- NOT redundant with
    // `minDistToLandmarks(_) == 0.0`: an ALREADY-chosen point's own entry is 0 forever (distance to itself),
    // but so is any UNCHOSEN point that happens to be an exact duplicate of one -- real input, not a
    // pathological one (`SheehyRipsSimplexStream` hit this with duplicate coordinates in a generated point
    // cloud). Without this exclusion, `maxBy`'s "first occurrence wins a tie" rule can re-pick the (lower-index)
    // already-chosen point over the genuinely unchosen duplicate, silently dropping the duplicate from
    // `landmarks`/`insertionRadius` forever even once `numLandmarks == metricSpace.size` (a full permutation
    // then ends up with fewer than `metricSpace.size` DISTINCT entries).
    val chosen = mutable.Set(firstLandmark)
    val insertionRadius = mutable.Map[Int, Double](firstLandmark -> Double.PositiveInfinity)
    val minDistToLandmarks = mutable.Map.from(sortedElements.map(x => x -> metricSpace.distance(x, firstLandmark)))
    while landmarks.size < numLandmarks do
      val next = sortedElements.filterNot(chosen).maxBy(minDistToLandmarks(_))
      insertionRadius(next) = minDistToLandmarks(next)
      landmarks += next
      chosen += next
      for x <- sortedElements do minDistToLandmarks(x) = math.min(minDistToLandmarks(x), metricSpace.distance(x, next))
    LandmarkSelection(landmarks.toIndexedSeq, sortedElements.map(minDistToLandmarks(_)).max, insertionRadius.toMap)

  /** `numLandmarks` distinct points chosen uniformly at random (reproducibly, from `seed`). Cheaper than `maxmin`, with no
    * covering guarantee; the covering radius is still computed and returned.
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
  * (`matlab.TDA4j` does this for `cycleVertices`). `coveringRadius` is `R = max_x min_l d(x,l)`. `insertionRadius` maps
  * an ambient index to its own greedy-permutation lambda (see `maxmin`'s doc); empty for `random` (no meaningful
  * lambda) and for a hand-built selection not routed through `maxmin`.
  */
case class LandmarkSelection(
  landmarks: IndexedSeq[Int],
  coveringRadius: Double,
  insertionRadius: Map[Int, Double] = Map.empty
)

/** The distances between landmarks and witnesses that a witness complex is built from (de Silva and Carlsson 2004;
  * JavaPlex's `WitnessStream`). Every point is a witness, landmarks included. Landmark `i` (`0 until landmarks.size`) is
  * point `landmarks(i)`; `D(l)(n)` is the distance from landmark `l` to witness `n`, an `L x N` matrix built once.
  * Assumes the metric space's elements are `0 until size`.
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

  /** The witness value of the landmark set `sigma` for the per-witness threshold `m`: the minimum over witnesses `n`
    * of `max(0, max over l in sigma of D(l)(n) - m(n))`, as in JavaPlex.
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

/** The 1-skeleton of the lazy witness complex (JavaPlex's `LazyWitnessStream`) as a metric space on the landmark
  * numbers: the lazy witness complex is the flag complex of this weighted graph, so any Vietoris-Rips machinery
  * (including the Ripser engine) computes it.
  *
  * Not a metric: two landmarks can be at distance 0, and the triangle inequality can fail. Use it only with the
  * combinatorial Vietoris-Rips constructions, not with spatial data structures.
  *
  * `nu` (0, 1 or 2, default 2) is JavaPlex's threshold: a witness sees a pair of landmarks at the distance to its
  * `nu`-th nearest landmark beyond its own (0: no threshold, the smallest complex).
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

/** The lazy witness complex (JavaPlex's `LazyWitnessStream`): the flag complex of [[WitnessMetricSpace]]. Its
  * `maxFiltrationValue` defaults to the minimum enclosing radius of that metric space, which is a valid cutoff for any
  * flag complex. Vertices are landmark numbers.
  */
private[tda4j] class LazyWitnessSimplexStream(
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

/** The general witness complex (JavaPlex's `WitnessStream`). Not a flag complex: a `k`-simplex is witnessed with a
  * threshold depending on `k` (the `(k+1)`-th nearest landmark), so its filtration value is the larger of its own
  * witness value and its facets' values (JavaPlex's `addCofaces_`), which makes the complex monotone. Its 1-skeleton
  * is that of the lazy complex with `nu = 2`.
  *
  * `maxFiltrationValue` defaults to `Infinity`: the enclosing radius is no cutoff for a complex that is not a flag
  * complex. Without a finite value or a dimension cap, the stream enumerates every subset of the landmarks; `Witness`
  * caps the dimension. Built through the companion `apply`, which builds the [[WitnessGeometry]] once.
  */
private[tda4j] class WitnessCofaceSimplexStream(
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

private[tda4j] object WitnessCofaceSimplexStream:
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

  /** The larger of `sigma`'s own witness value and its facets' values, memoized. Computes every facet's value rather
    * than reading a cache, since only one facet of a candidate has been visited when it is asked.
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
