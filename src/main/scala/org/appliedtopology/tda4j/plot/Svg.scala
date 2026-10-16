package org.appliedtopology.tda4j.plot

import java.util.Locale

/** Renders a [[Figure]] as one `<svg>` element: a surface, hairline grid and axes, the marks clipped to the plot area,
  * a legend and a color bar. Under `Theme.Auto` every themed color is a CSS variable of the `<svg>` (both modes
  * declared, `prefers-color-scheme` choosing); under `Light`/`Dark` every color is a plain hex.
  *
  * Positions go through the axes' [[Transform]]s here and nowhere else, so a figure holds data in its own units. A mark
  * with no place on the axes (zero on a log axis, `NaN`) is left out, and a line of small print under the plot counts
  * what was left out. The figure's [[Style]] sets the font, the size of text and marks, the gridlines, the legend's
  * place and the background.
  */
object Svg:
  private val counter = new java.util.concurrent.atomic.AtomicLong()

  /** XML text escaping. */
  def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /** A number for a tick or a tooltip: up to four significant figures, `∞` for infinity. */
  def number(v: Double): String =
    if v.isPosInfinity then "∞"
    else if v.isNegInfinity then "-∞"
    else if v.isNaN then "NaN"
    else if v == 0.0 then "0"
    else
      val a = math.abs(v)
      if a >= 1e5 || a < 1e-3 then f"$v%.3g"
      else
        val s = BigDecimal(v).round(new java.math.MathContext(4)).bigDecimal.stripTrailingZeros.toPlainString
        if s == "-0" then "0" else s

  /** About `target` ticks at round values (1, 2 or 5 times a power of ten) inside `[lo, hi]`. */
  def niceTicks(lo: Double, hi: Double, target: Int = 5): IndexedSeq[Double] =
    if !(hi > lo) || hi.isInfinite || lo.isInfinite then IndexedSeq(lo)
    else
      val raw = (hi - lo) / target
      val mag = math.pow(10, math.floor(math.log10(raw)))
      val norm = raw / mag
      val step = mag * (if norm < 1.5 then 1 else if norm < 3 then 2 else if norm < 7 then 5 else 10)
      val first = math.ceil(lo / step - 1e-9).toLong
      val last = math.floor(hi / step + 1e-9).toLong
      (first to last).map { i =>
        val t = i * step; if t == 0.0 then 0.0 else t
      }.toIndexedSeq

  private def fmt(v: Double): String =
    val r = math.round(v * 100) / 100.0
    if r == r.toLong then r.toLong.toString else r.toString

  /** The plot area on the page, and the range it shows. `x0..x1` and `y0..y1` are positions in the axes' transformed
    * space, which is data space on a linear axis.
    */
  private final case class Frame(
    xt: Transform,
    yt: Transform,
    x0: Double,
    x1: Double,
    y0: Double,
    y1: Double,
    left: Double,
    top: Double,
    w: Double,
    h: Double
  ):
    def px(x: Double): Double = left + (xt(x) - x0) / (x1 - x0) * w
    def py(y: Double): Double = top + h - (yt(y) - y0) / (y1 - y0) * h

    /** Pixels per data unit along x: the radius of a disc on a linear axis. */
    def scale: Double = w / (x1 - x0)

  /** The extent of the marks' positions in the axes' transformed space; marks without a place on the axes count for
    * nothing.
    */
  private def extent(marks: Seq[Mark], xt: Transform, yt: Transform): Option[(Double, Double, Double, Double)] =
    val xs = scala.collection.mutable.ArrayBuffer.empty[Double]
    val ys = scala.collection.mutable.ArrayBuffer.empty[Double]
    def add(p: XY): Unit =
      val (u, v) = (xt(p._1), yt(p._2))
      if u.isFinite && v.isFinite then
        xs += u; ys += v
    marks.foreach {
      case m: Mark.Points   => m.at.foreach(add)
      case m: Mark.Segments => m.ends.foreach { (a, b) => add(a); add(b) }
      case m: Mark.Polygons => m.corners.foreach(_.foreach(add))
      case m: Mark.Discs    =>
        m.centers.foreach { c => add((c._1 - m.radius, c._2 - m.radius)); add((c._1 + m.radius, c._2 + m.radius)) }
      case m: Mark.Boxes                   => m.boxes.foreach { b => add((b._1, b._2)); add((b._3, b._4)) }
      case m: Mark.Line                    => m.at.foreach(add)
      case m: Mark.Text                    => add(m.at)
      case _: Mark.Diagonal | _: Mark.Rule => ()
    }
    Option.when(xs.nonEmpty)((xs.min, xs.max, ys.min, ys.max))

  private def padded(lo: Double, hi: Double, fraction: Double): (Double, Double) =
    if hi > lo then (lo - (hi - lo) * fraction, hi + (hi - lo) * fraction)
    else (lo - 1, hi + 1)

  /** The visible range of an axis in transformed space: the ends the axis fixes (those the transform can place), the
    * rest from the marks' extent `lo..hi`, padded.
    */
  private def spaceRange(a: Axis, lo: Double, hi: Double): (Double, Double) =
    val fixedLo = a.min.map(a.transform(_)).filter(_.isFinite)
    val fixedHi = a.max.map(a.transform(_)).filter(_.isFinite)
    (fixedLo, fixedHi) match
      case (Some(l), Some(h)) => (l, h)
      case _                  =>
        val (pl, pu) = padded(lo, hi, 0.04)
        (fixedLo.getOrElse(pl), fixedHi.getOrElse(pu))

  private def checkRange(name: String, a: Axis, lo: Double, hi: Double): Unit =
    if !(hi > lo) then
      throw IllegalArgumentException(
        s"the $name axis would run from ${number(a.transform.inverse(lo))} to ${number(a.transform.inverse(hi))}: " +
          s"its minimum must be less than its maximum (see Scale.$name)"
      )

  /** The ranges the axes show, in data units, before any adjustment for an equal aspect ratio. */
  private[plot] def ranges(fig: Figure): ((Double, Double), (Double, Double)) =
    val (xt, yt) = (fig.x.transform, fig.y.transform)
    val (dx0, dx1, dy0, dy1) = extent(fig.marks, xt, yt).getOrElse((0.0, 1.0, 0.0, 1.0))
    val (x0, x1) = spaceRange(fig.x, dx0, dx1)
    val (y0, y1) = spaceRange(fig.y, dy0, dy1)
    ((xt.inverse(x0), xt.inverse(x1)), (yt.inverse(y0), yt.inverse(y1)))

  def render(fig: Figure, theme: Theme): String =
    val id = s"tda4j${counter.incrementAndGet()}"
    val style = fig.style
    val ts = style.textScale
    val ink = style.markScale
    val pal = fig.palette.withDegrees(fig.degrees)
    val (xt, yt) = (fig.x.transform, fig.y.transform)
    val sb = new StringBuilder

    def paint(p: Paint): String = p match
      case Paint.Value(v) =>
        fig.colorScale match
          case Some(c) => c.hex(v, pal)
          case None    =>
            throw IllegalArgumentException(
              "a mark is colored by a data value (Paint.Value) but the figure has no colorScale"
            )
      case _ => if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)

    // a degree's label and shape, where the figure changes them
    def shapeOf(m: Mark.Points, i: Int): Shape = m.paintAt(i) match
      case Paint.Series(k) => fig.degrees.get(k).flatMap(_.shape).getOrElse(m.shape)
      case _               => m.shape
    val legend = fig.legend.map { item =>
      item.paint match
        case Paint.Series(k) =>
          val d = fig.degrees.get(k)
          item.copy(label = d.flatMap(_.label).getOrElse(item.label), shape = d.flatMap(_.shape).getOrElse(item.shape))
        case _ => item
    }

    // the marks that cannot be placed on these axes are left out, and counted
    def ok(p: XY): Boolean = xt(p._1).isFinite && yt(p._2).isFinite
    val notShown = fig.marks.map {
      case m: Mark.Points                  => m.at.count(!ok(_))
      case m: Mark.Segments                => m.ends.count((a, b) => !(ok(a) && ok(b)))
      case m: Mark.Polygons                => m.corners.count(!_.forall(ok))
      case m: Mark.Discs                   => m.centers.count(!ok(_))
      case m: Mark.Boxes                   => m.boxes.count(b => !(ok((b._1, b._2)) && ok((b._3, b._4))))
      case m: Mark.Line                    => if m.at.forall(ok) then 0 else 1
      case m: Mark.Text                    => if ok(m.at) then 0 else 1
      case _: Mark.Diagonal | _: Mark.Rule => 0
    }.sum

    // layout: the margins make room for the title, the axes, the legend and the color bar
    val legendPlace = if legend.isEmpty then LegendPosition.Hidden else style.legend
    val legendChars = (legend.map(_.label.length) :+ fig.legendTitle.length).max.min(24)
    val legendWidth = if legendPlace == LegendPosition.Right then (34.0 + 7.5 * legendChars) * ts else 0.0
    val colorbarWidth = if fig.colorScale.exists(_.guide) then 74.0 * ts else 0.0
    val top =
      16.0 + (if fig.title.nonEmpty then 22.0 * ts else 0.0) + (if fig.subtitle.nonEmpty then 16.0 * ts else 0.0)
    val left = if fig.y.visible then 58.0 * ts else 14.0
    val axisBand = if fig.x.visible then (if fig.x.label.nonEmpty then 46.0 else 30.0) * ts else 14.0
    val right = 16.0 + legendWidth + colorbarWidth
    val w = math.max(40.0, fig.width - left - right)

    def itemWidth(item: LegendItem): Double = (30.0 + 7.5 * item.label.length.min(24)) * ts
    val titleWidth = if fig.legendTitle.isEmpty then 0.0 else (14.0 + 7.5 * fig.legendTitle.length.min(24)) * ts
    val legendRows: Vector[Vector[LegendItem]] =
      if legendPlace != LegendPosition.Bottom then Vector.empty
      else
        val rows = Vector.newBuilder[Vector[LegendItem]]
        var row = Vector.empty[LegendItem]
        var used = titleWidth
        legend.foreach { item =>
          if row.nonEmpty && used + itemWidth(item) > w then
            rows += row
            row = Vector.empty
            used = 0.0
          row = row :+ item
          used += itemWidth(item)
        }
        rows += row
        rows.result()
    val legendBand = if legendRows.isEmpty then 0.0 else (10.0 + 20.0 * legendRows.size) * ts
    val noteBand = if notShown > 0 then 14.0 * ts else 0.0
    val bottom = axisBand + legendBand + noteBand
    val h = math.max(40.0, fig.height - top - bottom)

    // the range shown
    val (dx0, dx1, dy0, dy1) = extent(fig.marks, xt, yt).getOrElse((0.0, 1.0, 0.0, 1.0))
    var (x0, x1) = spaceRange(fig.x, dx0, dx1)
    var (y0, y1) = spaceRange(fig.y, dy0, dy1)
    checkRange("x", fig.x, x0, x1)
    checkRange("y", fig.y, y0, y1)
    // Equal units on both axes mean something only when both are on one scale (log-log: a decade is as long either way).
    // Widening a range stops a margin below what the scale can place (nothing is below 0 on a square-root axis).
    if fig.equalAspect && xt == yt then
      val sx = (x1 - x0) / w
      val sy = (y1 - y0) / h
      if sx > sy then
        val c = (y0 + y1) / 2
        y0 = c - sx * h / 2; y1 = c + sx * h / 2
      else
        val c = (x0 + x1) / 2
        x0 = c - sy * w / 2; x1 = c + sy * w / 2
      val lowestX = xt.floor - 0.04 * (x1 - x0)
      if x0 < lowestX then
        x1 += lowestX - x0; x0 = lowestX
      val lowestY = yt.floor - 0.04 * (y1 - y0)
      if y0 < lowestY then
        y1 += lowestY - y0; y0 = lowestY
    val f = Frame(xt, yt, x0, x1, y0, y1, left, top, w, h)

    sb ++= s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="${fig.width}" height="${fig.height}" viewBox="0 0 ${fig.width} ${fig.height}" font-family="${escape(
        style.font
      )}" role="img">"""
    if theme == Theme.Auto then sb ++= s"<style>${pal.cssVariables(s"#$id")}</style>"
    if fig.title.nonEmpty then sb ++= s"<title>${escape(fig.title)}</title>"
    if !style.transparent then
      sb ++= s"""<rect width="${fig.width}" height="${fig.height}" style="fill:${paint(Paint.Surface)}"/>"""
    sb ++= s"""<defs><clipPath id="$id-clip"><rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(w)}" height="${fmt(
        h
      )}"/></clipPath></defs>"""

    // title and subtitle
    val titleSize = 15.0 * ts
    val titleBase = 9.0 + titleSize
    if fig.title.nonEmpty then
      sb ++= s"""<text x="${fmt(left)}" y="${fmt(titleBase)}" style="fill:${paint(
          Paint.Ink
        )}" font-size="${fmt(titleSize)}" font-weight="600">${escape(fig.title)}</text>"""
    if fig.subtitle.nonEmpty then
      val sy = if fig.title.nonEmpty then titleBase + 18.0 * ts else titleBase
      sb ++= s"""<text x="${fmt(left)}" y="${fmt(sy)}" style="fill:${paint(Paint.Secondary)}" font-size="${fmt(
          12.0 * ts
        )}">${escape(fig.subtitle)}</text>"""

    // grid, ticks, axes
    def ticksOf(a: Axis, t0: Double, t1: Double): IndexedSeq[(Double, String)] =
      val tr = a.transform
      val text: Double => String = a.format match
        case Some(pattern) => v => String.format(Locale.ROOT, pattern, Double.box(v))
        case None          => tr.tickLabel
      val seen = (tr.inverse(math.min(t0, t1)), tr.inverse(math.max(t0, t1)))
      // an automatic tick keeps clear of an extra one (the `∞`), so that the two labels never overprint
      def clear(v: Double): Boolean = a.extra.forall((at, _) => math.abs(tr(v) - tr(at)) >= 0.05 * math.abs(t1 - t0))
      val main = a.ticks match
        case Ticks.Auto           => tr.ticks(seen._1, seen._2).filter(clear).map(v => (v, text(v)))
        case Ticks.Within(lo, hi) =>
          tr.ticks(math.max(lo, seen._1), math.min(hi, seen._2)).filter(clear).map(v => (v, text(v)))
        case Ticks.At(positions)   => positions.map(v => (v, text(v)))
        case Ticks.Labelled(words) => words
      main ++ a.extra
    def within(tr: Transform, t0: Double, t1: Double)(tick: (Double, String)): Boolean =
      val u = tr(tick._1)
      u >= math.min(t0, t1) && u <= math.max(t0, t1)
    val inX = ticksOf(fig.x, x0, x1).filter(within(xt, x0, x1))
    val inY = ticksOf(fig.y, y0, y1).filter(within(yt, y0, y1))
    val tickSize = fmt(11.0 * ts)
    if style.grid && fig.x.visible && fig.x.grid then
      inX.foreach((t, _) =>
        sb ++= s"""<line x1="${fmt(f.px(t))}" y1="${fmt(top)}" x2="${fmt(f.px(t))}" y2="${fmt(
            top + h
          )}" style="stroke:${paint(Paint.Grid)}" stroke-width="1"/>"""
      )
    if style.grid && fig.y.visible && fig.y.grid then
      inY.foreach((t, _) =>
        sb ++= s"""<line x1="${fmt(left)}" y1="${fmt(f.py(t))}" x2="${fmt(left + w)}" y2="${fmt(
            f.py(t)
          )}" style="stroke:${paint(Paint.Grid)}" stroke-width="1"/>"""
      )
    if fig.x.visible then
      sb ++= s"""<line x1="${fmt(left)}" y1="${fmt(top + h)}" x2="${fmt(left + w)}" y2="${fmt(
          top + h
        )}" style="stroke:${paint(Paint.Axis)}" stroke-width="1"/>"""
      inX.foreach((t, label) =>
        sb ++= s"""<text x="${fmt(f.px(t))}" y="${fmt(
            top + h + 16 * ts
          )}" text-anchor="middle" font-size="$tickSize" style="fill:${paint(
            Paint.Muted
          )};font-variant-numeric:tabular-nums">${escape(label)}</text>"""
      )
      if fig.x.label.nonEmpty then
        sb ++= s"""<text x="${fmt(left + w / 2)}" y="${fmt(
            top + h + 36 * ts
          )}" text-anchor="middle" font-size="${fmt(12.0 * ts)}" style="fill:${paint(Paint.Secondary)}">${escape(
            fig.x.label
          )}</text>"""
    if fig.y.visible then
      sb ++= s"""<line x1="${fmt(left)}" y1="${fmt(top)}" x2="${fmt(left)}" y2="${fmt(top + h)}" style="stroke:${paint(
          Paint.Axis
        )}" stroke-width="1"/>"""
      inY.foreach((t, label) =>
        sb ++= s"""<text x="${fmt(left - 6 * ts)}" y="${fmt(
            f.py(t) + 4 * ts
          )}" text-anchor="end" font-size="$tickSize" style="fill:${paint(
            Paint.Muted
          )};font-variant-numeric:tabular-nums">${escape(label)}</text>"""
      )
      if fig.y.label.nonEmpty then
        val cy = top + h / 2
        val lx = 14.0 * ts
        sb ++= s"""<text x="${fmt(lx)}" y="${fmt(cy)}" transform="rotate(-90 ${fmt(lx)} ${fmt(
            cy
          )})" text-anchor="middle" font-size="${fmt(12.0 * ts)}" style="fill:${paint(Paint.Secondary)}">${escape(
            fig.y.label
          )}</text>"""

    // marks
    sb ++= s"""<g clip-path="url(#$id-clip)">"""
    def tip(m: Mark, i: Int): String = m.tipAt(i).fold("")(t => s"<title>${escape(t)}</title>")
    def item(open: String, m: Mark, i: Int): Unit =
      m.tipAt(i) match
        case Some(_) => sb ++= open.stripSuffix("/>") + ">" + tip(m, i) + "</" + open.drop(1).takeWhile(_ != ' ') + ">"
        case None    => sb ++= open
    def dash(dashed: Boolean): String = if dashed then """ stroke-dasharray="5 4"""" else ""
    fig.marks.foreach {
      case m: Mark.Discs =>
        val linear = xt == Transform.Identity && yt == Transform.Identity
        m.centers.indices.foreach(i =>
          val (cx, cy) = m.centers(i)
          if ok((cx, cy)) then
            val shapeStyle = s"""style="fill:${paint(m.paintAt(i))};fill-opacity:${m.opacity}""""
            if linear then
              item(
                s"""<circle cx="${fmt(f.px(cx))}" cy="${fmt(f.py(cy))}" r="${fmt(m.radius * f.scale)}" $shapeStyle/>""",
                m,
                i
              )
            else
              // on a curved axis a ball is no longer round: its extent along each axis
              val rx = math.abs(f.px(cx + m.radius) - f.px(cx))
              val ry = math.abs(f.py(cy + m.radius) - f.py(cy))
              if rx.isFinite && ry.isFinite then
                item(
                  s"""<ellipse cx="${fmt(f.px(cx))}" cy="${fmt(f.py(cy))}" rx="${fmt(rx)}" ry="${fmt(
                      ry
                    )}" $shapeStyle/>""",
                  m,
                  i
                )
        )
      case m: Mark.Boxes =>
        m.boxes.indices.foreach(i =>
          val (bx0, by0, bx1, by1) = m.boxes(i)
          if ok((bx0, by0)) && ok((bx1, by1)) then
            val (px0, px1) = (f.px(math.min(bx0, bx1)), f.px(math.max(bx0, bx1)))
            val (py0, py1) = (f.py(math.max(by0, by1)), f.py(math.min(by0, by1)))
            item(
              s"""<rect x="${fmt(px0)}" y="${fmt(py0)}" width="${fmt(px1 - px0 + 0.3)}" height="${fmt(
                  py1 - py0 + 0.3
                )}" style="fill:${paint(m.paintAt(i))};fill-opacity:${m.opacity}"/>""",
              m,
              i
            )
        )
      case m: Mark.Polygons =>
        m.corners.indices.foreach(i =>
          if m.corners(i).forall(ok) then
            val pts = m.corners(i).map((x, y) => s"${fmt(f.px(x))},${fmt(f.py(y))}").mkString(" ")
            item(s"""<polygon points="$pts" style="fill:${paint(m.paintAt(i))};fill-opacity:${m.opacity}"/>""", m, i)
        )
      case m: Mark.Segments =>
        val cap = if m.roundCaps then "round" else "butt"
        m.ends.indices.foreach(i =>
          val ((ax, ay), (bx, by)) = m.ends(i)
          if ok((ax, ay)) && ok((bx, by)) then
            item(
              s"""<line x1="${fmt(f.px(ax))}" y1="${fmt(f.py(ay))}" x2="${fmt(f.px(bx))}" y2="${fmt(
                  f.py(by)
                )}" style="stroke:${paint(m.paintAt(i))};stroke-opacity:${m.opacity}" stroke-width="${fmt(
                  m.width * ink
                )}" stroke-linecap="$cap"/>""",
              m,
              i
            )
        )
      case m: Mark.Line =>
        val pts = m.at.filter(ok).map((x, y) => s"${fmt(f.px(x))},${fmt(f.py(y))}").mkString(" ")
        item(
          s"""<polyline points="$pts" style="fill:none;stroke:${paint(m.paint)}" stroke-width="${fmt(
              m.width * ink
            )}" stroke-linejoin="round" stroke-linecap="round"/>""",
          m,
          0
        )
      case m: Mark.Points =>
        m.at.indices.foreach(i =>
          if ok(m.at(i)) then
            val (x, y) = m.at(i)
            item(
              marker(f.px(x), f.py(y), m.radius * ink, shapeOf(m, i), paint(m.paintAt(i)), paint(Paint.Surface)),
              m,
              i
            )
        )
      case m: Mark.Text =>
        if ok(m.at) then
          val anchor = m.anchor match
            case Anchor.Start  => "start"
            case Anchor.Middle => "middle"
            case Anchor.End    => "end"
          sb ++= s"""<text x="${fmt(f.px(m.at._1) + m.dx)}" y="${fmt(
              f.py(m.at._2) + m.dy
            )}" text-anchor="$anchor" font-size="${fmt(m.size * ts)}" style="fill:${paint(m.paint)}">${escape(
              m.text
            )}</text>"""
      case m: Mark.Rule =>
        val u = if m.vertical then xt(m.at) else yt(m.at)
        if u.isFinite then
          val (x1p, y1p, x2p, y2p) =
            if m.vertical then (f.px(m.at), top, f.px(m.at), top + h)
            else (left, f.py(m.at), left + w, f.py(m.at))
          sb ++= s"""<line x1="${fmt(x1p)}" y1="${fmt(y1p)}" x2="${fmt(x2p)}" y2="${fmt(y2p)}" style="stroke:${paint(
              m.paint
            )}" stroke-width="${fmt(m.width * ink)}"${dash(m.dashed)}/>"""
      case m: Mark.Diagonal =>
        // t runs over the values for which both x = t and y = t + offset lie on the plot
        val (xa, xb) = (xt.inverse(x0), xt.inverse(x1))
        val (ya, yb) = (yt.inverse(y0), yt.inverse(y1))
        val (tLo, tHi) = (math.max(xa, ya - m.offset), math.min(xb, yb - m.offset))
        if tLo < tHi then
          // a straight line when both axes are linear; otherwise a curve, followed at even steps along each axis
          val samples =
            if xt == Transform.Identity && yt == Transform.Identity then Seq(tLo, tHi)
            else
              val n = 64
              val evenInX = (0 to n).map(i => xt.inverse(xt(tLo) + (xt(tHi) - xt(tLo)) * i / n))
              val evenInY = (0 to n).map { i =>
                yt.inverse(yt(tLo + m.offset) + (yt(tHi + m.offset) - yt(tLo + m.offset)) * i / n) - m.offset
              }
              (evenInX ++ evenInY).filter(t => t >= tLo && t <= tHi).sorted
          val line = samples.filter(t => ok((t, t + m.offset))).map(t => (f.px(t), f.py(t + m.offset)))
          if line.size >= 2 then
            m.shade.foreach { shade =>
              // the corners are far outside the plot, which the clip trims: the polygon is the region below the line
              val far = 4.0 * (fig.width + fig.height)
              val corners =
                Seq((left + w + far, top - far), (left + w + far, top + h + far), (left - far, top + h + far))
              val pts = (line ++ corners).map((px, py) => s"${fmt(px)},${fmt(py)}").mkString(" ")
              sb ++= s"""<polygon points="$pts" style="fill:${paint(shade)};fill-opacity:${m.shadeOpacity}"/>"""
            }
            val pts = line.map((px, py) => s"${fmt(px)},${fmt(py)}").mkString(" ")
            sb ++= s"""<polyline points="$pts" style="fill:none;stroke:${paint(m.paint)}" stroke-width="${fmt(
                m.width * ink
              )}"${dash(m.dashed)}/>"""
    }
    sb ++= "</g>"

    // legend: beside the plot, or in rows below it
    val legendFont = fmt(12.0 * ts)
    def legendKey(item: LegendItem, lx: Double, cy: Double): String = item.key match
      case LegendItem.Key.Dot =>
        marker(lx + 6 * ts, cy, 4.5 * ts, item.shape, paint(item.paint), paint(Paint.Surface))
      case LegendItem.Key.Line =>
        s"""<line x1="${fmt(lx)}" y1="${fmt(cy)}" x2="${fmt(lx + 14 * ts)}" y2="${fmt(cy)}" style="stroke:${paint(
            item.paint
          )}" stroke-width="${fmt(2.5 * ts)}" stroke-linecap="round"/>"""
      case LegendItem.Key.Swatch =>
        s"""<rect x="${fmt(lx)}" y="${fmt(cy - 6 * ts)}" width="${fmt(12 * ts)}" height="${fmt(
            12 * ts
          )}" rx="2" style="fill:${paint(
            item.paint
          )}"/>"""
    def legendText(text: String, lx: Double, cy: Double, bold: Boolean = false): String =
      val weight = if bold then " font-weight=\"600\"" else ""
      s"""<text x="${fmt(lx)}" y="${fmt(cy + 4 * ts)}" font-size="$legendFont"$weight style="fill:${paint(
          Paint.Secondary
        )}">${escape(text)}</text>"""
    var lx = left + w + 18
    if legendPlace == LegendPosition.Right then
      val titleRows = if fig.legendTitle.isEmpty then 0 else 1
      if titleRows == 1 then sb ++= legendText(fig.legendTitle, lx, top + 8 * ts, bold = true)
      legend.zipWithIndex.foreach { (entry, k) =>
        val cy = top + 8 * ts + 20 * ts * (k + titleRows)
        sb ++= legendKey(entry, lx, cy)
        sb ++= legendText(entry.label, lx + 20 * ts, cy)
      }
      lx += legendWidth
    else if legendPlace == LegendPosition.Bottom then
      val y0Legend = top + h + axisBand
      legendRows.zipWithIndex.foreach { (row, r) =>
        val cy = y0Legend + 10 * ts + 20 * ts * r
        var x = left
        if r == 0 && fig.legendTitle.nonEmpty then
          sb ++= legendText(fig.legendTitle, x, cy, bold = true)
          x += titleWidth
        row.foreach { entry =>
          sb ++= legendKey(entry, x, cy)
          sb ++= legendText(entry.label, x + 20 * ts, cy)
          x += itemWidth(entry)
        }
      }

    // color bar
    fig.colorScale.filter(_.guide).foreach { cb =>
      val steps = 48
      val bh = math.min(h, 220.0 * ts)
      val bx = lx + 4
      val small = fmt(11.0 * ts)
      (0 until steps).foreach { s =>
        val t = (s + 0.5) / steps
        val yTop = top + bh * (1 - (s + 1.0) / steps)
        sb ++= s"""<rect x="${fmt(bx)}" y="${fmt(yTop)}" width="${fmt(12 * ts)}" height="${fmt(
            bh / steps + 0.5
          )}" style="fill:${cb.barColor(t, pal)}"/>"""
      }
      sb ++= s"""<text x="${fmt(bx + 16 * ts)}" y="${fmt(top + 4 * ts)}" font-size="$small" style="fill:${paint(
          Paint.Muted
        )}">${escape(number(cb.hi))}</text>"""
      sb ++= s"""<text x="${fmt(bx + 16 * ts)}" y="${fmt(top + bh + 4 * ts)}" font-size="$small" style="fill:${paint(
          Paint.Muted
        )}">${escape(number(cb.lo))}</text>"""
      if cb.label.nonEmpty then
        sb ++= s"""<text x="${fmt(bx)}" y="${fmt(top + bh + 22 * ts)}" font-size="$small" style="fill:${paint(
            Paint.Secondary
          )}">${escape(cb.label)}</text>"""
    }

    // small print: what the axes could not show
    if notShown > 0 then
      val scales = Seq(xt, yt).filter(_ != Transform.Identity).map(_.label).distinct
      val why =
        if scales.isEmpty then "a position that is not a number" else s"outside the ${scales.mkString(" and ")} scale"
      sb ++= s"""<text x="${fmt(left)}" y="${fmt(fig.height - 4 * ts)}" font-size="${fmt(
          11.0 * ts
        )}" style="fill:${paint(
          Paint.Muted
        )}">${notShown} ${if notShown == 1 then "mark" else "marks"} not shown: $why</text>"""
    sb ++= "</svg>"
    sb.toString

  /** A marker of `r` pixels centred at `(cx, cy)`, ringed in the surface color. */
  private def marker(cx: Double, cy: Double, r: Double, shape: Shape, fill: String, ring: String): String =
    val style = s"""style="fill:$fill;stroke:$ring" stroke-width="2""""
    shape match
      case Shape.Circle => s"""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r)}" $style/>"""
      case Shape.Square =>
        val s = r * 0.9
        s"""<rect x="${fmt(cx - s)}" y="${fmt(cy - s)}" width="${fmt(2 * s)}" height="${fmt(2 * s)}" $style/>"""
      case Shape.Triangle =>
        val s = r * 1.25
        val pts = Seq((cx, cy - s), (cx + s * 0.866, cy + s * 0.5), (cx - s * 0.866, cy + s * 0.5))
        s"""<polygon points="${pts.map((a, b) => s"${fmt(a)},${fmt(b)}").mkString(" ")}" $style/>"""
      case Shape.Diamond =>
        val s = r * 1.2
        val pts = Seq((cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy))
        s"""<polygon points="${pts.map((a, b) => s"${fmt(a)},${fmt(b)}").mkString(" ")}" $style/>"""
