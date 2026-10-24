package org.appliedtopology.tda4j.plot

import RootText.lowerRoot

/** The pieces of text of a plot, each of which has a look of its own: a figure's `Text(role, size = ...)` changes one.
  *
  * `Title` and `Subtitle` head the plot; `AxisLabel` names an axis and `TickLabel` numbers it; `Legend` is a legend's
  * entries (and a color bar's numbers are `TickLabel`), `LegendTitle` its heading, `ColorTitle` the title of a color
  * bar; `Note` is the small print under a plot (and a 3-D scene's hint); `Caption` is the plot's caption under it, and
  * `Tag` the letter of a panel of a board ("(a)").
  */
enum TextRole:
  case Title, Subtitle, AxisLabel, TickLabel, Legend, LegendTitle, ColorTitle, Note, Caption, Tag

object TextRole:
  private def key(s: String): String = s.filter(_.isLetter).lowerRoot

  /** The name a role has in settings and on the command line: `axis-label`. */
  def hyphenated(role: TextRole): String =
    role.toString.flatMap(c => if c.isUpper then s"-${c.toLower}" else c.toString).drop(1)

  /** The role called `name`, in any case, with or without hyphens: `title`, `axis-label`, `tick-label`, `legend`,
    * `legend-title`, `color-title`, `note`, `subtitle`.
    */
  def named(name: String): TextRole =
    values
      .find(r => key(r.toString) == key(name))
      .getOrElse(
        throw IllegalArgumentException(
          s"unknown text '$name'; expected one of ${values.map(hyphenated).mkString(", ")}"
        )
      )

  /** The size in pixels a role has unless changed (before the style's text scale). */
  def defaultSize(role: TextRole): Double = role match
    case Title       => 15.0
    case Subtitle    => 12.0
    case AxisLabel   => 12.0
    case TickLabel   => 11.0
    case Legend      => 12.0
    case LegendTitle => 12.0
    case ColorTitle  => 11.0
    case Note        => 11.0
    case Caption     => 11.0
    case Tag         => 16.0

/** How heavy a text is, as CSS weights go. */
enum Weight(val css: Int):
  case Light extends Weight(300)
  case Normal extends Weight(400)
  case Medium extends Weight(500)
  case SemiBold extends Weight(600)
  case Bold extends Weight(700)

object Weight:
  /** The weight called `name`, in any case: `light`, `normal`, `medium`, `semibold` (or `semi-bold`) and `bold`. */
  def named(name: String): Weight =
    val key = name.filter(_.isLetter).lowerRoot
    values
      .find(_.toString.lowerRoot == key)
      .getOrElse(
        throw IllegalArgumentException(s"unknown weight '$name'; expected one of light, normal, medium, semibold, bold")
      )

/** How a line is dashed. */
enum Dash(val pattern: Option[String]):
  case Solid extends Dash(None)
  case Dashed extends Dash(Some("5 4"))
  case Dotted extends Dash(Some("1.5 3"))

object Dash:
  /** The dash called `name`, in any case: `solid`, `dashed` or `dotted`. */
  def named(name: String): Dash =
    values
      .find(_.toString.equalsIgnoreCase(name.trim))
      .getOrElse(throw IllegalArgumentException(s"unknown dash '$name'; expected one of solid, dashed, dotted"))

/** Which way the tick marks of an axis point from it: away from the plot, or into it. */
enum TickDirection:
  case Out, In

object TickDirection:
  /** The direction called `name`, in any case: `out` or `in`. */
  def named(name: String): TickDirection =
    values
      .find(_.toString.equalsIgnoreCase(name.trim))
      .getOrElse(throw IllegalArgumentException(s"unknown tick direction '$name'; expected one of out, in"))

/** A side of the plot area: where an axis can sit. An x axis is at the `Bottom` (the default) or the `Top`, a y axis at
  * the `Left` (the default) or the `Right`.
  */
enum Side:
  case Bottom, Top, Left, Right

object Side:
  /** The side called `name`, in any case: `bottom`, `top`, `left` or `right`. */
  def named(name: String): Side =
    values
      .find(_.toString.equalsIgnoreCase(name.trim))
      .getOrElse(throw IllegalArgumentException(s"unknown side '$name'; expected one of bottom, top, left, right"))

