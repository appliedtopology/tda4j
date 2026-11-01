package org.appliedtopology.tda4j

import scala.collection.immutable

/** The filtered Dowker complex of a relation `R: L x W -> [0, Infinity]` between two finite sets (C. H. Dowker,
  * "Homology groups of relations", Ann. of Math. 56 (1952); filtered as in Chowdhury and Mémoli, "A functorial Dowker
  * theorem and persistent homology of asymmetric networks", 2018). A set `sigma` of rows is a simplex at `t` when one
  * witness `w` sees all of it by then:
  * {{{
  * f(sigma) = min_w max_{x in sigma} R(x, w)
  * }}}
  * `R` need not come from a metric. The unfiltered Dowker complex is the case `R(x, w)` in `{0, Infinity}`
  * ([[DowkerGeometry.fromBoolean]]). With `R` the landmark-to-witness distances (`WitnessGeometry.D`) it is the Čech
  * complex of the landmarks with the balls' common point required to be a witness. That is contained in every witness
  * complex but is none of them: the lazy witness complex with `nu = 0` is its flag complex (the same edges).
  *
  * The filtration is monotone (`f(tau) <= f(sigma)` for `tau` a face of `sigma`, since the max over a subset is smaller
  * for every `w`), vertices have values `min_w R(x, w)` that are generally nonzero, and the complex is not a flag
  * complex, so the Ripser engines do not apply (MATLAB and the CLI also do not offer chunks for relations).
  *
  * '''Duality''': the complexes of `R` (on the rows) and of its transpose ([[dual]], on the columns) are homotopy
  * equivalent at every `t`, and their persistence modules are isomorphic, so their barcodes agree. This holds for the
  * barcodes without zero-length bars, the default: with `includeZeroLength = true` the two sides differ, since each
  * vertex contributes an H₀ birth and the sides have different numbers of vertices.
  */
open class DowkerGeometry(val relation: Array[Array[Double]]):
  val numLeft: Int = relation.length
  val numWitnesses: Int = if numLeft == 0 then 0 else relation(0).length

  require(numLeft >= 1, "a Dowker relation needs at least one row (left-side point)")
  require(numWitnesses >= 1, "a Dowker relation needs at least one column (witness)")
  require(relation.forall(_.length == numWitnesses), "every row of `relation` must have the same length")
  require(relation.forall(_.forall(v => v >= 0.0)), "a Dowker relation's values must be non-negative")

  /** `min_w max_{x in sigma} R(x, w)`, in time `O(numWitnesses * sigma.size)`. */
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

  /** The same value for the simplex with the ascending vertices `vertices(from until from + width)`, with nothing built
    * for it.
    */
  def filtrationValue(vertices: Array[Int], from: Int, width: Int): Double =
    var best = Double.PositiveInfinity
    var w = 0
    while w < numWitnesses do
      var dmax = 0.0
      var i = from
      while i < from + width do
        val d = relation(vertices(i))(w)
        if d > dmax then dmax = d
        i += 1
      if dmax < best then best = dmax
      w += 1
    best

  /** The transposed relation: the columns become the vertices, witnessed by the rows. */
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

/** [[DowkerGeometry.filtrationValue]], memoized. Vertices get their own (generally nonzero) value. */
object DowkerFiltration:
  def apply(geometry: DowkerGeometry): PartialFunction[Simplex[Int], Double] =
    val cache = FiltrationCache.empty[Simplex[Int], Double]
    MemoizedFiltration(cache, spx => spx.forall(v => 0 <= v && v < geometry.numLeft))(spx =>
      geometry.filtrationValue(spx.toIndexedSeq)
    )

/** [[DowkerGeometry.filtrationValue]] for the candidates of a run, one parent after another: a candidate is a new row
  * on top of its parent, so each witness's largest entry over the parent's rows is computed once per parent and the
  * candidate takes the minimum over witnesses of the larger of that and its own row. A maximum and a minimum, so the
  * same value bit for bit. Not for two threads at once: one per run (`perRun`).
  */
