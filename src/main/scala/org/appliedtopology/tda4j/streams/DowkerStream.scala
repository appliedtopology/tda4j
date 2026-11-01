package org.appliedtopology.tda4j

import scala.collection.immutable
import scala.collection.mutable

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

  /** The column that sees every row of `sigma` soonest: the `w` attaining [[filtrationValue]], the lowest on ties. */
  def bestWitness(sigma: Simplex[Int]): Int =
    var best = Double.PositiveInfinity
    var bestColumn = 0
    var w = 0
    while w < numWitnesses do
      val dmax = sigma.iterator.map(x => relation(x)(w)).max
      if dmax < best then
        best = dmax
        bestColumn = w
      w += 1
    bestColumn

  /** `chain`, a chain of the complex on the rows, carried to the complex on the columns ([[dual]]) by the chain map
    * behind Dowker duality: a vertex goes to its [[bestWitness]], and a simplex `sigma` to the cone from
    * `bestWitness(sigma)` over the image of its boundary. Every simplex of the image is witnessed, on the column side,
    * by a row of `chain` within the value of the simplex of `chain` it came from, so a cycle of the row complex at `t`
    * goes to a cycle of the column complex at `t`. The map is an isomorphism of persistent homology: a representative
    * of a bar goes to a representative of the same bar on the other side, and a cycle that dies at `d` to one that dies
    * at `d`. A `k`-simplex has at most `(k+1)!` simplices in its image.
    *
    * For landmark-to-point distances (`WitnessGeometry.D`) the columns are the points, so this puts a landmark cycle on
    * the data: a loop through landmarks `l0, l1, ...` becomes the loop `l0, w01, l1, w12, ...`, where `w01` is the
    * point that witnesses the edge `l0 l1`. [[Dowker.lift]] does it for every bar of a diagram.
    */
  def lift[C: Field as field](chain: Chain[Simplex[Int], C]): Chain[Simplex[Int], C] =
    val images = mutable.HashMap.empty[Simplex[Int], Map[Simplex[Int], C]]
    def add(into: mutable.HashMap[Simplex[Int], C], cell: Simplex[Int], c: C): Unit =
      into(cell) = into.get(cell).fold(c)(field.plus(_, c))
    def nonzero(of: mutable.HashMap[Simplex[Int], C]): Map[Simplex[Int], C] =
      of.iterator.filterNot((_, c) => field.isEqual(c, field.zero)).toMap
    def image(sigma: Simplex[Int]): Map[Simplex[Int], C] = images.get(sigma) match
      case Some(known) => known
      case None        =>
        val apex = bestWitness(sigma)
        val result =
          if sigma.size == 1 then Map(Simplex(apex) -> field.one)
          else
            val cone = mutable.HashMap.empty[Simplex[Int], C]
            // The face without the i-th vertex has sign (-1)^i; [apex, w_0 < ... < w_j] is (-1)^(#w_i < apex) times
            // the sorted simplex, and zero when apex is one of the w_i.
            for
              (vertex, i) <- sigma.iterator.zipWithIndex
              (tau, c) <- image((sigma.underlying - vertex).asSimplex)
              if !tau.contains(apex)
            do
              val sign = (i + tau.iterator.count(_ < apex)) % 2 == 0
              add(cone, (tau.underlying + apex).asSimplex, if sign then c else field.negate(c))
            nonzero(cone)
        images(sigma) = result
        result
    val total = mutable.HashMap.empty[Simplex[Int], C]
    for (sigma, c) <- chain.terms do
      require(
        sigma.nonEmpty && sigma.forall(x => 0 <= x && x < numLeft),
        s"$sigma is not a simplex on the rows of this relation, which has $numLeft rows: lift takes a chain of the " +
          "complex on the rows (for a diagram computed with dual = true, lift with the dual geometry)"
      )
      for (tau, d) <- image(sigma) do add(total, tau, field.times(c, d))
    // The cells' own order, not the chain's: a chain of a stream may be ordered by that stream's filtration, which
    // knows nothing of the columns.
    Chain.from(nonzero(total).toSeq)

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