/** What to change about the look of one [[TextRole]]. Whatever is `None` stays as the plot has it.
  *
  * @param size
  *   in pixels, before the style's `textScale`
  * @param align
  *   where along the plot area the text is: `Start` (its left edge), `Middle` or `End`; for a title, subtitle, axis
  *   label and note
  * @param angle
  *   degrees counterclockwise: the x axis's tick labels, slanted to fit long labels
  * @param font
  *   a CSS `font-family` list for this text alone
  * @param paint
  *   a palette role (`Paint.Muted`) or a color of your own (`Paint.Fixed("#333333")`)
  * @param limit
  *   the longest the text may be, in pixels: a longer one is cut and ends in `…`, with the whole text as its tooltip.
  *   The width is estimated letter by letter ([[TextWidth]]), as the font the reader sees is not known
  */
final case class TextStyle(
  size: Option[Double] = None,
  weight: Option[Weight] = None,
  italic: Option[Boolean] = None,
  align: Option[Anchor] = None,
  angle: Option[Double] = None,
  font: Option[String] = None,
  paint: Option[Paint] = None,
  limit: Option[Double] = None
):
  /** `later` over this one: what `later` sets wins, the rest stays. */
  def merge(later: TextStyle): TextStyle = TextStyle(
    later.size.orElse(size),
    later.weight.orElse(weight),
    later.italic.orElse(italic),
    later.align.orElse(align),
    later.angle.orElse(angle),
    later.font.orElse(font),
    later.paint.orElse(paint),
    later.limit.orElse(limit)
  )

/** What to change about the look of a line of the plot (gridlines, an axis line, a tick mark, a frame). Whatever is
  * `None` stays as the plot has it.
  *
  * @param visible
  *   `false` to draw none
  * @param width
  *   in pixels; the style's `markScale` does not change it
  * @param paint
  *   a palette role or a color of your own
  */
final case class LineStyle(
  visible: Option[Boolean] = None,
  width: Option[Double] = None,
  paint: Option[Paint] = None,
  dash: Option[Dash] = None,
  opacity: Option[Double] = None
):
  def merge(later: LineStyle): LineStyle = LineStyle(
    later.visible.orElse(visible),
    later.width.orElse(width),
    later.paint.orElse(paint),
    later.dash.orElse(dash),
    later.opacity.orElse(opacity)
  )

/** What to change about the tick marks of an axis. `length` is in pixels, `0` for none.
  */
final case class TickStyle(
  length: Option[Double] = None,
  direction: Option[TickDirection] = None,
  line: LineStyle = LineStyle()
):
  def merge(later: TickStyle): TickStyle =
    TickStyle(later.length.orElse(length), later.direction.orElse(direction), line.merge(later.line))

/** A text as it is drawn: every setting has a value (`weight` and `font` are `None` where the plot sets none). */
final case class TextFace(
  size: Double,
  weight: Option[Weight],
  italic: Boolean,
  align: Anchor,
  angle: Double,
  font: Option[String],
  paint: Paint,
  limit: Option[Double] = None
):
  /** `text` as far as it fits within `limit` at the pixel size `px`, and whether it was cut. The width of a text is
    * estimated letter by letter ([[TextWidth]]) for this face's font, or `font` (the style's font list) when the face
    * names none; a cut text ends in an ellipsis, which counts toward the limit.
    */
  def fit(text: String, px: Double, font: String = Style.DefaultFont): (String, Boolean) =
    limit match
      case Some(l) =>
        val family = TextWidth.Family.of(this.font.getOrElse(font))
        val bold = weight.exists(_.css >= 600)
        def width(t: String) = TextWidth.of(t, px, family, bold)
        if width(text) <= l then (text, false)
        else
          val dots = width("…")
          val widths = text.scanLeft(0.0)((w, c) => w + width(c.toString)).tail
          val keep = math.max(1, widths.count(_ + dots <= l))
          (text.take(keep) + "…", true)
      case _ => (text, false)

/** A line as it is drawn: every setting has a value. */
final case class LineStroke(visible: Boolean, width: Double, paint: Paint, dash: Dash, opacity: Double)

/** Tick marks as they are drawn: a length in pixels (`0` is none), a direction, and the line they are drawn in. */
final case class TickMarks(length: Double, direction: TickDirection, stroke: LineStroke):
  def drawn: Boolean = stroke.visible && length > 0

