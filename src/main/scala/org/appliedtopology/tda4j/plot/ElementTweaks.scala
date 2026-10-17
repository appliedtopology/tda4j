package org.appliedtopology.tda4j.plot

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
  private val alignable = Set(TextRole.Title, TextRole.Subtitle, TextRole.AxisLabel, TextRole.Note)

  /** The roles a 3-D scene has: it has no axes, legend or color bar. */
  private[plot] val sceneRoles = Set(TextRole.Title, TextRole.Subtitle, TextRole.Note)

  /** The text of `role`. `size` is in pixels, `weight` a [[Weight]], `italic` slants it, `align` puts a title,
    * subtitle, axis label or note at the `Start`, `Middle` or `End` of the plot area, `angle` slants the x axis's tick
    * labels (degrees counterclockwise, between -90 and 90), `font` a CSS `font-family` list for this text alone, and
    * `color` (a CSS color) or `paint` (a palette role, so that it follows the theme) its color.
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
    paint: Optional[Paint] = Optional.empty
  ): Tweak =
    val who = s"Text(${TextRole.hyphenated(role)})"
    Check.positive(who, "size", size)
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
        Check.paint(who, color, paint)
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
  * `diagonal` with `shade` (`none` or a color), `shade-opacity`, `color`, `width`, `dashed`; and `x-axis` (`bottom`,
  * `top`) and `y-axis` (`left`, `right`).
  */
object Settings:
  private val textProperties = Seq("size", "weight", "italic", "align", "angle", "font", "color")
  private val lineProperties = Seq("visible", "width", "color", "dash", "opacity")
  private val tickProperties = Seq("length", "direction", "visible", "width", "color", "opacity")
  private val elementNames =
    TextRole.values.map(TextRole.hyphenated).toSeq ++
      Seq("grid", "minor-grid", "axis-line", "ticks", "minor-ticks", "frame", "panel", "diagonal", "x-axis", "y-axis")

  private def opt[A](a: A): Optional[A] = Optional(Some(a))

  private def number(spec: String, v: String): Double =
    v.trim.toDoubleOption.filter(_.isFinite).getOrElse(throw IllegalArgumentException(s"$spec: '$v' is not a number"))

  private def flag(spec: String, v: String): Boolean = v.trim.toLowerCase(java.util.Locale.ROOT) match
    case "true" | "yes" | "on"  => true
    case "false" | "no" | "off" => false
    case _                      => throw IllegalArgumentException(s"$spec: '$v' should be true or false")

  private def anchor(v: String): Anchor = v.trim.toLowerCase(java.util.Locale.ROOT) match
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

  /** The change `spec` (`element.property=value`) stands for. */
  def parse(spec: String): Tweak =
    val eq = spec.indexOf('=')
    require(eq > 0, s"setting '$spec' should be element.property=value, such as title.size=18")
    val key = spec.take(eq).trim.toLowerCase(java.util.Locale.ROOT)
    val value = spec.drop(eq + 1).trim
    key match
      case "x-axis" => Scale.x(position = Side.named(value))
      case "y-axis" => Scale.y(position = Side.named(value))
      case _        =>
        val dot = key.indexOf('.')
        require(
          dot > 0,
          s"setting '$spec' should be element.property=value, such as title.size=18 (elements: ${elementNames.mkString(", ")})"
        )
        val (element, property) = (key.take(dot), key.drop(dot + 1))
        if TextRole.values.exists(r => TextRole.hyphenated(r) == element) then
          val role = TextRole.named(element)
          property match
            case "size"   => Text(role, size = number(spec, value))
            case "weight" => Text(role, weight = Weight.named(value))
            case "italic" => Text(role, italic = flag(spec, value))
            case "align"  => Text(role, align = anchor(value))
            case "angle"  => Text(role, angle = number(spec, value))
            case "font"   => Text(role, font = value)
            case "color"  => Text(role, color = value)
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
