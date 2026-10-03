package org.appliedtopology.tda4j

/** A finished persistence computation as a plain, immutable value: every bar with its representative chain, plus what
  * is needed to read it at a smaller parameter. What `Persistence(...)` returns, and what `HomologyState.snapshotAt(f)`
  * takes from a cursor.
  *
  * The coefficient field is chosen at runtime (`characteristic = 17`, ...), so its type is the member `Coefficient`;
  * `import diagram.given` brings the field into scope for arithmetic on representatives.
  */
trait PersistenceDiagram[CellT]:
  type Coefficient
  given coefficientField: (Coefficient is Field) = compiletime.deferred

  /** Every bar of degree `0 .. maxDimension`, each with its representative (a cycle at birth). */
  def bars: List[PersistenceBar[Double, Chain[CellT, Coefficient]]]

  /** The top homological degree computed. */
  def maxDimension: Int

  /** The filtration value of the last cell of the complex: a class alive at `f` is essential only from here on. */
  def lastFiltrationValue: Double

  /** The scale `significant` measures persistence against by default (a point cloud's minimum enclosing radius). */
  def scale: Option[Double]

  private def withBars(
    bs: List[PersistenceBar[Double, Chain[CellT, Coefficient]]]
  ): PersistenceDiagram.Of[CellT, Coefficient] =
    PersistenceDiagram[CellT, Coefficient](bs, maxDimension, lastFiltrationValue, scale)

  /** The bars of degree `k`. */
  def dim(k: Int): PersistenceDiagram.Of[CellT, Coefficient] = withBars(bars.filter(_.dim == k))

  /** The diagram truncated at `f`: bars born at or before `f`, deaths capped at `f`; a class alive at `f` dies "at `f`"
    * unless `f` is at or past `lastFiltrationValue`, where it is essential. Pure: the same `f` always gives the same
    * answer.
    */
  def at(f: Double): PersistenceDiagram.Of[CellT, Coefficient] =
    withBars(bars.filter(_.birth <= f).map { b =>
      if b.death <= f then b
      else if f >= lastFiltrationValue then new PersistenceBar(b.dim, b.lower, PositiveInfinity[Double](), b.annotation)
      else new PersistenceBar(b.dim, b.lower, OpenEndpoint(f), b.annotation)
    })

  /** Bars that never die. */
  def essential: List[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.filter(_.death.isPosInfinity)

  /** The most persistent bar (an essential one counts as infinitely persistent). */
  def longest: Option[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.maxByOption(_.persistence)

  /** The `n` most persistent bars, longest first. */
  def longest(n: Int): List[PersistenceBar[Double, Chain[CellT, Coefficient]]] = bars.sortBy(-_.persistence).take(n)

  /** Bars worth reporting: essential, or persistence above `fraction` of `scale` (default: the diagram's own scale,
    * else the filtration range) -- the MATLAB/CLI facade's default policy, `PersistenceFilter.significant`.
    */
  def significant(
    fraction: Double = PersistenceFilter.DefaultFraction,
    scale: Optional[Double] = Optional.empty
  ): PersistenceDiagram.Of[CellT, Coefficient] =
    withBars(PersistenceFilter.significant(bars, fraction = fraction, scale = scale.toOption.orElse(this.scale)))

  /** `dim H_k` of the whole complex for `k = 0 .. maxDimension`: the number of essential classes. */
  def bettiNumbers: Vector[Int] = Vector.tabulate(maxDimension + 1)(k => essential.count(_.dim == k))

  /** `(dimension, birth, death)` triples, the shape `diagramAt` returns. */
  def triples: List[(Int, Double, Double)] = bars.map(_.toTriple)

  def size: Int = bars.size
  def isEmpty: Boolean = bars.isEmpty

  override def toString: String =
    val byDim = bars.groupBy(_.dim).toList.sortBy(_._1)
    val lines = byDim.map { (d, bs) =>
      val shown = bs.sortBy(-_.persistence).take(5).map(b => f"[${b.birth}%.4g, ${b.death}%.4g)").mkString(" ")
      s"  H$d: ${bs.size} bars" + (if bs.nonEmpty then s", longest $shown" else "") + (if bs.size > 5 then " ..."
                                                                                       else "")
    }
    (s"PersistenceDiagram(${bars.size} bars, degrees 0..$maxDimension)" :: lines).mkString("\n")

object PersistenceDiagram:
  type Of[CellT, C] = PersistenceDiagram[CellT] { type Coefficient = C }

  def apply[CellT, C](
    bars: List[PersistenceBar[Double, Chain[CellT, C]]],
    maxDimension: Int,
    lastFiltrationValue: Double,
    scale: Option[Double] = None
  )(using field: C is Field): Of[CellT, C] =
    val (b, m, l, s) = (bars, maxDimension, lastFiltrationValue, scale)
    new PersistenceDiagram[CellT]:
      type Coefficient = C
      override given coefficientField: (C is Field) = field
      val bars = b
      val maxDimension = m
      val lastFiltrationValue = l
      val scale = s
