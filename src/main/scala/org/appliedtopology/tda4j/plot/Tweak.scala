package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import java.util.{IllegalFormatException, Locale}

/** A change to how a plot looks, kept as a value. Add it to a plot with `+`, combine tweaks with `+`, keep them in a
  * `val` and use them on every figure of a paper:
  *
  * {{{
  * val house = Style.slides + Palette.reference + Size(960, 540)
  * Plot.diagram(d) + house + Labels(title = "Noisy circle") + Scale.xy(Transform.Log10)
  * }}}
  *
  * Tweaks change independent parts of a figure (its labels, its axes, its colors, its look), so tweaks of different
  * parts can be added in any order; of the same part, the later one wins. A `Palette`, a [[Style]], [[Labels]],
  * [[Size]], the `Scale` constructors, [[Text]], [[Lines]] and [[Guide]] are tweaks.
  *
  * A tweak that has no meaning for a plot (an axis of a 3-D scene) refuses, saying so; one that merely has nothing to
  * change (a color scale on a figure without one) leaves the plot as it is, so that one bundle fits every plot.
  */
trait Tweak:
  /** `figure` with this change. */
  def onFigure(figure: Figure): Figure

  /** `scene` with this change: only tweaks of the labels, the size and the look apply to a 3-D scene. */
  def onScene(scene: Scene): Scene =
    throw UnsupportedOperationException(
      s"$this changes a 2-D figure; a 3-D scene has no axes, legend or color bar " +
        "(a Palette, a Style, a Size and the title and subtitle of Labels apply to a scene)"
    )

  /** `board` with this change in every figure of it. */
  def onBoard(board: Board): Board = board.copy(plots = board.plots.map(onFigure))

  /** This change, then `that` one. */
  def +(that: Tweak): Tweak = Tweak.Both(this, that)

object Tweak:
  /** Two tweaks in a row: what `a + b` is. */
  final case class Both(first: Tweak, second: Tweak) extends Tweak:
    def onFigure(figure: Figure): Figure = second.onFigure(first.onFigure(figure))
    override def onScene(scene: Scene): Scene = second.onScene(first.onScene(scene))
    override def onBoard(board: Board): Board = second.onBoard(first.onBoard(board))

  /** A tweak of your own, from a function on figures: `fig + Tweak(_.copy(equalAspect = true))`. */
  def apply(change: Figure => Figure): Tweak = new Tweak:
    def onFigure(figure: Figure): Figure = change(figure)
    override def toString: String = "Tweak(<function>)"

/** The words of a plot. Only what is given changes; `Labels(x = "birth (cm)")` leaves the title as it is.
  *
  * @param title
  *   the heading
  * @param subtitle
  *   the line under it
  * @param x
  *   the x axis label
  * @param y
  *   the y axis label
  * @param legend
  *   a heading over the legend's entries
  * @param color
  *   the title of the color bar
  */
final case class Labels(
  title: Optional[String] = Optional.empty,
  subtitle: Optional[String] = Optional.empty,
  x: Optional[String] = Optional.empty,
  y: Optional[String] = Optional.empty,
  legend: Optional[String] = Optional.empty,
  color: Optional[String] = Optional.empty
) extends Tweak:
  def onFigure(f: Figure): Figure = f.copy(
    title = title.getOrElse(f.title),
    subtitle = subtitle.getOrElse(f.subtitle),
    x = f.x.copy(label = x.getOrElse(f.x.label)),
    y = f.y.copy(label = y.getOrElse(f.y.label)),
    legendTitle = legend.getOrElse(f.legendTitle),
    colorScale = f.colorScale.map(c => c.copy(label = color.getOrElse(c.label)))
  )

  override def onScene(s: Scene): Scene =
    val axesWords = Seq("x" -> x, "y" -> y, "legend" -> legend, "color" -> color).collect {
      case (name, o) if !o.isEmpty => name
    }
    if axesWords.nonEmpty then
      throw UnsupportedOperationException(
        s"a 3-D scene has no axes, legend or color bar to label (${axesWords.mkString(", ")}); " +
          "Labels(title = ..., subtitle = ...) apply"
      )
    s.copy(title = title.getOrElse(s.title), subtitle = subtitle.getOrElse(s.subtitle))

  // A board's title is its page title; every panel keeps its own, and takes the other labels.
  override def onBoard(b: Board): Board =
    b.copy(title = title.getOrElse(b.title), plots = b.plots.map(copy(title = Optional.empty).onFigure))

/** The size of a plot in pixels, width by height. Only what is given changes (`Size(width = 900)`); the plot area takes
  * what the margins leave. The plots choose a height that suits what they draw (a barcode grows with its bars), so give
  * both numbers to fix both.
  */
