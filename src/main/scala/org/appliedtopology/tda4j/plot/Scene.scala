package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** A point in 3-D data coordinates. */
type XYZ = (Double, Double, Double)

/** What a 3-D scene is made of: points, segments and triangles, each with one paint or one per item; balls around
  * points (translucent discs, as a sphere looks in an orthographic view) and halos (discs of a size in pixels, to ring
  * a point).
  */
sealed trait Layer:
  def paints: IndexedSeq[Paint]
  final def paintAt(i: Int): Paint = if paints.size == 1 then paints(0) else paints(i)

object Layer:
  final case class Points(at: IndexedSeq[XYZ], paints: IndexedSeq[Paint], radius: Double = 3.5) extends Layer
  final case class Segments(
    ends: IndexedSeq[(XYZ, XYZ)],
    paints: IndexedSeq[Paint],
    width: Double = 1.2,
    opacity: Double = 1.0
  ) extends Layer
  final case class Triangles(corners: IndexedSeq[(XYZ, XYZ, XYZ)], paints: IndexedSeq[Paint], opacity: Double = 0.25)
      extends Layer

  /** Balls of `radius` (in data units) around `centers`: what the overlaps of a Vietoris-Rips or Cech complex record.
    * An orthographic view of a ball is a disc of the same radius, so a ball is drawn as a translucent disc; the scene's
    * frame comes from the centres, so the picture stays put as the balls grow (an item of an interactive page can
    * follow a control with [[Behavior.Radius]]).
    */
  final case class Balls(centers: IndexedSeq[XYZ], radius: Double, paints: IndexedSeq[Paint], opacity: Double = 0.09)
      extends Layer

  /** Translucent discs of `radius` pixels at `at`: a ring around a point (the halo of a chosen item, see
    * [[Panel.Spatial.selecting]]).
    */
  final case class Halos(at: IndexedSeq[XYZ], paints: IndexedSeq[Paint], radius: Double = 8.5, opacity: Double = 0.3)
      extends Layer

/** A 3-D plot. In a page (`html`, the live viewer) it is drawn on a canvas you can turn (drag) and zoom (scroll), with
  * no library to load; as an `.svg` file it is one view, from `azimuth`/`elevation` (degrees), drawn back to front.
  *
  * With `turns = false` the view stays as `azimuth` and `elevation` give it and a drag pans instead; the scroll wheel
  * zooms about the pointer. A flat picture (points in the plane, seen from above: `azimuth = 0`, `elevation = 90`) is
  * drawn this way when it has too many items for an SVG, which a canvas draws in a fraction of the time and bytes.
  *
  * The scene is fitted into the picture by its points (the centres of balls and halos count, their extent does not).
  * `fitted` gives the centre and the radius to fit to instead: an interactive page does so for a scene whose items come
  * and go or move with a control, so that the picture stays put (see [[Interactive]]).
  *
  * `axes` draws an x axis along the foot of the picture and a y axis along its left edge, numbered with the coordinates
  * of the plane at round values as a plot's axes are, for a flat picture (seen from above: `azimuth = 0`,
  * `elevation = 90`), with gridlines at the numbers. The picture then keeps to the room inside the axes, and a canvas
  * that pans and zooms numbers its axes again for what it shows. `xAxis` and `yAxis` hold what the axes are given by
  * [[Labels]] (`x`, `y`) and [[Scale]] (`transform`, `min`, `max`, `padding`, `breaks`, `format`, `grid`, `visible`,
  * `reverse`): an axis can be linear, log, square-root or symmetric log (`Transform.Custom` has no ticks a page can
  * draw) and can run the other way, and the picture is then the data after the transforms, drawn at one scale on both
  * axes: a straight segment between two places is a straight line between their positions. `min`, `max` and `padding`
  * give the range the picture opens on (and returns to), widened to the shape of the plot area; without them the
  * picture is fitted to its points.
  *
  * `region` is a rectangle drawn over the picture, a translucent fill with a dashed outline, in the units of the view
  * plane: the view from `azimuth` and `elevation` (at `zoom` 1), `x` to the right and `y` up, in the radius of the
  * fitted frame. [[PageState.brush]] puts it in a still picture of a [[Panel.Spatial]], where it is the rectangle a
  * viewer has dragged out.
  */
