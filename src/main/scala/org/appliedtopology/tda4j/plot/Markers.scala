package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import java.util.Locale

/** What to change about how markers (the points of a figure) look. Whatever is `None` stays as the plot has it.
  *
  * A marker is a shape, drawn `filled` in the mark's own color or open (`fill = false`: no inside, an outline in its
  * own color), with an edge: a line around it, by default a ring of 2 pixels in the surface color that keeps
  * overlapping markers apart. The edge has a width (`0` for none), a color, an opacity and a dash, and can be removed.
  * A shape that is strokes only (a plus, a cross) is drawn in its own color, as wide as the edge.
  *
  * @param shape
  *   the shape; the plots give each homological degree its own
  * @param size
  *   the radius in pixels (before the style's `markScale`)
  * @param opacity
  *   of the whole marker, 0 to 1
  * @param fill
  *   `false` for an open marker
  * @param edge
  *   the line around the marker (`visible`, `width`, `paint`, `dash`, `opacity`): an open marker's or a stroke's own
  *   color by default, the surface's for a filled one
  */
final case class MarkerStyle(
  shape: Option[Shape] = None,
  size: Option[Double] = None,
  opacity: Option[Double] = None,
  fill: Option[Boolean] = None,
  edge: LineStyle = LineStyle()
):
  /** `later` over this one: what `later` sets wins. */
  def merge(later: MarkerStyle): MarkerStyle = MarkerStyle(
    later.shape.orElse(shape),
    later.size.orElse(size),
    later.opacity.orElse(opacity),
    later.fill.orElse(fill),
    edge.merge(later.edge)
  )

/** A marker as it is drawn: every setting has a value. */
final case class MarkerFace(shape: Shape, size: Double, opacity: Double, filled: Boolean, edge: LineStroke)

