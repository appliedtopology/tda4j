package org.appliedtopology.tda4j.plot

import RootText.lowerRoot

import org.appliedtopology.tda4j.*

/** Checks shared by the tweaks of this file, each saying which tweak refused and what to give instead. */
private object Check:
  /** The paint of `color` (a CSS color of your own) or `paint` (a palette role): at most one. */
  def paint(who: String, color: Optional[String], paint: Optional[Paint]): Option[Paint] =
    require(color.isEmpty || paint.isEmpty, s"$who: give color or paint, not both")
    color.toOption
      .map { c =>
        require(
          c.nonEmpty && c.forall(ch => ch.isLetterOrDigit || "#(),.% -".contains(ch)),
          s"$who: color \"$c\" is not a CSS color; try \"#d62728\" or \"tomato\""
        )
        Paint.Fixed(c)
      }
      .orElse(paint.toOption)

  def positive(who: String, what: String, v: Optional[Double]): Unit =
    v.toOption.foreach(x =>
      require(x > 0 && x.isFinite, s"$who: $what must be a positive number, not ${Svg.number(x)}")
    )

  def atLeastZero(who: String, what: String, v: Optional[Double]): Unit =
    v.toOption.foreach(x => require(x >= 0 && x.isFinite, s"$who: $what must be 0 or more, not ${Svg.number(x)}"))

  def fraction(who: String, what: String, v: Optional[Double]): Unit =
    v.toOption.foreach(x => require(x >= 0 && x <= 1, s"$who: $what must be between 0 and 1, not ${Svg.number(x)}"))

/** The look of the text of a plot, one [[TextRole]] at a time: `fig + Text(TextRole.Title, size = 18, align =
  * Anchor.Middle)`. Only what is given changes, and a later `Text` of the same role keeps the rest of an earlier one:
  *
  * {{{
  * Plot.diagram(d)
  *   .withText(TextRole.Title, size = 18, weight = Weight.Bold, align = Anchor.Middle)
  *   .withText(TextRole.TickLabel, size = 9, angle = 45)
  * }}}
  *
  * Sizes are in pixels before the style's `textScale`, so `Style.slides` still scales them. A role that does not apply
  * (`align` of a tick label, whose place is its tick's) refuses, naming what to use.
  */
object Text:
  private val alignable =
    Set(TextRole.Title, TextRole.Subtitle, TextRole.AxisLabel, TextRole.Note, TextRole.Caption)

  /** The roles a 3-D scene has: it has no axes, legend or color bar. */
  private[plot] val sceneRoles = Set(TextRole.Title, TextRole.Subtitle, TextRole.Note)

  /** The text of `role`. `size` is in pixels, `weight` a [[Weight]], `italic` slants it, `align` puts a title,
    * subtitle, axis label or note at the `Start`, `Middle` or `End` of the plot area, `angle` slants the x axis's tick
    * labels (degrees counterclockwise, between -90 and 90), `font` a CSS `font-family` list for this text alone, and
    * `color` (a CSS color) or `paint` (a palette role, so that it follows the theme) its color. `limit` is the longest
    * the text may be in pixels: a longer one is cut to fit and ends in `…`, with the whole of it as its tooltip (the
    * width is estimated from the characters; a font is not known).
    */
  def apply(
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
  ): Tweak =
    val who = s"Text(${TextRole.hyphenated(role)})"
    Check.positive(who, "size", size)
    Check.positive(who, "limit", limit)
    require(
      align.isEmpty || alignable(role),
      s"$who: align has no meaning here; only a title, subtitle, axis label and note can be aligned"
    )
    angle.toOption.foreach { a =>
      require(
        role == TextRole.TickLabel,
        s"$who: only tick labels can be slanted; angle is for Text(TextRole.TickLabel)"
      )
      require(
        a.isFinite && a >= -90 && a <= 90,
        s"$who: angle should be between -90 and 90 degrees, not ${Svg.number(a)}"
      )
    }
    font.toOption.foreach(f =>
      require(f.nonEmpty && !f.contains('<'), s"$who: font must be a CSS font-family list, such as \"Georgia, serif\"")
    )
    TextChange(
      role,
      TextStyle(
        size.toOption,
        weight.toOption,
        italic.toOption,
        align.toOption,
        angle.toOption,
        font.toOption,
        Check.paint(who, color, paint),
        limit.toOption
      )
    )

  private final case class TextChange(role: TextRole, style: TextStyle) extends Tweak:
    def onFigure(f: Figure): Figure = f.copy(elements = f.elements.withText(role, style))
    override def onScene(s: Scene): Scene =
      if sceneRoles(role) then s.copy(elements = s.elements.withText(role, style))
      else
        throw UnsupportedOperationException(
          s"a 3-D scene has a title, a subtitle and a hint (note); the ${TextRole.hyphenated(role)} text needs axes, a " +
            "legend or a color bar"
        )
    override def toString: String = s"Text(${TextRole.hyphenated(role)}, $style)"