final case class Size(width: Optional[Int] = Optional.empty, height: Optional[Int] = Optional.empty) extends Tweak:
  Seq("width" -> width, "height" -> height).foreach { (name, o) =>
    o.toOption.foreach(v =>
      require(v >= 100 && v <= 20000, s"Size: the $name should be between 100 and 20000 pixels, not $v")
    )
  }

  def onFigure(f: Figure): Figure = f.copy(width = width.getOrElse(f.width), height = height.getOrElse(f.height))
  override def onScene(s: Scene): Scene = s.copy(width = width.getOrElse(s.width), height = height.getOrElse(s.height))

/** Scales: how data become positions, colors and marks. The tweaks that change them:
  *
  *   - [[Scale.x]], [[Scale.y]], [[Scale.xy]]: an axis's transform (log, square root), limits, tick positions and
  *     number format, gridlines;
  *   - [[Scale.color]]: the limits, the colors and the transform of a color scale (a heatmap, a cocycle's values);
  *   - [[Scale.degree]]: the color, label and marker shape of a homological degree.
  *
  * Every argument is optional, and only what is given changes.
  */
object Scale:
  /** The x axis. `min` and `max` are the ends of the visible range in data units (give one to move one end); `breaks`
    * the tick positions; `format` a printf pattern for the tick numbers (`"%.1f"`, `"%.0f%%"`); `grid` and `visible`
    * turn the gridlines and the whole axis (ticks, labels, line) on or off. On a log axis what is at 0 (an `H0` bar's
    * birth) sits on a `-∞` line below the lowest number: `minusInfinity = false` leaves it out and counts it instead,
    * `true` keeps the line even with nothing on it (panels that must line up). `position` puts the axis at the
    * `Side.Top` of the plot (or back at the `Side.Bottom`).
    */
  def x(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty,
    position: Optional[Side] = Optional.empty
  ): Tweak =
    checkSide("x", position)
    Position(true, false, transform, min, max, breaks, format, grid, visible, minusInfinity, position)

  /** The y axis; as [[x]], with `position` the `Side.Left` or `Side.Right` of the plot. */
  def y(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty,
    position: Optional[Side] = Optional.empty
  ): Tweak =
    checkSide("y", position)
    Position(false, true, transform, min, max, breaks, format, grid, visible, minusInfinity, position)

  /** Both axes alike: a log-log persistence diagram is `Scale.xy(Transform.Log10)`. */
  def xy(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty
  ): Tweak = Position(true, true, transform, min, max, breaks, format, grid, visible, minusInfinity, Optional.empty)

  /** The figure's color scale, if it has one (a heatmap, a persistence image, a cocycle, circular coordinates; any
    * other figure is left as it is). `min` and `max` set the values at the ends of the ramp, so that two figures can
    * share one scale; for a diverging scale (a cocycle) `max` alone, the scale being symmetric about zero. `stops`
    * replaces a sequential ramp's colors (`ColorScale.viridis`); `guide = false` hides the color bar.
    */
  def color(
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    transform: Optional[Transform] = Optional.empty,
    stops: Optional[Seq[String]] = Optional.empty,
    guide: Optional[Boolean] = Optional.empty
  ): Tweak = ColorChange(min, max, transform, stops, guide)

  /** Homological degree `k` (a categorical slot, the `k`-th landscape in `Plot.landscape`): its `color` (in dark mode
    * `darkColor`, default the same), its legend `label` (the tooltips keep `H1`) and its marker `shape`. The color
    * follows the degree through the whole figure, whatever palette it has. `marker` changes how the degree's markers
    * look (their size, opacity, whether they are open, their edge) over the figure's own: `marker = MarkerStyle(size =
    * Some(3.5), fill = Some(false))`.
    */
  def degree(
    k: Int,
    color: Optional[String] = Optional.empty,
    darkColor: Optional[String] = Optional.empty,
    label: Optional[String] = Optional.empty,
    shape: Optional[Shape] = Optional.empty,
    marker: Optional[MarkerStyle] = Optional.empty
  ): Tweak =
    require(k >= 0, s"Scale.degree: the degree is 0 or more, not $k")
    Seq("color" -> color, "darkColor" -> darkColor).foreach { (name, o) =>
      o.toOption.foreach(c =>
        require(
          c.nonEmpty && c.forall(ch => ch.isLetterOrDigit || "#(),.% -".contains(ch)),
          s"Scale.degree: $name \"$c\" is not a CSS color; try \"#d62728\" or \"tomato\""
        )
      )
    }
    DegreeChange(
      k,
      DegreeStyle(
        color.toOption,
        darkColor.toOption,
        label.toOption,
        shape.toOption,
        marker.toOption.getOrElse(MarkerStyle())
      )
    )

  // an axis is on one of two sides: refused when the tweak is made, so a command line says so before anything is computed
  private def checkSide(which: String, position: Optional[Side]): Unit =
    position.toOption.foreach { side =>
      val allowed = if which == "x" then Seq(Side.Bottom, Side.Top) else Seq(Side.Left, Side.Right)
      require(
        allowed.contains(side),
        s"Scale.$which: the $which axis is at the ${allowed.mkString(" or the ").toLowerCase(Locale.ROOT)}, not the ${side.toString.toLowerCase(Locale.ROOT)}"
      )
    }

  private final case class Position(
    onX: Boolean,
    onY: Boolean,
    transform: Optional[Transform],
    min: Optional[Double],
    max: Optional[Double],
    breaks: Optional[Seq[Double]],
    format: Optional[String],
    grid: Optional[Boolean],
    visible: Optional[Boolean],
    minusInfinity: Optional[Boolean],
    position: Optional[Side]
  ) extends Tweak:
    def onFigure(f: Figure): Figure =
      f.copy(x = if onX then update(f.x, "x") else f.x, y = if onY then update(f.y, "y") else f.y)

    override def toString: String = s"Scale.${if onX && onY then "xy" else if onX then "x" else "y"}(...)"

    private def update(a: Axis, which: String): Axis =
      val tr = transform.getOrElse(a.transform)
      tr match
        case Transform.SymLog(c) =>
          require(c > 0 && c.isFinite, s"Scale.$which: a symmetric log axis needs a positive linear stretch, not $c")
        case _ => ()
      Seq("min" -> min, "max" -> max).foreach { (what, o) =>
        o.toOption.foreach(v => require(v.isFinite, s"Scale.$which: $what = ${Svg.number(v)} is not a number"))
      }
      val lo = min.toOption.orElse(a.min)
      val hi = max.toOption.orElse(a.max)
      for l <- lo; h <- hi do
        require(l < h, s"Scale.$which: min (${Svg.number(l)}) must be less than max (${Svg.number(h)})")
      format.toOption.foreach { pattern =>
        val works =
          try String.format(Locale.ROOT, pattern, Double.box(1.5)).nonEmpty
          catch case _: IllegalFormatException => false
        require(
          works,
          s"Scale.$which: format \"$pattern\" is not a printf pattern for one number; try \"%.2f\" or \"%.0f%%\""
        )
      }
      a.copy(
        transform = tr,
        min = lo,
        max = hi,
        ticks = breaks.toOption.fold(a.ticks)(bs => Ticks.At(bs.toIndexedSeq)),
        format = format.toOption.orElse(a.format),
        grid = grid.getOrElse(a.grid),
        visible = visible.getOrElse(a.visible),
        minusInfinity = minusInfinity.toOption.orElse(a.minusInfinity),
        side = position.toOption.orElse(a.side)
      )

  private final case class ColorChange(
    min: Optional[Double],
    max: Optional[Double],
    transform: Optional[Transform],
    stops: Optional[Seq[String]],
    guide: Optional[Boolean]
  ) extends Tweak:
    def onFigure(f: Figure): Figure = f.copy(colorScale = f.colorScale.map(change))

    private def change(c: ColorScale): ColorScale =
      val tr = transform.getOrElse(c.transform)
      c.ramp match
        case Ramp.Sequential =>
          val lo = min.getOrElse(c.lo)
          val hi = max.getOrElse(c.hi)
          require(lo < hi, s"Scale.color: min (${Svg.number(lo)}) must be less than max (${Svg.number(hi)})")
          c.copy(
            lo = lo,
            hi = hi,
            transform = tr,
            stops = stops.toOption.map(_.toIndexedSeq).orElse(c.stops),
            guide = guide.getOrElse(c.guide)
          )
        case Ramp.Diverging =>
          require(min.isEmpty, "Scale.color: a diverging scale is symmetric about zero; give max, not min")
          require(transform.isEmpty && stops.isEmpty, "Scale.color: a diverging scale has no transform or stops")
          val m = max.getOrElse(c.hi)
          require(m > 0, s"Scale.color: max must be positive for a diverging scale, not ${Svg.number(m)}")
          c.copy(lo = -m, hi = m, guide = guide.getOrElse(c.guide))
        case Ramp.Cyclic =>
          require(
            min.isEmpty && max.isEmpty && transform.isEmpty && stops.isEmpty,
            "Scale.color: a cyclic scale always runs over one turn; only guide can change"
          )
          c.copy(guide = guide.getOrElse(c.guide))

  private final case class DegreeChange(k: Int, style: DegreeStyle) extends Tweak:
    private def merged(old: Map[Int, DegreeStyle]): Map[Int, DegreeStyle] =
      old.updated(k, old.getOrElse(k, DegreeStyle()).merge(style))
    def onFigure(f: Figure): Figure = f.copy(degrees = merged(f.degrees))
    override def onScene(s: Scene): Scene = s.copy(degrees = merged(s.degrees))
