package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** A point in data coordinates. */
type XY = (Double, Double)

/** Marker shapes; the plots give each homological degree its own (the first four, in order), so degree never rests on
  * color alone. `Plus`, `Cross`, `VLine` and `HLine` are strokes with no inside; `Glyph` is a few characters of text
  * drawn as the marker (`Shape.Glyph("★")`), in the marker's color. For a shape written as a string, see
  * [[Shape.named]].
  */
enum Shape:
  case Circle, Triangle, Square, Diamond
  case TriangleDown, TriangleLeft, TriangleRight, Pentagon, Hexagon, Star, Dot, Plus, Cross, VLine, HLine
  case Glyph(text: String)

  /** Whether the shape is strokes only, with no inside to fill: a plus, a cross, a bar. */
  def isLine: Boolean = this match
    case Plus | Cross | VLine | HLine => true
    case _                            => false

object Shape:
  private val byDegree = IndexedSeq(Circle, Triangle, Square, Diamond)

  /** The marker of homological degree `k`. */
  def ofDegree(k: Int): Shape = byDegree(k % byDegree.length)

  /** The shape written as `spec`: a name (`circle`, `triangle`, `triangle-down`, `star`, ...), a one-letter matplotlib
    * code (`o`, `^`, `s`, `D`, `+`, `x`, `*`, ...), an R or ggplot `pch` number (`pch:1`, or a bare `0` to `25`), or
    * `glyph:text` for text. See [[MarkerStyle.named]] for what each spelling means, and for the open shapes of `pch`.
    */
  def named(spec: String): Shape = MarkerStyle.named(spec).shape.get

enum Anchor:
  case Start, Middle, End

/** What a figure is made of, all in data coordinates. Every mark takes either one paint (for all its items) or one per
  * item, and optionally one tooltip per item (shown on hover, as the SVG `<title>` of the item).
  */
sealed trait Mark:
  def paints: IndexedSeq[Paint]
  def tips: IndexedSeq[String]
  final def paintAt(i: Int): Paint = if paints.size == 1 then paints(0) else paints(i)
  final def tipAt(i: Int): Option[String] = if i < tips.size then Some(tips(i)) else None

object Mark:
  /** Markers of `radius` pixels, each with a 2-pixel ring in the surface color so overlapping markers stay legible. */
  final case class Points(
    at: IndexedSeq[XY],
    paints: IndexedSeq[Paint],
    radius: Double = 4.0,
    shape: Shape = Shape.Circle,
    tips: IndexedSeq[String] = IndexedSeq.empty
  ) extends Mark

  /** Line segments `width` pixels wide; `roundCaps = false` ends them square (barcode bars end exactly at their
    * values).
    */
  final case class Segments(
    ends: IndexedSeq[(XY, XY)],
    paints: IndexedSeq[Paint],
    width: Double = 1.5,
    opacity: Double = 1.0,
    roundCaps: Boolean = true,
    tips: IndexedSeq[String] = IndexedSeq.empty
  ) extends Mark

  /** Filled polygons (a simplex's triangle, a ball's outline), translucent by default so overlaps read as depth. */
  final case class Polygons(
    corners: IndexedSeq[IndexedSeq[XY]],
    paints: IndexedSeq[Paint],
    opacity: Double = 0.18,
    tips: IndexedSeq[String] = IndexedSeq.empty
  ) extends Mark

  /** Discs of `radius` data units (balls around points: Cech and alpha at radius `r`, Vietoris-Rips at `r / 2`). */
  final case class Discs(
    centers: IndexedSeq[XY],
    radius: Double,
    paints: IndexedSeq[Paint],
    opacity: Double = 0.10,
    tips: IndexedSeq[String] = IndexedSeq.empty
  ) extends Mark

  /** Axis-aligned boxes `(x0, y0, x1, y1)`: heatmap cells, pixels. */
  final case class Boxes(
    boxes: IndexedSeq[(Double, Double, Double, Double)],
    paints: IndexedSeq[Paint],
    opacity: Double = 1.0,
    tips: IndexedSeq[String] = IndexedSeq.empty
  ) extends Mark

  /** A polyline through `at`, 2 pixels wide. */
  final case class Line(at: IndexedSeq[XY], paint: Paint, width: Double = 2.0, tip: String = "") extends Mark:
    def paints: IndexedSeq[Paint] = IndexedSeq(paint)
    def tips: IndexedSeq[String] = if tip.isEmpty then IndexedSeq.empty else IndexedSeq(tip)

  /** Text at a data point, shifted by `(dx, dy)` pixels. */
  final case class Text(
    at: XY,
    text: String,
    paint: Paint = Paint.Secondary,
    anchor: Anchor = Anchor.Start,
    dx: Double = 0,
    dy: Double = 0,
    size: Double = 11
  ) extends Mark:
    def paints: IndexedSeq[Paint] = IndexedSeq(paint)
    def tips: IndexedSeq[String] = IndexedSeq.empty

  /** The line `y = x + offset` across the whole plot, with the region below the line shaded when `shade` is given: a
    * persistence diagram's diagonal, or (`offset > 0`, dashed) the line above which a bar persists longer than
    * `offset`. It is drawn in the plot's own scales, so it stays the line `y = x` on log axes, and it takes no part in
    * choosing the visible range.
    */
  final case class Diagonal(
    offset: Double = 0.0,
    paint: Paint = Paint.Axis,
    width: Double = 1.0,
    dashed: Boolean = false,
    shade: Option[Paint] = None,
    shadeOpacity: Double = 0.6
  ) extends Mark:
    def paints: IndexedSeq[Paint] = IndexedSeq(paint) ++ shade
    def tips: IndexedSeq[String] = IndexedSeq.empty

  /** A line across the whole plot at one value: `vertical` at `x = at`, otherwise horizontal at `y = at`. A threshold,
    * a scale of interest; it takes no part in choosing the visible range.
    */
  final case class Rule(
    at: Double,
    vertical: Boolean = false,
    paint: Paint = Paint.Axis,
    width: Double = 1.0,
    dashed: Boolean = false
  ) extends Mark:
    def paints: IndexedSeq[Paint] = IndexedSeq(paint)
    def tips: IndexedSeq[String] = IndexedSeq.empty

  object Rule:
    /** The line `y = value`. */
    def horizontal(value: Double): Rule = Rule(value)

    /** The line `x = value`. */
    def vertical(value: Double): Rule = Rule(value, vertical = true)

