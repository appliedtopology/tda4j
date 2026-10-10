package org.appliedtopology.tda4j.plot

/** Renders a [[Figure]] as one `<svg>` element: a surface, hairline grid and axes, the marks clipped to the plot area,
  * a legend and a colorbar to the right. Under `Theme.Auto` every themed color is a CSS variable of the `<svg>` (both
  * modes declared, `prefers-color-scheme` choosing); under `Light`/`Dark` every color is a plain hex.
  */
object Svg:
  private val Font = "system-ui,-apple-system,'Segoe UI',sans-serif"
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

  private final case class Frame(
    x0: Double,
    x1: Double,
    y0: Double,
    y1: Double,
    left: Double,
    top: Double,
    w: Double,
    h: Double
  ):
    def px(x: Double): Double = left + (x - x0) / (x1 - x0) * w
    def py(y: Double): Double = top + h - (y - y0) / (y1 - y0) * h
    def scale: Double = w / (x1 - x0)

  private def extent(marks: Seq[Mark]): Option[(Double, Double, Double, Double)] =
    val xs = scala.collection.mutable.ArrayBuffer.empty[Double]
    val ys = scala.collection.mutable.ArrayBuffer.empty[Double]
    def add(p: XY): Unit = if !p._1.isInfinite && !p._2.isInfinite && !p._1.isNaN && !p._2.isNaN then
      xs += p._1; ys += p._2
    marks.foreach {
      case m: Mark.Points   => m.at.foreach(add)
      case m: Mark.Segments => m.ends.foreach { (a, b) => add(a); add(b) }
      case m: Mark.Polygons => m.corners.foreach(_.foreach(add))
      case m: Mark.Discs    =>
        m.centers.foreach { c => add((c._1 - m.radius, c._2 - m.radius)); add((c._1 + m.radius, c._2 + m.radius)) }
      case m: Mark.Boxes => m.boxes.foreach { b => add((b._1, b._2)); add((b._3, b._4)) }
      case m: Mark.Line  => m.at.foreach(add)
      case m: Mark.Text  => add(m.at)
    }
    Option.when(xs.nonEmpty)((xs.min, xs.max, ys.min, ys.max))

  private def padded(lo: Double, hi: Double, fraction: Double): (Double, Double) =
    if hi > lo then (lo - (hi - lo) * fraction, hi + (hi - lo) * fraction)
    else (lo - 1, hi + 1)

  def render(fig: Figure, theme: Theme): String =
    val id = s"tda4j${counter.incrementAndGet()}"
    val pal = fig.palette
    def paint(p: Paint): String = if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)
    val sb = new StringBuilder

    val legendWidth = if fig.legend.isEmpty then 0.0 else 34.0 + 7.5 * fig.legend.map(_.label.length).max.min(24)
    val colorbarWidth = if fig.colorbar.isEmpty then 0.0 else 74.0
    val top = 16.0 + (if fig.title.nonEmpty then 22 else 0) + (if fig.subtitle.nonEmpty then 16 else 0)
    val left = if fig.y.visible then 58.0 else 14.0
    val bottom = if fig.x.visible then if fig.x.label.nonEmpty then 46.0 else 30.0 else 14.0
    val right = 16.0 + legendWidth + colorbarWidth
    val w = math.max(40.0, fig.width - left - right)
    val h = math.max(40.0, fig.height - top - bottom)

    val (dx0, dx1, dy0, dy1) = extent(fig.marks).getOrElse((0.0, 1.0, 0.0, 1.0))
    var (x0, x1) = fig.x.range.getOrElse(padded(dx0, dx1, 0.04))
    var (y0, y1) = fig.y.range.getOrElse(padded(dy0, dy1, 0.04))
    if fig.equalAspect then
      val sx = (x1 - x0) / w
      val sy = (y1 - y0) / h
      if sx > sy then
        val c = (y0 + y1) / 2
        y0 = c - sx * h / 2; y1 = c + sx * h / 2
      else
        val c = (x0 + x1) / 2
        x0 = c - sy * w / 2; x1 = c + sy * w / 2
    val f = Frame(x0, x1, y0, y1, left, top, w, h)

    sb ++= s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="${fig.width}" height="${fig.height}" viewBox="0 0 ${fig.width} ${fig.height}" font-family="$Font" role="img">"""
    if theme == Theme.Auto then sb ++= s"<style>${pal.cssVariables(s"#$id")}</style>"
    if fig.title.nonEmpty then sb ++= s"<title>${escape(fig.title)}</title>"
    sb ++= s"""<rect width="${fig.width}" height="${fig.height}" style="fill:${paint(Paint.Surface)}"/>"""
    sb ++= s"""<defs><clipPath id="$id-clip"><rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(w)}" height="${fmt(
        h
      )}"/></clipPath></defs>"""

    // title and subtitle
    if fig.title.nonEmpty then
      sb ++= s"""<text x="${fmt(left)}" y="24" style="fill:${paint(
          Paint.Ink
        )}" font-size="15" font-weight="600">${escape(fig.title)}</text>"""
    if fig.subtitle.nonEmpty then
      val sy = if fig.title.nonEmpty then 42 else 24
      sb ++= s"""<text x="${fmt(left)}" y="$sy" style="fill:${paint(Paint.Secondary)}" font-size="12">${escape(
          fig.subtitle
        )}</text>"""

    // grid, ticks, axes
    val xticks = fig.x.ticks.getOrElse(niceTicks(x0, x1).map(t => (t, number(t))))
    val yticks = fig.y.ticks.getOrElse(niceTicks(y0, y1).map(t => (t, number(t))))
    val inX = xticks.filter((t, _) => t >= math.min(x0, x1) && t <= math.max(x0, x1))
    val inY = yticks.filter((t, _) => t >= math.min(y0, y1) && t <= math.max(y0, y1))
    if fig.x.visible && fig.x.grid then
      inX.foreach((t, _) =>
        sb ++= s"""<line x1="${fmt(f.px(t))}" y1="${fmt(top)}" x2="${fmt(f.px(t))}" y2="${fmt(
            top + h
          )}" style="stroke:${paint(Paint.Grid)}" stroke-width="1"/>"""
      )
    if fig.y.visible && fig.y.grid then
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
            top + h + 16
          )}" text-anchor="middle" font-size="11" style="fill:${paint(
            Paint.Muted
          )};font-variant-numeric:tabular-nums">${escape(label)}</text>"""
      )
      if fig.x.label.nonEmpty then
        sb ++= s"""<text x="${fmt(left + w / 2)}" y="${fmt(
            top + h + 36
          )}" text-anchor="middle" font-size="12" style="fill:${paint(Paint.Secondary)}">${escape(
            fig.x.label
          )}</text>"""
    if fig.y.visible then
      sb ++= s"""<line x1="${fmt(left)}" y1="${fmt(top)}" x2="${fmt(left)}" y2="${fmt(top + h)}" style="stroke:${paint(
          Paint.Axis
        )}" stroke-width="1"/>"""
      inY.foreach((t, label) =>
        sb ++= s"""<text x="${fmt(left - 6)}" y="${fmt(
            f.py(t) + 4
          )}" text-anchor="end" font-size="11" style="fill:${paint(
            Paint.Muted
          )};font-variant-numeric:tabular-nums">${escape(label)}</text>"""
      )
      if fig.y.label.nonEmpty then
        val cy = top + h / 2
        sb ++= s"""<text x="14" y="${fmt(cy)}" transform="rotate(-90 14 ${fmt(
            cy
          )})" text-anchor="middle" font-size="12" style="fill:${paint(Paint.Secondary)}">${escape(
            fig.y.label
          )}</text>"""

    // marks
    sb ++= s"""<g clip-path="url(#$id-clip)">"""
    def tip(m: Mark, i: Int): String = m.tipAt(i).fold("")(t => s"<title>${escape(t)}</title>")
    def item(open: String, m: Mark, i: Int): Unit =
      m.tipAt(i) match
        case Some(_) => sb ++= open.stripSuffix("/>") + ">" + tip(m, i) + "</" + open.drop(1).takeWhile(_ != ' ') + ">"
        case None    => sb ++= open
    fig.marks.foreach {
      case m: Mark.Discs =>
        val r = m.radius * f.scale
        m.centers.indices.foreach(i =>
          val (cx, cy) = m.centers(i)
          item(
            s"""<circle cx="${fmt(f.px(cx))}" cy="${fmt(f.py(cy))}" r="${fmt(r)}" style="fill:${paint(
                m.paintAt(i)
              )};fill-opacity:${m.opacity}"/>""",
            m,
            i
          )
        )
      case m: Mark.Boxes =>
        m.boxes.indices.foreach(i =>
          val (bx0, by0, bx1, by1) = m.boxes(i)
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
          val pts = m.corners(i).map((x, y) => s"${fmt(f.px(x))},${fmt(f.py(y))}").mkString(" ")
          item(s"""<polygon points="$pts" style="fill:${paint(m.paintAt(i))};fill-opacity:${m.opacity}"/>""", m, i)
        )
      case m: Mark.Segments =>
        val cap = if m.roundCaps then "round" else "butt"
        m.ends.indices.foreach(i =>
          val ((ax, ay), (bx, by)) = m.ends(i)
          item(
            s"""<line x1="${fmt(f.px(ax))}" y1="${fmt(f.py(ay))}" x2="${fmt(f.px(bx))}" y2="${fmt(
                f.py(by)
              )}" style="stroke:${paint(m.paintAt(i))};stroke-opacity:${m.opacity}" stroke-width="${fmt(
                m.width
              )}" stroke-linecap="$cap"/>""",
            m,
            i
          )
        )
      case m: Mark.Line =>
        val pts = m.at.map((x, y) => s"${fmt(f.px(x))},${fmt(f.py(y))}").mkString(" ")
        item(
          s"""<polyline points="$pts" style="fill:none;stroke:${paint(m.paint)}" stroke-width="${fmt(
              m.width
            )}" stroke-linejoin="round" stroke-linecap="round"/>""",
          m,
          0
        )
      case m: Mark.Points =>
        m.at.indices.foreach(i =>
          val (x, y) = m.at(i)
          item(marker(f.px(x), f.py(y), m.radius, m.shape, paint(m.paintAt(i)), paint(Paint.Surface)), m, i)
        )
      case m: Mark.Text =>
        val anchor = m.anchor match
          case Anchor.Start  => "start"
          case Anchor.Middle => "middle"
          case Anchor.End    => "end"
        sb ++= s"""<text x="${fmt(f.px(m.at._1) + m.dx)}" y="${fmt(
            f.py(m.at._2) + m.dy
          )}" text-anchor="$anchor" font-size="${fmt(m.size)}" style="fill:${paint(m.paint)}">${escape(
            m.text
          )}</text>"""
    }
    sb ++= "</g>"

    // legend
    var lx = left + w + 18
    if fig.legend.nonEmpty then
      fig.legend.zipWithIndex.foreach { (item, k) =>
        val cy = top + 8 + 20 * k
        val key = item.key match
          case LegendItem.Key.Dot =>
            marker(lx + 6, cy, 4.5, item.shape, paint(item.paint), paint(Paint.Surface))
          case LegendItem.Key.Line =>
            s"""<line x1="${fmt(lx)}" y1="${fmt(cy)}" x2="${fmt(lx + 14)}" y2="${fmt(cy)}" style="stroke:${paint(
                item.paint
              )}" stroke-width="2.5" stroke-linecap="round"/>"""
          case LegendItem.Key.Swatch =>
            s"""<rect x="${fmt(lx)}" y="${fmt(cy - 6)}" width="12" height="12" rx="2" style="fill:${paint(
                item.paint
              )}"/>"""
        sb ++= key
        sb ++= s"""<text x="${fmt(lx + 20)}" y="${fmt(cy + 4)}" font-size="12" style="fill:${paint(
            Paint.Secondary
          )}">${escape(item.label)}</text>"""
      }
      lx += legendWidth

    // colorbar
    fig.colorbar.foreach { cb =>
      val steps = 48
      val bh = math.min(h, 220.0)
      val bx = lx + 4
      (0 until steps).foreach { s =>
        val t = (s + 0.5) / steps
        val yTop = top + bh * (1 - (s + 1.0) / steps)
        sb ++= s"""<rect x="${fmt(bx)}" y="${fmt(yTop)}" width="12" height="${fmt(bh / steps + 0.5)}" style="fill:${cb
            .color(t)}"/>"""
      }
      sb ++= s"""<text x="${fmt(bx + 16)}" y="${fmt(top + 4)}" font-size="11" style="fill:${paint(
          Paint.Muted
        )}">${escape(number(cb.hi))}</text>"""
      sb ++= s"""<text x="${fmt(bx + 16)}" y="${fmt(top + bh + 4)}" font-size="11" style="fill:${paint(
          Paint.Muted
        )}">${escape(number(cb.lo))}</text>"""
      if cb.label.nonEmpty then
        sb ++= s"""<text x="${fmt(bx)}" y="${fmt(top + bh + 22)}" font-size="11" style="fill:${paint(
            Paint.Secondary
          )}">${escape(cb.label)}</text>"""
    }
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