final case class Scene(
  layers: Vector[Layer] = Vector.empty,
  title: String = "",
  subtitle: String = "",
  width: Int = 640,
  height: Int = 520,
  azimuth: Double = -35,
  elevation: Double = 25,
  palette: Palette = Palette.default,
  style: Style = Style.default,
  degrees: Map[Int, DegreeStyle] = Map.empty,
  elements: Elements = Elements(),
  zoom: Double = 1.0,
  turns: Boolean = true,
  fitted: Option[(XYZ, Double)] = None,
  region: Option[Window] = None,
  axes: Boolean = false,
  xAxis: Axis = Axis(),
  yAxis: Axis = Axis()
) extends Plot:
  def +(layer: Layer): Scene = copy(layers = layers :+ layer)
  def ++(more: IterableOnce[Layer]): Scene = copy(layers = layers ++ more)

  /** This scene with the change `tweak` makes. Only the tweaks that need no axes apply: a palette, a [[Style]], a
    * [[Size]], the [[Camera]], the title and subtitle of [[Labels]] and the colors of `Scale.degree`.
    */
  def +(tweak: Tweak): Scene = tweak.onScene(this)

  // The tweaks that apply to a scene, as methods to chain (as on [[Figure]]); the plain-argument ones are for MATLAB.

  /** The title and subtitle, and the labels of the axes of a flat picture (`withAxes`): only what is given changes. A
    * scene has no legend or color bar to label.
    */
  def withLabels(
    title: Optional[String] = Optional.empty,
    subtitle: Optional[String] = Optional.empty,
    x: Optional[String] = Optional.empty,
    y: Optional[String] = Optional.empty
  ): Scene =
    this + Labels(title = title, subtitle = subtitle, x = x, y = y)

  /** The x axis label of a flat picture with axes. */
  def withXLabel(text: String): Scene = withLabels(x = text)

  /** The y axis label of a flat picture with axes. */
  def withYLabel(text: String): Scene = withLabels(y = text)

  /** The x axis of a flat picture with axes, as [[Scale.x]] has it: its `transform` (linear, log, square-root or
    * symmetric log), the `min` and `max` of the range the picture opens on (`padding`, the fraction of the data's
    * extent left empty at each end where an end is not given, is 0.04 unless set), the `breaks` where the numbers
    * stand, the printf `format` of the numbers (`%f` and `%e`), whether it has `grid`lines, whether it is `visible` at
    * all, and whether it runs the other way (`reverse`). A canvas keeps equal units on both axes, so a range the
    * picture is opened on is widened to the shape of the plot area, and its pan and zoom move off it. `position`,
    * `crossesAt` and `minusInfinity` of [[Scale.x]] are not here: a canvas's axes stay at the foot and the left edge of
    * its plot area, and a log axis has no `-∞` line (`Transform.SymLog` keeps zero and negative numbers).
    */
  def withXScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty
  ): Scene =
    this + Scale.x(
      transform = transform,
      min = min,
      max = max,
      breaks = breaks,
      format = format,
      grid = grid,
      visible = visible,
      reverse = reverse,
      padding = padding
    )

  /** The y axis of a flat picture with axes; as [[withXScale]]. */
  def withYScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty
  ): Scene =
    this + Scale.y(
      transform = transform,
      min = min,
      max = max,
      breaks = breaks,
      format = format,
      grid = grid,
      visible = visible,
      reverse = reverse,
      padding = padding
    )

  /** Both axes alike: a log-log picture is `withXYScale(Transform.Log10)`. */
  def withXYScale(
    transform: Optional[Transform] = Optional.empty,
    min: Optional[Double] = Optional.empty,
    max: Optional[Double] = Optional.empty,
    breaks: Optional[Seq[Double]] = Optional.empty,
    format: Optional[String] = Optional.empty,
    grid: Optional[Boolean] = Optional.empty,
    visible: Optional[Boolean] = Optional.empty,
    reverse: Optional[Boolean] = Optional.empty,
    padding: Optional[Double] = Optional.empty
  ): Scene =
    this + Scale.xy(
      transform = transform,
      min = min,
      max = max,
      breaks = breaks,
      format = format,
      grid = grid,
      visible = visible,
      reverse = reverse,
      padding = padding
    )

  /** The x axis on the scale called `name`: `linear`, `log`, `sqrt` or `symlog`. */
  def withXScale(name: String): Scene = withXScale(Transform.named(name))

  /** The y axis on the scale called `name`: `linear`, `log`, `sqrt` or `symlog`. */
  def withYScale(name: String): Scene = withYScale(Transform.named(name))

  /** The range of the x axis the picture opens on, in data units, for MATLAB and Java. */
  def withXRange(min: Double, max: Double): Scene = withXScale(min = min, max = max)

  /** The range of the y axis the picture opens on, in data units, for MATLAB and Java. */
  def withYRange(min: Double, max: Double): Scene = withYScale(min = min, max = max)

  /** The size in pixels; give one to keep the other. */
  def withSize(width: Optional[Int] = Optional.empty, height: Optional[Int] = Optional.empty): Scene =
    this + Size(width, height)

  /** The color of homological degree `k` (and its dark-mode color). See [[Scale.degree]]. */
  def withDegree(
    k: Int,
    color: Optional[String] = Optional.empty,
    darkColor: Optional[String] = Optional.empty
  ): Scene =
    this + Scale.degree(k, color = color, darkColor = darkColor)

  /** Where the camera looks from (`azimuth` and `elevation`, in degrees) and how close (`zoom`). See [[Camera]]. */
  def withCamera(
    azimuth: Optional[Double] = Optional.empty,
    elevation: Optional[Double] = Optional.empty,
    zoom: Optional[Double] = Optional.empty
  ): Scene = this + Camera(azimuth, elevation, zoom)

  /** The camera by plain numbers, for MATLAB and Java: `withCamera(-35, 25, 1)` is the default view. */
  def withCamera(azimuth: Double, elevation: Double, zoom: Double): Scene = this + Camera(azimuth, elevation, zoom)

  /** This scene with an x axis and a y axis, numbered with the coordinates of the plane: for a flat picture, seen from
    * above (`azimuth = 0`, `elevation = 90`). See [[Scene]].
    */
  def withAxes: Scene = copy(axes = true)

  /** This palette. */
  def withPalette(palette: Palette): Scene = this + palette

  /** This style, in place of the scene's. */
  def withStyle(style: Style): Scene = this + style

  /** The heading. */
  def withTitle(text: String): Scene = withLabels(title = text)

  /** The line under the heading. */
  def withSubtitle(text: String): Scene = withLabels(subtitle = text)

  /** The size in pixels. */
  def withSize(width: Int, height: Int): Scene = this + Size(width, height)

  /** The look of the title, the subtitle or the hint (`TextRole.Note`): the text a scene has. See [[Text]]. */
  def withText(
    role: TextRole,
    size: Optional[Double] = Optional.empty,
    weight: Optional[Weight] = Optional.empty,
    italic: Optional[Boolean] = Optional.empty,
    align: Optional[Anchor] = Optional.empty,
    font: Optional[String] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty
  ): Scene = this + Text(role, size, weight, italic, align, Optional.empty, font, color, paint)

  /** The markers of the points of a scene: `size`, `opacity` and the edge (`edgeWidth`, `edgeColor`, `edgeVisible`); a
    * scene's points are round and filled. See [[Geom.points]].
    */
  def withPoints(
    size: Optional[Double] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    edgeWidth: Optional[Double] = Optional.empty,
    edgeColor: Optional[String] = Optional.empty,
    edgeVisible: Optional[Boolean] = Optional.empty
  ): Scene = this + Geom.points(
    size = size,
    opacity = opacity,
    edgeWidth = edgeWidth,
    edgeColor = edgeColor,
    edgeVisible = edgeVisible
  )

  /** One change written as text, `element.property=value`: `withSetting("title.size=18")`. See [[Settings]]; a scene
    * has only the title, subtitle and note.
    */
  def withSetting(spec: String): Scene = this + Settings.parse(spec)

  /** The palette called `name`: `brand`, `reference`, `print`, `grayscale`, `okabe-ito` or `clear`. */
  def withPalette(name: String): Scene = withPalette(Palette.named(name))

  /** The style called `name`: `default`, `slides`, `print` or `clear`. */
  def withStyle(name: String): Scene = withStyle(Style.named(name))

  /** The color of homological degree `degree`, as a CSS color. */
  def withDegreeColor(degree: Int, color: String): Scene = withDegree(degree, color = color)

  /** A factor on every text size. */
  def withTextScale(factor: Double): Scene = withStyle(style.copy(textScale = factor))

  /** A factor on the size of markers and the width of lines. */
  def withMarkScale(factor: Double): Scene = withStyle(style.copy(markScale = factor))

  /** Without a background. */
  def withTransparentBackground(): Scene = withStyle(style.copy(transparent = true))

  /** With its palette's surface as the background in a saved `.svg` too, where it has none unless asked. */
  def withOpaqueBackground(): Scene = withStyle(style.copy(transparent = false))

  // One line, so that a REPL echoing the value of `scene.view()` does not print every point.
  override def toString: String = Plot.describe("Scene", title, s"${layers.size} layers")

  /** This scene's layers drawn over `under`'s. */
  def over(under: Scene): Scene = copy(layers = under.layers ++ layers)

  // where a place of the data lies in the picture: its x and y through the transforms of the axes, and the other way round
  // on an axis that runs backwards (a picture without axes has none); a transform with no position for a number (a log
  // axis and a number that is not positive) is an error that says what to do, not a place that is left out
  private def transformed: Boolean =
    axes && Seq(xAxis, yAxis).exists(a => a.transform != Transform.Identity || a.reverse)

  // the sign an axis gives the transformed numbers: -1 where it runs the other way
  private def sign(a: Axis): Double = if a.reverse then -1.0 else 1.0

  private def place(p: XYZ): XYZ =
    if !transformed then p
    else (sign(xAxis) * onAxis("x", xAxis.transform, p._1), sign(yAxis) * onAxis("y", yAxis.transform, p._2), p._3)

  private def onAxis(which: String, t: Transform, v: Double): Double =
    require(
      !t.isInstanceOf[Transform.Custom],
      s"Scene: the $which axis has a custom transform; a canvas's ticks are those of Transform.Identity, Log10, Sqrt and SymLog"
    )
    val u = t(v)
    require(
      !u.isNaN || v.isNaN,
      s"Scene: the $which axis is on a ${t.label} scale, which has no place for ${Svg.number(v)} " +
        "(a log axis needs positive numbers; Transform.SymLog keeps zero and negative ones)"
    )
    u

  // the centres of balls and halos count, their extent does not: a ball that grows leaves the picture where it was
  private def allPoints: Seq[XYZ] = layers
    .flatMap {
      case l: Layer.Points    => l.at
      case l: Layer.Segments  => l.ends.flatMap((a, b) => Seq(a, b))
      case l: Layer.Triangles => l.corners.flatMap((a, b, c) => Seq(a, b, c))
      case l: Layer.Balls     => l.centers
      case l: Layer.Halos     => l.at
    }
    .map(place)

  /** The centre and radius that fit `ps` into a unit ball. */
  private def fit(ps: Seq[XYZ]): (XYZ, Double) =
    if ps.isEmpty then ((0.0, 0.0, 0.0), 1.0)
    else
      def mid(f: XYZ => Double) = (ps.map(f).min + ps.map(f).max) / 2
      val c = (mid(_._1), mid(_._2), mid(_._3))
      val r = ps.map(p => math.sqrt(math.pow(p._1 - c._1, 2) + math.pow(p._2 - c._2, 2) + math.pow(p._3 - c._3, 2))).max
      (c, if r > 0 then r else 1.0)

  // the axes give the range the picture opens on (a `min`, a `max` or a `padding` on either): the frame is then that window
  private def limited: Boolean =
    axes && Seq(xAxis, yAxis).exists(a => a.min.isDefined || a.max.isDefined || a.padding.isDefined)

  // the centre and radius of the window the axes ask for, over the places `ps` (which give the ends an axis leaves free).
  // The picture keeps equal units on both axes, so the window is widened to the shape of the plot area: of the page's
  // (the canvas is 40 pixels shorter than the picture, the title is above it) and of the still's (which holds the title
  // inside its height, and so has the same area when there is one); a window fits in both
  private def window(ps: Seq[XYZ]): (XYZ, Double) =
    val (l, r_, t_, b) = axisRoom
    val boxW = width - l - r_
    val heights = Seq(height - 40.0 - t_ - b, height - headHeight - t_ - b)
    def range(a: Axis, which: String, coordinate: XYZ => Double): (Double, Double) =
      val s = sign(a)
      val seen = Option.when(ps.nonEmpty)((ps.map(p => s * coordinate(p)).min, ps.map(p => s * coordinate(p)).max))
      val (lo, hi) = seen.getOrElse((0.0, 1.0))
      val fixedLo = a.min.map(onAxis(which, a.transform, _))
      val fixedHi = a.max.map(onAxis(which, a.transform, _))
      val (u0, u1) = (fixedLo, fixedHi) match
        case (Some(f), Some(g)) => (f, g)
        case _                  =>
          val pad = a.padding.getOrElse(0.04)
          val (pl, pu) = if hi > lo then (lo - (hi - lo) * pad, hi + (hi - lo) * pad) else (lo - 1, hi + 1)
          (fixedLo.getOrElse(pl), fixedHi.getOrElse(pu))
      require(
        u1 > u0,
        s"Scene: the $which axis would run from ${Svg.number(a.transform.inverse(u0))} to ${Svg.number(a.transform.inverse(u1))}: " +
          s"its minimum must be less than its maximum (see Scale.$which)"
      )
      if s > 0 then (u0, u1) else (-u1, -u0)
    val ((x0, x1), (y0, y1)) = (range(xAxis, "x", _._1), range(yAxis, "y", _._2))
    val z = if ps.isEmpty then 0.0 else (ps.map(_._3).min + ps.map(_._3).max) / 2
    // the unit of the frame is 0.46 of the shorter side of the plot area, in pixels (`staticSvg`, the page's `sc`)
    val radius = heights.map { boxH =>
      val unit = 0.92 * math.min(boxW, boxH)
      math.max((x1 - x0) / 2 * unit / boxW, (y1 - y0) / 2 * unit / boxH)
    }.max
    (((x0 + x1) / 2, (y0 + y1) / 2, z), radius)

  // the room above the plot area of a still: the title, or a margin
  private def headHeight: Double =
    if title.nonEmpty then 16.0 + 24.0 * style.textScale * (elements.face(TextRole.Title).size / 15.0) else 12.0

  // the frame of the places `ps`: the window the axes ask for, or the ball that holds them
  private def framed(ps: Seq[XYZ]): (XYZ, Double) = if limited then window(ps) else fit(ps)

  /** The centre and radius that fit the scene into a unit ball: its own points, unless it is [[fitted]]; for a picture
    * whose axes give a range (`min`, `max`, `padding`), the window of it.
    */
  private[plot] def frame: (XYZ, Double) = fitted.getOrElse(framed(allPoints))

  /** This scene fitted to its points and `extra` ones: the places an item can be at when it moves. */
  private[plot] def fitting(extra: Seq[XYZ]): Scene = copy(fitted = Some(framed(allPoints ++ extra.map(place))))

  /** A scene's point as it is drawn: round and filled, with a ring of 1.5 pixels in the surface color, then whatever
    * the scene's marker changes (its size, opacity and edge).
    */
  private def sceneMarker(radius: Double, own: Paint): MarkerFace =
    MarkerStyle.resolve(
      Shape.Circle,
      radius,
      own,
      Seq(MarkerStyle(edge = LineStyle(width = Some(1.5))), elements.marker)
    )

  /** Where the view takes a place: the screen x, the screen y (up) and the depth (away from the viewer), in the radius
    * of the frame. The frame is worked out once, not for each place.
    */
  private def viewer: XYZ => (Double, Double, Double) =
    val (c, r) = frame
    val az = math.toRadians(azimuth)
    val el = math.toRadians(elevation)
    q =>
      val p = place(q)
      val (x, y, z) = ((p._1 - c._1) / r, (p._2 - c._2) / r, (p._3 - c._3) / r)
      val x1 = x * math.cos(az) - y * math.sin(az)
      val y1 = x * math.sin(az) + y * math.cos(az)
      val y2 = y1 * math.cos(el) - z * math.sin(el)
      val z2 = y1 * math.sin(el) + z * math.cos(el)
      (x1, z2, y2)

  /** The items, as (layer, item) pairs, whose shape meets the rectangle `w` of the view plane: a point whose centre is
    * in it, a segment that crosses or lies in it, a triangle that overlaps it. Marker sizes and line widths do not
    * count, so the answer does not depend on the size of the picture. Balls and halos are never chosen.
    */
  private[plot] def touching(w: Window): Seq[(Int, Int)] =
    val view = viewer
    def at(p: XYZ): (Double, Double) =
      val v = view(p); (v._1, v._2)
    val box = (w.x0, w.y0, w.x1, w.y1)
    layers.zipWithIndex.flatMap { (layer, li) =>
      layer match
        case l: Layer.Points =>
          l.at.indices.filter(i => Scene.inBox(at(l.at(i)), box)).map(li -> _)
        case l: Layer.Segments =>
          l.ends.indices.filter(i => Scene.cuts(at(l.ends(i)._1), at(l.ends(i)._2), box)).map(li -> _)
        case l: Layer.Triangles =>
          l.corners.indices
            .filter { i =>
              val (a, b, d) = l.corners(i)
              Scene.overlaps(at(a), at(b), at(d), box)
            }
            .map(li -> _)
        case _ => Seq.empty
    }

  // the axes of a flat picture: the room they take (left, right, top, bottom, in pixels at text scale 1), and the plot
  // area that is left, which the picture is fitted into
  private def axisRoom: (Double, Double, Double, Double) =
    require(
      azimuth == 0.0 && elevation == 90.0,
      s"Scene: axes number the plane of a picture seen from above, so they need azimuth = 0 and elevation = 90 (this scene is seen from azimuth $azimuth, elevation $elevation)"
    )
    val ts = style.textScale
    val label = elements.face(TextRole.AxisLabel).size * ts + 6.0
    // an axis that is not visible takes no room, and has no label
    (
      if yAxis.visible then 48.0 * ts + (if yAxis.label.nonEmpty then label else 0.0) else 8.0,
      8.0,
      8.0,
      if xAxis.visible then 26.0 * ts + (if xAxis.label.nonEmpty then label else 0.0) else 8.0
    )

  // the numbers an axis shows between the positions `u0` and `u1` of the picture (the ends of the plot area, in the places
  // of `frame`, either way round): round values of the range, or the breaks the axis is given that lie in it (with a little
  // room at the ends, so that a break at the end of a range is not lost to rounding)
  private def ticksIn(a: Axis, u0: Double, u1: Double): IndexedSeq[Double] =
    val s = sign(a)
    val t = a.transform
    val (lo, hi) = (math.min(s * u0, s * u1), math.max(s * u0, s * u1))
    a.ticks match
      case Ticks.At(breaks) =>
        val room = 1e-9 * (hi - lo)
        breaks.filter { v =>
          val u = t(v)
          u >= lo - room && u <= hi + room
        }
      case _ =>
        val (v0, v1) = (t.inverse(s * u0), t.inverse(s * u1))
        t.ticks(math.min(v0, v1), math.max(v0, v1))

  // how an axis writes a number: by its printf format, or the way of its transform
  private def tickText(a: Axis): Double => String =
    a.format.fold(a.transform.tickLabel)(pattern => v => RootText.format(pattern, Double.box(v)))

  // ----- the static view (SVG)

  def svgFile(theme: Theme = Theme.Light): String =
    """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + staticSvg(theme, saved = true)

  /** One view as an `<svg>`, primitives sorted back to front (orthographic projection). With `saved`, the view of a
    * file, which draws no background unless the style asks for one (see [[Style.transparent]]).
    */
  def staticSvg(theme: Theme = Theme.Light, saved: Boolean = false): String =
    val pal = palette.withDegrees(degrees)
    val (ts, ink) = (style.textScale, style.markScale)
    def paint(p: Paint) = if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)
    val (c, r) = frame
    val view = viewer
    val top = headHeight
    // the plot area: all of the picture, or what is left inside the axes
    val (boxL, boxR, boxT, boxB) =
      if axes then
        val (l, r_, t_, b) = axisRoom
        (l, width - r_, top + t_, height - b)
      else (0.0, width.toDouble, top, height.toDouble)
    val scale = 0.46 * math.min(boxR - boxL, boxB - boxT) * zoom
    val (cx, cy) = ((boxL + boxR) / 2.0, (boxT + boxB) / 2.0)
    def sx(v: (Double, Double, Double)) = f"${cx + v._1 * scale}%.2f"
    def sy(v: (Double, Double, Double)) = f"${cy - v._2 * scale}%.2f"
    val items = layers.flatMap {
      case l: Layer.Triangles =>
        l.corners.indices.map { i =>
          val (a, b, d) = l.corners(i)
          val (va, vb, vd) = (view(a), view(b), view(d))
          val depth = (va._3 + vb._3 + vd._3) / 3
          depth -> s"""<polygon points="${sx(va)},${sy(va)} ${sx(vb)},${sy(vb)} ${sx(vd)},${sy(
              vd
            )}" style="fill:${paint(l.paintAt(i))};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Segments =>
        l.ends.indices.map { i =>
          val (a, b) = l.ends(i)
          val (va, vb) = (view(a), view(b))
          (va._3 + vb._3) / 2 - 1e-6 -> s"""<line x1="${sx(va)}" y1="${sy(va)}" x2="${sx(vb)}" y2="${sy(
              vb
            )}" style="stroke:${paint(l.paintAt(i))}${
              if l.opacity == 1.0 then ""
              else s";stroke-opacity:${l.opacity}"
            }" stroke-width="${l.width * ink}" stroke-linecap="round"/>"""
        }
      case l: Layer.Balls =>
        l.centers.indices.map { i =>
          val v = view(l.centers(i))
          v._3 + 1e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(
              v
            )}" r="${f"${l.radius / r * scale}%.2f"}" style="fill:${paint(
              l.paintAt(i)
            )};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Halos =>
        l.at.indices.map { i =>
          val v = view(l.at(i))
          v._3 - 3e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(v)}" r="${l.radius * ink}" style="fill:${paint(
              l.paintAt(i)
            )};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Points =>
        l.at.indices.map { i =>
          val v = view(l.at(i))
          val face = sceneMarker(l.radius, l.paintAt(i))
          val attrs =
            MarkerStyle.attributes(face, paint(l.paintAt(i)), paint(face.edge.paint), _.toString.stripSuffix(".0"))
          v._3 - 2e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(v)}" r="${face.size * ink}" $attrs/>"""
        }
    }
    val id = s"tda4j3d${Scene.counter.incrementAndGet()}"
    // the axes of a flat picture: the items are clipped to the plot area, and the axes lie outside it
    val drawn =
      val picture = items.sortBy(-_._1).map(_._2).mkString
      if !axes then picture
      else
        val face = elements.face(TextRole.TickLabel)
        val size = face.size * ts
        val ink2 = paint(face.paint)
        val (tx, ty) = (xAxis.transform, yAxis.transform)
        def p2(v: Double) = f"$v%.2f"
        // the numbers of an axis: round values of what the plot area shows (or the breaks it is given, those in view), at
        // their positions in the picture; an axis that is not visible has none
        val xs =
          if !xAxis.visible then IndexedSeq.empty
          else ticksIn(xAxis, c._1 + r * (boxL - cx) / scale, c._1 + r * (boxR - cx) / scale)
        val ys =
          if !yAxis.visible then IndexedSeq.empty
          else ticksIn(yAxis, c._2 + r * (cy - boxB) / scale, c._2 + r * (cy - boxT) / scale)
        val (xText, yText) = (tickText(xAxis), tickText(yAxis))
        val (sx, sy) = (sign(xAxis), sign(yAxis))
        def line(x1: Double, y1: Double, x2: Double, y2: Double) =
          s"""<line x1="${p2(x1)}" y1="${p2(y1)}" x2="${p2(x2)}" y2="${p2(y2)}"/>"""
        def label(x: Double, y: Double, anchor: String, text: String) =
          s"""<text x="${p2(x)}" y="${p2(y)}" text-anchor="$anchor" font-size="${Svg.number(
              size
            )}" style="fill:$ink2;stroke:none">${Svg.escape(text)}</text>"""
        def xAt(v: Double) = cx + (sx * tx(v) - c._1) / r * scale
        def yAt(v: Double) = cy - (sy * ty(v) - c._2) / r * scale
        val xTicks =
          xs.map(v => line(xAt(v), boxB, xAt(v), boxB + 4) + label(xAt(v), boxB + 4 + size, "middle", xText(v)))
        val yTicks =
          ys.map(v => line(boxL - 4, yAt(v), boxL, yAt(v)) + label(boxL - 6, yAt(v) + 0.35 * size, "end", yText(v)))
        // gridlines at the numbers, under the picture, as a figure's
        val gs = elements.gridStroke
        val grid =
          if !(style.grid && gs.visible) then ""
          else
            val lines =
              (if xAxis.grid then xs.map(v => line(xAt(v), boxT, xAt(v), boxB)) else Seq.empty) ++
                (if yAxis.grid then ys.map(v => line(boxL, yAt(v), boxR, yAt(v))) else Seq.empty)
            if lines.isEmpty then ""
            else
              s"""<g class="tda4j-grid" style="stroke:${paint(gs.paint)}${
                  if gs.opacity == 1.0 then "" else s";stroke-opacity:${gs.opacity}"
                }" stroke-width="${Svg.number(gs.width)}"${gs.dash.pattern.fold("")(d =>
                  s""" stroke-dasharray="$d""""
                )}>${lines.mkString}</g>"""
        // the labels of the axes: under the numbers, and turned to read upwards beside them
        val words = elements.face(TextRole.AxisLabel)
        val wordSize = words.size * ts
        def word(text: String, attrs: String, x: Double, y: Double) =
          s"""<text class="tda4j-axis-label"$attrs x="${p2(x)}" y="${p2(y)}" text-anchor="middle" font-size="${Svg
              .number(
                wordSize
              )}" style="fill:${paint(words.paint)};stroke:none">${Svg.escape(text)}</text>"""
        val xWord =
          if xAxis.label.isEmpty || !xAxis.visible then "" else word(xAxis.label, "", (boxL + boxR) / 2, height - 6.0)
        val yWord =
          if yAxis.label.isEmpty || !yAxis.visible then ""
          else
            val (wx, wy) = (0.85 * wordSize + 2.0, (boxT + boxB) / 2)
            word(yAxis.label, s""" transform="rotate(-90 ${p2(wx)} ${p2(wy)})"""", wx, wy)
        val clip = s"$id-clip"
        grid + s"""<defs><clipPath id="$clip"><rect x="${p2(boxL)}" y="${p2(boxT)}" width="${p2(
            boxR - boxL
          )}" height="${p2(
            boxB - boxT
          )}"/></clipPath></defs><g clip-path="url(#$clip)">$picture</g>""" +
          s"""<g class="tda4j-axes" style="stroke:$ink2" stroke-width="1">${
              if xAxis.visible then line(boxL, boxB, boxR, boxB) else ""
            }${
              if yAxis.visible then line(boxL, boxT, boxL, boxB) else ""
            }${xTicks.mkString}${yTicks.mkString}$xWord$yWord</g>"""
    val body = drawn + region.fold("") { w =>
      val color = paint(Paint.Series(0))
      s"""<rect x="${f"${cx + w.x0 * scale}%.2f"}" y="${f"${cy - w.y1 * scale}%.2f"}" width="${f"${(w.x1 - w.x0) * scale}%.2f"}" height="${f"${(w.y1 - w.y0) * scale}%.2f"}" style="fill:$color;fill-opacity:.12;stroke:$color" stroke-width="${Svg
          .number(ink)}" stroke-dasharray="${Svg.number(4 * ink)} ${Svg.number(
          3 * ink
        )}"/>"""
    }
    val css = if theme == Theme.Auto then s"<style>${pal.cssVariables(s"#$id")}</style>" else ""
    def num(v: Double) = if v == v.toLong then v.toLong.toString else f"$v%.2f"
    // the title and the subtitle, as the style and the text roles have them
    val (titleFace, subtitleFace) = (elements.face(TextRole.Title), elements.face(TextRole.Subtitle))
    val (titlePx, subtitlePx) = (titleFace.size * ts, subtitleFace.size * ts)
    val titleBase = 9.0 + titlePx
    def at(f: TextFace): (Double, String) = f.align match
      case Anchor.Start  => (14.0, "")
      case Anchor.Middle => (width / 2.0, """ text-anchor="middle"""")
      case Anchor.End    => (width - 14.0, """ text-anchor="end"""")
    def extras(f: TextFace): String =
      f.weight.fold("")(wt => s""" font-weight="${wt.css}"""") + (if f.italic then """ font-style="italic"""" else "") +
        f.font.fold("")(ff => s""" font-family="${Svg.escape(ff)}"""")
    val heading =
      (if title.nonEmpty then
         val (tx, anchor) = at(titleFace)
         s"""<text x="${num(tx)}" y="${num(titleBase)}"$anchor font-size="${num(titlePx)}"${extras(
             titleFace
           )} style="fill:${paint(
             titleFace.paint
           )}">${Svg.escape(title)}</text>"""
       else "")
      +
        (if subtitle.nonEmpty then
           val (sx, anchor) = at(subtitleFace)
           val sy =
             if title.nonEmpty then
               titleBase + 16.0 * ts + 0.25 * (titlePx - 15.0 * ts) + 0.75 * (subtitlePx - 12.0 * ts)
             else titleBase + (subtitlePx - 12.0 * ts)
           s"""<text x="${num(sx)}" y="${num(sy)}"$anchor font-size="${num(subtitlePx)}"${extras(
               subtitleFace
             )} style="fill:${paint(
               subtitleFace.paint
             )}">${Svg.escape(subtitle)}</text>"""
         else "")
    val background =
      if !style.hasBackground(saved) then ""
      else s"""<rect width="$width" height="$height" style="fill:${paint(Paint.Surface)}"/>"""
    s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="$width" height="$height" viewBox="0 0 $width $height" font-family="${Svg
        .escape(style.font)}">$css$background$heading$body</svg>"""

  // ----- the interactive view (canvas)

  def fragment(theme: Theme = Theme.Auto): String = build(theme, None, None)

  /** This scene as one panel of an [[Interactive]] page: the items whose reaction is [[Behavior.Shown]] come and go as
    * the controls move, balls follow a [[Behavior.Radius]], items appear while a key is chosen ([[Behavior.Selected]])
    * and choose one when the pointer is on them ([[Behavior.Selects]]. The canvas listens for the page's `tda4j:change`
    * events.
    */
  private[plot] def fragmentReacting(
    theme: Theme,
    reactions: Map[Int, IndexedSeq[Reaction]],
    id: String,
    brush: Option[String] = None
  ): String =
    build(theme, Some(reactions), Some(id), brush)

  private def build(
    theme: Theme,
    reacting: Option[Map[Int, IndexedSeq[Reaction]]],
    named: Option[String],
    brush: Option[String] = None
  ): String =
    val pal = palette.withDegrees(degrees)
    val (ts, ink) = (style.textScale, style.markScale)
    def px(v: Double) = if v == v.toLong then v.toLong.toString else f"$v%.2f"
    // a text's size, weight, slant, font and alignment as CSS, then its color
    def css(role: TextRole, rest: String): String =
      val f = elements.face(role)
      val weight = f.weight.fold("")(wt => s"font-weight:${wt.css};")
      val slant = if f.italic then "font-style:italic;" else ""
      val family = f.font.fold("")(ff => s"font-family:${Svg.escape(ff)};")
      val align = f.align match
        case Anchor.Start  => ""
        case Anchor.Middle => "text-align:center;"
        case Anchor.End    => "text-align:right;"
      s"font-size:${px(f.size * ts)}px;$weight$slant$family$align$rest"
    val (c, r) = frame
    def col(p: Paint) = "\"" + (if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)) + "\""
    // The positions are written once, as a flat list of x, y, z (in the unit ball); a layer's items are numbers into it.
    // A cell that two layers share, or a point that many cells share, costs a number, not three.
    val places = scala.collection.mutable.LinkedHashMap.empty[XYZ, Int]
    def at(p: XYZ): Int = places.getOrElseUpdate(place(p), places.size)
    def short(v: Double): String =
      val t = f"$v%.5f".reverse.dropWhile(_ == '0').reverse
      if t.endsWith(".") then t.dropRight(1) else if t == "-0" then "0" else t
    // the frame is in the units of the data, whose scale is not the unit ball's: every digit (a range of 1e-4 has none to spare)
    def exact(v: Double): String = v.toString
    // the paints of a layer: a table of CSS colors and which entry each item takes (one number when all do)
    def paintsOf(l: Layer, n: Int): String =
      val table = (0 until n).map(i => col(l.paintAt(i))).distinct
      val which =
        if l.paints.size == 1 || table.size == 1 then "0"
        else (0 until n).map(i => table.indexOf(col(l.paintAt(i)))).mkString("[", ",", "]")
      s""""pal":[${(if table.isEmpty then Seq(col(l.paintAt(0))) else table).mkString(",")}],"c":$which"""
    // what the controls and selections ask of the items of a layer, for a panel of an interactive page
    def reacts(index: Int, layer: Layer): String =
      reacting.flatMap(_.get(index)).fold("") { rs =>
        val n = Interactive.itemCount(layer)
        val per = (0 until n).map(i => Interactive.reactionOf(rs, i).behaviors)
        val radius = per.headOption.flatMap(_.collectFirst { case Behavior.Radius(ctl, factor) => (ctl, factor) })
        val rest = per.map(_.filter {
          case _: Behavior.Radius | _: Behavior.Custom | _: Behavior.Selects | _: Behavior.Follows => false
          case _                                                                                   => true
        })
        val picks = per.map(_.collect { case Behavior.Selects(sel, key) => (sel, key) })
        def number(v: Double) =
          if v.isPosInfinity then "Infinity" else if v.isNegInfinity then "-Infinity" else v.toString
        val shownOnly = rest.forall(_ match
          case Seq(_: Behavior.Shown) => true
          case _                      => false)
        val controls = rest.flatMap(_.collect { case Behavior.Shown(ctl, _, _) => ctl }).distinct
        val shown =
          if n > 0 && shownOnly && controls.size == 1 then
            val sh = rest.map { case Seq(Behavior.Shown(_, from, until)) => (from, until); case _ => (0.0, 0.0) }
            val untils =
              if sh.forall(_._2.isPosInfinity) then "null" else sh.map(x => number(x._2)).mkString("[", ",", "]")
            s""","sh":{"c":"${controls.head}","f":${sh.map(x => number(x._1)).mkString("[", ",", "]")},"u":$untils}"""
          else if rest.forall(_.isEmpty) then ""
          else
            val entries = rest.map { bs =>
              if bs.isEmpty then "null"
              else
                bs.map {
                  case Behavior.Shown(ctl, from, until) => s"""["v","$ctl",${number(from)},${number(until)}]"""
                  case Behavior.Selected(sel, key)      => s"""["w","$sel",$key]"""
                  case _                                => "null"
                }.mkString("[", ",", "]")
            }
            s""","ia":${entries.mkString("[", ",", "]")}"""
        val ball = radius.fold("")((ctl, factor) => s""","ra":["$ctl",${short(factor / r)}]""")
        // the items that move with a control: which coordinate (0, 1, 2), of which ends (1 first, 2 second, 3 all), between
        val follows = per.map(_.collect { case f: Behavior.Follows => f })
        val moves =
          if follows.forall(_.isEmpty) then ""
          else
            val entries = follows.map { fs =>
              if fs.isEmpty then "null"
              else
                fs.map { f =>
                  val axis = f.coordinate match
                    case Coordinate.X => 0
                    case Coordinate.Y => 1
                    case Coordinate.Z => 2
                  val ends = f.ends match
                    case Ends.First  => 1
                    case Ends.Second => 2
                    case Ends.Both   => 3
                  s"""["${f.control}",$axis,$ends,${number(f.lo)},${number(f.hi)}]"""
                }.mkString("[", ",", "]")
            }
            s""","fo":${entries.mkString("[", ",", "]")}"""
        val chooses =
          if picks.forall(_.isEmpty) then ""
          else
            val entries = picks.map(ps =>
              if ps.isEmpty then "null" else ps.map((sel, key) => s"""["$sel",$key]""").mkString("[", ",", "]")
            )
            s""","pk":${entries.mkString("[", ",", "]")}"""
        shown + ball + chooses + moves
      }
    val layerJson = layers.zipWithIndex.map { (layer, li) =>
      val n = Interactive.itemCount(layer)
      val body = layer match
        case l: Layer.Points =>
          val face = sceneMarker(l.radius, l.paints.headOption.getOrElse(Paint.Ink))
          val edge = if face.edge.visible then face.edge.width else 0.0
          s""""k":"p","r":${short(face.size * ink)},"op":${face.opacity},"ew":$edge,"ec":${col(
              face.edge.paint
            )},"i":${l.at.map(at).mkString("[", ",", "]")}"""
        case l: Layer.Segments =>
          s""""k":"s","w":${short(l.width * ink)},"op":${l.opacity},"i":${l.ends
              .flatMap((a, b) => Seq(at(a), at(b)))
              .mkString("[", ",", "]")}"""
        case l: Layer.Triangles =>
          s""""k":"t","op":${l.opacity},"i":${l.corners
              .flatMap((a, b, d) => Seq(at(a), at(b), at(d)))
              .mkString("[", ",", "]")}"""
        case l: Layer.Balls =>
          s""""k":"b","rad":${short(l.radius / r)},"op":${l.opacity},"i":${l.centers.map(at).mkString("[", ",", "]")}"""
        case l: Layer.Halos =>
          s""""k":"h","r":${short(l.radius * ink)},"op":${l.opacity},"i":${l.at.map(at).mkString("[", ",", "]")}"""
      s"""{$body,${paintsOf(layer, n)}${reacts(li, layer)}}"""
    }
    // `keys` is a Set: flatMap over it would collapse equal coordinates, so go through a Vector
    val positions = places.keys.toVector.flatMap(p =>
      Seq(short((p._1 - c._1) / r), short((p._2 - c._2) / r), short((p._3 - c._3) / r))
    )
    val json = layerJson.mkString("[", ",", "]")
    // choosing by rectangle: the box button, what a drag draws, and what it touches
    val brushing = brush.isDefined
    val clearRect = if brushing then "rect=null;kb=null;" else ""
    val dropRect = if brushing then "if(rect||kb){rect=null;kb=null;draw();}" else ""
    val paintBox = if brushing then "if(rect)paintRect();if(kb)paintCross();" else ""
    val dragBox =
      if brushing then
        "if(boxMode){const a=loc(drag[0],drag[1]),b=loc(e.clientX,e.clientY);rect=[a[0],a[1],b[0],b[1]];draw();return;}"
      else ""
    val liftBox = if brushing then "if(was&&boxMode&&moved>=4&&rect){chooseBox(e.shiftKey);return;}" else ""
    val boxScript = brush.fold("") { selection =>
      s"""
         |// a brushable panel: the box button makes a drag draw a rectangle (pixels of the canvas, kept until the next click or
         |// until the view moves) that chooses the keys of the items it touches, those of the items drawn now
         |const BR=${"\"" + Svg.escape(selection) + "\""},S0=color(${col(Paint.Series(0))}),SF=color(${col(
          Paint.Surface
        )});
         |// a corner of the rectangle is held inside the plot area (the canvas when there are no axes), as a flat panel's is
         |const loc=(cx,cy)=>{const r=cv.getBoundingClientRect(),k=cv.clientWidth/r.width;
         |  return[Math.min(Math.max((cx-r.left)*k,box[0]),box[2]),Math.min(Math.max((cy-r.top)*k,box[1]),box[3])];};
         |function paintRect(){g.save();g.beginPath();g.rect(box[0],box[1],box[2]-box[0],box[3]-box[1]);g.clip();g.beginPath();g.rect(Math.min(rect[0],rect[2]),Math.min(rect[1],rect[3]),Math.abs(rect[2]-rect[0]),Math.abs(rect[3]-rect[1]));
         |  g.globalAlpha=0.12;g.fillStyle=S0;g.fill();g.globalAlpha=1;g.strokeStyle=S0;g.lineWidth=1;g.setLineDash([4,3]);g.stroke();g.restore();}
         |const inBox=(x,y,x0,y0,x1,y1)=>x>=x0&&x<=x1&&y>=y0&&y<=y1;
         |// does the segment from a to b meet the box (Liang-Barsky)
         |function cuts(ax,ay,bx,by,x0,y0,x1,y1){let t0=0,t1=1;const dx=bx-ax,dy=by-ay;
         |  for(const[p,q]of[[-dx,ax-x0],[dx,x1-ax],[-dy,ay-y0],[dy,y1-ay]]){
         |    if(p===0){if(q<0)return false;continue;}
         |    const r=q/p;if(p<0){if(r>t1)return false;if(r>t0)t0=r;}else{if(r<t0)return false;if(r<t1)t1=r;}}
         |  return true;}
         |// a point whose centre is in the box, a segment that meets it, a triangle that overlaps it; sizes and widths do not count
         |function touches(L,k,x0,y0,x1,y1){
         |  if(L.k==='p')return inBox(X(L.i[k]),Y(L.i[k]),x0,y0,x1,y1);
         |  if(L.k==='s')return cuts(X(L.i[2*k]),Y(L.i[2*k]),X(L.i[2*k+1]),Y(L.i[2*k+1]),x0,y0,x1,y1);
         |  if(L.k!=='t')return false;
         |  const a=L.i[3*k],b=L.i[3*k+1],c=L.i[3*k+2],ax=X(a),ay=Y(a),bx=X(b),by=Y(b),cx=X(c),cy=Y(c);
         |  return cuts(ax,ay,bx,by,x0,y0,x1,y1)||cuts(bx,by,cx,cy,x0,y0,x1,y1)||cuts(cx,cy,ax,ay,x0,y0,x1,y1)||
         |    [[x0,y0],[x1,y0],[x1,y1],[x0,y1]].some(q=>inTri(q[0],q[1],ax,ay,bx,by,cx,cy));}
         |function chooseBox(shift){const x0=Math.min(rect[0],rect[2]),x1=Math.max(rect[0],rect[2]),y0=Math.min(rect[1],rect[3]),y1=Math.max(rect[1],rect[3]),ks=new Set();
         |  for(const it of last){const L=layers[it[1]],pk=L.pk&&L.pk[it[2]];
         |    if(pk&&touches(L,it[2],x0,y0,x1,y1))pk.forEach(q=>{if(q[0]===BR)ks.add(q[1]);});}
         |  if(link())link().choose(BR,[...ks],shift);}
         |const boxBtn=root.querySelector('.tda4j-box');
         |boxBtn.addEventListener('click',()=>{boxMode=!boxMode;boxBtn.setAttribute('aria-pressed',String(boxMode));
         |  boxBtn.style.background=boxMode?S0:'transparent';boxBtn.style.color=boxMode?SF:'inherit';cv.style.cursor=idle();rect=null;draw();});
         |// for the page's `brush(panel, window)`: the rectangle in the units of the view plane (x to the right, y up, the radius of the frame)
         |root._region={show:w=>{if(!w){rect=null;draw();return;}rect=[ox+w[0]*sc,oy-w[3]*sc,ox+w[1]*sc,oy-w[2]*sc];draw();},
         |  window:()=>rect?[(Math.min(rect[0],rect[2])-ox)/sc,(Math.max(rect[0],rect[2])-ox)/sc,(oy-Math.max(rect[1],rect[3]))/sc,(oy-Math.min(rect[1],rect[3]))/sc]:null};
         |// the keyboard (a focused canvas): arrows move a cross over the plot area (shift: further), ] and [ put it on the next and the
         |// previous item drawn (left to right, top to bottom; Page Down and Page Up too), Home and End on the first and the last, Enter
         |// sets the first corner and then the second, which chooses (with shift: adds), Escape lets go
         |let kb=null;
         |function paintCross(){g.save();g.strokeStyle=S0;g.lineWidth=1.5;g.beginPath();g.moveTo(kb.x-8,kb.y);g.lineTo(kb.x+8,kb.y);g.moveTo(kb.x,kb.y-8);g.lineTo(kb.x,kb.y+8);g.stroke();g.restore();}
         |const lightUp=pk=>{if(frame){cancelAnimationFrame(frame);frame=0;}if(link())choosers.forEach(n=>link().hover(n,keysFor(pk,n)));};
         |// a change of the light or of the choice redraws the canvas by itself (the page tells it); draw only when nothing did
         |const afterwards=f=>{const d=drawn;f();if(drawn===d)draw();};
         |// the middles of the items the rectangle can choose that the plot area shows, in the order the cross steps through them
         |function placesOf(){const out=[];
         |  for(const it of last){const L=layers[it[1]],k=it[2],pk=L.pk&&L.pk[k];if(!pk||!pk.some(q=>q[0]===BR))continue;let x,y;
         |    if(L.k==='p'){x=X(L.i[k]);y=Y(L.i[k]);}
         |    else if(L.k==='s'){x=(X(L.i[2*k])+X(L.i[2*k+1]))/2;y=(Y(L.i[2*k])+Y(L.i[2*k+1]))/2;}
         |    else if(L.k==='t'){const a=L.i[3*k],b=L.i[3*k+1],c=L.i[3*k+2];x=(X(a)+X(b)+X(c))/3;y=(Y(a)+Y(b)+Y(c))/3;}
         |    else continue;
         |    if(inBox(x,y,box[0],box[1],box[2],box[3]))out.push([x,y,pk]);}
         |  return out.sort((u,v)=>u[0]-v[0]||u[1]-v[1]);}
         |function stepTo(dir){const ps=placesOf();if(!ps.length)return;let q;
         |  if(dir==='first'||(dir==='next'&&!kb))q=ps[0];
         |  else if(dir==='last'||(dir==='prev'&&!kb))q=ps[ps.length-1];
         |  else if(dir==='next')q=ps.find(u=>u[0]>kb.x+1e-6||(Math.abs(u[0]-kb.x)<=1e-6&&u[1]>kb.y+1e-6));
         |  else q=ps.slice().reverse().find(u=>u[0]<kb.x-1e-6||(Math.abs(u[0]-kb.x)<=1e-6&&u[1]<kb.y-1e-6));
         |  if(!q)return;
         |  kb=kb||{x:q[0],y:q[1],from:null};kb.x=q[0];kb.y=q[1];if(kb.from)rect=[kb.from.x,kb.from.y,kb.x,kb.y];
         |  afterwards(()=>lightUp(ps.filter(u=>Math.abs(u[0]-q[0])<=1e-6&&Math.abs(u[1]-q[1])<=1e-6).flatMap(u=>u[2])));}
         |function letGo(){if(!kb)return;kb=null;afterwards(()=>lightUp(null));}
         |cv.addEventListener('blur',letGo);
         |cv.addEventListener('keydown',e=>{if(e.ctrlKey||e.metaKey||e.altKey)return;
         |  const d={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[e.key];
         |  if(d){e.preventDefault();kb=kb||{x:(box[0]+box[2])/2,y:(box[1]+box[3])/2,from:null};const st=e.shiftKey?0.16:0.04;
         |    kb.x=Math.min(Math.max(kb.x+d[0]*st*(box[2]-box[0]),box[0]),box[2]);kb.y=Math.min(Math.max(kb.y+d[1]*st*(box[3]-box[1]),box[1]),box[3]);
         |    if(kb.from)rect=[kb.from.x,kb.from.y,kb.x,kb.y];draw();return;}
         |  const dir={']':'next','}':'next',PageDown:'next','[':'prev','{':'prev',PageUp:'prev',Home:'first',End:'last'}[e.key];
         |  if(dir){e.preventDefault();stepTo(dir);return;}
         |  if((e.key==='Enter'||e.key===' ')&&kb){e.preventDefault();
         |    if(!kb.from){kb.from={x:kb.x,y:kb.y};rect=[kb.x,kb.y,kb.x,kb.y];draw();return;}
         |    rect=[kb.from.x,kb.from.y,kb.x,kb.y];kb=null;afterwards(()=>{chooseBox(e.shiftKey);lightUp(null);});return;}
         |  if(e.key==='Escape'&&kb){e.preventDefault();kb=null;rect=null;afterwards(()=>lightUp(null));}});"""
    }
    // the script of a panel of an interactive page also asks which items the controls and selections leave out
    val hook =
      if reacting.isEmpty then ""
      else
        s"""
           |const ia=root.closest('.tda4j-ia'),vals={};
           |function hid(L,k){
           |  if(L.sh){const v=vals[L.sh.c];if(v!==undefined&&!(v>=L.sh.f[k]&&v<(L.sh.u?L.sh.u[k]:Infinity)))return true;}
           |  const a=L.ia&&L.ia[k];
           |  if(a)for(const e of a){const v=vals[e[1]];
           |    if(e[0]==='v'){if(v!==undefined&&!(v>=e[2]&&v<e[3]))return true;}
           |    else if(!v||v.indexOf(e[2])<0)return true;}
           |  return false;}
           |// an item that follows a control has a position of its own (a slot after the shared ones) for each end that moves
           |const FR=[${exact(c._1)},${exact(c._2)},${exact(c._3)},${exact(r)}],movers=[],follow=new Set();
           |layers.forEach(L=>{if(!L.fo)return;const m=L.k==='s'?2:L.k==='t'?3:1;
           |  L.fo.forEach((fs,k)=>{if(!fs)return;
           |    for(let j=0;j<m;j++){const sp=fs.filter(s=>s[2]===3||s[2]===j+1);if(!sp.length)continue;
           |      const at=L.i[m*k+j],slot=P.length/3;P.push(P[3*at],P[3*at+1],P[3*at+2]);L.i[m*k+j]=slot;
           |      movers.push({s:slot,b:[P[3*at],P[3*at+1],P[3*at+2]],sp:sp});sp.forEach(s=>follow.add(s[0]));}});});
           |// where a value of a coordinate (data units) lies in the picture: through the transform and the direction of its axis
           |const placed=${
            if transformed then "(k,v)=>k===0?AX[3]*tr[TX[0]](v,TX[1]):k===1?AX[4]*tr[TY[0]](v,TY[1]):v"
            else "(k,v)=>v"
          };
           |// the unit-ball position of a coordinate that is the control's value held between lo and hi (data units)
           |function moveAll(){for(const q of movers){const p=q.b.slice();
           |  for(const s of q.sp){const v=vals[s[0]];if(v!==undefined)p[s[1]]=(placed(s[1],Math.min(Math.max(v,s[3]),s[4]))-FR[s[1]])/FR[3];}
           |  P[3*q.s]=p[0];P[3*q.s+1]=p[1];P[3*q.s+2]=p[2];}}
           |if(ia)ia.addEventListener('tda4j:change',e=>{vals[e.detail.name]=e.detail.value;if(follow.has(e.detail.name)){moveAll();order=null;}draw();});""".stripMargin
    // the axes of a flat picture: the plot area they leave, the clip to it, and the ticks, drawn from the same rules as the still's
    val tickFace = elements.face(TextRole.TickLabel)
    val tickSize = tickFace.size * ts
    val (axL, axR, axT, axB) = if axes then axisRoom else (0.0, 0.0, 0.0, 0.0)
    val geometry =
      if axes then
        s"const bx0=${px(axL)},bx1=W-${px(axR)},by0=${px(axT)},by1=H-${px(axB)};sc=0.46*Math.min(bx1-bx0,by1-by0)*zoom;box=[bx0,by0,bx1,by1];bc=[(bx0+bx1)/2,(by0+by1)/2];ox=bc[0]+pan[0];oy=bc[1]+pan[1];"
      else "sc=0.46*Math.min(W,H)*zoom;box=[0,0,W,H];bc=[W/2,H/2];ox=W/2+pan[0];oy=H/2+pan[1];"
    val gridStroke = elements.gridStroke
    // a gridline belongs to a numbered axis: one that is not visible has none
    val (gridX, gridY) = (xAxis.grid && xAxis.visible, yAxis.grid && yAxis.visible)
    val gridOn = axes && style.grid && gridStroke.visible && (gridX || gridY)
    val openClip =
      if axes then
        s"g.save();g.beginPath();g.rect(bx0,by0,bx1-bx0,by1-by0);g.clip();${if gridOn then "paintGrid();" else ""}"
      else ""
    val closeClip = if axes then "g.restore();paintAxes();" else ""
    val fontJs = "\"" + s"${px(tickSize)}px ${style.font}".replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val wordFace = elements.face(TextRole.AxisLabel)
    val wordSize = wordFace.size * ts
    val wordFont = "\"" + s"${px(wordSize)}px ${style.font}".replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    // the transform of an axis as the page names it: a letter and, for a symmetric log, the linear stretch
    def code(a: Axis): String = a.transform match
      case Transform.Identity  => "['i',1]"
      case Transform.Log10     => "['l',1]"
      case Transform.Sqrt      => "['s',1]"
      case Transform.SymLog(k) => s"['m',${short(k)}]"
      case _: Transform.Custom => "['i',1]" // refused by `place` before a page is written
    // the breaks and the number format of an axis as the page has them: a list and a pattern, or null
    def breaksJs(a: Axis): String = a.ticks match
      case Ticks.At(breaks) => breaks.map(_.toString).mkString("[", ",", "]")
      case _                => "null"
    def formatJs(a: Axis): String = a.format.fold("null")(Scene.jsText)
    val axesScript =
      if !axes then ""
      else
        s"""
           |// the axes: ticks at round values of the plane's coordinates, after the transforms of the axes, over what the plot area shows now
           |${Scene.tickScript}
           |${if xAxis.format.isDefined || yAxis.format.isDefined then Scene.formatScript else ""}
           |const AX=[${exact(c._1)},${exact(c._2)},${exact(r)},${short(sign(xAxis))},${short(
            sign(yAxis)
          )}],AXC=color(${col(
            tickFace.paint
          )}),TX=${code(
            xAxis
          )},TY=${code(
            yAxis
          )},BX=${breaksJs(xAxis)},BY=${breaksJs(yAxis)},FX=${formatJs(xAxis)},FY=${formatJs(yAxis)},XL=${Scene.jsText(
            if xAxis.visible then xAxis.label else ""
          )},YL=${Scene.jsText(if yAxis.visible then yAxis.label else "")};
           |const xAt=v=>ox+(AX[3]*tr[TX[0]](v,TX[1])-AX[0])/AX[2]*sc,yAt=v=>oy-(AX[4]*tr[TY[0]](v,TY[1])-AX[1])/AX[2]*sc;
           |// the numbers an axis shows between the places pl and pr of the picture (the ends of the plot area): round values, or the breaks in view
           |const tickOf=(T,s,B,pl,pr)=>{const a=s*pl,b=s*pr,u0=Math.min(a,b),u1=Math.max(a,b);
           |  if(B){const e=1e-9*(u1-u0);return B.filter(v=>{const u=tr[T[0]](v,T[1]);return u>=u0-e&&u<=u1+e;});}
           |  const v0=inv[T[0]](a,T[1]),v1=inv[T[0]](b,T[1]);return trTicks(T[0],T[1],Math.min(v0,v1),Math.max(v0,v1),5);};
           |const wordOf=(T,F,v)=>F!==null?javaFormat(F,v):T[0]==='l'?logLabel(v):numberText(v);
           |const xTicks=()=>tickOf(TX,AX[3],BX,AX[0]+AX[2]*(box[0]-ox)/sc,AX[0]+AX[2]*(box[2]-ox)/sc),yTicks=()=>tickOf(TY,AX[4],BY,AX[1]+AX[2]*(oy-box[3])/sc,AX[1]+AX[2]*(oy-box[1])/sc);
           |function paintGrid(){
           |  g.save();g.font=$fontJs;g.strokeStyle=color(${col(gridStroke.paint)});g.lineWidth=${px(
            gridStroke.width
          )};g.globalAlpha=${gridStroke.opacity};g.setLineDash([${gridStroke.dash.pattern
            .fold("")(_.split(' ').mkString(","))}]);
           |  const line=(x1,y1,x2,y2)=>{g.beginPath();g.moveTo(x1,y1);g.lineTo(x2,y2);g.stroke();};
           |  ${if gridX then "for(const v of xTicks()){const x=xAt(v);line(x,box[1],x,box[3]);}" else ""}
           |  ${if gridY then "for(const v of yTicks()){const y=yAt(v);line(box[0],y,box[2],y);}" else ""}
           |  g.restore();}
           |function paintAxes(){
           |  g.save();g.strokeStyle=AXC;g.fillStyle=AXC;g.lineWidth=1;g.globalAlpha=1;g.font=$fontJs;
           |  const line=(x1,y1,x2,y2)=>{g.beginPath();g.moveTo(x1,y1);g.lineTo(x2,y2);g.stroke();};
           |  ${if xAxis.visible then "line(box[0],box[3],box[2],box[3]);" else ""}${
            if yAxis.visible then "line(box[0],box[1],box[0],box[3]);"
            else ""
          }
           |  g.textAlign='center';
           |  ${
            if xAxis.visible then
              s"for(const v of xTicks()){const x=xAt(v);line(x,box[3],x,box[3]+4);g.fillText(wordOf(TX,FX,v),x,box[3]+4+${px(tickSize)});}"
            else ""
          }
           |  g.textAlign='right';
           |  ${
            if yAxis.visible then
              s"for(const v of yTicks()){const y=yAt(v);line(box[0]-4,y,box[0],y);g.fillText(wordOf(TY,FY,v),box[0]-6,y+0.35*${px(tickSize)});}"
            else ""
          }
           |  g.fillStyle=color(${col(wordFace.paint)});g.font=$wordFont;g.textAlign='center';
           |  if(XL)g.fillText(XL,(box[0]+box[2])/2,box[3]+${px(axB - 6.0)});
           |  if(YL){g.translate(${px(
            0.85 * wordSize + 2.0
          )},(box[1]+box[3])/2);g.rotate(-Math.PI/2);g.fillText(YL,0,0);}
           |  g.restore();}""".stripMargin
    val id = named.getOrElse(s"tda4j3d${Scene.counter.incrementAndGet()}")
    val vars = if theme == Theme.Auto then pal.cssVariables(s"#$id") else ""
    s"""<div id="$id" style="position:relative;width:${width}px;max-width:100%;font-family:${Svg.escape(style.font)}">
       |<style>$vars #$id canvas{display:block;width:100%;touch-action:none;cursor:grab;border-radius:4px}</style>
       |${
        if title.nonEmpty then
          s"""<div style="${css(
              TextRole.Title,
              s"margin:4px 0 2px;color:${col(elements.face(TextRole.Title).paint).replace("\"", "")}"
            )}">${Svg.escape(title)}</div>"""
        else ""
      }
       |${
        if subtitle.nonEmpty then
          s"""<div style="${css(
              TextRole.Subtitle,
              s"margin:0 0 6px;color:${col(elements.face(TextRole.Subtitle).paint).replace("\"", "")}"
            )}">${Svg.escape(subtitle)}</div>"""
        else ""
      }
       |<canvas width="$width" height="${height - 40}"${if brushing then " tabindex=\"0\"" else ""}></canvas>
       |<div style="${css(
        TextRole.Note,
        s"color:${col(elements.face(TextRole.Note).paint).replace("\"", "")};margin-top:4px"
      )}">${if turns then "drag to turn, scroll to zoom" else "drag to pan, scroll to zoom"}${brush.fold("")(_ =>
        """ <button type="button" class="tda4j-box" aria-pressed="false" title="drag a rectangle to choose what it touches (a drag no longer turns the picture while this is on)" style="font:inherit;margin-left:6px;padding:1px 8px;border:1px solid currentColor;border-radius:4px;background:transparent;color:inherit;cursor:pointer">box</button>"""
      )}</div>
       |<script>(function(){
       |const root=document.getElementById('$id'),cv=root.querySelector('canvas'),g=cv.getContext('2d');
       |const P=${positions.mkString("[", ",", "]")},layers=$json;$hook
       |const css=getComputedStyle(root),np=P.length/3,Q=new Float64Array(3*np),resolved=new Map();
       |const color=s=>{if(!s.startsWith('var('))return s;let c=resolved.get(s);
       |  if(c===undefined){c=css.getPropertyValue(s.slice(4,-1)).trim();resolved.set(s,c);}return c;};
       |let az=${math.toRadians(azimuth)},el=${math.toRadians(elevation)},zoom=${Svg.number(
        zoom
      )},boxMode=false,rect=null;
       |let pan=[0,0];const turns=$turns,ends=L=>L.k==='s'?2:L.k==='t'?3:1,count=L=>L.i.length/ends(L),tint=(L,k)=>L.pal[typeof L.c==='number'?L.c:L.c[k]];
       |function project(){const ca=Math.cos(az),sa=Math.sin(az),ce=Math.cos(el),se=Math.sin(el);
       |  for(let i=0;i<np;i++){const x=P[3*i],y=P[3*i+1],z=P[3*i+2],x1=x*ca-y*sa,y1=x*sa+y*ca;
       |    Q[3*i]=x1;Q[3*i+1]=y1*se+z*ce;Q[3*i+2]=y1*ce-z*se;}}
       |let last=[],sc=1,ox=0,oy=0,bc=[0,0],box=[0,0,0,0];
       |const X=i=>ox+Q[3*i]*sc,Y=i=>oy-Q[3*i+1]*sc;
       |// the painting order depends on the view alone, not on the sliders or the zoom: it is sorted when the view turns
       |const off=[];let total=0;layers.forEach(L=>{off.push(total);total+=count(L);});
       |const LI=new Uint16Array(total),KK=new Int32Array(total),dep=new Float64Array(total);let order=null;
       |layers.forEach((L,li)=>{const n=count(L);for(let k=0;k<n;k++){LI[off[li]+k]=li;KK[off[li]+k]=k;}});
       |function sortAll(){project();
       |  layers.forEach((L,li)=>{const n=count(L),o=off[li];
       |    for(let k=0;k<n;k++){let d;
       |      if(L.k==='t')d=(Q[3*L.i[3*k]+2]+Q[3*L.i[3*k+1]+2]+Q[3*L.i[3*k+2]+2])/3;
       |      else if(L.k==='s')d=(Q[3*L.i[2*k]+2]+Q[3*L.i[2*k+1]+2])/2-1e-6;
       |      else d=Q[3*L.i[k]+2]+(L.k==='p'?-2e-6:L.k==='b'?1e-6:-3e-6);
       |      dep[o+k]=d;}});
       |  order=new Int32Array(total);for(let i=0;i<total;i++)order[i]=i;order.sort((u,v)=>dep[v]-dep[u]);}
       |function draw(){drawn++;const dpr=window.devicePixelRatio||1,W=cv.clientWidth,H=W*cv.height/cv.width;
       |  if(cv.width!==Math.round(W*dpr)){cv.width=Math.round(W*dpr);cv.height=Math.round(H*dpr);}
       |  g.setTransform(dpr,0,0,dpr,0,0);g.fillStyle=color(${col(Paint.Surface)});g.fillRect(0,0,W,H);
       |  if(!order)sortAll();$geometry last=[];$openClip
       |  for(let q=0;q<total;q++){const gi=order[q],li=LI[gi],k=KK[gi],L=layers[li];
       |    ${
        if reacting.isEmpty then "" else "if(hid(L,k))continue;"
      }
       |    if(L.pk&&L.pk[k])last.push([dep[gi],li,k]);
       |    const c=color(tint(L,k));
       |    if(L.k==='t'){g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.moveTo(X(L.i[3*k]),Y(L.i[3*k]));g.lineTo(X(L.i[3*k+1]),Y(L.i[3*k+1]));g.lineTo(X(L.i[3*k+2]),Y(L.i[3*k+2]));g.closePath();g.fill();g.globalAlpha=1;}
       |    else if(L.k==='s'){g.globalAlpha=L.op;g.strokeStyle=c;g.lineWidth=L.w;g.lineCap='round';g.beginPath();g.moveTo(X(L.i[2*k]),Y(L.i[2*k]));g.lineTo(X(L.i[2*k+1]),Y(L.i[2*k+1]));g.stroke();g.globalAlpha=1;}
       |    else if(L.k==='p'){g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.arc(X(L.i[k]),Y(L.i[k]),L.r,0,2*Math.PI);g.fill();if(L.ew>0){g.strokeStyle=color(L.ec);g.lineWidth=L.ew;g.stroke();}g.globalAlpha=1;}
       |    else{const rad=L.k==='b'?radiusOf(L)*sc:L.r;g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.arc(X(L.i[k]),Y(L.i[k]),rad,0,2*Math.PI);g.fill();g.globalAlpha=1;}}$closeClip$paintBox}
       |function radiusOf(L){if(L.ra){const v=vals[L.ra[0]];if(v!==undefined)return v*L.ra[1];}return L.rad;}
       |// what the pointer is over: the nearest drawn item of a layer that chooses, within 4 pixels of its shape
       |const segDist=(px,py,ax,ay,bx,by)=>{const dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy,t=l?Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/l)):0;return Math.hypot(px-ax-t*dx,py-ay-t*dy);};
       |const inTri=(x,y,ax,ay,bx,by,cx,cy)=>{const s1=(x-bx)*(ay-by)-(ax-bx)*(y-by),s2=(x-cx)*(by-cy)-(bx-cx)*(y-cy),s3=(x-ax)*(cy-ay)-(cx-ax)*(y-ay);
       |  return !((s1<0||s2<0||s3<0)&&(s1>0||s2>0||s3>0));};
       |function reach(L,k,x,y){
       |  if(L.k==='p')return Math.max(0,Math.hypot(x-X(L.i[k]),y-Y(L.i[k]))-L.r);
       |  if(L.k==='s')return Math.max(0,segDist(x,y,X(L.i[2*k]),Y(L.i[2*k]),X(L.i[2*k+1]),Y(L.i[2*k+1]))-L.w/2);
       |  if(L.k!=='t')return Infinity;
       |  const a=L.i[3*k],b=L.i[3*k+1],c=L.i[3*k+2],ax=X(a),ay=Y(a),bx=X(b),by=Y(b),cx=X(c),cy=Y(c);
       |  if(inTri(x,y,ax,ay,bx,by,cx,cy))return 0;
       |  return Math.min(segDist(x,y,ax,ay,bx,by),segDist(x,y,bx,by,cx,cy),segDist(x,y,cx,cy,ax,ay));}
       |const choosers=new Set();layers.forEach(L=>{if(L.pk)L.pk.forEach(p=>{if(p)p.forEach(q=>choosers.add(q[0]));});});
       |function pickAt(clientX,clientY){const r=cv.getBoundingClientRect(),x=(clientX-r.left)*cv.clientWidth/r.width,y=(clientY-r.top)*cv.clientWidth/r.width;
       |  if(x<box[0]||x>box[2]||y<box[1]||y>box[3])return null;
       |  let best=null,bd=Infinity,bz=Infinity;
       |  for(const it of last){const L=layers[it[1]];if(!L.pk||!L.pk[it[2]])continue;
       |    const d=reach(L,it[2],x,y);if(d>4)continue;
       |    if(d<bd||(d===bd&&it[0]<bz)){bd=d;bz=it[0];best=L.pk[it[2]];}}
       |  return best;}
       |const keysFor=(pk,n)=>pk?pk.filter(q=>q[0]===n).map(q=>q[1]):[];
       |const link=()=>ia&&ia.tda4j;
       |let frame=0,xy=null,drawn=0;
       |function hoverAt(e){if(!choosers.size||!link())return;xy=[e.clientX,e.clientY];
       |  if(frame)return;
       |  frame=requestAnimationFrame(()=>{frame=0;const pk=pickAt(xy[0],xy[1]);
       |    choosers.forEach(n=>link().hover(n,keysFor(pk,n)));cv.style.cursor=pk?'pointer':idle();});}
       |function leave(){if(frame){cancelAnimationFrame(frame);frame=0;}
       |  if(link())choosers.forEach(n=>link().hover(n,[]));cv.style.cursor=idle();}
       |let drag=null,moved=0;const idle=()=>boxMode?'crosshair':'grab';
       |// two fingers: the distance between them zooms (about their midpoint on a flat canvas) and their midpoint turns or pans
       |const fingers=new Map();let pinch=null;
       |const spread=()=>{const f=[...fingers.values()];return {d:Math.hypot(f[0][0]-f[1][0],f[0][1]-f[1][1]),m:[(f[0][0]+f[1][0])/2,(f[0][1]+f[1][1])/2]};};
       |cv.addEventListener('pointerdown',e=>{
       |  if(e.pointerType==='touch'){fingers.set(e.pointerId,[e.clientX,e.clientY]);
       |    if(fingers.size===2){const s=spread();pinch={d:s.d,m:s.m,zoom:zoom,az:az,el:el,pan:pan.slice()};drag=null;moved=99;${dropRect}cv.setPointerCapture(e.pointerId);return;}}
       |  ${dropRect}drag=[e.clientX,e.clientY,az,el,pan[0],pan[1]];moved=0;cv.setPointerCapture(e.pointerId);cv.style.cursor=boxMode?'crosshair':'grabbing';});
       |cv.addEventListener('pointermove',e=>{
       |  if(fingers.has(e.pointerId)){fingers.set(e.pointerId,[e.clientX,e.clientY]);
       |    if(pinch&&fingers.size===2){const s=spread(),z=Math.max(0.2,Math.min(turns?8:200,pinch.zoom*s.d/Math.max(pinch.d,1)));
       |      const dx=s.m[0]-pinch.m[0],dy=s.m[1]-pinch.m[1];
       |      if(turns){az=pinch.az+dx*0.01;el=Math.max(-1.55,Math.min(1.55,pinch.el+dy*0.01));order=null;}
       |      else{const r=cv.getBoundingClientRect(),k=cv.clientWidth/r.width,x=(pinch.m[0]-r.left)*k-bc[0],y=(pinch.m[1]-r.top)*k-bc[1];
       |        pan=[x-(x-pinch.pan[0])*z/pinch.zoom+dx*k,y-(y-pinch.pan[1])*z/pinch.zoom+dy*k];}
       |      zoom=z;draw();return;}}
       |  if(!drag){hoverAt(e);return;}
       |  moved=Math.max(moved,Math.hypot(e.clientX-drag[0],e.clientY-drag[1]));
       |  if(moved<4)return;
       |  $dragBox
       |  if(turns){az=drag[2]+(e.clientX-drag[0])*0.01;el=Math.max(-1.55,Math.min(1.55,drag[3]+(e.clientY-drag[1])*0.01));order=null;}
       |  else pan=[drag[4]+(e.clientX-drag[0])*cv.clientWidth/cv.getBoundingClientRect().width,drag[5]+(e.clientY-drag[1])*cv.clientWidth/cv.getBoundingClientRect().width];
       |  draw();});
       |const lift=e=>{fingers.delete(e.pointerId);if(fingers.size<2)pinch=null;};
       |cv.addEventListener('pointercancel',e=>{lift(e);drag=null;});
       |cv.addEventListener('pointerup',e=>{lift(e);const was=drag;drag=null;cv.style.cursor=idle();
       |  $liftBox
       |  if(was&&moved<4&&choosers.size&&link()){const pk=pickAt(e.clientX,e.clientY);choosers.forEach(n=>link().click(n,keysFor(pk,n),e.shiftKey));}});
       |cv.addEventListener('pointerleave',leave);
       |cv.addEventListener('wheel',e=>{e.preventDefault();${clearRect}const z=Math.max(0.2,Math.min(turns?8:200,zoom*Math.exp(-e.deltaY*0.001)));
       |  if(!turns){const r=cv.getBoundingClientRect(),k=cv.clientWidth/r.width,x=(e.clientX-r.left)*k-bc[0],y=(e.clientY-r.top)*k-bc[1];
       |    pan=[x-(x-pan[0])*z/zoom,y-(y-pan[1])*z/zoom];}
       |  zoom=z;draw();},{passive:false});
       |$boxScript$axesScript
       |matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>{resolved.clear();draw();});
       |window.addEventListener('resize',()=>{${clearRect}draw();});draw();})();</script>
       |</div>""".stripMargin

  def html(theme: Theme = Theme.Auto): String = Html.page(title, fragment(theme), palette)

object Scene:
  // Element ids for the fragments of one page; scratch, no meaning outside this file.
  private[plot] val counter = new java.util.concurrent.atomic.AtomicLong()

  // `Svg.number`, `Svg.niceTicks` and the transforms with their ticks (`Transform.ticks`, `tickLabel`) in JavaScript, for
  // the axes the page draws itself: a zoomed figure's (the page's script) and a flat canvas's (the canvas's). The
  // transforms are named by a letter: `i` identity, `l` log, `s` square root, `m` symmetric log (with its linear
  // stretch `c`). One source for both; change it with the Scala ones: the check script compares the ticks of a page
  // with those of the still picture of it.
  private[plot] val tickScript: String =
    """function numberText(v){const a=Math.abs(v);if(v===0)return '0';
  if(a>=1e5||a<1e-3){if(a<1e-3&&a>=1e-4)return v.toPrecision(3);
    const m=/^(-?[0-9.]+)e([+-])([0-9]+)$/.exec(v.toExponential(2));return m[1]+'e'+m[2]+(m[3].length<2?'0'+m[3]:m[3]);}
  const s=String(Number(v.toPrecision(4)));return s==='-0'?'0':s;}
