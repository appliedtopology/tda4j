package org.appliedtopology.tda4j

import scala.collection.concurrent.TrieMap
import scala.collection.immutable

/** The DTM-filtration of Anai, Chazal, Glisse, Ike, Lecci, Rouvreau, Saulnier and Wasserman, "DTM-based
  * filtrations" (arXiv:1811.04757, Prop. 3.5), a weighted Rips filtration with weights `f(x)` the distance to measure
  * ([[DistanceToMeasure]]). Values are diameters, as in Vietoris-Rips and GUDHI; with `f = 0` (as for `k = 1`) this is
  * the Vietoris-Rips filtration. For `p = 1` it agrees with GUDHI's `DTMRipsComplex`.
  *
  * `p` is the ball-radius exponent of Def. 3.1 (not the exponent `q` of the distance to measure), `1` or `2`:
  *   - `p = 1`: `t(f_x, f_y, d) = max(f_x, f_y, (d + f_x + f_y) / 2)`, as in GUDHI;
  *   - `p = 2`: `t = max(f_x, f_y, sqrt(u² + f_x²))` with `u = (d² + f_y² - f_x²) / (2d)` when `|f_y² - f_x²| <= d²`, and
  *     `max(f_x, f_y)` otherwise (one ball contains the other).
  *
  * A flag complex on the weighted distances `2 t`, with vertex `x` born at `2 f(x)`. `maxFiltrationValue` defaults to
  * the minimum enclosing radius of the weighted distances, which is at least every vertex's birth and beyond which the
  * complex is a cone. Vertices are born at nonzero values, so the Ripser engines do not apply.
  */
private[tda4j] class DtmRipsSimplexStream(
  val reified: DtmRipsSimplexStream.DtmMetricSpace,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None,
  parallelFiltrationValue: Boolean = false
) extends RipserCofaceSimplexStream(
      reified,
      keepCriterion,
      maxFiltrationValue,
      Some(DtmRipsSimplexStream.filtrationValueOverride(reified)),
      parallelFiltrationValue
    ):
  def ambientMetricSpace: FiniteMetricSpace[Int] = reified.ambient
  def f: IndexedSeq[Double] = reified.f
  def p: Double = reified.p

  // The base class's own case 0 (inherited from EnumeratingCofaceSimplexStream/RipserCofaceSimplexStream) emits
  // metricSpace.elements unsorted and unfiltered -- see the class doc's "ordering contract" paragraph for why
  // that's only safe when every vertex ties at filtration 0, which is never true here.
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] =
    val dim0: PartialFunction[Int, Iterator[Simplex[Int]]] = { case 0 =>
      currentDimensionCache = sortedByFiltration(
        reified.elements.map(v => Simplex(v)).filter(keptByThresholdAndCriterion)
      ).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
      currentDimensionCache.iterator
    }
    dim0.orElse(super.iterateDimension)

private[tda4j] object DtmRipsSimplexStream:

  def apply(
    ambientMetricSpace: FiniteMetricSpace[Int],
    f: IndexedSeq[Double],
    p: Double = 1.0,
    keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
    maxFiltrationValue: Option[Double] = None,
    parallelFiltrationValue: Boolean = false
  ): DtmRipsSimplexStream =
    new DtmRipsSimplexStream(
      DtmMetricSpace(ambientMetricSpace, f, p),
      keepCriterion,
      maxFiltrationValue,
      parallelFiltrationValue
    )

  /** `t(f_x, f_y, d)` of Prop. 3.5 (a radius; the filtration uses `2 t`). */
  def edgeValue(fx: Double, fy: Double, d: Double, p: Double): Double =
    if p == 1.0 then math.max(fx, math.max(fy, (d + fx + fy) / 2.0))
    else if d <= 0.0 || math.abs(fy * fy - fx * fx) >= d * d then math.max(fx, fy)
    else
      val u = (d * d + fy * fy - fx * fx) / (2.0 * d)
      // Wrapped in max(fx, fy, ...) as a rounding guard only -- the in-regime branch is already provably
      // >= max(fx, fy) by construction (see the class doc), this just protects against float error at the
      // boundary between the two branches.
      math.max(fx, math.max(fy, math.sqrt(u * u + fx * fx)))

  /** Reifies the DTM-Rips 1-skeleton as a `FiniteMetricSpace[Int]` (doubled `2*t`) so it slots into
    * `RipserCofaceSimplexStream` unchanged for every dimension `>= 1`, the same trick `WitnessMetricSpace` uses for the
    * lazy witness complex. '''Not a real metric''' (no triangle-inequality guarantee) -- never hand this to
    * `JVPTree`/`SparseMetricSpace`/`alpha`, only to combinatorial coface generation.
    */
  class DtmMetricSpace(val ambient: FiniteMetricSpace[Int], val f: IndexedSeq[Double], val p: Double = 1.0)
      extends FiniteMetricSpace[Int]:
    require(p == 1.0 || p == 2.0, s"p must be 1.0 or 2.0 (the only closed forms this class implements), got $p")
    require(f.size == ambient.size, s"f must have one entry per point of ambient (${ambient.size}), got ${f.size}")
    require(f.forall(_ >= 0.0), "distance-to-measure values must be nonnegative")

    def size: Int = ambient.size
    def elements: Iterable[Int] = ambient.elements
    def contains(x: Int): Boolean = ambient.contains(x)

    // Canonicalized to (min, max) index order before calling edgeValue: p=1's formula is bit-exactly symmetric
    // under argument swap (max(...) doesn't care about order, and floating-point addition is commutative), but
    // p=2's closed form is NOT -- it computes fy^2 - fx^2 as a literal difference, and floating-point
    // subtraction of two differently-rounded squares need not give bit-identical results under argument swap.
    // Canonicalizing means distance(x,y) and distance(y,x) always evaluate the identical expression, so there is
    // no ULP-level monotonicity risk here of the kind CechFiltration's own facet-floor clamp exists to guard
    // against (this function has no such clamp because it needs none, not because the risk was overlooked).
    def distance(x: Int, y: Int): Double =
      if x == y then 0.0
      else
        val (a, b) = if x < y then (x, y) else (y, x)
        2.0 * edgeValue(f(a), f(b), ambient.distance(a, b), p)

  /** `dim 0 => 2*f(x)`, `dim >= 1 => the inherited "max pairwise (doubled) distance" flag-complex default over
    * `reified` (a flag complex needs no dimension-specific formula beyond its own edge weights) -- memoized with its
    * own `TrieMap`, since `RipserCofaceSimplexStream` only memoizes the plain default it builds itself, never a
    * caller-supplied override (same reasoning as `CechFiltration`'s own cache).
    */
  def filtrationValueOverride(reified: DtmMetricSpace): PartialFunction[Simplex[Int], Double] =
    val base = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](reified)
    val cache = TrieMap.empty[Simplex[Int], Double]
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = base.isDefinedAt(spx)
      def apply(spx: Simplex[Int]): Double =
        cache.getOrElseUpdate(spx, if spx.dim <= 0 then 2.0 * reified.f(spx.min) else base(spx))
end DtmRipsSimplexStream