/** Where an axis puts its ticks. */
enum Ticks:
  /** Round values over the visible range. */
  case Auto

  /** Round values, only inside `[lo, hi]`: the plot keeps clear of a special tick (the `∞` of a barcode). */
  case Within(lo: Double, hi: Double)

  /** These positions, labelled with the axis's number format. */
  case At(positions: IndexedSeq[Double])

  /** These positions with these words, for an axis that is not a number line (the `H0`, `H1` rows of a barcode). */
  case Labelled(ticks: IndexedSeq[(Double, String)])

/** One axis. `min` and `max` fix the ends of the visible range in data units (otherwise the marks' extent, padded);
  * `transform` is the scale the data are drawn on; `ticks` chooses the tick positions and `extra` adds labelled ones
  * (the `∞` mark); `format` is a printf pattern for the numbers; `visible = false` drops ticks, labels and baseline (a
  * point cloud's frame). On a log axis what is at 0 (an `H0` bar's birth) sits on a `-∞` line below the lowest number,
  * as an essential bar ends at `∞`: `minusInfinity = None` draws the line when something is there, `Some(true)` always
  * (panels that must line up), `Some(false)` never, leaving what is at 0 out and counting it. `reverse` runs the axis
  * the other way (large values at the left, or at the bottom); `padding` is the fraction of the data's extent left
  * empty at each end when the range is not fixed (default 0.04). `side` puts the axis on the other side of the plot (an
  * x axis at the `Top`, a y axis at the `Right`). `crossesAt` draws the axis where the other axis has that value (an x
  * axis through `y = 0`, a y axis through `x = 0`), in the data units of the other axis, with the numbers beside it and
  * the label at the edge; a value the other axis does not show leaves the axis at the edge. Change an axis of a figure
  * with `Scale.x(...)` and `Labels(x = ...)`.
  */
final case class Axis(
  label: String = "",
  min: Option[Double] = None,
  max: Option[Double] = None,
  ticks: Ticks = Ticks.Auto,
  extra: IndexedSeq[(Double, String)] = IndexedSeq.empty,
  grid: Boolean = true,
  visible: Boolean = true,
  transform: Transform = Transform.Identity,
  format: Option[String] = None,
  minusInfinity: Option[Boolean] = None,
  side: Option[Side] = None,
  reverse: Boolean = false,
  padding: Option[Double] = None,
  crossesAt: Option[Double] = None
):
  /** Where the axis sits: `side`, or the bottom of an x axis and the left of a y axis, which are the defaults. */
  def sideOr(default: Side): Side = side.getOrElse(default)

  /** Both ends, when both are fixed. */
  def range: Option[(Double, Double)] = for l <- min; h <- max yield (l, h)

