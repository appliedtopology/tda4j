package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable

/** The (filtered) Dowker complex of a relation `R: L x W -> [0, Infinity]` between two, generally distinct, finite
  * sets `L` ("left") and `W` ("witnesses"), after Dowker's own theorem (C.H. Dowker, "Homology groups of relations",
  * Ann. of Math. 56 (1952)), in the real-valued generalization used e.g. by Chowdhury & Mémoli ("A functorial Dowker
  * theorem and persistent homology of asymmetric networks", 2018): a subset `sigma subseteq L` is a simplex at
  * filtration value `t` iff some `w in W` witnesses every element of `sigma` by time `t`, i.e.
  * `f(sigma) = min_{w in W} max_{x in sigma} R(x, w) <= t`. Unlike De Silva-Carlsson's witness complex (`nu`-indexed,
  * `WitnessStream.scala`), `R` here is an ARBITRARY non-negative relation -- not necessarily derived from a metric, and
  * `L`/`W` need not be subsets of a common ambient space or of each other. The classical (unfiltered, boolean) Dowker
  * complex is the special case `R(x,w) in {0, Infinity}` (`DowkerGeometry.fromBoolean`): `sigma` is a simplex iff some
  * `w` relates to every `x in sigma`, exactly as usual, with no notion of "when."
  *
  * '''Monotone by construction, no recursive facet clamp needed''' (contrast `WitnessCofaceSimplexStream`'s own
  * `recursiveFiltrationValue`, needed there only because its per-dimension threshold `m_k` genuinely changes the
  * formula from one dimension to the next): for `tau subseteq sigma`, `max_{x in tau} R(x,w) <= max_{x in sigma} R(x,w)`
  * for every single `w`, so taking `min_w` on both sides preserves the inequality: `f(tau) <= f(sigma)`. This is
  * CLAUDE.md's ordering-contract rule 3 (`fv(face) <= fv(coface)`), proved directly from the formula rather than
  * enforced by a facet-floor clamp the way `CechFiltration`'s own ULP guard is.
  *
  * '''Not a flag complex, in general''': `f(sigma)` is not determined by `sigma`'s own edges alone (the minimizing
  * witness `w` for a triangle need not be the one that witnesses any of its edges) -- same non-flag status as Cech and
  * the general witness complex, and for the identical underlying reason (a "some witness sees the whole set at once"
  * condition, not a pairwise one). So this construction is built on `RipserCofaceSimplexStream`'s generic "try every
  * remaining vertex against every already-accepted lower-dimensional simplex" coface loop (valid for ANY
  * downward-closed criterion, per that class's own doc), never on the flag-specific incremental-diameter machinery
  * `PackedRipserCohomologyContext`/`RipserCohomologyContext` depend on -- `engine=ripser`/`chunks` are not offered for
  * this construction (see `matlab.TDA4j`'s dispatch, once wired) for the same reason they aren't for the general
  * witness complex or Cech.
  *
  * '''Vertices carry real, possibly distinct, nonzero filtration values''' (`f({x}) = min_w R(x,w)`) -- the same
  * situation `DtmRipsSimplexStream` was the first stream in this codebase to hit, and the same fix applies:
  * `DowkerCofaceSimplexStream`'s own `case 0` must be sorted by `filtrationOrdering.reverse` and filtered by threshold
  * like every other dimension, unlike plain VR's base-class shortcut (every vertex tied at 0, so sorting/filtering is a
  * harmless no-op there but a correctness requirement here).
  *
  * '''Duality is the point''': Dowker's theorem says the `L`-side complex (this class, vertices = rows of `R`) and the
  * `W`-side complex (vertices = columns, built from `R`'s transpose -- `DowkerGeometry.dual`/`.dual` below) are homotopy
  * equivalent at EVERY threshold `t` (the simplicial complexes of the relation and its transpose are simplicially
  * homotopy equivalent, not merely isomorphic in homology), and the FUNCTORIAL form of the theorem (Chowdhury & Mémoli,
  * "A functorial Dowker theorem and persistent homology of asymmetric networks", 2018) extends this to the whole
  * filtration at once: the two sides' persistence MODULES are naturally isomorphic, so their barcodes agree exactly --
  * '''once zero-persistence (birth == death) bars are dropped from both''' (`DowkerStreamSpec.dropZeroPersistence`).
  * Those are a total-order tie-break artifact, not a real topological feature (same status they already have
  * elsewhere in this codebase, e.g. `WitnessStreamSpec`'s own tie-heavy witness complexes) -- but they matter more
  * here than usual: if `numLeft != numWitnesses`, the RAW barcodes can't possibly match bar-for-bar even in principle,
  * since a simplicial filtration records exactly one `H_0` birth event per VERTEX, unconditionally, so a 3-row/4-column
  * relation's two sides literally have 3 vs. 4 raw `H_0` births -- confirmed by construction during this class's own
  * development (a hand-worked 3x4 example), not merely a theoretical aside. Every one of those extra raw births is
  * zero-persistence (the "extra" vertex is always born already-tied to an edge born at the identical filtration value),
  * so the filtered barcodes still agree exactly, as the theorem promises.
  *
  * This also directly subsumes the "witness complex from a distance matrix"
  * special case: `WitnessGeometry.witnessValue(sigma, m = _ => 0.0)` (De Silva-Carlsson's `nu = 0`) is EXACTLY this
  * class's `filtrationValue` with `R = D` (the landmark-to-witness distance matrix) -- not implemented by delegating to
  * `WitnessGeometry` (that class's own shape -- an ambient metric space plus a landmark subset -- doesn't fit a general
  * relation with no shared ambient space at all), but the same formula, independently re-derived, is worth knowing
  * about if the two ever need to be cross-checked against each other.
  */