/** The box around a legend and how its entries are arranged, as asked for: nothing is drawn behind a legend unless it
  * has a `fill` or a visible `frame`.
  *
  * @param fill
  *   the background of the box (a paint and its opacity), if it has one
  * @param frame
  *   the line round the box
  * @param padding
  *   the room between the box and its entries, in pixels before the legend's text scale (6 if not given)
  * @param columns
  *   the number of columns of entries (1 if not given), filled one column before the next
  */
final case class LegendBox(
  fill: Option[(Paint, Double)] = None,
  frame: LineStyle = LineStyle(),
  padding: Option[Double] = None,
  columns: Option[Int] = None
)

/** The finer-grained look of a figure: what each piece of text, the gridlines, the axis lines, the tick marks, a frame
  * and the plot area's own fill are like, where the plot's [[Style]] has only the font, the scales of text and marks,
  * the legend's place and the background.
  *
  * It sits beside the style, not in it, so that a `Style` added later does not undo it and tweaks of both come in any
  * order. Change it with the `Text(...)`, `Lines.grid(...)`, `Lines.ticks(...)`, `Guide.panel(...)` tweaks, each of
  * which changes only what it is given.
  *
  * @param text
  *   the look of each text role that was changed
  * @param panel
  *   the fill of the plot area, if it has one
  * @param legendBox
  *   the box round the legend and its columns
  * @param marker
  *   what to change about every point's marker (the shape, size, opacity, fill and edge)
  * @param numbers
  *   how the numbers of the tick labels and the color bar are written
  */
final case class Elements(
  text: Map[TextRole, TextStyle] = Map.empty,
  grid: LineStyle = LineStyle(),
  minorGrid: LineStyle = LineStyle(),
  axis: LineStyle = LineStyle(),
  ticks: TickStyle = TickStyle(),
  minorTicks: TickStyle = TickStyle(),
  frame: LineStyle = LineStyle(),
  panel: Option[(Paint, Double)] = None,
  marker: MarkerStyle = MarkerStyle(),
  numbers: NumberStyle = NumberStyle(),
  legendBox: LegendBox = LegendBox()
):
  /** This with `style` merged into what the text role `role` has. */
  def withText(role: TextRole, style: TextStyle): Elements =
    copy(text = text.updated(role, text.getOrElse(role, TextStyle()).merge(style)))

  /** The text of `role` as it is drawn. */
  def face(role: TextRole): TextFace =
    val base = Elements.defaultText(role)
    val s = text.getOrElse(role, TextStyle())
    TextFace(
      size = s.size.getOrElse(TextRole.defaultSize(role)),
      weight = s.weight.orElse(base.weight),
      italic = s.italic.getOrElse(false),
      align = s.align.getOrElse(base.align),
      angle = s.angle.getOrElse(0.0),
      font = s.font,
      paint = s.paint.getOrElse(base.paint),
      limit = s.limit
    )

  private def stroke(line: LineStyle, base: LineStroke): LineStroke = LineStroke(
    line.visible.getOrElse(base.visible),
    line.width.getOrElse(base.width),
    line.paint.getOrElse(base.paint),
    line.dash.getOrElse(base.dash),
    line.opacity.getOrElse(base.opacity)
  )

  private def marks(t: TickStyle, base: TickMarks): TickMarks =
    TickMarks(t.length.getOrElse(base.length), t.direction.getOrElse(base.direction), stroke(t.line, base.stroke))

  def gridStroke: LineStroke = stroke(grid, LineStroke(true, 1.0, Paint.Grid, Dash.Solid, 1.0))
  def minorGridStroke: LineStroke = stroke(minorGrid, LineStroke(false, 0.5, Paint.Grid, Dash.Solid, 1.0))
  def axisStroke: LineStroke = stroke(axis, LineStroke(true, 1.0, Paint.Axis, Dash.Solid, 1.0))
  def frameStroke: LineStroke = stroke(frame, LineStroke(false, 1.0, Paint.Axis, Dash.Solid, 1.0))

  /** The line round the legend's box: off unless asked for. */
  def legendFrameStroke: LineStroke = stroke(legendBox.frame, LineStroke(false, 1.0, Paint.Axis, Dash.Solid, 1.0))

  /** Whether the legend has a box: a fill, or a frame that shows. */
  def legendBoxDrawn: Boolean = legendBox.fill.isDefined || legendFrameStroke.visible

  /** The room between the legend's box and its entries, in pixels before the legend's text scale. */
  def legendPadding: Double = legendBox.padding.getOrElse(Elements.DefaultLegendPadding)

  def tickMarks: TickMarks =
    marks(ticks, TickMarks(0.0, TickDirection.Out, LineStroke(true, 1.0, Paint.Muted, Dash.Solid, 1.0)))
  def minorTickMarks: TickMarks =
    marks(minorTicks, TickMarks(0.0, TickDirection.Out, LineStroke(true, 1.0, Paint.Muted, Dash.Solid, 1.0)))