/** A legend entry: a key (a dot of the given shape, a line, or a swatch) in `paint`, and its label. */
final case class LegendItem(
  label: String,
  paint: Paint,
  key: LegendItem.Key = LegendItem.Key.Dot,
  shape: Shape = Shape.Circle
)

object LegendItem:
  enum Key:
    case Dot, Line, Swatch

/** A 2-D figure: marks in data coordinates, two axes, a legend, an optional color scale. Immutable: `+` adds a mark or
  * a [[Tweak]] (labels, scales, size, palette, style), `++` more marks, `copy` changes the rest. `svg` renders it
  * ([[Svg]]); `view()` shows it live; `save("f.svg")` writes it.
  *
  * The parts follow the grammar of graphics: the `marks` are the data drawn, `x` and `y` the position scales, `palette`
  * with `degrees` and `colorScale` the color scales, `legend` and the color bar their guides, `style` the look and
  * `elements` its finer parts (each text, the lines, the plot area). `legendPlace` is where the plot puts its legend
  * unless the style says otherwise. `equalAspect` keeps a unit of x as long as a unit of y when both axes are on one
  * scale, and `aspect` is any other ratio of the length of a y unit to an x unit (`Scale.aspect`). `caption` is a line
  * of text under the plot, `tag` the letter of a panel of a board, and `description` what a screen reader says of the
  * plot (the `<desc>` of the SVG).
  */
