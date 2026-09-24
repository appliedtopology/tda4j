package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable

/** The DTM-based (weighted Rips) filtration of Anai, Chazal, Glisse, Ike, Lecci, Rouvreau, Saulnier & Wasserman,
  * "DTM-based filtrations" (arXiv:1811.04757, Prop. 3.5), checked byte-for-byte against GUDHI's own
  * `gudhi.dtm_rips_complex.DTMRipsComplex`/`gudhi.weighted_rips_complex.WeightedRipsComplex` for `p = 1`
  * (`DtmRipsStreamSpec`; see `.claude/WORKLOG-dtm-filtrations.md` for the fetched source and every oracle value).
  * Values are in the same "doubled"/diameter units every other VR stream in this codebase uses (GUDHI's own
  * choice too, for the identical reason: consistency with plain, unweighted Rips, whose edge filtration is the
  * raw pairwise distance, not half of it).
  *
  * `f(x)` is the empirical distance-to-measure per ambient point (`streams.DistanceToMeasure`); at `f = 0`
  * everywhere (in particular at `k = 1`, `DistanceToMeasure`'s own degenerate case) this reduces EXACTLY to plain
  * Vietoris-Rips, threshold included -- `DtmRipsStreamSpec` checks this too.
  *
  * `p` selects the ball-radius exponent of Def. 3.1 -- NOT `DistanceToMeasure`'s own exponent `q`, a different
  * knob entirely. Only `p = 1` and `p = 2` are implemented:
  *   - `p = 1`: `t(f_x, f_y, d) = max(f_x, f_y, (d + f_x + f_y) / 2)`. The only variant GUDHI's own Python
  *     bindings implement, and the one every published worked example (including this class's own regression
  *     oracle) targets.
  *   - `p = 2`: closed form `t(f_x, f_y, d) = max(f_x, f_y, sqrt(u^2 + f_x^2))` where
  *     `u = (d^2 + f_y^2 - f_x^2) / (2d)`, valid (and symmetric in x/y -- verified algebraically, not just
  *     numerically) whenever `|f_y^2 - f_x^2| <= d^2`; otherwise one ball already contains the other and
  *     `t = max(f_x, f_y)` directly (the closed form overshoots outside that regime: e.g. `f_x=0, f_y=10, d=1`
  *     gives 50.5 from the raw formula against a true value of 10). GUDHI's own Python bindings do not implement
  *     this case at all, so unlike `p = 1` it is NOT checked against an external reference implementation here --
  *     it exists only as a cross-validation device against `alpha.PowerDistance`'s own DTM weighting (both are
  *     the `p = 2` ball equation, `.claude/WORKLOG-dtm-filtrations.md`'s cross-check), not as a recommended
  *     production default.
  *   - `p = Infinity` (Def. 3.1's third named case) is NOT implemented: no user need identified.
  *
  * A FLAG complex, exactly like plain Vietoris-Rips: `t` above is only ever used to build a reified, non-metric
  * `FiniteMetricSpace[Int]` (`DtmMetricSpace`, doubled: `2*t`) that gets handed to `RipserCofaceSimplexStream`
  * unchanged for every dimension `>= 1` -- the inherited default "max pairwise distance" filtration functional is
  * already exactly the flag-complex extension this construction wants (same relationship `WitnessMetricSpace` has
  * to `LazyWitnessSimplexStream`). The only override needed is at dimension 0: unlike plain VR (where every
  * vertex is born at filtration 0, so the base class's hardcoded `dim <= 0 => 0.0` and its unsorted, unfiltered
  * `case 0` vertex emission are both harmless -- see `.claude/WORKLOG-dtm-filtrations.md` for why), THIS is the
  * first coface stream in this codebase whose vertices have distinct, nonzero filtration values, so `case 0` MUST
  * be sorted by `filtrationOrdering.reverse` and filtered by threshold like every other dimension (ordering
  * contract rule 2 in CLAUDE.md) -- silently violating that would corrupt `Chain`'s pivot table exactly like the
  * historical "no tie-break" bugs did, and would let vertices past `maxFiltrationValue` leak into the complex as
  * spurious isolated components.
  *
  * `maxFiltrationValue` defaults (via `None`) to the reified space's own `minimumEnclosingRadius`, the same
  * convention every other flag-complex VR stream in this codebase uses (not GUDHI's own `max_filtration =
  * +Infinity` default) -- valid here for a real, checked reason, not just by analogy: `t(f_x, f_y, d) >=
  * max(f_x, f_y)` by construction for both `p = 1` and `p = 2` (immediate for `p=1`'s outer max; for `p=2`,
  * `t^2 = u^2 + f_x^2 >= f_x^2` and the symmetric `v = d - u` form gives `t^2 = v^2 + f_y^2 >= f_y^2` too), so
  * taking `x* = argmin_x max_y distance(x,y)` and `R = distance(x*,·)`'s own max: for every vertex `z`,
  * `2*f(z) <= distance(x*,z) <= R` (since `distance(x*,z) = 2*t(f_{x*},f_z,d) >= 2*f_z`) -- every vertex's own
  * birth is `<= R`, so truncating there cannot silently drop a vertex, and beyond `R` the complex is a cone from
  * `x*` exactly as in the unweighted case. Refuses `engine=ripser` in `matlab.TDA4j`'s dispatch (both Ripser
  * engines assume vertex births at 0 and a diameter-only incremental formula); `naive`/`chunks`/`cohomology` all
  * consume this like any other `CofaceSimplexStream[Int, Double]`.
  */
class DtmRipsSimplexStream(
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
      currentDimensionCache =
        sortedByFiltration(reified.elements.map(v => Simplex(v)).filter(keptByThresholdAndCriterion)).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
      currentDimensionCache.iterator
    }
    dim0.orElse(super.iterateDimension)

object DtmRipsSimplexStream:

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

  /** `t(f_x, f_y, d)` of Prop. 3.5, undoubled -- see the class doc for both closed forms and their derivations. */
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
    * `RipserCofaceSimplexStream` unchanged for every dimension `>= 1`, the same trick `WitnessMetricSpace` uses
    * for the lazy witness complex. '''Not a real metric''' (no triangle-inequality guarantee) -- never hand this
    * to `JVPTree`/`SparseMetricSpace`/`alpha`, only to combinatorial coface generation.
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
    * `reified` (a flag complex needs no dimension-specific formula beyond its own edge weights) -- memoized with
    * its own `TrieMap`, since `RipserCofaceSimplexStream` only memoizes the plain default it builds itself, never
    * a caller-supplied override (same reasoning as `CechFiltration`'s own cache).
    */
  def filtrationValueOverride(reified: DtmMetricSpace): PartialFunction[Simplex[Int], Double] =
    val base = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](reified)
    val cache = TrieMap.empty[Simplex[Int], Double]
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = base.isDefinedAt(spx)
      def apply(spx: Simplex[Int]): Double =
        cache.getOrElseUpdate(spx, if spx.dim <= 0 then 2.0 * reified.f(spx.min) else base(spx))
end DtmRipsSimplexStream
