package org.appliedtopology.tda4j.plot

/** A point in data coordinates. */
type XY = (Double, Double)

/** Marker shapes; the plots give each homological degree its own, so degree never rests on color alone. */
enum Shape:
  case Circle, Triangle, Square, Diamond

object Shape:
  /** The marker of homological degree `k`. */
  def ofDegree(k: Int): Shape = Shape.fromOrdinal(k % Shape.values.length)

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

/** One axis. `range` fixes the data interval shown (otherwise the marks' extent, padded); `ticks` replaces the
  * automatic ticks with labelled positions; `visible = false` drops ticks, labels and baseline (a point cloud's frame).
  */
final case class Axis(
  label: String = "",
  range: Option[(Double, Double)] = None,
  ticks: Option[IndexedSeq[(Double, String)]] = None,
  grid: Boolean = true,
  visible: Boolean = true
)

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

/** A color scale beside the plot: `color(t)` for `t` in `[0, 1]` spans `lo` to `hi`. */
final case class Colorbar(label: String, lo: Double, hi: Double, color: Double => String)

/** A 2-D figure: marks in data coordinates, two axes, a legend, an optional colorbar. Immutable: `+`/`++` add marks,
  * `copy` changes the rest. `svg` renders it ([[Svg]]); `view()` shows it live; `save("f.svg")` writes it.
  */
final case class Figure(
  marks: Vector[Mark] = Vector.empty,
  title: String = "",
  subtitle: String = "",
  x: Axis = Axis(),
  y: Axis = Axis(),
  legend: Vector[LegendItem] = Vector.empty,
  colorbar: Option[Colorbar] = None,
  width: Int = 640,
  height: Int = 440,
  equalAspect: Boolean = false,
  palette: Palette = Palette.default
) extends Plot:
  def +(mark: Mark): Figure = copy(marks = marks :+ mark)
  def ++(more: IterableOnce[Mark]): Figure = copy(marks = marks ++ more)

  /** This figure's marks drawn over `under`'s (axes, title and legend from this one, legends merged). */
  def over(under: Figure): Figure = copy(marks = under.marks ++ marks, legend = under.legend ++ legend)

  def svg(theme: Theme = Theme.Auto): String = Svg.render(this, theme)
  def fragment(theme: Theme = Theme.Auto): String = svg(theme)
  def svgFile(theme: Theme): String = """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + svg(theme)
  def html(theme: Theme = Theme.Auto): String = Html.page(title, svg(theme), palette)