final case class Figure(
  marks: Vector[Mark] = Vector.empty,
  title: String = "",
  subtitle: String = "",
  x: Axis = Axis(),
  y: Axis = Axis(),
  legend: Vector[LegendItem] = Vector.empty,
  colorScale: Option[ColorScale] = None,
  width: Int = 640,
  height: Int = 440,
  equalAspect: Boolean = false,
  aspect: Option[Double] = None,
  palette: Palette = Palette.default,
  style: Style = Style.default,
  degrees: Map[Int, DegreeStyle] = Map.empty,
  legendTitle: String = "",
  elements: Elements = Elements(),
  legendPlace: LegendPosition = LegendPosition.Right,
  caption: String = "",
  tag: String = "",
  description: String = ""
) extends Plot:
  def +(mark: Mark): Figure = copy(marks = marks :+ mark)
  def ++(more: IterableOnce[Mark]): Figure = copy(marks = marks ++ more)

  /** This figure with the change `tweak` makes: `fig + Labels(title = "A circle") + Scale.xy(Transform.Log10)`. */
  def +(tweak: Tweak): Figure = tweak.onFigure(this)

  // ----- the same changes as methods, to chain: `Plot.diagram(d).withLabels(title = "Loops").withXYScale(Transform.Log10)`.
  // Each is `this + <tweak>`; the tweaks are the values to keep (`val house = Style.slides + Palette.reference`).

  /** The words: only what is given changes (`withLabels(x = "birth")` leaves the title). See [[Labels]]. */
  def withLabels(
    title: Optional[String] = Optional.empty,
    subtitle: Optional[String] = Optional.empty,
    x: Optional[String] = Optional.empty,
    y: Optional[String] = Optional.empty,
    legend: Optional[String] = Optional.empty,
    color: Optional[String] = Optional.empty,
    caption: Optional[String] = Optional.empty,
    tag: Optional[String] = Optional.empty,
    description: Optional[String] = Optional.empty
  ): Figure = this + Labels(title, subtitle, x, y, legend, color, caption, tag, description)

  /** The size in pixels; give one to keep the other (`withSize(width = 900)`). */
  def withSize(width: Optional[Int] = Optional.empty, height: Optional[Int] = Optional.empty): Figure =
    this + Size(width, height)

  /** The x axis: its scale, visible range, ticks, number format, gridlines, and its side (`Side.Top`). See [[Scale.x]].
    */
  def withXScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty,
    position: Optional[Side] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty,
    crossesAt: Optional[Double] = Optional.empty
  ): Figure =
    this + Scale.x(
      transform,
      min,
      max,
      breaks,
      format,
      grid,
      visible,
      minusInfinity,
      position,
      reverse,
      padding,
      crossesAt
    )

  /** The y axis; as [[withXScale]]. */
  def withYScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty,
    position: Optional[Side] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty,
    crossesAt: Optional[Double] = Optional.empty
  ): Figure =
    this + Scale.y(
      transform,
      min,
      max,
      breaks,
      format,
      grid,
      visible,
      minusInfinity,
      position,
      reverse,
      padding,
      crossesAt
    )

  /** Both axes alike: a log-log diagram is `withXYScale(Transform.Log10)`. */
  def withXYScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    minusInfinity: Optional[Boolean] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty
  ): Figure = this + Scale.xy(transform, min, max, breaks, format, grid, visible, minusInfinity, reverse, padding)

  /** The aspect ratio of the plot area: `withAspect(1)` for equal units, `withAspect(free = true)` for none. See
    * [[Scale.aspect]].
    */
  def withAspect(ratio: Optional[Double] = Optional.empty, free: Optional[Boolean] = Optional.empty): Figure =
    this + Scale.aspect(ratio, free)

  /** The aspect ratio as a number, for MATLAB and Java: `withAspect(1)` for equal units. */
  def withAspect(ratio: Double): Figure = this + Scale.aspect(ratio = ratio)

  /** The color scale of a heatmap or a cocycle, if the figure has one. See [[Scale.color]]. */
  def withColorScale(
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    transform: Optional[Transform] = Optional.empty,
    stops: Optional[Seq[String]] = Optional.empty,
    guide: Optional[Boolean] = Optional.empty
  ): Figure = this + Scale.color(min, max, transform, stops, guide)

  /** The color, legend label and marker of homological degree `k`. See [[Scale.degree]]. */
  def withDegree(
    k: Int,
    color: Optional[String] = Optional.empty,
    darkColor: Optional[String] = Optional.empty,
    label: Optional[String] = Optional.empty,
    shape: Optional[Shape] = Optional.empty,
    marker: Optional[MarkerStyle] = Optional.empty
  ): Figure = this + Scale.degree(k, color, darkColor, label, shape, marker)

  /** This palette. */
  def withPalette(palette: Palette): Figure = this + palette

  /** This style, in place of the figure's. */
  def withStyle(style: Style): Figure = this + style

  /** The look of one kind of text: `withText(TextRole.Title, size = 18, align = Anchor.Middle)`. See [[Text]]. */
  def withText(
    role: TextRole,
    size: Optional[Double] = Optional.empty,
    weight: Optional[Weight] = Optional.empty,
    italic: Optional[Boolean] = Optional.empty,
    align: Optional[Anchor] = Optional.empty,
    angle: Optional[Double] = Optional.empty,
    font: Optional[String] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    limit: Optional[Double] = Optional.empty
  ): Figure = this + Text(role, size, weight, italic, align, angle, font, color, paint, limit)

  /** How the numbers of tick labels and the color bar are written. See [[Numbers]]. */
  def withNumbers(
    decimal: Optional[String] = Optional.empty,
    grouping: Optional[String] = Optional.empty,
    minus: Optional[String] = Optional.empty
  ): Figure = this + Numbers(decimal, grouping, minus)

  /** The decimal separator and minus sign of a locale (`withLocale("de")`). See [[Numbers.locale]]. */
  def withLocale(tag: String): Figure = this + Numbers.locale(tag)

  /** The gridlines: width, color, dash, opacity. See [[Lines.grid]]. */
  def withGridLines(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.grid(width, color, paint, dash, opacity, visible)

  /** Minor gridlines between the ticks (drawn once any setting is given). See [[Lines.minorGrid]]. */
  def withMinorGridLines(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.minorGrid(width, color, paint, dash, opacity, visible)

  /** The axis lines. See [[Lines.axis]]. */
  def withAxisLines(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.axis(width, color, paint, dash, opacity, visible)

  /** A frame around the plot area (drawn once any setting is given). See [[Lines.frame]]. */
  def withFrame(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.frame(width, color, paint, dash, opacity, visible)

  /** Tick marks at the major ticks: `withTickMarks(length = 4)`. See [[Lines.ticks]]. */
  def withTickMarks(
    length: Optional[Double] = Optional.empty,
    direction: Optional[TickDirection] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.ticks(length, direction, width, color, paint, opacity, visible)

  /** Minor tick marks between the major ones. See [[Lines.minorTicks]]. */
  def withMinorTickMarks(
    length: Optional[Double] = Optional.empty,
    direction: Optional[TickDirection] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Lines.minorTicks(length, direction, width, color, paint, opacity, visible)

  /** The fill of the plot area. See [[Guide.panel]]. */
  def withPanel(
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Figure = this + Guide.panel(color, paint, opacity, visible)

  /** A box round the legend and its entries in columns:
    * `withLegendBox(background = "white", frame = true, columns = 2)`. See [[Guide.legend]].
    */
  def withLegendBox(
    box: Optional[Boolean] = Optional.empty,
    background: Optional[String] = Optional.empty,
    backgroundPaint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    frame: Optional[Boolean] = Optional.empty,
    frameColor: Optional[String] = Optional.empty,
    framePaint: Optional[Paint] = Optional.empty,
    frameWidth: Optional[Double] = Optional.empty,
    padding: Optional[Double] = Optional.empty,
    columns: Optional[Int] = Optional.empty
  ): Figure =
    this + Guide.legend(
      box,
      background,
      backgroundPaint,
      opacity,
      frame,
      frameColor,
      framePaint,
      frameWidth,
      padding,
      columns
    )

  /** The markers of the points: `withPoints(size = 3.5, opacity = 0.6, edgeVisible = false)`. See [[Geom.points]]. */
  def withPoints(
    shape: Optional[Shape] = Optional.empty,
    size: Optional[Double] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    fill: Optional[Boolean] = Optional.empty,
    edgeWidth: Optional[Double] = Optional.empty,
    edgeColor: Optional[String] = Optional.empty,
    edgePaint: Optional[Paint] = Optional.empty,
    edgeOpacity: Optional[Double] = Optional.empty,
    edgeDash: Optional[Dash] = Optional.empty,
    edgeVisible: Optional[Boolean] = Optional.empty
  ): Figure =
    this + Geom.points(shape, size, opacity, fill, edgeWidth, edgeColor, edgePaint, edgeOpacity, edgeDash, edgeVisible)

  /** The line segments (a barcode's bars): width, opacity. See [[Geom.segments]]. */
  def withSegments(width: Optional[Double] = Optional.empty, opacity: Optional[Double] = Optional.empty): Figure =
    this + Geom.segments(width, opacity)

  /** The filled polygons (a complex's triangles): opacity. See [[Geom.polygons]]. */
  def withPolygons(opacity: Optional[Double] = Optional.empty): Figure = this + Geom.polygons(opacity)

  /** The discs (the balls of a Cech or alpha complex): opacity. See [[Geom.discs]]. */
  def withDiscs(opacity: Optional[Double] = Optional.empty): Figure = this + Geom.discs(opacity)

  /** The polylines (a cycle, a landscape): width. See [[Geom.lines]]. */
  def withLines(width: Optional[Double] = Optional.empty): Figure = this + Geom.lines(width)

  /** The diagonal of a persistence diagram and its shaded half plane. See [[Guide.diagonal]]. */
  def withDiagonal(
    shade: Optional[Boolean] = Optional.empty,
    shadeColor: Optional[String] = Optional.empty,
    shadePaint: Optional[Paint] = Optional.empty,
    shadeOpacity: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    dashed: Optional[Boolean] = Optional.empty
  ): Figure = this + Guide.diagonal(shade, shadeColor, shadePaint, shadeOpacity, color, paint, width, dashed)

  // The same with plain arguments only (strings, ints, doubles) and no defaults, for Java and MATLAB, which can neither
  // build a `Labels` nor call a method with defaults: `fig.withTitle('A circle').withSize(800, 500).withXScale('log')`.

  /** The heading. */
  def withTitle(text: String): Figure = withLabels(title = text)

  /** The line under the heading. */
  def withSubtitle(text: String): Figure = withLabels(subtitle = text)

  /** The x axis label. */
  def withXLabel(text: String): Figure = withLabels(x = text)

  /** The y axis label. */
  def withYLabel(text: String): Figure = withLabels(y = text)

  /** The heading over the legend's entries. */
  def withLegendTitle(text: String): Figure = withLabels(legend = text)

  /** A line of text under the plot. */
  def withCaption(text: String): Figure = withLabels(caption = text)

  /** The letter or number of a panel, in the corner of the figure: `"(a)"`. */
  def withTag(text: String): Figure = withLabels(tag = text)

  /** What a screen reader says of the plot (the `<desc>` of the SVG). */
  def withDescription(text: String): Figure = withLabels(description = text)

  /** The title of the color bar. */
  def withColorLabel(text: String): Figure = withLabels(color = text)

  /** The size in pixels. */
  def withSize(width: Int, height: Int): Figure = this + Size(width, height)

  /** The x axis on the scale called `name`: `linear`, `log`, `sqrt` or `symlog`. */
  def withXScale(name: String): Figure = withXScale(Transform.named(name))

  /** The y axis on the scale called `name`: `linear`, `log`, `sqrt` or `symlog`. */
  def withYScale(name: String): Figure = withYScale(Transform.named(name))

  /** The x axis drawn through `y = value` (see [[Scale.x]]), for MATLAB and Java: `withXCrossesAt(0)`. */
  def withXCrossesAt(value: Double): Figure = withXScale(crossesAt = value)

  /** The y axis drawn through `x = value` (see [[Scale.y]]), for MATLAB and Java: `withYCrossesAt(0)`. */
  def withYCrossesAt(value: Double): Figure = withYScale(crossesAt = value)

  /** The visible range of the x axis, in data units. */
  def withXRange(min: Double, max: Double): Figure = withXScale(min = min, max = max)

  /** The visible range of the y axis, in data units. */
  def withYRange(min: Double, max: Double): Figure = withYScale(min = min, max = max)

  /** The palette called `name`: `brand`, `reference`, `print`, `grayscale` or `clear`. */
  def withPalette(name: String): Figure = withPalette(Palette.named(name))

  /** The style called `name`: `default`, `slides`, `print` or `clear`. */
  def withStyle(name: String): Figure = withStyle(Style.named(name))

  /** The color of homological degree `degree`, as a CSS color (`#d62728`, `tomato`). */
  def withDegreeColor(degree: Int, color: String): Figure = withDegree(degree, color = color)

  /** The legend label of homological degree `degree`. */
  def withDegreeLabel(degree: Int, label: String): Figure = withDegree(degree, label = label)

  /** A factor on every text size (`1.3` for a slide). */
  def withTextScale(factor: Double): Figure = withStyle(style.copy(textScale = factor))

  /** A factor on the size of markers and the width of lines. */
  def withMarkScale(factor: Double): Figure = withStyle(style.copy(markScale = factor))

  /** A box round the legend, filled with `color` (a CSS color) and framed: `withLegendBox("white")`. */
  def withLegendBox(color: String): Figure = withLegendBox(box = true, background = color)

  /** The legend's entries in `columns` columns, one filled before the next. */
  def withLegendColumns(columns: Int): Figure = withLegendBox(columns = columns)

  /** The legend at `right`, `bottom`, or `none`. */
  def withLegend(position: String): Figure = withStyle(style.copy(legend = LegendPosition.named(position)))

  /** Without gridlines. */
  def withoutGrid(): Figure = withStyle(style.copy(grid = false))

  /** Without a background, so that the figure takes the color of the page behind it. */
  def withTransparentBackground(): Figure = withStyle(style.copy(transparent = true))

  /** One change written as text, `element.property=value`: `withSetting("title.size=18")`,
    * `withSetting("tick-label.angle=45")`, `withSetting("grid.dash=dotted")`, `withSetting("x-axis=top")`. See
    * [[Settings]] for the elements and their properties.
    */
  def withSetting(spec: String): Figure = this + Settings.parse(spec)

  // One line, so that a REPL echoing the value of `fig.view()` does not print every coordinate of every mark.
  override def toString: String = Plot.describe("Figure", title, s"${marks.size} marks")

  /** This figure's marks drawn over `under`'s (axes, title, legend heading and look from this one, legends merged; the
    * color scale of whichever has one).
    */
  def over(under: Figure): Figure = copy(
    marks = under.marks ++ marks,
    legend = under.legend ++ legend,
    colorScale = colorScale.orElse(under.colorScale),
    degrees = under.degrees ++ degrees
  )

  /** The visible range of each axis in data units, `((xMin, xMax), (yMin, yMax))`, as `svg` will draw it before any
    * adjustment for an equal aspect ratio.
    */
  def ranges: ((Double, Double), (Double, Double)) = Svg.ranges(this)

  def svg(theme: Theme = Theme.Auto): String = Svg.render(this, theme)
  def fragment(theme: Theme = Theme.Auto): String = svg(theme)
  def svgFile(theme: Theme): String = """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + svg(theme)
  def html(theme: Theme = Theme.Auto): String = Html.page(title, svg(theme), palette)
