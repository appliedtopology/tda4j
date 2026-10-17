package org.appliedtopology.tda4j.plot

import java.util.Locale

/** The pieces of text of a plot, each of which has a look of its own: a figure's `Text(role, size = ...)` changes one.
  *
  * `Title` and `Subtitle` head the plot; `AxisLabel` names an axis and `TickLabel` numbers it; `Legend` is a legend's
  * entries (and a color bar's numbers are `TickLabel`), `LegendTitle` its heading, `ColorTitle` the title of a color
  * bar; `Note` is the small print under a plot (and a 3-D scene's hint).
  */
enum TextRole:
  case Title, Subtitle, AxisLabel, TickLabel, Legend, LegendTitle, ColorTitle, Note

object TextRole:
  private def key(s: String): String = s.filter(_.isLetter).toLowerCase(Locale.ROOT)

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
    val key = name.filter(_.isLetter).toLowerCase(Locale.ROOT)
    values
      .find(_.toString.toLowerCase(Locale.ROOT) == key)
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
  */
final case class TextStyle(
  size: Option[Double] = None,
  weight: Option[Weight] = None,
  italic: Option[Boolean] = None,
  align: Option[Anchor] = None,
  angle: Option[Double] = None,
  font: Option[String] = None,
  paint: Option[Paint] = None
):
  /** `later` over this one: what `later` sets wins, the rest stays. */
  def merge(later: TextStyle): TextStyle = TextStyle(
    later.size.orElse(size),
    later.weight.orElse(weight),
    later.italic.orElse(italic),
    later.align.orElse(align),
    later.angle.orElse(angle),
    later.font.orElse(font),
    later.paint.orElse(paint)
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
  paint: Paint
)

/** A line as it is drawn: every setting has a value. */
final case class LineStroke(visible: Boolean, width: Double, paint: Paint, dash: Dash, opacity: Double)

/** Tick marks as they are drawn: a length in pixels (`0` is none), a direction, and the line they are drawn in. */
final case class TickMarks(length: Double, direction: TickDirection, stroke: LineStroke):
  def drawn: Boolean = stroke.visible && length > 0

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
  */
final case class Elements(
  text: Map[TextRole, TextStyle] = Map.empty,
  grid: LineStyle = LineStyle(),
  minorGrid: LineStyle = LineStyle(),
  axis: LineStyle = LineStyle(),
  ticks: TickStyle = TickStyle(),
  minorTicks: TickStyle = TickStyle(),
  frame: LineStyle = LineStyle(),
  panel: Option[(Paint, Double)] = None
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
      paint = s.paint.getOrElse(base.paint)
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
  def tickMarks: TickMarks =
    marks(ticks, TickMarks(0.0, TickDirection.Out, LineStroke(true, 1.0, Paint.Muted, Dash.Solid, 1.0)))
  def minorTickMarks: TickMarks =
    marks(minorTicks, TickMarks(0.0, TickDirection.Out, LineStroke(true, 1.0, Paint.Muted, Dash.Solid, 1.0)))

object Elements:
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
    TextFace(TextRole.defaultSize(role), weight, false, align, 0.0, None, paint)
