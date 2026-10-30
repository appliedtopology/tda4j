package org.appliedtopology.tda4j

import cats.Show

/** A finished persistence computation as a plain, immutable value: the bars with their representative chains, plus what
  * is needed to read them at a smaller parameter. What `Persistence(...)` returns, and what
  * `HomologyState.snapshotAt(f)` takes from a cursor.
  *
  * The coefficient field is chosen at runtime (`characteristic = 17`, ...), so its type is the member `Coefficient`;
  * `import diagram.given` brings the field into scope for arithmetic on representatives.
  */
trait PersistenceDiagram[CellT]:
  type Coefficient
  given coefficientField: (Coefficient is Field) = compiletime.deferred

  /** The bars of degree `0 .. maxDimension`, each with its representative (a cycle at birth). Zero-length bars are
    * absent unless the diagram was computed with `includeZeroLength = true`. Reading a representative can be where it
    * is computed (`Persistence.Engine.ParallelRipser` with `computeAllRepresentatives = false`).
    */
  def bars: List[PersistenceBar[Double, Chain[CellT, Coefficient]]]

  /** The top homological degree computed. */
  def maxDimension: Int

  /** The filtration value of the last cell of the complex: a class alive at `f` is essential only from here on. */
  def lastFiltrationValue: Double

  /** The scale `longerThanFraction` measures persistence against by default (a point cloud's minimum enclosing radius).
    */
  def scale: Option[Double]

  /** Which kind of representative the bars carry: what `Persistence(...)` was asked for, `None` for a diagram that does
    * not say (one built by hand, or by an engine's cursor). [[CircularCoordinates]] reads a cocycle straight off a
    * diagram that says `Some(Representatives.Cocycles)` and recovers it otherwise.
    */
  def representatives: Option[Representatives] = None

  /** Whether the representative of a finite bar is the cycle that its death cell bounds, which is zero in homology from
    * the bar's death on. Then the representatives of the bars alive at a value `t` are a basis of the homology there,
    * one for each bar, and each of them generates exactly its bar: what [[PersistenceMap]] needs to write a map in
    * terms of bars. True of the cycles of the cohomology engines and of Ripser; false of the cycles of the naive and
    * chunked engines, which are the generators the reduction works with and stay nonzero classes after their bar ends,
    * and of a diagram that does not say.
    */
  def representativesDieWithTheirBars: Boolean = false

  private def withBars(
    bs: List[PersistenceBar[Double, Chain[CellT, Coefficient]]]
  ): PersistenceDiagram.Of[CellT, Coefficient] =
    PersistenceDiagram[CellT, Coefficient](
      bs,
      maxDimension,
      lastFiltrationValue,
      scale,
      representatives,
      representativesDieWithTheirBars
    )

  /** The bars of degree `k`. */
  def dim(k: Int): PersistenceDiagram.Of[CellT, Coefficient] = withBars(bars.filter(_.dim == k))

  /** The diagram truncated at `f`: bars born at or before `f`; a class alive at `f` becomes `[birth, f]`, or stays
    * essential when `f` is at or past `lastFiltrationValue`.
    */
  def at(f: Double): PersistenceDiagram.Of[CellT, Coefficient] =
    withBars(bars.filter(_.birth <= f).map { b =>
      // A class cut off at `f` has no killer in the truncated complex.
      def alive(upper: BarcodeEndpoint[Double]) =
        new PersistenceBar(b.dim, b.lower, upper, b.annotation, b.cells.map(p => Involution.Pair(p.dim, p.birth, None)))
      if b.death <= f then b
      else if f >= lastFiltrationValue then alive(PositiveInfinity[Double]())
      else alive(ClosedEndpoint(f))
    })

  /** Bars that never die. */
  def essential: List[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.filter(_.death.isPosInfinity)

  /** The most persistent bar (an essential one counts as infinitely persistent). */
  def longest: Option[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.maxByOption(_.persistence)

  /** The `n` most persistent bars, longest first. */
  def longest(n: Int): List[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.sortBy(-_.persistence).take(n)

  /** Essential bars and bars with persistence strictly greater than `minPersistence`: `diagram.longerThan(0.1)`. */
  def longerThan(minPersistence: Double): PersistenceDiagram.Of[CellT, Coefficient] =
    withBars(bars.filter(b => b.death.isPosInfinity || b.persistence > minPersistence))

  /** Essential bars and bars longer than `fraction` of `scale` (default: the diagram's own scale -- a point cloud's
    * minimum enclosing radius -- else its filtration range). With the defaults, the bars the MATLAB/CLI facade shows. A
    * rule of thumb for what to show, not a test of which bars are noise; [[BootstrapBand]] estimates that from the
    * data.
    */
  def longerThanFraction(
    fraction: Double = PersistenceFilter.DefaultFraction,
    scale: Optional[Double] = Optional.empty
  ): PersistenceDiagram.Of[CellT, Coefficient] =
    withBars(PersistenceFilter.longerThanFraction(bars, fraction = fraction, scale = scale.toOption.orElse(this.scale)))

  /** `dim H_k` for `k = 0 .. maxDimension`: the classes alive at the end of the diagram. For a whole diagram these are
    * the essential classes, the Betti numbers of the complex; for `at(f)` they are the Betti numbers at `f`
    * (`d.at(0.9).bettiNumbers`).
    */
  def bettiNumbers: Vector[Int] =
    def aliveAtEnd(b: PersistenceBar[Double, ?]) = b.upper match
      case PositiveInfinity() | ClosedEndpoint(_) => true
      case _                                      => false
    Vector.tabulate(maxDimension + 1)(k => bars.count(b => b.dim == k && aliveAtEnd(b)))

  /** `(dimension, birth, death)` triples, the shape `diagramAt` returns. */
  def triples: List[(Int, Double, Double)] = bars.map(_.toTriple)

  def size: Int = bars.size
  def isEmpty: Boolean = bars.isEmpty

  override def toString: String =
    val byDim = bars.groupBy(_.dim).toList.sortBy(_._1)
    val lines = byDim.map { (d, bs) =>
      val shown = bs.sortBy(-_.persistence).take(5).map(b => f"[${b.birth}%.4g, ${b.death}%.4g)").mkString(" ")
      val count = if bs.size == 1 then "1 bar" else s"${bs.size} bars"
      s"  H$d: $count" + (if bs.nonEmpty then s", longest $shown" else "") + (if bs.size > 5 then " ..." else "")
    }
    (s"PersistenceDiagram(${bars.size} bars, degrees 0..$maxDimension)" :: lines).mkString("\n")

object PersistenceDiagram:
  type Of[CellT, C] = PersistenceDiagram[CellT] { type Coefficient = C }

  /** `diagram.show`: a header, then every bar with its representative, degree by degree (the bars' and chains' own
    * `Show`s, the coefficients by the diagram's field). Typed for every diagram type, `PersistenceDiagram.Of[...]`
    * (what `dim`, `at` and `longerThanFraction` return) included. `toString` is the short summary.
    */
  given diagramShow: [CellT: Show, D <: PersistenceDiagram[CellT]] => Show[D] = Show.show { d =>
    given (d.Coefficient is Field) = d.coefficientField
    val barShow = summon[Show[PersistenceBar[Double, Chain[CellT, d.Coefficient]]]]
    val bars = d.bars.sortBy(_.dim).map(b => "  " + barShow.show(b))
    val count = if d.bars.size == 1 then "1 bar" else s"${d.bars.size} bars"
    (s"PersistenceDiagram($count, degrees 0..${d.maxDimension})" :: bars).mkString("\n")
  }

  def apply[CellT, C](
    bars: List[PersistenceBar[Double, Chain[CellT, C]]],
    maxDimension: Int,
    lastFiltrationValue: Double,
    scale: Option[Double] = None,
    representatives: Option[Representatives] = None,
    representativesDieWithTheirBars: Boolean = false
  )(using field: C is Field): Of[CellT, C] =
    val (b, m, l, s, k, d) =
      (bars, maxDimension, lastFiltrationValue, scale, representatives, representativesDieWithTheirBars)
    new PersistenceDiagram[CellT]:
      type Coefficient = C
      override given coefficientField: (C is Field) = field
      val bars = b
      val maxDimension = m
      val lastFiltrationValue = l
      val scale = s
      override val representatives = k
      override val representativesDieWithTheirBars = d