object Elements:
  /** The room inside a legend's box, in pixels. */
  val DefaultLegendPadding: Double = 6.0

  /** The fill of a legend's box when only its opacity or nothing is given: the page, a little see-through. */
  val DefaultLegendFill: (Paint, Double) = (Paint.Surface, 0.9)

  /** What each text role is like unless changed: its weight, its alignment and its color. */
  private[plot] def defaultText(role: TextRole): TextFace =
    val (weight, align, paint) = role match
      case TextRole.Title       => (Some(Weight.SemiBold), Anchor.Start, Paint.Ink)
      case TextRole.Subtitle    => (None, Anchor.Start, Paint.Secondary)
      case TextRole.AxisLabel   => (None, Anchor.Middle, Paint.Secondary)
      case TextRole.TickLabel   => (None, Anchor.Middle, Paint.Muted)
      case TextRole.Legend      => (None, Anchor.Start, Paint.Secondary)
      case TextRole.LegendTitle => (Some(Weight.SemiBold), Anchor.Start, Paint.Secondary)
      case TextRole.ColorTitle  => (None, Anchor.Start, Paint.Secondary)
      case TextRole.Note        => (None, Anchor.Start, Paint.Muted)
      case TextRole.Caption     => (None, Anchor.Start, Paint.Muted)
      case TextRole.Tag         => (Some(Weight.SemiBold), Anchor.Start, Paint.Ink)
    TextFace(TextRole.defaultSize(role), weight, false, align, 0.0, None, paint)

/** How the numbers of tick labels and color bars are written: the decimal separator, a separator between groups of
  * three digits (none unless given) and the minus sign (`"−"`, the typographic one, is a common wish). Whatever is
  * `None` stays as it is: `.`, no grouping, `-`.
  */
final case class NumberStyle(
  decimal: Option[String] = None,
  grouping: Option[String] = None,
  minus: Option[String] = None
):
  def merge(later: NumberStyle): NumberStyle =
    NumberStyle(later.decimal.orElse(decimal), later.grouping.orElse(grouping), later.minus.orElse(minus))

  def isDefault: Boolean = decimal.isEmpty && grouping.isEmpty && minus.isEmpty

  /** `text` with each number in it written as this style has it: `-1234.5` with `−`, `,` and a space is `−1 234,5`; a
    * number with an exponent (`1e-4`) keeps it, with the minus sign; what is not a number (`H0`, `∞`) is left alone.
    */
  def apply(text: String): String =
    if isDefault then text
    else if text.startsWith("-∞") then minus.getOrElse("-") + text.drop(1)
    else
      NumberStyle.number.replaceAllIn(
        text,
        m =>
          val sign = if m.group(1).isEmpty then "" else minus.getOrElse("-")
          val whole = m.group(2)
          val grouped = grouping.fold(whole) { g =>
            whole.reverse.grouped(3).mkString(g.reverse).reverse
          }
          val frac = Option(m.group(3)).fold("")(f => decimal.getOrElse(".") + f)
          val exp = Option(m.group(4)).fold("")(e => "e" + e.replace("-", minus.getOrElse("-")))
          scala.util.matching.Regex.quoteReplacement(sign + grouped + frac + exp)
      )

object NumberStyle:
  private val number = """(-?)(\d+)(?:\.(\d+))?(?:e(-?\d+))?""".r

  /** The decimal separator and the minus sign of the locale `tag` (`de`, `fr`, `sv-SE`); grouping is not taken, as a
    * plot's four-digit numbers are rarely grouped.
    */
  def locale(tag: String): NumberStyle =
    val (decimal, minus) = RootText.decimalAndMinus(tag)
    NumberStyle(Some(decimal), None, Some(minus))