object MarkerStyle:
  private val byName: Map[String, Shape] = Map(
    "circle" -> Shape.Circle,
    "triangle" -> Shape.Triangle,
    "triangle-up" -> Shape.Triangle,
    "square" -> Shape.Square,
    "diamond" -> Shape.Diamond,
    "triangle-down" -> Shape.TriangleDown,
    "triangle-left" -> Shape.TriangleLeft,
    "triangle-right" -> Shape.TriangleRight,
    "pentagon" -> Shape.Pentagon,
    "hexagon" -> Shape.Hexagon,
    "star" -> Shape.Star,
    "dot" -> Shape.Dot,
    "plus" -> Shape.Plus,
    "cross" -> Shape.Cross,
    "vline" -> Shape.VLine,
    "hline" -> Shape.HLine
  )

  // matplotlib's one-letter codes (`+` and `x` are strokes; `P` and `X` its filled variants, which are these here)
  private val byCode: Map[String, Shape] = Map(
    "o" -> Shape.Circle,
    "." -> Shape.Dot,
    "," -> Shape.Dot,
    "^" -> Shape.Triangle,
    "v" -> Shape.TriangleDown,
    "<" -> Shape.TriangleLeft,
    ">" -> Shape.TriangleRight,
    "s" -> Shape.Square,
    "p" -> Shape.Pentagon,
    "h" -> Shape.Hexagon,
    "H" -> Shape.Hexagon,
    "*" -> Shape.Star,
    "+" -> Shape.Plus,
    "P" -> Shape.Plus,
    "x" -> Shape.Cross,
    "X" -> Shape.Cross,
    "D" -> Shape.Diamond,
    "d" -> Shape.Diamond,
    "|" -> Shape.VLine,
    "_" -> Shape.HLine
  )

  // R and ggplot's `pch`: 0 to 14 are open (the composite ones, 7 and 9 to 14, as their outer shape), 15 to 25 filled
  private val byPch: Map[Int, (Shape, Boolean)] =
    Map(
      0 -> (Shape.Square, false),
      1 -> (Shape.Circle, false),
      2 -> (Shape.Triangle, false),
      3 -> (Shape.Plus, true),
      4 -> (Shape.Cross, true),
      5 -> (Shape.Diamond, false),
      6 -> (Shape.TriangleDown, false),
      7 -> (Shape.Square, false),
      8 -> (Shape.Star, false),
      9 -> (Shape.Diamond, false),
      10 -> (Shape.Circle, false),
      11 -> (Shape.Star, false),
      12 -> (Shape.Square, false),
      13 -> (Shape.Circle, false),
      14 -> (Shape.Square, false),
      15 -> (Shape.Square, true),
      16 -> (Shape.Circle, true),
      17 -> (Shape.Triangle, true),
      18 -> (Shape.Diamond, true),
      19 -> (Shape.Circle, true),
      20 -> (Shape.Dot, true),
      21 -> (Shape.Circle, true),
      22 -> (Shape.Square, true),
      23 -> (Shape.Diamond, true),
      24 -> (Shape.Triangle, true),
      25 -> (Shape.TriangleDown, true)
    )

  /** The names, the one-letter codes and the `pch` numbers `named` knows. */
  private def known: String =
    s"names (${byName.keys.toSeq.sorted.mkString(", ")}), matplotlib codes (${byCode.keys.toSeq.sorted.mkString(" ")}), " +
      "pch:0 to pch:25, or glyph:text"

  /** The marker written as `spec`, for MATLAB, Java and the command line, which have no [[Shape]] to pass:
    *
    *   - a **name**, in any case: `circle`, `triangle` (`triangle-up`), `triangle-down`, `triangle-left`,
    *     `triangle-right`, `square`, `diamond`, `pentagon`, `hexagon`, `star`, `dot`, `plus`, `cross`, `vline`,
    *     `hline`;
    *   - a **matplotlib code**: `o . , ^ v < > s p h H * + P x X D d | _`;
    *   - an **R or ggplot `pch` number**, `pch:16` or a bare `0` to `25`: 0 to 14 are open shapes (the composite ones,
    *     7 and 9 to 14, drawn as their outer shape), 15 to 25 filled; so `pch:1` is an open circle (`fill = false`);
    *   - **`glyph:text`**, a few characters of text as the marker (`glyph:★`), and any other single character is a
    *     glyph.
    *
    * A code wins over a glyph: write `glyph:x` for the letter. Anything else says what it could have been.
    */
  def named(spec: String): MarkerStyle =
    val trimmed = spec.trim
    val lower = trimmed.toLowerCase(Locale.ROOT)
    def pch(n: Int): MarkerStyle =
      byPch.get(n) match
        case Some((shape, filled)) => MarkerStyle(shape = Some(shape), fill = Some(filled))
        case None                  => throw IllegalArgumentException(s"unknown pch $n; expected 0 to 25")
    if lower.startsWith("glyph:") then
      val text = trimmed.drop("glyph:".length)
      require(
        text.nonEmpty && text.length <= 4,
        s"glyph:$text should be one to four characters of text, such as glyph:★"
      )
      MarkerStyle(shape = Some(Shape.Glyph(text)))
    else if lower.startsWith("pch:") then
      pch(
        lower.drop(4).trim.toIntOption.getOrElse(throw IllegalArgumentException(s"'$spec' should be pch: and a number"))
      )
    else if byCode.contains(trimmed) then MarkerStyle(shape = Some(byCode(trimmed)))
    else if byName.contains(lower) then MarkerStyle(shape = Some(byName(lower)))
    else if trimmed.toIntOption.exists(n => n >= 0 && n <= 25) then pch(trimmed.toInt)
    else if trimmed.codePointCount(0, trimmed.length) == 1 then MarkerStyle(shape = Some(Shape.Glyph(trimmed)))
    else throw IllegalArgumentException(s"unknown marker '$spec'; expected one of $known")

  /** The `style` (and `stroke-width`, `stroke-dasharray`) attributes of a shape drawn as `face`: filled with `fill` or
    * open, with its edge in `edge`; `number` formats a width. A filled marker with the default ring is
    * `style="fill:F;stroke:R" stroke-width="2"`.
    */
  private[plot] def attributes(face: MarkerFace, fill: String, edge: String, number: Double => String): String =
    val fillPart = if face.filled then s"fill:$fill" else "fill:none"
    val fillOpacity = if face.filled && face.opacity != 1.0 then s";fill-opacity:${face.opacity}" else ""
    val strokeOpacity = face.opacity * face.edge.opacity
    val stroke =
      if face.edge.visible then
        s";stroke:$edge" + (if strokeOpacity == 1.0 then "" else s";stroke-opacity:$strokeOpacity")
      else ""
    val width = if face.edge.visible then s""" stroke-width="${number(face.edge.width)}"""" else ""
    val dash = if face.edge.visible then face.edge.dash.pattern.fold("")(p => s""" stroke-dasharray="$p"""") else ""
    s"""style="$fillPart$fillOpacity$stroke"$width$dash"""

  /** The marker as it is drawn: `base` (a mark's own shape, its radius, the paint it is in) with each of `layers` over
    * it, later ones winning, and what none of them sets filled in. The edge of a filled shape is a 2-pixel ring in the
    * surface color; an open shape's or a stroke's is its own color, 1.5 pixels or 2.
    */
  private[plot] def resolve(shape: Shape, size: Double, own: Paint, layers: Seq[MarkerStyle]): MarkerFace =
    val m = layers.foldLeft(MarkerStyle(shape = Some(shape), size = Some(size)))(_.merge(_))
    val sh = m.shape.get
    val filled = m.fill.getOrElse(true) && !sh.isLine
    val outline = !filled || sh.isLine
    val glyph = sh match
      case Shape.Glyph(_) => true
      case _              => false
    val width = m.edge.width.getOrElse(if sh.isLine then 2.0 else if outline then 1.5 else if glyph then 0.0 else 2.0)
    val edge = LineStroke(
      m.edge.visible.getOrElse(true) && width > 0,
      width,
      m.edge.paint.getOrElse(if outline then own else Paint.Surface),
      m.edge.dash.getOrElse(Dash.Solid),
      m.edge.opacity.getOrElse(1.0)
    )
    MarkerFace(sh, m.size.getOrElse(size), m.opacity.getOrElse(1.0), filled, edge)

/** What to change about how the marks of a plot are drawn, by kind of mark: the points (`Geom.points`: shape, size,
  * opacity, open or filled, and the edge, which can be removed), the segments (`Geom.segments`: the bars of a barcode,
  * the edges of a complex), the polygons and discs, and lines. Each changes only what it is given, in every mark of the
  * kind the figure has, and leaves a figure without any as it is:
  *
  * {{{
  * Plot.diagram(d).withPoints(size = 3.5, opacity = 0.6, edgeVisible = false)
  * Plot.barcode(d).withSegments(width = 3)
  * }}}
  *
  * A single degree's markers are changed with `Scale.degree(k, marker = MarkerStyle(...))`.
  */
object Geom:
  private def edgeOf(
    who: String,
    width: Optional[Double],
    color: Optional[String],
    paint: Optional[Paint],
    opacity: Optional[Double],
    dash: Optional[Dash],
    visible: Optional[Boolean]
  ): LineStyle =
    Check.atLeastZero(who, "edgeWidth", width)
    Check.fraction(who, "edgeOpacity", opacity)
    LineStyle(visible.toOption, width.toOption, Check.paint(who, color, paint), dash.toOption, opacity.toOption)

  /** The markers of every figure's points: `shape` ([[Shape]]; for a string, `Shape.named`), `size` the radius in
    * pixels, `opacity` of the whole marker, `fill = false` for open markers, and the edge: `edgeWidth` (0 for none),
    * `edgeColor` (a CSS color) or `edgePaint` (a palette role), `edgeOpacity`, `edgeDash`, `edgeVisible = false` to
    * remove it. A marker's edge is a ring in the surface color unless it is open, or a stroke (plus, cross), when it is
    * the marker's own color.
    */
  def points(
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
  ): Tweak =
    Check.positive("Geom.points", "size", size)
    Check.fraction("Geom.points", "opacity", opacity)
    PointsChange(
      MarkerStyle(
        shape.toOption,
        size.toOption,
        opacity.toOption,
        fill.toOption,
        edgeOf("Geom.points", edgeWidth, edgeColor, edgePaint, edgeOpacity, edgeDash, edgeVisible)
      )
    )

  /** The markers' change that `style` is, for the text settings. */
  private[plot] def pointsOf(style: MarkerStyle): Tweak = PointsChange(style)

  /** The line segments (a barcode's bars, a complex's edges): `width` in pixels, `opacity`. */
  def segments(width: Optional[Double] = Optional.empty, opacity: Optional[Double] = Optional.empty): Tweak =
    Check.positive("Geom.segments", "width", width)
    Check.fraction("Geom.segments", "opacity", opacity)
    MarksChange("segments", width.toOption, opacity.toOption)

  /** The filled polygons (a complex's triangles): `opacity`. */
  def polygons(opacity: Optional[Double] = Optional.empty): Tweak =
    Check.fraction("Geom.polygons", "opacity", opacity)
    MarksChange("polygons", None, opacity.toOption)

  /** The discs (the balls of a Cech or alpha complex): `opacity`. */
  def discs(opacity: Optional[Double] = Optional.empty): Tweak =
    Check.fraction("Geom.discs", "opacity", opacity)
    MarksChange("discs", None, opacity.toOption)

  /** The polylines (a cycle, a landscape): `width` in pixels. */
  def lines(width: Optional[Double] = Optional.empty): Tweak =
    Check.positive("Geom.lines", "width", width)
    MarksChange("lines", width.toOption, None)

  private final case class PointsChange(style: MarkerStyle) extends Tweak:
    def onFigure(f: Figure): Figure = f.copy(elements = f.elements.copy(marker = f.elements.marker.merge(style)))
    override def onScene(s: Scene): Scene =
      if style.shape.isDefined || style.fill.isDefined then
        throw UnsupportedOperationException(
          "a 3-D scene draws round, filled points; its marker has a size, an opacity and an edge, not a shape or a fill"
        )
      s.copy(elements = s.elements.copy(marker = s.elements.marker.merge(style)))
    override def toString: String = s"Geom.points($style)"

  private final case class MarksChange(kind: String, width: Option[Double], opacity: Option[Double]) extends Tweak:
    def onFigure(f: Figure): Figure = f.copy(marks = f.marks.map {
      case m: Mark.Segments if kind == "segments" =>
        m.copy(width = width.getOrElse(m.width), opacity = opacity.getOrElse(m.opacity))
      case m: Mark.Polygons if kind == "polygons" => m.copy(opacity = opacity.getOrElse(m.opacity))
      case m: Mark.Discs if kind == "discs"       => m.copy(opacity = opacity.getOrElse(m.opacity))
      case m: Mark.Line if kind == "lines"        => m.copy(width = width.getOrElse(m.width))
      case other                                  => other
    })
    override def toString: String = s"Geom.$kind(width = $width, opacity = $opacity)"