class DowkerGeometry(val relation: Array[Array[Double]]):
  val numLeft: Int = relation.length
  val numWitnesses: Int = if numLeft == 0 then 0 else relation(0).length

  require(numLeft >= 1, "a Dowker relation needs at least one row (left-side point)")
  require(numWitnesses >= 1, "a Dowker relation needs at least one column (witness)")
  require(relation.forall(_.length == numWitnesses), "every row of `relation` must have the same length")
  require(relation.forall(_.forall(v => v >= 0.0)), "a Dowker relation's values must be non-negative")

  /** `min_{w} max_{x in sigma} R(x, w)` -- see the class doc for the monotonicity proof. `O(numWitnesses * sigma.size)`
    * per call, the same shape as `WitnessGeometry.witnessValue` (with an always-zero threshold `m`), independently
    * written here since this formula has no per-witness clamp to share code with.
    */
  def filtrationValue(sigma: IndexedSeq[Int]): Double =
    var best = Double.PositiveInfinity
    var w = 0
    while w < numWitnesses do
      var dmax = 0.0
      var i = 0
      while i < sigma.size do
        val d = relation(sigma(i))(w)
        if d > dmax then dmax = d
        i += 1
      if dmax < best then best = dmax
      w += 1
    best

  /** The dual geometry (transpose of `relation`): vertices become the ORIGINAL witnesses, witnessed in turn by the
    * original left-side points. Dowker's theorem is exactly the statement that the complex built from this and the
    * complex built from `this` are homotopy equivalent at every threshold -- see the class doc.
    */
  lazy val dual: DowkerGeometry =
    new DowkerGeometry(Array.tabulate(numWitnesses, numLeft)((w, x) => relation(x)(w)))