// File-private: it holds the maxima of the parent it saw last, which is only right inside one run of candidates.
private final class DowkerFlatFiltration(geometry: DowkerGeometry) extends RipserCofaceSimplexStream.FlatFiltration:
  private val maxima = new Array[Double](geometry.numWitnesses)
  private var parent = new Array[Int](0)
  private var parentSeen = false

  def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
    if width == 1 then geometry.filtrationValue(vertices, from, width)
    else
      if !isParent(vertices, from + 1, width - 1) then setParent(vertices, from + 1, width - 1)
      val row = geometry.relation(vertices(from))
      var best = Double.PositiveInfinity
      var w = 0
      while w < maxima.length do
        val d = row(w)
        val m = maxima(w)
        val larger = if d > m then d else m
        if larger < best then best = larger
        w += 1
      best

  private def isParent(vertices: Array[Int], from: Int, width: Int): Boolean =
    var same = parentSeen && parent.length == width
    var i = 0
    while same && i < width do
      same = parent(i) == vertices(from + i)
      i += 1
    same

  private def setParent(vertices: Array[Int], from: Int, width: Int): Unit =
    if parent.length != width then parent = new Array[Int](width)
    System.arraycopy(vertices, from, parent, 0, width)
    parentSeen = true
    java.util.Arrays.fill(maxima, 0.0)
    var i = 0
    while i < width do
      val row = geometry.relation(parent(i))
      var w = 0
      while w < maxima.length do
        if row(w) > maxima(w) then maxima(w) = row(w)
        w += 1
      i += 1

/** Stands in for `RipserCofaceSimplexStream`'s own `FiniteMetricSpace[Int]` constructor parameter, purely to supply
  * `size`/`elements`/`contains` over `0 until n` -- `distance` is never actually read, since
  * `DowkerCofaceSimplexStream` always supplies its own `filtrationValueOverride` for every dimension including edges
  * (same "lazy, not eager" reason `WitnessMetricSpace` gives for the general witness variant's own identical
  * situation). A relation is not a metric, so this is a dedicated placeholder rather than reusing/misusing
  * `ExplicitMetricSpace` with a throwaway matrix.
  */
// File-private: it answers 0 for every distance, so it is only fit to fill the constructor slot explained above.
private class DowkerPlaceholderMetricSpace(n: Int) extends FiniteMetricSpace[Int]:
  def distance(x: Int, y: Int): Double = 0.0
  def size: Int = n
  def elements: Iterable[Int] = 0 until n
  def contains(x: Int): Boolean = 0 <= x && x < n

/** The filtered Dowker complex on the rows of `geometry.relation` (vertex `i` is row `i`); [[dual]] gives the one on
  * the columns. `maxFiltrationValue` defaults to `Infinity`: a relation has no enclosing radius to truncate at.
  */
open class DowkerCofaceSimplexStream(
  val geometry: DowkerGeometry,
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = CofaceSimplexStream.keepAll
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
  override protected def keptValue(value: Double): Boolean = super.keptValue(value) && value.isFinite

  private val flat = new RipserCofaceSimplexStream.FlatFiltration:
    def value(vertices: Array[Int], from: Int, width: Int, parentValue: Double): Double =
      geometry.filtrationValue(vertices, from, width)
    override def perRun: RipserCofaceSimplexStream.FlatFiltration = new DowkerFlatFiltration(geometry)

  override protected def flatFiltration: Option[RipserCofaceSimplexStream.FlatFiltration] = Some(flat)

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

  /** The Dowker complex on the columns, with the same threshold. A new stream each call, so the two sides can be
    * iterated independently.
    */
  def dual: DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry.dual, maxFiltrationValue, keepCriterion)

object DowkerCofaceSimplexStream:
  def apply(
    relation: Array[Array[Double]],
    maxFiltrationValue: Double = Double.PositiveInfinity,
    keepCriterion: PartialFunction[Simplex[Int], Boolean] = CofaceSimplexStream.keepAll
  ): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(DowkerGeometry(relation), maxFiltrationValue, keepCriterion)

  /** Overloads taking an already-built `DowkerGeometry` directly -- e.g. a caller who already has one on hand (as
    * `.dual` does), avoiding rebuilding it from a raw matrix. Scala forbids default arguments on more than one
    * overloaded `apply` variant (same constraint `WitnessCofaceSimplexStream.apply` documents), so these mirror the
    * primary constructor's own defaults by hand instead of sharing them.
    */
  def apply(geometry: DowkerGeometry): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, Double.PositiveInfinity, CofaceSimplexStream.keepAll)

  def apply(geometry: DowkerGeometry, maxFiltrationValue: Double): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, maxFiltrationValue, CofaceSimplexStream.keepAll)

  def apply(
    geometry: DowkerGeometry,
    maxFiltrationValue: Double,
    keepCriterion: PartialFunction[Simplex[Int], Boolean]
  ): DowkerCofaceSimplexStream =
    new DowkerCofaceSimplexStream(geometry, maxFiltrationValue, keepCriterion)