/** The look of the lines of a plot: gridlines, the axis lines, tick marks, and a frame around the plot area. Each
  * changes only what it is given (`width`, a `color` or a palette `paint`, `dash`, `opacity`, `visible`):
  *
  * {{{
  * Plot.diagram(d).withGridLines(dash = Dash.Dotted).withTickMarks(length = 4).withFrame()
  * }}}
  *
  * The minor gridlines, the minor tick marks and the frame are not drawn unless asked: giving any of them a setting
  * turns it on (`visible = false` turns it off). Tick marks have a `length` in pixels (`0` is none) and a `direction`,
  * out of the plot or into it; asked for with no length they are 4 pixels, minor ones 2.5. On a log axis the minor ones
  * are the 2 to 9 of each decade; on any other axis the halves between two ticks.
  */
object Lines:
  private def style(
    who: String,
    width: Optional[Double],
    color: Optional[String],
    paint: Optional[Paint],
    dash: Optional[Dash],
    opacity: Optional[Double],
    visible: Optional[Boolean]
  ): LineStyle =
    Check.positive(who, "width", width)
    Check.fraction(who, "opacity", opacity)
    LineStyle(visible.toOption, width.toOption, Check.paint(who, color, paint), dash.toOption, opacity.toOption)

  /** The gridlines. (`Scale.x(grid = false)` turns one axis's off, `Style(grid = false)` all.) */
  def grid(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak = LineChange("grid", style("Lines.grid", width, color, paint, dash, opacity, visible))

  /** The minor gridlines, between the ticks: off unless asked for. */
  def minorGrid(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak = LineChange(
    "minorGrid",
    style("Lines.minorGrid", width, color, paint, dash, opacity, visible.toOption.orElse(Some(true)))
  )

  /** The axis lines (`visible = false` keeps the ticks and numbers and drops the line). */
  def axis(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak = LineChange("axis", style("Lines.axis", width, color, paint, dash, opacity, visible))

  /** A frame around the plot area, drawn over the marks: off unless asked for. */
  def frame(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak =
    LineChange("frame", style("Lines.frame", width, color, paint, dash, opacity, visible.toOption.orElse(Some(true))))

  /** The tick marks at the major ticks of both axes: `length` in pixels, `direction` `Out` of the plot (default) or
    * `In`. They are on a side where the axis is: they follow it to the top or the right.
    */
  def ticks(
    length: Optional[Double] = Optional.empty,
    direction: Optional[TickDirection] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak =
    TickChange("ticks", false, ticksOf("Lines.ticks", length, direction, width, color, paint, opacity, visible))

  /** The minor tick marks, between the major ones: off unless asked for. */
  def minorTicks(
    length: Optional[Double] = Optional.empty,
    direction: Optional[TickDirection] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak = TickChange(
    "minorTicks",
    true,
    ticksOf("Lines.minorTicks", length, direction, width, color, paint, opacity, visible)
  )

  private def ticksOf(
    who: String,
    length: Optional[Double],
    direction: Optional[TickDirection],
    width: Optional[Double],
    color: Optional[String],
    paint: Optional[Paint],
    opacity: Optional[Double],
    visible: Optional[Boolean]
  ): TickStyle =
    Check.atLeastZero(who, "length", length)
    TickStyle(
      length.toOption,
      direction.toOption,
      style(who, width, color, paint, Optional.empty, opacity, visible)
    )

  private final case class LineChange(which: String, change: LineStyle) extends Tweak:
    def onFigure(f: Figure): Figure =
      val e = f.elements
      f.copy(elements = which match
        case "grid"      => e.copy(grid = e.grid.merge(change))
        case "minorGrid" => e.copy(minorGrid = e.minorGrid.merge(change))
        case "axis"      => e.copy(axis = e.axis.merge(change))
        case _           => e.copy(frame = e.frame.merge(change)))
    override def toString: String = s"Lines.$which($change)"

  private final case class TickChange(which: String, minor: Boolean, change: TickStyle) extends Tweak:
    def onFigure(f: Figure): Figure =
      val e = f.elements
      val old = if minor then e.minorTicks else e.ticks
      // asked for with no length: a mark you can see, not an invisible one
      val withLength =
        if change.length.isEmpty && old.length.isEmpty then change.copy(length = Some(if minor then 2.5 else 4.0))
        else change
      val merged = old.merge(withLength)
      f.copy(elements = if minor then e.copy(minorTicks = merged) else e.copy(ticks = merged))
    override def toString: String = s"Lines.$which($change)"

/** How the numbers of the tick labels and the color bar are written: `Numbers(decimal = ",", minus = "−")` for a German
  * paper, `Numbers.locale("de")` for what the locale has (its decimal separator and minus sign), `grouping = " "` to
  * group the digits of a large number in threes. Only what is given changes.
  */
object Numbers:
  def apply(
    decimal: Optional[String] = Optional.empty,
    grouping: Optional[String] = Optional.empty,
    minus: Optional[String] = Optional.empty
  ): Tweak =
    Seq("decimal" -> decimal, "grouping" -> grouping, "minus" -> minus).foreach { (name, o) =>
      o.toOption.foreach(v =>
        require(
          v.length <= 3 && !v.exists(c => c.isDigit || c == '<'),
          s"Numbers: $name \"$v\" should be a symbol of one to three characters, not a digit"
        )
      )
    }
    NumbersChange(NumberStyle(decimal.toOption, grouping.toOption, minus.toOption))

  /** The decimal separator and minus sign of the locale `tag` (`de`, `fr`, `sv`): see [[NumberStyle.locale]]. */
  def locale(tag: String): Tweak =
    val style =
      try NumberStyle.locale(tag)
      catch
        case _: RuntimeException =>
          throw IllegalArgumentException(s"Numbers.locale: '$tag' is not a locale; try \"de\", \"fr\" or \"sv-SE\"")
    NumbersChange(style)

  private final case class NumbersChange(style: NumberStyle) extends Tweak:
    def onFigure(f: Figure): Figure = f.copy(elements = f.elements.copy(numbers = f.elements.numbers.merge(style)))
    override def toString: String = s"Numbers($style)"

/** The camera of a 3-D scene: where it looks from, `azimuth` around the vertical axis and `elevation` above the horizon
  * (both in degrees), and how close, `zoom` (1 is the default, 2 shows the scene twice as large). A page's canvas
  * starts from this view and can still be turned and zoomed; a saved `.svg` is this one view. Only what is given
  * changes. A 2-D figure has no camera and refuses it.
  */
object Camera:
  /** The largest and smallest zoom, which are the canvas's own limits. */
  val zoomRange: (Double, Double) = (0.2, 8.0)

  def apply(
    azimuth: Optional[Double] = Optional.empty,
    elevation: Optional[Double] = Optional.empty,
    zoom: Optional[Double] = Optional.empty
  ): Tweak =
    azimuth.toOption.foreach(a =>
      require(a.isFinite, s"Camera: the azimuth is an angle in degrees, not ${Svg.number(a)}")
    )
    elevation.toOption.foreach(e =>
      require(
        e.isFinite && e >= -90 && e <= 90,
        s"Camera: the elevation is an angle from -90 (below) to 90 (above) degrees, not ${Svg.number(e)}"
      )
    )
    zoom.toOption.foreach(z =>
      require(
        z >= zoomRange._1 && z <= zoomRange._2,
        s"Camera: the zoom should be between ${Svg.number(zoomRange._1)} and ${Svg.number(zoomRange._2)}, not ${Svg.number(z)}"
      )
    )
    CameraChange(azimuth.toOption, elevation.toOption, zoom.toOption)

  private final case class CameraChange(azimuth: Option[Double], elevation: Option[Double], zoom: Option[Double])
      extends Tweak:
    def onFigure(f: Figure): Figure =
      throw UnsupportedOperationException(
        s"$this is the camera of a 3-D scene; a 2-D figure has none (Scale.x, Scale.aspect and Size shape a figure)"
      )
    override def onScene(s: Scene): Scene =
      s.copy(
        azimuth = azimuth.getOrElse(s.azimuth),
        elevation = elevation.getOrElse(s.elevation),
        zoom = zoom.getOrElse(s.zoom)
      )
    override def toString: String = "Camera(...)"

/** The guides a plot draws for itself, which are not axes or text: the plot area's own fill, and the diagonal of a
  * persistence diagram with its shaded half plane.
  */
object Guide:
  /** The fill of the plot area, behind the gridlines and marks (`color`, or a palette `paint`; `opacity` 0 to 1,
    * default 0.5 over the figure's own background); `visible = false` removes it.
    */
  def panel(
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  ): Tweak =
    Check.fraction("Guide.panel", "opacity", opacity)
    PanelChange(Check.paint("Guide.panel", color, paint), opacity.toOption, visible.toOption)

  /** The diagonal `y = x` of a persistence diagram and the half plane below it, where nothing can be (a bar dies after
    * it is born). `shade = false` draws no half plane, `shade = true` the default one; `shadeColor` or `shadePaint` and
    * `shadeOpacity` change it; `color` or `paint`, `width` and `dashed` the line. A figure without a diagonal is left
    * as it is.
    */
  def diagonal(
    shade: Optional[Boolean] = Optional.empty,
    shadeColor: Optional[String] = Optional.empty,
    shadePaint: Optional[Paint] = Optional.empty,
    shadeOpacity: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    dashed: Optional[Boolean] = Optional.empty
  ): Tweak =
    Check.fraction("Guide.diagonal", "shadeOpacity", shadeOpacity)
    Check.positive("Guide.diagonal", "width", width)
    DiagonalChange(
      shade.toOption,
      Check.paint("Guide.diagonal (shade)", shadeColor, shadePaint),
      shadeOpacity.toOption,
      Check.paint("Guide.diagonal", color, paint),
      width.toOption,
      dashed.toOption
    )

  /** The legend's box and columns. A legend has no box unless asked for: `box = true` gives it the page's own color, a
    * little see-through (`opacity` 0.9), and a thin frame; `background` (a CSS color) or `backgroundPaint` and
    * `opacity` set the fill, `frame` and `frameColor` or `framePaint` and `frameWidth` the line round it, `padding` the
    * room inside, in pixels (6). Giving a fill or a frame turns the box on; `box = false` takes it away.
    *
    * `columns` puts the entries in that many columns, filled one column before the next (a legend of seven entries in
    * three columns is 3, 3, 1). A legend never takes more than two fifths of the figure's width beside the plot or more
    * than the plot area's width above, below or inside it: the columns that do not fit are given up.
    */
  def legend(
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
  ): Tweak =
    Check.fraction("Guide.legend", "opacity", opacity)
    Check.positive("Guide.legend", "frameWidth", frameWidth)
    Check.atLeastZero("Guide.legend", "padding", padding)
    columns.toOption.foreach(n => require(n >= 1, s"Guide.legend: columns must be 1 or more, not $n"))
    val fillPaint = Check.paint("Guide.legend (background)", background, backgroundPaint)
    val framed = Check.paint("Guide.legend (frame)", frameColor, framePaint)
    // a fill or a frame asked for turns that part on, and `box` turns both on or off
    val boxOn = box.toOption
    val boxOff = boxOn.contains(false)
    val fillOn = fillPaint.isDefined || opacity.toOption.isDefined || boxOn.contains(true)
    val frameOn =
      if boxOff then Some(false)
      else
        frame.toOption.orElse(
          Option.when(framed.isDefined || frameWidth.toOption.isDefined || boxOn.contains(true))(true)
        )
    LegendChange(
      boxOff,
      fillOn,
      fillPaint,
      opacity.toOption,
      LineStyle(frameOn, frameWidth.toOption, framed, None, None),
      padding.toOption,
      columns.toOption.map(_.toInt)
    )

  private final case class LegendChange(
    boxOff: Boolean,
    fillOn: Boolean,
    fillPaint: Option[Paint],
    opacity: Option[Double],
    frame: LineStyle,
    padding: Option[Double],
    columns: Option[Int]
  ) extends Tweak:
    def onFigure(f: Figure): Figure =
      val old = f.elements.legendBox
      val (defaultPaint, defaultOpacity) = Elements.DefaultLegendFill
      val fill =
        if boxOff then None
        else if fillOn then
          Some(
            (
              fillPaint.orElse(old.fill.map(_._1)).getOrElse(defaultPaint),
              opacity.orElse(old.fill.map(_._2)).getOrElse(defaultOpacity)
            )
          )
        else old.fill
      f.copy(elements =
        f.elements.copy(legendBox =
          LegendBox(fill, old.frame.merge(frame), padding.orElse(old.padding), columns.orElse(old.columns))
        )
      )
    override def toString: String = s"Guide.legend($boxOff, $fillPaint, $opacity, $frame, $padding, $columns)"

  private final case class PanelChange(paint: Option[Paint], opacity: Option[Double], visible: Option[Boolean])
      extends Tweak:
    def onFigure(f: Figure): Figure =
      val old = f.elements.panel
      val next =
        if visible.contains(false) then None
        else if paint.isEmpty && opacity.isEmpty && visible.isEmpty then old
        else Some((paint.orElse(old.map(_._1)).getOrElse(Paint.Grid), opacity.orElse(old.map(_._2)).getOrElse(0.5)))
      f.copy(elements = f.elements.copy(panel = next))
    override def toString: String = s"Guide.panel($paint, $opacity, $visible)"

  private final case class DiagonalChange(
    shade: Option[Boolean],
    shadePaint: Option[Paint],
    shadeOpacity: Option[Double],
    paint: Option[Paint],
    width: Option[Double],
    dashed: Option[Boolean]
  ) extends Tweak:
    private def change(d: Mark.Diagonal): Mark.Diagonal =
      val shaded =
        if shade.contains(false) then None
        else if shade.contains(true) || shadePaint.isDefined then Some(shadePaint.orElse(d.shade).getOrElse(Paint.Grid))
        else d.shade
      d.copy(
        paint = paint.getOrElse(d.paint),
        width = width.getOrElse(d.width),
        dashed = dashed.getOrElse(d.dashed),
        shade = shaded,
        shadeOpacity = shadeOpacity.getOrElse(d.shadeOpacity)
      )
    def onFigure(f: Figure): Figure = f.copy(marks = f.marks.map {
      case d: Mark.Diagonal => change(d)
      case other            => other
    })
    override def toString: String = s"Guide.diagonal($shade, $shadePaint, $shadeOpacity, $paint, $width, $dashed)"

/** Changes written as `element.property=value` text, the way MATLAB, Java and the command line (`--set`) reach what
  * `Text(...)`, `Lines.grid(...)`, `Guide.panel(...)` and `Scale.x(position = ...)` do:
  *
  * {{{
  * Settings.parse("title.size=18")           // Text(TextRole.Title, size = 18)
  * Settings.parse("tick-label.angle=45")
  * Settings.parse("grid.dash=dotted")
  * Settings.parse("ticks.length=4")
  * Settings.parse("diagonal.shade=none")
  * Settings.parse("x-axis=top")
  * }}}
  *
  * The elements are the text roles (`title`, `subtitle`, `axis-label`, `tick-label`, `legend`, `legend-title`,
  * `color-title`, `note`) with the properties `size`, `weight`, `italic`, `align`, `angle`, `font` and `color`; `grid`,
  * `minor-grid`, `axis-line` and `frame` with `visible`, `width`, `color`, `dash`, `opacity`; `ticks` and `minor-ticks`
  * with `length`, `direction`, `visible`, `width`, `color`, `opacity`; `panel` with `color`, `opacity`, `visible`;
  * `legend-box` with `background` (a color, or `none` for no box at all), `opacity`, `frame` (a color or `none`),
  * `frame-width`, `padding` and `columns`; `diagonal` with `shade` (`none` or a color), `shade-opacity`, `color`,
  * `width`, `dashed`; `marker` with `shape` (a name, a matplotlib code, an R `pch`, `glyph:text`; see
  * [[MarkerStyle.named]]), `size`, `opacity`, `fill`, `edge-width` (0 for none), `edge-color`, `edge-opacity`,
  * `edge-dash`, `edge-visible`; `degree-0`, `degree-1`, ... with `color`, `dark-color`, `label` and the `marker`
  * properties; `segments` (`width`, `opacity`), `polygons` and `discs` (`opacity`), `lines` (`width`); `numbers`
  * (`locale`, `decimal`, `grouping`, `minus`); `size` (`width`, `height`, in pixels or with `in`, `cm`, `mm`, `pt`);
  * `camera` (`azimuth`, `elevation`, `zoom`, of a 3-D scene); `palette` and `style` alone (`palette=print`); `aspect`
  * (`1`, or `free`); and `x-axis` and `y-axis`, alone (`x-axis=top`) or with `side`, `reverse`, `padding`, `min`,
  * `max`, `format`, `crosses-at` (the value of the other axis it goes through).
  */
object Settings:
  private val textProperties = Seq("size", "weight", "italic", "align", "angle", "font", "color", "limit")
  private val lineProperties = Seq("visible", "width", "color", "dash", "opacity")
  private val tickProperties = Seq("length", "direction", "visible", "width", "color", "opacity")
  private val elementNames =
    TextRole.values.map(TextRole.hyphenated).toSeq ++
      Seq(
        "grid",
        "minor-grid",
        "axis-line",
        "ticks",
        "minor-ticks",
        "frame",
        "panel",
        "legend-box",
        "diagonal",
        "marker",
        "degree-<k>",
        "segments",
        "polygons",
        "discs",
        "lines",
        "palette",
        "style",
        "numbers",
        "size",
        "camera",
        "aspect",
        "x-axis",
        "y-axis"
      )

  private def opt[A](a: A): Optional[A] = Optional(Some(a))

  private def number(spec: String, v: String): Double =
    v.trim.toDoubleOption.filter(_.isFinite).getOrElse(throw IllegalArgumentException(s"$spec: '$v' is not a number"))

  private def flag(spec: String, v: String): Boolean = v.trim.lowerRoot match
    case "true" | "yes" | "on"  => true
    case "false" | "no" | "off" => false
    case _                      => throw IllegalArgumentException(s"$spec: '$v' should be true or false")

  private def anchor(v: String): Anchor = v.trim.lowerRoot match
    case "start" | "left"               => Anchor.Start
    case "middle" | "center" | "centre" => Anchor.Middle
    case "end" | "right"                => Anchor.End
    case other => throw IllegalArgumentException(s"unknown align '$other'; expected one of start, middle, end")

  private def unknownProperty(element: String, property: String, known: Seq[String]): Nothing =
    throw IllegalArgumentException(s"unknown property '$property' of $element; expected one of ${known.mkString(", ")}")

  private final case class LineArgs(
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    dash: Optional[Dash] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  )

  private final case class TickArgs(
    length: Optional[Double] = Optional.empty,
    direction: Optional[TickDirection] = Optional.empty,
    width: Optional[Double] = Optional.empty,
    color: Optional[String] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty
  )

  private val markerProperties =
    Seq("shape", "size", "opacity", "fill", "edge-width", "edge-color", "edge-opacity", "edge-dash", "edge-visible")

  /** The marker change a setting of `marker` or of a degree stands for. */
  private def markerSetting(spec: String, element: String, property: String, value: String): MarkerStyle =
    def edge(change: LineStyle) = MarkerStyle(edge = change)
    property match
      case "shape" => MarkerStyle.named(value)
      case "size"  =>
        val v = number(spec, value)
        require(v > 0, s"$spec: size must be a positive number")
        MarkerStyle(size = Some(v))
      case "opacity" =>
        val v = number(spec, value)
        require(v >= 0 && v <= 1, s"$spec: opacity must be between 0 and 1")
        MarkerStyle(opacity = Some(v))
      case "fill"       => MarkerStyle(fill = Some(flag(spec, value)))
      case "edge-width" =>
        val v = number(spec, value)
        require(v >= 0, s"$spec: edge-width must be 0 or more")
        edge(LineStyle(width = Some(v)))
      case "edge-color"   => edge(LineStyle(paint = Check.paint(spec, opt(value), Optional.empty)))
      case "edge-opacity" =>
        val v = number(spec, value)
        require(v >= 0 && v <= 1, s"$spec: edge-opacity must be between 0 and 1")
        edge(LineStyle(opacity = Some(v)))
      case "edge-dash"    => edge(LineStyle(dash = Some(Dash.named(value))))
      case "edge-visible" => edge(LineStyle(visible = Some(flag(spec, value))))
      case _              => unknownProperty(element, property, markerProperties)

  /** A length in pixels, written as a number of pixels or with a unit: `326`, `3.4in`, `8.6cm`, `86mm`, `245pt`. */
  private def pixels(spec: String, value: String): Int =
    val v = value.trim.lowerRoot
    val units = Seq("px" -> 1.0, "in" -> 96.0, "cm" -> 96.0 / 2.54, "mm" -> 96.0 / 25.4, "pt" -> 96.0 / 72.0)
    val (digits, factor) =
      units.collectFirst { case (u, f) if v.endsWith(u) => (v.dropRight(2), f) }.getOrElse((v, 1.0))
    math.round(number(spec, digits) * factor).toInt

  /** The axis `x-axis.reverse=true`, `x-axis.padding=0.1`, `x-axis.side=top`, `x-axis.min=0`, `x-axis.max=2`. */
  private def axisSetting(spec: String, element: String, property: String, value: String): Tweak =
    def scale(
      position: Optional[Side] = Optional.empty,
      reverse: Optional[Boolean] = Optional.empty,
      padding: Optional[Double] = Optional.empty,
      min: Optional[Double] = Optional.empty,
      max: Optional[Double] = Optional.empty,
      format: Optional[String] = Optional.empty,
      crossesAt: Optional[Double] = Optional.empty
    ): Tweak =
      if element == "x-axis" then
        Scale.x(
          min = min,
          max = max,
          format = format,
          position = position,
          reverse = reverse,
          padding = padding,
          crossesAt = crossesAt
        )
      else
        Scale.y(
          min = min,
          max = max,
          format = format,
          position = position,
          reverse = reverse,
          padding = padding,
          crossesAt = crossesAt
        )
    property match
      case "side"       => scale(position = Side.named(value))
      case "reverse"    => scale(reverse = flag(spec, value))
      case "padding"    => scale(padding = number(spec, value))
      case "min"        => scale(min = number(spec, value))
      case "max"        => scale(max = number(spec, value))
      case "format"     => scale(format = value)
      case "crosses-at" => scale(crossesAt = number(spec, value))
      case _            =>
        unknownProperty(element, property, Seq("side", "reverse", "padding", "min", "max", "format", "crosses-at"))

  /** The degree `degree-1.color=#c2185b` and its markers (`degree-1.size=6`, `degree-1.shape=pch:1`). */
  private def degreeSetting(spec: String, element: String, property: String, value: String): Tweak =
    val k = element
      .drop("degree-".length)
      .toIntOption
      .filter(_ >= 0)
      .getOrElse(
        throw IllegalArgumentException(s"unknown element '$element'; a degree is written degree-0, degree-1, ...")
      )
    property match
      case "color"      => Scale.degree(k, color = value)
      case "dark-color" => Scale.degree(k, darkColor = value)
      case "label"      => Scale.degree(k, label = value)
      case _            =>
        if markerProperties.contains(property) then
          Scale.degree(k, marker = markerSetting(spec, element, property, value))
        else unknownProperty(element, property, Seq("color", "dark-color", "label") ++ markerProperties)

  /** The change `spec` (`element.property=value`) stands for. */
  def parse(spec: String): Tweak =
    val eq = spec.indexOf('=')
    require(eq > 0, s"setting '$spec' should be element.property=value, such as title.size=18")
    val key = spec.take(eq).trim.lowerRoot
    val raw = spec.drop(eq + 1)
    val value = raw.trim
    // a separator may be a space, which the trim would take away: `numbers.grouping= ` or `numbers.grouping=space`
    val symbol = if value.equalsIgnoreCase("space") then " " else if value.isEmpty && raw.nonEmpty then raw else value
    key match
      case "palette" => Palette.named(value)
      case "style"   => Style.named(value)
      case "x-axis"  => Scale.x(position = Side.named(value))
      case "y-axis"  => Scale.y(position = Side.named(value))
      case "aspect"  =>
        if value.equalsIgnoreCase("free") then Scale.aspect(free = true) else Scale.aspect(ratio = number(spec, value))
      case _ =>
        val dot = key.indexOf('.')
        require(
          dot > 0,
          s"setting '$spec' should be element.property=value, such as title.size=18 (elements: ${elementNames.mkString(", ")})"
        )
        val (element, property) = (key.take(dot), key.drop(dot + 1))
        if element.startsWith("degree-") then degreeSetting(spec, element, property, value)
        else if TextRole.values.exists(r => TextRole.hyphenated(r) == element) then
          val role = TextRole.named(element)
          property match
            case "size"   => Text(role, size = number(spec, value))
            case "weight" => Text(role, weight = Weight.named(value))
            case "italic" => Text(role, italic = flag(spec, value))
            case "align"  => Text(role, align = anchor(value))
            case "angle"  => Text(role, angle = number(spec, value))
            case "font"   => Text(role, font = value)
            case "color"  => Text(role, color = value)
            case "limit"  => Text(role, limit = number(spec, value))
            case _        => unknownProperty(element, property, textProperties)
        else
          element match
            case "grid" | "minor-grid" | "axis-line" | "frame" =>
              val a = property match
                case "width"   => LineArgs(width = opt(number(spec, value)))
                case "color"   => LineArgs(color = opt(value))
                case "dash"    => LineArgs(dash = opt(Dash.named(value)))
                case "opacity" => LineArgs(opacity = opt(number(spec, value)))
                case "visible" => LineArgs(visible = opt(flag(spec, value)))
                case _         => unknownProperty(element, property, lineProperties)
              element match
                case "grid"       => Lines.grid(a.width, a.color, Optional.empty, a.dash, a.opacity, a.visible)
                case "minor-grid" => Lines.minorGrid(a.width, a.color, Optional.empty, a.dash, a.opacity, a.visible)
                case "axis-line"  => Lines.axis(a.width, a.color, Optional.empty, a.dash, a.opacity, a.visible)
                case _            => Lines.frame(a.width, a.color, Optional.empty, a.dash, a.opacity, a.visible)
            case "ticks" | "minor-ticks" =>
              val a = property match
                case "length"    => TickArgs(length = opt(number(spec, value)))
                case "direction" => TickArgs(direction = opt(TickDirection.named(value)))
                case "width"     => TickArgs(width = opt(number(spec, value)))
                case "color"     => TickArgs(color = opt(value))
                case "opacity"   => TickArgs(opacity = opt(number(spec, value)))
                case "visible"   => TickArgs(visible = opt(flag(spec, value)))
                case _           => unknownProperty(element, property, tickProperties)
              if element == "ticks" then
                Lines.ticks(a.length, a.direction, a.width, a.color, Optional.empty, a.opacity, a.visible)
              else Lines.minorTicks(a.length, a.direction, a.width, a.color, Optional.empty, a.opacity, a.visible)
            case "panel" =>
              property match
                case "color"   => Guide.panel(color = value)
                case "opacity" => Guide.panel(opacity = number(spec, value))
                case "visible" => Guide.panel(visible = flag(spec, value))
                case _         => unknownProperty(element, property, Seq("color", "opacity", "visible"))
            case "legend-box" =>
              def none = value.equalsIgnoreCase("none")
              property match
                case "background"  => if none then Guide.legend(box = false) else Guide.legend(background = value)
                case "opacity"     => Guide.legend(opacity = number(spec, value))
                case "frame"       => if none then Guide.legend(frame = false) else Guide.legend(frameColor = value)
                case "frame-width" => Guide.legend(frameWidth = number(spec, value))
                case "padding"     => Guide.legend(padding = number(spec, value))
                case "columns"     =>
                  val n = number(spec, value)
                  require(n >= 1 && n == n.toInt, s"$spec: columns must be a whole number, 1 or more")
                  Guide.legend(columns = n.toInt)
                case _ =>
                  unknownProperty(
                    element,
                    property,
                    Seq("background", "opacity", "frame", "frame-width", "padding", "columns")
                  )
            case "marker"            => Geom.pointsOf(markerSetting(spec, element, property, value))
            case "x-axis" | "y-axis" => axisSetting(spec, element, property, value)
            case "numbers"           =>
              property match
                case "locale"   => Numbers.locale(value)
                case "decimal"  => Numbers(decimal = symbol)
                case "grouping" => Numbers(grouping = symbol)
                case "minus"    => Numbers(minus = symbol)
                case _          => unknownProperty(element, property, Seq("locale", "decimal", "grouping", "minus"))
            case "camera" =>
              property match
                case "azimuth"   => Camera(azimuth = number(spec, value))
                case "elevation" => Camera(elevation = number(spec, value))
                case "zoom"      => Camera(zoom = number(spec, value))
                case _           => unknownProperty(element, property, Seq("azimuth", "elevation", "zoom"))
            case "size" =>
              property match
                case "width"  => Size(width = pixels(spec, value))
                case "height" => Size(height = pixels(spec, value))
                case _        => unknownProperty(element, property, Seq("width", "height"))
            case "segments" =>
              property match
                case "width"   => Geom.segments(width = number(spec, value))
                case "opacity" => Geom.segments(opacity = number(spec, value))
                case _         => unknownProperty(element, property, Seq("width", "opacity"))
            case "polygons" =>
              property match
                case "opacity" => Geom.polygons(opacity = number(spec, value))
                case _         => unknownProperty(element, property, Seq("opacity"))
            case "discs" =>
              property match
                case "opacity" => Geom.discs(opacity = number(spec, value))
                case _         => unknownProperty(element, property, Seq("opacity"))
            case "lines" =>
              property match
                case "width" => Geom.lines(width = number(spec, value))
                case _       => unknownProperty(element, property, Seq("width"))
            case "diagonal" =>
              property match
                case "shade" =>
                  if value.equalsIgnoreCase("none") then Guide.diagonal(shade = false)
                  else Guide.diagonal(shadeColor = value)
                case "shade-opacity" => Guide.diagonal(shadeOpacity = number(spec, value))
                case "color"         => Guide.diagonal(color = value)
                case "width"         => Guide.diagonal(width = number(spec, value))
                case "dashed"        => Guide.diagonal(dashed = flag(spec, value))
                case _ => unknownProperty(element, property, Seq("shade", "shade-opacity", "color", "width", "dashed"))
            case _ =>
              throw IllegalArgumentException(
                s"unknown element '$element'; expected one of ${elementNames.mkString(", ")}"
              )

  /** The changes of each of `specs`, in order, as one tweak; `None` for no settings. */
  def parseAll(specs: Seq[String]): Option[Tweak] = specs.map(parse).reduceOption(_ + _)