object DowkerGeometry:
  def apply(relation: Array[Array[Double]]): DowkerGeometry = new DowkerGeometry(relation)
  def apply(relation: Seq[Seq[Double]]): DowkerGeometry = new DowkerGeometry(relation.map(_.toArray).toArray)

  /** The classical boolean Dowker relation, lifted into real-valued form: `true` (related) becomes `0.0` (witnessed
    * from the start), `false` (unrelated) becomes `+Infinity` (never witnessed) -- so `filtrationValue(sigma)` is `0.0`
    * if `sigma` is a classical Dowker simplex (some `w` relates to every `x in sigma`) and `+Infinity` (never included,
    * at any finite threshold) otherwise, exactly recovering the unfiltered classical complex as the `t=0` slice.
    */
  def fromBoolean(relation: Seq[Seq[Boolean]]): DowkerGeometry =
    apply(relation.map(_.map(b => if b then 0.0 else Double.PositiveInfinity)))

/** `DowkerGeometry.filtrationValue`, memoized -- the same "a caller-supplied `filtrationValueOverride` is not cached
  * by `RipserCofaceSimplexStream` itself, so a genuinely expensive one must cache itself" reasoning as
  * `CechFiltration`/`WitnessCofaceSimplexStream.recursiveFiltrationValue`. Deliberately no `spx.dim <= 0 => 0.0` special
  * case (contrast `CechFiltration`/`MaximumDistanceFiltrationValue`): a Dowker vertex's own filtration value is
  * generally nonzero and meaningful, not a VR-style convention-only placeholder -- see the class doc.
  */
object DowkerFiltration:
  def apply(geometry: DowkerGeometry): PartialFunction[Simplex[Int], Double] =
    val cache = TrieMap.empty[Simplex[Int], Double]
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = spx.forall(v => 0 <= v && v < geometry.numLeft)
      def apply(spx: Simplex[Int]): Double = cache.getOrElseUpdate(spx, geometry.filtrationValue(spx.toIndexedSeq))

/** Stands in for `RipserCofaceSimplexStream`'s own `FiniteMetricSpace[Int]` constructor parameter, purely to supply
  * `size`/`elements`/`contains` over `0 until n` -- `distance` is never actually read, since `DowkerCofaceSimplexStream`
  * always supplies its own `filtrationValueOverride` for every dimension including edges (same "lazy, not eager" reason
  * `WitnessMetricSpace` gives for the general witness variant's own identical situation). A relation is not a metric,
  * so this is a dedicated placeholder rather than reusing/misusing `ExplicitMetricSpace` with a throwaway matrix.
  */
private class DowkerPlaceholderMetricSpace(n: Int) extends FiniteMetricSpace[Int]:
  def distance(x: Int, y: Int): Double = 0.0
  def size: Int = n
  def elements: Iterable[Int] = 0 until n
  def contains(x: Int): Boolean = 0 <= x && x < n

/** The (filtered) Dowker complex on the LEFT side of `geometry` -- see the class doc on `DowkerGeometry` above for the
  * full mathematical picture, monotonicity proof, and non-flag status. `maxFiltrationValue` defaults to `+Infinity`,
  * not `metricSpace.minimumEnclosingRadius`-style truncation (contrast every genuine-flag-complex VR stream in this
  * codebase): an arbitrary relation gives no cone argument to truncate against, the same reasoning
  * `WitnessCofaceSimplexStream`'s general (non-lazy) variant already documents for its own dimension-specific formula.
  *
  * Vertex ids in every emitted `Simplex[Int]` are indices into `geometry.relation`'s rows (`0 until geometry.numLeft`);
  * `.dual` gives the complex on the other side (`geometry.dual`'s rows, `geometry`'s original columns), which Dowker's
  * theorem guarantees is homotopy equivalent to this one at every threshold.
  */