function niceTicks(lo,hi,target){
  if(!(hi>lo)||!isFinite(hi)||!isFinite(lo))return [lo];
  const raw=(hi-lo)/target,mag=Math.pow(10,Math.floor(Math.log10(raw))),norm=raw/mag;
  const step=mag*(norm<1.5?1:norm<3?2:norm<7?5:10),first=Math.ceil(lo/step-1e-9),last=Math.floor(hi/step+1e-9),out=[];
  for(let i=first;i<=last;i++){const t=i*step;out.push(t===0?0:t);}return out;}
const tr={i:v=>v,l:v=>Math.log10(v),s:v=>Math.sign(v)*Math.sqrt(Math.abs(v)),m:(v,c)=>Math.sign(v)*Math.log10(1+Math.abs(v)/c)};
const inv={i:u=>u,l:u=>Math.pow(10,u),s:u=>Math.sign(u)*u*u,m:(u,c)=>Math.sign(u)*c*(Math.pow(10,Math.abs(u))-1)};
function logLabel(v){if(v>0&&(v<1e-3||v>=1e5)){const e=Math.floor(Math.log10(v)+1e-9),m=v/Math.pow(10,e);
  if(Math.abs(m-Math.round(m))<1e-9)return Math.round(m)+'e'+e;}return numberText(v);}
function logTicks(lo,hi,target){
  if(!(lo>0&&hi>lo&&isFinite(hi)))return [];
  const kLo=Math.floor(Math.log10(lo)),kHi=Math.ceil(Math.log10(hi)),decades=kHi-kLo,stride=Math.max(1,Math.ceil(decades/6)),multiples=decades>=4?[1]:[1,2,5],found=[];
  for(let k=kLo;k<=kHi;k+=stride)for(const m of multiples){const t=m*Math.pow(10,k);if(t>=lo*(1-1e-9)&&t<=hi*(1+1e-9))found.push(t);}
  return found.length>=2?found:niceTicks(lo,hi,target);}
function trTicks(t,c,lo,hi,target){
  if(t==='l')return logTicks(lo,hi,target);
  if(t==='m'){const reach=Math.max(Math.abs(lo),Math.abs(hi));if(reach<=10*c)return niceTicks(lo,hi,target);
    const mag=logTicks(c,reach,target);return mag.map(x=>-x).reverse().concat([0],mag).filter(x=>x>=lo&&x<=hi);}
  return niceTicks(lo,hi,target);}"""

  // `String.format`'s %f and %e in JavaScript (rounding the shortest decimal half up, as Java does), for the axes with a number
  // format: a zoomed figure's (the page's script) and a flat canvas's. The page writes these two conversions with the flags
  // - + 0 and , (for f), a width and a precision, between any text and %%: `Interactive.formatSupported` says which patterns.
  private[plot] val formatScript: String =
    """function digitsOf(a){if(a===0)return {d:'0',e:0};
  const m=/^([0-9])(?:\.([0-9]+))?e([+-][0-9]+)$/.exec(a.toExponential());return {d:m[1]+(m[2]||''),e:+m[3]};}