class DowkerCofaceSimplexStream(
  val geometry: DowkerGeometry,
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true }
) extends RipserCofaceSimplexStream(
      DowkerPlaceholderMetricSpace(geometry.numLeft),
      keepCriterion,
      Some(maxFiltrationValue),
      Some(DowkerFiltration(geometry))
    ):

  // The base class's own `keptByThresholdAndCriterion` gates on `filtrationValue(spx) <= resolvedMaxFiltrationValue`
  // -- a `<=` that is TRUE when both sides are `+Infinity`. Every other stream in this codebase treats
  // `maxFiltrationValue = +Infinity` as "untruncated" and never actually produces a genuinely infinite
  // filtration value to compare against it (Witness's own `+Infinity`-default general variant, e.g., only ever
  // computes finite `witnessValue`s). `DowkerGeometry.fromBoolean` is different ON PURPOSE: it uses a literal
  // `+Infinity` to mean "never witnessed, at any finite time" (the classical Dowker complex's own "not a
  // simplex" case) -- so an unfiltered `+Infinity <= +Infinity` comparison would silently admit every candidate
  // regardless of whether any witness ever actually covers it, collapsing the whole construction to the full
  // simplex on `geometry.numLeft` vertices (confirmed empirically: the pentagon fixture below produced the
  // complete graph K5's 10 edges, not the intended 5-cycle, before this override existed). A finite value is
  // always `<= +Infinity` regardless of this override, so untruncated callers see no behavior change for any
  // simplex that is genuinely, finitely witnessed.
  override protected def keptByThresholdAndCriterion(spx: Simplex[Int]): Boolean =
    super.keptByThresholdAndCriterion(spx) &&
      filtrationValue.applyOrElse(spx, (_: Simplex[Int]) => Double.PositiveInfinity).isFinite

  // The base class's own inherited `case 0` (EnumeratingCofaceSimplexStream/RipserCofaceSimplexStream) emits
  // metricSpace.elements unsorted and unfiltered by threshold -- only safe when every vertex ties at filtration 0
  // (plain VR's convention). Dowker vertices don't: see the class doc's "vertices carry real ... values" paragraph,
  // the exact situation DtmRipsSimplexStream's own identical override already fixed once.
  override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] =
    val dim0: PartialFunction[Int, Iterator[Simplex[Int]]] = { case 0 =>
      currentDimensionCache = sortedByFiltration(
        (0 until geometry.numLeft).map(v => Simplex(v)).filter(keptByThresholdAndCriterion)
      ).to(immutable.Queue)
      currentDimension = 0
      lastDimensionCache = IndexedSeq.empty[Simplex[Int]]
      currentDimensionCache.iterator
    }
    dim0.orElse(super.iterateDimension)

  /** The dual-side Dowker complex (`geometry.dual`), same threshold and keep-criterion -- see the class doc's
    * "duality is the point" paragraph. Not memoized: cheap to construct (just wraps `geometry.dual`, itself memoized on
    * `DowkerGeometry`), and a caller driving both sides concurrently would otherwise share mutable coface-generation
    * state (`currentDimensionCache` et al.) it should not share.
    */
  def dual: DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry.dual, maxFiltrationValue, keepCriterion)

object DowkerCofaceSimplexStream:
  def apply(
    relation: Array[Array[Double]],
    maxFiltrationValue: Double = Double.PositiveInfinity,
    keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true }
  ): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(DowkerGeometry(relation), maxFiltrationValue, keepCriterion)

  /** Overloads taking an already-built `DowkerGeometry` directly -- e.g. a caller who already has one on hand (as
    * `.dual` does), avoiding rebuilding it from a raw matrix. Scala forbids default arguments on more than one
    * overloaded `apply` variant (same constraint `WitnessCofaceSimplexStream.apply` documents), so these mirror the
    * primary constructor's own defaults by hand instead of sharing them.
    */
  def apply(geometry: DowkerGeometry): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, Double.PositiveInfinity, { case _ => true })

  def apply(geometry: DowkerGeometry, maxFiltrationValue: Double): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, maxFiltrationValue, { case _ => true })

  def apply(
    geometry: DowkerGeometry,
    maxFiltrationValue: Double,
    keepCriterion: PartialFunction[Simplex[Int], Boolean]
  ): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, maxFiltrationValue, keepCriterion)