function scaled(a,frac){const g=digitsOf(a),keep=g.e+1+frac;if(keep<0)return 0n;
  let n=keep===0?0n:BigInt(g.d.slice(0,keep).padEnd(keep,'0'));if(g.d.charAt(keep)>='5')n+=1n;return n;}
function expo(a,prec){if(a===0)return [prec>0?'0.'+'0'.repeat(prec):'0',0];
  const g=digitsOf(a),keep=prec+1;let n=BigInt(g.d.slice(0,keep).padEnd(keep,'0'));if(g.d.charAt(keep)>='5')n+=1n;
  let t=n.toString(),e=g.e;if(t.length>keep){t=t.slice(0,keep);e+=1;}
  return [t.charAt(0)+(prec>0?'.'+t.slice(1):''),e];}
function javaFormat(pattern,v){
  return pattern.replace(/%(?:(%)|([-+0]*,?[-+0]*)([0-9]*)(?:\.([0-9]+))?([feE]))/g,(all,pct,flags,width,prec,conv)=>{
    if(pct)return '%';
    const w=width?+width:0;
    // NaN and the infinities are words: padded with spaces (never zeros), a sign only on an infinity
    if(!isFinite(v)){let word=isNaN(v)?'NaN':(v<0?'-':flags.includes('+')?'+':'')+'Infinity';if(conv==='E')word=word.toUpperCase();
      return word.length>=w?word:flags.includes('-')?word.padEnd(w):word.padStart(w);}
    const neg=v<0||Object.is(v,-0),a=Math.abs(v),sign=neg?'-':flags.includes('+')?'+':'',p=prec===undefined?6:+prec;
    let text;
    if(conv==='f'){const n=scaled(a,p).toString().padStart(p+1,'0'),whole=n.slice(0,n.length-p),part=n.slice(n.length-p);
      text=(flags.includes(',')?whole.replace(/\B(?=([0-9]{3})+(?![0-9]))/g,','):whole)+(p>0?'.'+part:'');}
    else{const x=expo(a,p),ae=Math.abs(x[1]);text=x[0]+'e'+(x[1]<0?'-':'+')+(ae<10?'0'+ae:String(ae));if(conv==='E')text=text.toUpperCase();}
    let out=sign+text;
    if(out.length<w){if(flags.includes('-'))out=out.padEnd(w);else if(flags.includes('0'))out=sign+text.padStart(w-sign.length,'0');else out=out.padStart(w);}
    return out;});}"""

  // a text as a JavaScript string literal, safe inside a script element
  private[plot] def jsText(t: String): String =
    "\"" + t.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '<'  => "\\u003c"
      case '\n' => "\\n"
      case c    => c.toString
    } + "\""

  // The geometry of choosing by rectangle, in the plane of the view; the page's script does the same sums on the pixels
  // (a place in the plane and its pixel are an affine map apart, so what meets what is the same).
  private type Pt = (Double, Double)
  private type Box = (Double, Double, Double, Double) // x0, y0, x1, y1

  private[plot] def inBox(p: Pt, b: Box): Boolean = p._1 >= b._1 && p._1 <= b._3 && p._2 >= b._2 && p._2 <= b._4

  // Liang-Barsky: does the segment from `a` to `b` meet the box?
  private[plot] def cuts(a: Pt, b: Pt, box: Box): Boolean =
    val (dx, dy) = (b._1 - a._1, b._2 - a._2)
    var (t0, t1) = (0.0, 1.0)
    var inside = true
    for (p, q) <- Seq((-dx, a._1 - box._1), (dx, box._3 - a._1), (-dy, a._2 - box._2), (dy, box._4 - a._2)) do
      if p == 0.0 then
        if q < 0 then inside = false
      else
        val r = q / p
        if p < 0 then
          if r > t1 then inside = false
          if r > t0 then t0 = r
        else
          if r < t0 then inside = false
          if r < t1 then t1 = r
    inside

  // is `p` in the triangle `a b c` (its edges count)?
  private def inTriangle(p: Pt, a: Pt, b: Pt, c: Pt): Boolean =
    val s1 = (p._1 - b._1) * (a._2 - b._2) - (a._1 - b._1) * (p._2 - b._2)
    val s2 = (p._1 - c._1) * (b._2 - c._2) - (b._1 - c._1) * (p._2 - c._2)
    val s3 = (p._1 - a._1) * (c._2 - a._2) - (c._1 - a._1) * (p._2 - a._2)
    !((s1 < 0 || s2 < 0 || s3 < 0) && (s1 > 0 || s2 > 0 || s3 > 0))

  // does the triangle overlap the box: an edge meets it, or the box lies inside the triangle
  private[plot] def overlaps(a: Pt, b: Pt, c: Pt, box: Box): Boolean =
    cuts(a, b, box) || cuts(b, c, box) || cuts(c, a, box) ||
      Seq((box._1, box._2), (box._3, box._2), (box._3, box._4), (box._1, box._4)).exists(inTriangle(_, a, b, c))
