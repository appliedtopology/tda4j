package org.appliedtopology.tda4j.plot

/** Renders a [[Figure]] as one `<svg>` element: a surface, hairline grid and axes, the marks clipped to the plot area,
  * a legend and a color bar. Under `Theme.Auto` every themed color is a CSS variable of the `<svg>` (both modes
  * declared, `prefers-color-scheme` choosing); under `Light`/`Dark` every color is a plain hex.
  *
  * Positions go through the axes' [[Transform]]s here and nowhere else, so a figure holds data in its own units. What
  * is at exactly 0 on a log axis sits on a `-∞` line beside the lowest number, as an essential bar ends at `∞`. A mark
  * with no other place on the axes (a negative value on a log axis, `NaN`) is left out, and a line of small print under
  * the plot counts what was left out. The figure's [[Style]] sets the font, the size of text and marks, the gridlines,
  * the legend's place and the background.
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

  /** The title of a board, as an `<svg>` of `width` pixels to put above its panels, and its height: the text is set as
    * the title of `first`, the board's first panel (its font, size, weight, color and alignment, in its palette).
    */
  private[plot] def heading(first: Figure, text: String, width: Int, theme: Theme): (String, Int) =
    val id = s"tda4j${counter.incrementAndGet()}"
    val ts = first.style.textScale
    val face = first.elements.face(TextRole.Title)
    val size = face.size * ts
    val height = math.ceil(16.0 + 22.0 * ts * (face.size / TextRole.defaultSize(TextRole.Title))).toInt
    val pal = first.palette.withDegrees(first.degrees)
    def ink(p: Paint): String = if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)
    val (x, anchor) = face.align match
      case Anchor.Start  => (16.0, "")
      case Anchor.Middle => (width / 2.0, """ text-anchor="middle"""")
      case Anchor.End    => (width - 16.0, """ text-anchor="end"""")
    val extra = face.weight.fold("")(wt => s""" font-weight="${wt.css}"""") +
      (if face.italic then """ font-style="italic"""" else "") + face.font.fold("")(ff =>
        s""" font-family="${escape(ff)}""""
      )
    val (shown, cut) = face.fit(text, size, first.style.font)
    val svg = new StringBuilder
    svg ++= s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="$width" height="$height" viewBox="0 0 $width $height" font-family="${escape(
        first.style.font
      )}" role="img">"""
    if theme == Theme.Auto then svg ++= s"<style>${pal.cssVariables(s"#$id")}</style>"
    svg ++= s"<title>${escape(text)}</title>"
    if !first.style.transparent then
      svg ++= s"""<rect width="$width" height="$height" style="fill:${ink(Paint.Surface)}"/>"""
    svg ++= s"""<text x="${fmt(x)}" y="${fmt(9.0 + size)}"$anchor style="fill:${ink(face.paint)}" font-size="${fmt(
        size
      )}"$extra>${escape(shown)}${if cut then s"<title>${escape(text)}</title>" else ""}</text></svg>"""
    (svg.toString, height)

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
    h: Double,
    bandX: Option[Double],
    bandY: Option[Double],
    revX: Boolean = false,
    revY: Boolean = false
  ):
    // 0 on a log axis has no position of its own: it is on the `-∞` line, `bandX`/`bandY` in transformed space
    private def tx(x: Double): Double = if x == 0.0 && bandX.isDefined then bandX.get else xt(x)
    private def ty(y: Double): Double = if y == 0.0 && bandY.isDefined then bandY.get else yt(y)
    def px(x: Double): Double = left + (if revX then x1 - tx(x) else tx(x) - x0) / (x1 - x0) * w
    def py(y: Double): Double = top + h - (if revY then y1 - ty(y) else ty(y) - y0) / (y1 - y0) * h

    /** Pixels per data unit along x: the radius of a disc on a linear axis. */
    def scale: Double = w / (x1 - x0)

  /** The extent of the marks' positions along each axis, in the axes' transformed space, and whether anything sits at 0
    * on a log axis (`zeroX`, `zeroY`: the `-∞` line). Marks without a place on the axes count for nothing.
    */
  private final case class Extent(
    x: Option[(Double, Double)],
    y: Option[(Double, Double)],
    zeroX: Boolean,
    zeroY: Boolean
  )

  /** The coordinates the marks sit at. With `balls`, a ball's extent (centre plus and minus its radius) rather than its
    * centre.
    */
  private def foreachPoint(marks: Seq[Mark], balls: Boolean)(f: XY => Unit): Unit = marks.foreach {
    case m: Mark.Points   => m.at.foreach(f)
    case m: Mark.Segments => m.ends.foreach { (a, b) => f(a); f(b) }
    case m: Mark.Polygons => m.corners.foreach(_.foreach(f))
    case m: Mark.Discs    =>
      m.centers.foreach { c =>
        if balls then
          f((c._1 - m.radius, c._2 - m.radius)); f((c._1 + m.radius, c._2 + m.radius))
        else f(c)
      }
    case m: Mark.Boxes                   => m.boxes.foreach { b => f((b._1, b._2)); f((b._3, b._4)) }
    case m: Mark.Line                    => m.at.foreach(f)
    case m: Mark.Text                    => f(m.at)
    case _: Mark.Diagonal | _: Mark.Rule => ()
  }

  /** What an axis has to place: whether any value is negative, and the smallest nonzero magnitude. */
  private final case class Signs(negative: Boolean, magnitude: Option[Double])

  private def signs(marks: Seq[Mark]): (Signs, Signs) =
    var (negX, negY) = (false, false)
    var (magX, magY) = (Double.PositiveInfinity, Double.PositiveInfinity)
    foreachPoint(marks, balls = false) { p =>
      if p._1 < 0 then negX = true
      if p._1.isFinite && p._1 != 0.0 then magX = math.min(magX, math.abs(p._1))
      if p._2 < 0 then negY = true
      if p._2.isFinite && p._2 != 0.0 then magY = math.min(magY, math.abs(p._2))
    }
    (Signs(negX, Option.when(magX.isFinite)(magX)), Signs(negY, Option.when(magY.isFinite)(magY)))

  /** The transform an axis is drawn with: its own, except that a log axis with a negative value in the marks has no
    * place for it and is drawn as a symmetric log, linear within the smallest nonzero magnitude on the axis, so that
    * nothing is left out. The plot says so. (A limit alone changes nothing: a plot pads its own lower bound below
    * zero.)
    */
  private def resolve(a: Axis, sg: Signs): Transform = a.transform match
    case Transform.Log10 if sg.negative => Transform.SymLog(sg.magnitude.getOrElse(1.0))
    case other                          => other

  /** The transforms the axes are drawn with (see `resolve`). */
  private[plot] def resolved(fig: Figure): (Transform, Transform) =
    val (sx, sy) = signs(fig.marks)
    (resolve(fig.x, sx), resolve(fig.y, sy)) match
      // two axes that were both log and both switched share a stretch, so one scale reads alike along both (and a
      // diagram's diagonal stays a line)
      case (Transform.SymLog(a), Transform.SymLog(b))
          if fig.x.transform == Transform.Log10 && fig.y.transform == Transform.Log10 =>
        val c = math.min(a, b)
        (Transform.SymLog(c), Transform.SymLog(c))
      case both => both

  /** Zero is a place on this axis: the `-∞` line, unless the axis opted out. Only a log axis has one. */
  private def zeroOk(a: Axis, tr: Transform): Boolean = tr == Transform.Log10 && !a.minusInfinity.contains(false)

  private def extent(marks: Seq[Mark], xt: Transform, yt: Transform, zeroOkX: Boolean, zeroOkY: Boolean): Extent =
    val xs = scala.collection.mutable.ArrayBuffer.empty[Double]
    val ys = scala.collection.mutable.ArrayBuffer.empty[Double]
    var (zx, zy) = (false, false)
    def add(p: XY): Unit =
      val (u, v) = (xt(p._1), yt(p._2))
      val (atZeroX, atZeroY) = (zeroOkX && p._1 == 0.0, zeroOkY && p._2 == 0.0)
      if (u.isFinite || atZeroX) && (v.isFinite || atZeroY) then
        if u.isFinite then xs += u else zx = true
        if v.isFinite then ys += v else zy = true
    foreachPoint(marks, balls = true)(add)
    Extent(
      Option.when(xs.nonEmpty)((xs.min, xs.max)),
      Option.when(ys.nonEmpty)((ys.min, ys.max)),
      zx,
      zy
    )

  private def padded(lo: Double, hi: Double, fraction: Double): (Double, Double) =
    if hi > lo then (lo - (hi - lo) * fraction, hi + (hi - lo) * fraction)
    else (lo - 1, hi + 1)

  /** The visible range of an axis in transformed space: the ends the axis fixes (those the transform can place), the
    * rest from the marks' extent `lo..hi`, padded.
    */
  private def spaceRange(a: Axis, tr: Transform, seen: Option[(Double, Double)]): (Double, Double) =
    val (lo, hi) = seen.getOrElse((0.0, 1.0))
    val fixedLo = a.min.map(tr(_)).filter(_.isFinite)
    val fixedHi = a.max.map(tr(_)).filter(_.isFinite)
    (fixedLo, fixedHi) match
      case (Some(l), Some(h)) => (l, h)
      case _                  =>
        val (pl, pu) = padded(lo, hi, a.padding.getOrElse(0.04))
        (fixedLo.getOrElse(pl), fixedHi.getOrElse(pu))

  private def checkRange(name: String, tr: Transform, lo: Double, hi: Double): Unit =
    if !(hi > lo) then
      throw IllegalArgumentException(
        s"the $name axis would run from ${number(tr.inverse(lo))} to ${number(tr.inverse(hi))}: " +
          s"its minimum must be less than its maximum (see Scale.$name)"
      )

  /** The ranges of numbers the axes show, in data units, before any adjustment for an equal aspect ratio and without
    * the room for the `-∞` line.
    */
  private[plot] def ranges(fig: Figure): ((Double, Double), (Double, Double)) =
    val (xt, yt) = resolved(fig)
    val ext = extent(fig.marks, xt, yt, zeroOk(fig.x, xt), zeroOk(fig.y, yt))
    val (x0, x1) = spaceRange(fig.x, xt, ext.x)
    val (y0, y1) = spaceRange(fig.y, yt, ext.y)
    ((xt.inverse(x0), xt.inverse(x1)), (yt.inverse(y0), yt.inverse(y1)))

  /** Whether the figure has marks at 0 on its x axis and on its y axis, which a log axis puts on a `-∞` line. */
  private[plot] def atMinusInfinity(fig: Figure): (Boolean, Boolean) =
    val (xt, yt) = resolved(fig)
    val ext = extent(fig.marks, xt, yt, zeroOk(fig.x, xt), zeroOk(fig.y, yt))
    (ext.zeroX, ext.zeroY)

  def render(fig: Figure, theme: Theme): String =
    val id = s"tda4j${counter.incrementAndGet()}"
    val style = fig.style
    val ts = style.textScale
    val ink = style.markScale
    val pal = fig.palette.withDegrees(fig.degrees)
    val (xt, yt) = resolved(fig)
    val (signsX, signsY) = signs(fig.marks)
    val sb = new StringBuilder

    // The look of each kind of text, and the scale of the layout that was written for its default size: `px` is the size
    // a text is drawn at, `sc` that over the default's, so a margin made for 11-pixel tick labels follows the tick labels.
    val el = fig.elements
    val faces = TextRole.values.map(r => r -> el.face(r)).toMap
    def face(r: TextRole): TextFace = faces(r)
    def px(r: TextRole): Double = face(r).size * ts
    def sc(r: TextRole): Double = ts * (face(r).size / TextRole.defaultSize(r))
    // what follows `font-size` of a text: its weight, slant and font, only where the plot sets them
    def extra(r: TextRole): String =
      val f = face(r)
      f.weight.fold("")(wt => s""" font-weight="${wt.css}"""") + (if f.italic then """ font-style="italic"""" else "") +
        f.font.fold("")(ff => s""" font-family="${escape(ff)}"""")

    // a text's content, cut to the role's limit (the whole text is then its tooltip), and a number as the plot writes them
    def body(r: TextRole, text: String): String =
      val (shown, cut) = face(r).fit(text, px(r), style.font)
      escape(shown) + (if cut then s"<title>${escape(text)}</title>" else "")
    def loc(text: String): String = el.numbers(text)

    def paint(p: Paint): String = p match
      case Paint.Value(v) =>
        fig.colorScale match
          case Some(c) => c.hex(v, pal)
          case None    =>
            throw IllegalArgumentException(
              "a mark is colored by a data value (Paint.Value) but the figure has no colorScale"
            )
      case _ => if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)

    // a marker as it is drawn: the mark's own shape and radius, then the figure's markers, then its degree's
    val faces2 = scala.collection.mutable.Map.empty[(Shape, Double, Paint), MarkerFace]
    def faceOf(shape: Shape, radius: Double, own: Paint): MarkerFace =
      faces2.getOrElseUpdate(
        (shape, radius, own), {
          val degree = own match
            case Paint.Series(k) => fig.degrees.get(k)
            case _               => None
          val layers = Seq(el.marker) ++ degree.toSeq.flatMap(d => Seq(MarkerStyle(shape = d.shape), d.marker))
          MarkerStyle.resolve(shape, radius, own, layers)
        }
      )
    val legend = fig.legend.map { item =>
      item.paint match
        case Paint.Series(k) =>
          val d = fig.degrees.get(k)
          item.copy(label = d.flatMap(_.label).getOrElse(item.label), shape = d.flatMap(_.shape).getOrElse(item.shape))
        case _ => item
    }

    // the marks that cannot be placed on these axes are left out, and counted; 0 on a log axis is on the `-∞` line
    val (zeroOkX, zeroOkY) = (zeroOk(fig.x, xt), zeroOk(fig.y, yt))
    def okAt(t: Transform, zero: Boolean)(v: Double): Boolean = t(v).isFinite || (zero && v == 0.0)
    def ok(p: XY): Boolean = okAt(xt, zeroOkX)(p._1) && okAt(yt, zeroOkY)(p._2)
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

    // the range shown
    val ext = extent(fig.marks, xt, yt, zeroOkX, zeroOkY)
    val (rangeX, rangeY) = (spaceRange(fig.x, xt, ext.x), spaceRange(fig.y, yt, ext.y))
    var (x0, x1) = rangeX
    var (y0, y1) = rangeY
    checkRange("x", xt, x0, x1)
    checkRange("y", yt, y0, y1)
    // where an axis crosses the other: at the value asked for when the other axis shows it (the range before an equal
    // aspect widens it, so that the margins do not depend on the plot area), else at the edge
    def shows(tr: Transform, range: (Double, Double))(v: Double): Boolean =
      val u = tr(v)
      u.isFinite && u >= math.min(range._1, range._2) && u <= math.max(range._1, range._2)
    val xAxisAt = fig.x.crossesAt.filter(shows(yt, rangeY)) // a value of y
    val yAxisAt = fig.y.crossesAt.filter(shows(xt, rangeX)) // a value of x

    // layout: the margins make room for the title, the axes, the legend and the color bar
    val legendPlace =
      if legend.isEmpty then LegendPosition.Hidden else style.legend.toOption.getOrElse(fig.legendPlace)
    val (sTick, sAxis, sLegend, sLegendTitle) =
      (sc(TextRole.TickLabel), sc(TextRole.AxisLabel), sc(TextRole.Legend), sc(TextRole.LegendTitle))
    val sLegends = math.max(sLegend, sLegendTitle)
    val legendChars = (legend.map(_.label.length) :+ fig.legendTitle.length).max.min(24)
    // what a legend text takes as drawn (cut at its limit), in pixels, and the most a legend makes room for: 7.5 pixels a
    // character for 30 characters. A legend is never narrower than 7.5 pixels for each of its first 24 characters.
    def textWidth(role: TextRole, text: String): Double =
      val f = face(role)
      TextWidth.of(
        f.fit(text, px(role), style.font)._1,
        px(role),
        TextWidth.Family.of(f.font.getOrElse(style.font)),
        f.weight.exists(_.css >= 600)
      )
    val mostText = 7.5 * 30 * sLegends
    val legendTexts =
      (legend
        .map(item => textWidth(TextRole.Legend, item.label)) :+ textWidth(TextRole.LegendTitle, fig.legendTitle)).max
        .min(mostText)
    def itemWidth(item: LegendItem): Double =
      math.max(
        (30.0 + 7.5 * item.label.length.min(24)) * sLegend,
        30.0 * sLegend + math.min(textWidth(TextRole.Legend, item.label), mostText)
      )
    val titleWidth =
      if fig.legendTitle.isEmpty then 0.0
      else
        math.max(
          (14.0 + 7.5 * fig.legendTitle.length.min(24)) * sLegendTitle,
          14.0 * sLegendTitle + math.min(textWidth(TextRole.LegendTitle, fig.legendTitle), mostText)
        )
    // a box behind the legend (room inside it grows with the text), and the entries in the columns asked for, a column
    // filled before the next: the first column is the tallest
    val boxed = legendPlace != LegendPosition.Hidden && el.legendBoxDrawn
    val boxPad = if boxed then el.legendPadding * sLegends else 0.0
    val columnsAsked = el.legendBox.columns.getOrElse(1)
    def inColumns(n: Int): Vector[Vector[LegendItem]] =
      if n <= 1 || legend.size <= 1 then Vector(legend.toVector)
      else legend.toVector.grouped(math.ceil(legend.size.toDouble / n).toInt).toVector
    def pitch(column: Vector[LegendItem]): Double = column.map(itemWidth).maxOption.getOrElse(0.0)
    // as many of the columns asked for as fit in `room` pixels; one always does
    def columnsIn(room: Double): Vector[Vector[LegendItem]] =
      (columnsAsked to 2 by -1).iterator
        .map(inColumns)
        .find(columns => columns.map(pitch).sum - 10 * sLegend <= room)
        .getOrElse(inColumns(1))
    // beside the plot, a legend takes at most two fifths of the figure's width
    val sideColumns = if columnsAsked > 1 then columnsIn(0.4 * fig.width) else inColumns(1)
    // the width of the entries in `columns`, a key and its text at a time, and the title
    def legendBlock(columns: Vector[Vector[LegendItem]]): Double =
      math.max(columns.map(pitch).sum, titleWidth) - 10 * sLegend
    val legendColumn =
      if boxed then 18.0 + legendBlock(sideColumns) + 2 * boxPad
      else if columnsAsked <= 1 then math.max((34.0 + 7.5 * legendChars) * sLegends, 34.0 * sLegends + legendTexts)
      else
        math.max(
          sideColumns.map(pitch).sum + 4.0 * sLegends,
          34.0 * sLegends + math.min(textWidth(TextRole.LegendTitle, fig.legendTitle), mostText)
        )
    val legendWidth = if legendPlace == LegendPosition.Right then legendColumn else 0.0
    val legendWidthLeft = if legendPlace == LegendPosition.Left then legendColumn else 0.0
    val colorbarWidth =
      if fig.colorScale.exists(_.guide) then 74.0 * math.max(sTick, sc(TextRole.ColorTitle)) else 0.0
    // where the axes are, the tick marks that point away from the plot (they push the numbers outward), and the room
    // that tick labels slanted by `angle` need (their length is a guess from the longest label's characters)
    val xTop = fig.x.sideOr(Side.Bottom) == Side.Top
    val yRight = fig.y.sideOr(Side.Left) == Side.Right
    val (majorMarks, minorMarks) = (el.tickMarks, el.minorTickMarks)
    val markOut =
      Seq(majorMarks, minorMarks)
        .filter(m => m.drawn && m.direction == TickDirection.Out)
        .map(_.length)
        .maxOption
        .getOrElse(0.0)
    val slant =
      val angle = math.toRadians(math.abs(face(TextRole.TickLabel).angle))
      val chars = fig.x.ticks match
        case Ticks.Labelled(words) => words.map(_._2.length).maxOption.getOrElse(5)
        case _                     => 6
      if angle == 0.0 then 0.0
      else math.max(0.0, px(TextRole.TickLabel) * (chars * 0.6 * math.sin(angle) + 0.9 * math.cos(angle) - 0.9))
    // an axis through the middle of the plot has its numbers there, and needs the margin for its label alone
    val xAxisBand =
      if !fig.x.visible then 0.0
      else if xAxisAt.isDefined then if fig.x.label.nonEmpty then 16.0 * sAxis else 0.0
      else 30.0 * sTick + (if fig.x.label.nonEmpty then 16.0 * sAxis else 0.0) + markOut + slant
    val yAxisBand =
      if !fig.y.visible then 0.0
      else if yAxisAt.isDefined then if fig.y.label.nonEmpty then 16.0 * sAxis else 0.0
      else 16.0 * sAxis + 42.0 * sTick + markOut
    val head =
      16.0 + (if fig.title.nonEmpty then 22.0 * sc(TextRole.Title) else 0.0) +
        (if fig.subtitle.nonEmpty then 16.0 * sc(TextRole.Subtitle) else 0.0)
    val left = legendWidthLeft +
      (if fig.y.visible && !yRight then if yAxisAt.isDefined then math.max(14.0, yAxisBand) else yAxisBand
       else 14.0)
    val rightAxisBand = if fig.y.visible && yRight then yAxisBand else 0.0
    // what is below the plot: the axis (or a margin, when it is above or not drawn)
    val axisBand =
      if fig.x.visible && !xTop then if xAxisAt.isDefined then math.max(14.0, xAxisBand) else xAxisBand else 14.0
    val right = 16.0 + rightAxisBand + legendWidth + colorbarWidth
    val w = math.max(40.0, fig.width - left - right)

    val aboveOrBelow = legendPlace == LegendPosition.Bottom || legendPlace == LegendPosition.Top
    // a legend above or below the plot with columns asked for is a grid beside its title; without, entries fill a row
    // as far as the plot is wide
    val gridColumns = if aboveOrBelow && columnsAsked > 1 then columnsIn(w - 2 * boxPad - titleWidth) else Vector.empty
    val legendRows: Vector[Vector[LegendItem]] =
      if !aboveOrBelow then Vector.empty
      else if gridColumns.nonEmpty then Vector.tabulate(gridColumns.head.size)(r => gridColumns.flatMap(_.lift(r)))
      else
        val rows = Vector.newBuilder[Vector[LegendItem]]
        var row = Vector.empty[LegendItem]
        var used = titleWidth
        legend.foreach { item =>
          if row.nonEmpty && used + itemWidth(item) > w - 2 * boxPad then
            rows += row
            row = Vector.empty
            used = 0.0
          row = row :+ item
          used += itemWidth(item)
        }
        rows += row
        rows.result()
    val legendBand = if legendRows.isEmpty then 0.0 else (10.0 + 20.0 * legendRows.size) * sLegends + 2 * boxPad
    // the plot area starts below the head, the legend when it is above, and the x axis when it is
    val top = head + (if legendPlace == LegendPosition.Top then legendBand else 0.0) + (if xTop then xAxisBand else 0.0)
    // small print under the plot: what an axis did instead of what was asked, and what could not be drawn
    val notes: Seq[String] =
      Seq("x" -> (fig.x, xt), "y" -> (fig.y, yt))
        .collect { case (name, (a, Transform.SymLog(c))) if a.transform == Transform.Log10 => (name, c) }
        .groupMap(_._2)(_._1)
        .toSeq
        .sortBy(_._1)
        .map { (c, names) =>
          if names.size == 1 then
            s"negative values: the ${names.head} axis is a symmetric log scale, linear within ±${number(c)}"
          else
            s"negative values: the ${names.mkString(" and ")} axes are symmetric log scales, linear within ±${number(c)}"
        } ++ Option.when(notShown > 0) {
        val withoutLine = (xt == Transform.Log10 && fig.x.minusInfinity.contains(false)) ||
          (yt == Transform.Log10 && fig.y.minusInfinity.contains(false))
        val why =
          if withoutLine then "at 0 on a log axis drawn without a -∞ line" else "a position that is not a number"
        s"$notShown ${if notShown == 1 then "mark" else "marks"} not shown: $why"
      } ++ Seq(("x", fig.x.crossesAt, xAxisAt, "y"), ("y", fig.y.crossesAt, yAxisAt, "x")).collect {
        case (name, Some(v), None, other) =>
          s"the $name axis cannot cross at ${number(v)}: the $other axis does not show it, so it stays at the edge"
      }
    val noteBand = 14.0 * sc(TextRole.Note) * notes.size
    val captionBand = if fig.caption.isEmpty then 0.0 else 16.0 * sc(TextRole.Caption)
    val bottom =
      axisBand + (if legendPlace == LegendPosition.Bottom then legendBand else 0.0) + noteBand + captionBand
    val h = math.max(40.0, fig.height - top - bottom)

    // What is at 0 on a log axis goes on a `-∞` line below the lowest number; the numbers keep the pixels the line and
    // the room for a marker under it leave (`reserveX`, `reserveY`), which is also the length equal units are measured on.
    val bandOnX = zeroOkX && (ext.zeroX || fig.x.minusInfinity.contains(true))
    val bandOnY = zeroOkY && (ext.zeroY || fig.y.minusInfinity.contains(true))
    val (reserveX, reserveY) = (if bandOnX then 44.0 * ts else 0.0, if bandOnY then 44.0 * ts else 0.0)
    val (lengthX, lengthY) = (math.max(1.0, w - reserveX), math.max(1.0, h - reserveY))
    // Equal units on both axes mean something only when both are on one scale (log-log: a decade is as long either way).
    // Widening a range stops a margin below what the scale can place (nothing is below 0 on a square-root axis).
    // (`aspect` asks for another ratio of the length of a y unit to an x unit, and takes it whatever the scales)
    val aspectRatio = fig.aspect.orElse(Option.when(fig.equalAspect && xt == yt)(1.0))
    aspectRatio.foreach { ratio =>
      val sx = (x1 - x0) / lengthX
      val sy = (y1 - y0) / lengthY
      val wanted = sx / ratio // data per pixel along y, for a y unit to be `ratio` times as long as an x unit
      if sy < wanted then
        val c = (y0 + y1) / 2
        y0 = c - wanted * lengthY / 2; y1 = c + wanted * lengthY / 2
      else
        val c = (x0 + x1) / 2
        x0 = c - sy * ratio * lengthX / 2; x1 = c + sy * ratio * lengthX / 2
      val lowestX = (if signsX.negative then Double.NegativeInfinity else xt.floor) - 0.04 * (x1 - x0)
      if x0 < lowestX then
        x1 += lowestX - x0; x0 = lowestX
      val lowestY = (if signsY.negative then Double.NegativeInfinity else yt.floor) - 0.04 * (y1 - y0)
      if y0 < lowestY then
        y1 += lowestY - y0; y0 = lowestY
    }
    // where the numbers of an axis start, and the `-∞` line 34 pixels below that, with the room for a marker under it
    val (startX, startY) = (x0, y0)
    var (bandX, bandY) = (Option.empty[Double], Option.empty[Double])
    if bandOnX then
      val unit = (x1 - x0) / lengthX
      bandX = Some(x0 - 34.0 * ts * unit)
      x0 -= reserveX * unit
    if bandOnY then
      val unit = (y1 - y0) / lengthY
      bandY = Some(y0 - 34.0 * ts * unit)
      y0 -= reserveY * unit
    val f = Frame(xt, yt, x0, x1, y0, y1, left, top, w, h, bandX, bandY, fig.x.reverse, fig.y.reverse)

    sb ++= s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="${fig.width}" height="${fig.height}" viewBox="0 0 ${fig.width} ${fig.height}" font-family="${escape(
        style.font
      )}" role="img">"""
    if theme == Theme.Auto then sb ++= s"<style>${pal.cssVariables(s"#$id")}</style>"
    if fig.title.nonEmpty then sb ++= s"<title>${escape(fig.title)}</title>"
    if fig.description.nonEmpty then sb ++= s"<desc>${escape(fig.description)}</desc>"
    if !style.transparent then
      sb ++= s"""<rect width="${fig.width}" height="${fig.height}" style="fill:${paint(Paint.Surface)}"/>"""
    sb ++= s"""<defs><clipPath id="$id-clip"><rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(w)}" height="${fmt(
        h
      )}"/></clipPath></defs>"""

    // where a text of `role` starts, in the plot area's width: its x, and the attribute that anchors it there
    def alignedAt(r: TextRole): (Double, String) = face(r).align match
      case Anchor.Start  => (left, "")
      case Anchor.Middle => (left + w / 2, """ text-anchor="middle"""")
      case Anchor.End    => (left + w, """ text-anchor="end"""")

    // title and subtitle
    val titleSize = px(TextRole.Title)
    val titleBase = 9.0 + titleSize
    if fig.title.nonEmpty then
      val (tx, anchor) = alignedAt(TextRole.Title)
      sb ++= s"""<text x="${fmt(tx)}" y="${fmt(titleBase)}"$anchor style="fill:${paint(
          face(TextRole.Title).paint
        )}" font-size="${fmt(titleSize)}"${extra(TextRole.Title)}>${body(TextRole.Title, fig.title)}</text>"""
    if fig.subtitle.nonEmpty then
      val (sx, anchor) = alignedAt(TextRole.Subtitle)
      val subSize = px(TextRole.Subtitle)
      // the gap between the two baselines grows with the descent of the title and the ascent of the subtitle
      val sy =
        if fig.title.nonEmpty then titleBase + 18.0 * ts + 0.25 * (titleSize - 15.0 * ts) + 0.75 * (subSize - 12.0 * ts)
        else titleBase + (subSize - 12.0 * ts)
      sb ++= s"""<text x="${fmt(sx)}" y="${fmt(sy)}"$anchor style="fill:${paint(
          face(TextRole.Subtitle).paint
        )}" font-size="${fmt(subSize)}"${extra(TextRole.Subtitle)}>${body(TextRole.Subtitle, fig.subtitle)}</text>"""

    // the plot area's own fill
    el.panel.foreach { (fill, opacity) =>
      sb ++= s"""<rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(w)}" height="${fmt(h)}" style="fill:${paint(
          fill
        )};fill-opacity:$opacity"/>"""
    }

    // a line as drawn: its color and opacity, width and dash
    def stroke(s: LineStroke): String =
      val opacity = if s.opacity == 1.0 then "" else s";stroke-opacity:${s.opacity}"
      s"""style="stroke:${paint(s.paint)}$opacity" stroke-width="${fmt(s.width)}"${s.dash.pattern.fold("")(d =>
          s""" stroke-dasharray="$d""""
        )}"""
    def line(x1: Double, y1: Double, x2: Double, y2: Double, s: LineStroke): String =
      s"""<line x1="${fmt(x1)}" y1="${fmt(y1)}" x2="${fmt(x2)}" y2="${fmt(y2)}" ${stroke(s)}/>"""

    // grid, ticks, axes
    def mainTicks(a: Axis, tr: Transform, t0: Double, t1: Double): IndexedSeq[(Double, String)] =
      val text: Double => String = a.format match
        case Some(pattern) => v => RootText.format(pattern, Double.box(v))
        case None          => tr.tickLabel
      val seen = (tr.inverse(math.min(t0, t1)), tr.inverse(math.max(t0, t1)))
      // an automatic tick keeps clear of an extra one (the `∞`), so that the two labels never overprint
      def clear(v: Double): Boolean = a.extra.forall((at, _) => math.abs(tr(v) - tr(at)) >= 0.05 * math.abs(t1 - t0))
      a.ticks match
        case Ticks.Auto           => tr.ticks(seen._1, seen._2).filter(clear).map(v => (v, text(v)))
        case Ticks.Within(lo, hi) =>
          tr.ticks(math.max(lo, seen._1), math.min(hi, seen._2)).filter(clear).map(v => (v, text(v)))
        case Ticks.At(positions)   => positions.map(v => (v, text(v)))
        case Ticks.Labelled(words) => words
    def within(tr: Transform, t0: Double, t1: Double)(tick: (Double, String)): Boolean =
      val u = tr(tick._1)
      u >= math.min(t0, t1) && u <= math.max(t0, t1)
    val mainX = mainTicks(fig.x, xt, startX, x1)
    val mainY = mainTicks(fig.y, yt, startY, y1)
    val inX = (mainX ++ fig.x.extra).filter(within(xt, x0, x1))
    val inY = (mainY ++ fig.y.extra).filter(within(yt, y0, y1))
    // the minor ticks, between the major ones; a labelled axis (the rows of a barcode) has none
    def minorOf(a: Axis, tr: Transform, main: IndexedSeq[(Double, String)], t0: Double, t1: Double): Seq[Double] =
      a.ticks match
        case Ticks.Labelled(_) => Seq.empty
        case _                 =>
          minorPositions(tr, main.map(_._1), (tr.inverse(math.min(t0, t1)), tr.inverse(math.max(t0, t1))))
            .filter(v => within(tr, t0, t1)((v, "")))
    val minorX = minorOf(fig.x, xt, mainX, startX, x1)
    val minorY = minorOf(fig.y, yt, mainY, startY, y1)
    val (gridStroke, minorGridStroke, axisStroke) = (el.gridStroke, el.minorGridStroke, el.axisStroke)
    // the axis lines: at the edge of the plot area, or through the value asked for
    val xEdgeY = if xTop then top else top + h
    val yEdgeX = if yRight then left + w else left
    val xAxisY = xAxisAt.fold(xEdgeY)(f.py)
    val yAxisX = yAxisAt.fold(yEdgeX)(f.px)
    // a tick mark of `length` pixels on an axis, pointing out of the plot or into it
    def markEnd(axisAt: Double, outSign: Double, m: TickMarks): Double =
      axisAt + outSign * m.length * (if m.direction == TickDirection.Out then 1.0 else -1.0)
    val (xOutSign, yOutSign) = (if xTop then -1.0 else 1.0, if yRight then 1.0 else -1.0)
    val tickSize = fmt(px(TextRole.TickLabel))
    val tickFill = paint(face(TextRole.TickLabel).paint)
    def drawGrid(positions: Seq[Double], s: LineStroke, vertical: Boolean): Unit =
      if s.visible && style.grid then
        positions.foreach(t =>
          sb ++= (
            if vertical then line(f.px(t), top, f.px(t), top + h, s)
            else line(left, f.py(t), left + w, f.py(t), s)
          )
        )
    if fig.x.visible && fig.x.grid then
      drawGrid(minorX, minorGridStroke, vertical = true)
      drawGrid(inX.map(_._1), gridStroke, vertical = true)
    if fig.y.visible && fig.y.grid then
      drawGrid(minorY, minorGridStroke, vertical = false)
      drawGrid(inY.map(_._1), gridStroke, vertical = false)
    if fig.x.visible then
      if axisStroke.visible then sb ++= line(left, xAxisY, left + w, xAxisY, axisStroke)
      if majorMarks.drawn then
        inX.foreach((t, _) =>
          sb ++= line(f.px(t), xAxisY, f.px(t), markEnd(xAxisY, xOutSign, majorMarks), majorMarks.stroke)
        )
      if minorMarks.drawn then
        minorX.foreach(t =>
          sb ++= line(f.px(t), xAxisY, f.px(t), markEnd(xAxisY, xOutSign, minorMarks), minorMarks.stroke)
        )
      // the numbers sit below the axis, or above it; slanted ones end (or start) at their tick
      val tickAngle = face(TextRole.TickLabel).angle
      val labelY = if xTop then xAxisY - markOut - 8.0 * sTick else xAxisY + markOut + 16.0 * sTick
      val tickAnchor =
        if tickAngle == 0.0 then "middle" else if xTop == (tickAngle < 0) then "end" else "start"
      def tickText(at: Double, label: String): String =
        val turn =
          if tickAngle == 0.0 then "" else s""" transform="rotate(${fmt(-tickAngle)} ${fmt(at)} ${fmt(labelY)})""""
        s"""<text x="${fmt(at)}" y="${fmt(labelY)}"$turn text-anchor="$tickAnchor" font-size="$tickSize"${extra(
            TextRole.TickLabel
          )} style="fill:$tickFill;font-variant-numeric:tabular-nums">${body(TextRole.TickLabel, loc(label))}</text>"""
      inX.foreach((t, label) => sb ++= tickText(f.px(t), label))
      if fig.x.label.nonEmpty then
        val (lx, anchor) = face(TextRole.AxisLabel).align match
          case Anchor.Start  => (left, "start")
          case Anchor.Middle => (left + w / 2, "middle")
          case Anchor.End    => (left + w, "end")
        val labelBase =
          if xAxisAt.isDefined then if xTop then xEdgeY - 4.0 * sAxis else xEdgeY + 12.0 * sAxis
          else if xTop then xAxisY - markOut - 30.0 * sTick - slant + 0.7 * sAxis
          else xAxisY + markOut + 30.0 * sTick + 6.0 * sAxis + slant
        sb ++= s"""<text x="${fmt(lx)}" y="${fmt(labelBase)}" text-anchor="$anchor" font-size="${fmt(
            px(TextRole.AxisLabel)
          )}"${extra(TextRole.AxisLabel)} style="fill:${paint(face(TextRole.AxisLabel).paint)}">${body(
            TextRole.AxisLabel,
            fig.x.label
          )}</text>"""
    if fig.y.visible then
      if axisStroke.visible then sb ++= line(yAxisX, top, yAxisX, top + h, axisStroke)
      if majorMarks.drawn then
        inY.foreach((t, _) =>
          sb ++= line(yAxisX, f.py(t), markEnd(yAxisX, yOutSign, majorMarks), f.py(t), majorMarks.stroke)
        )
      if minorMarks.drawn then
        minorY.foreach(t =>
          sb ++= line(yAxisX, f.py(t), markEnd(yAxisX, yOutSign, minorMarks), f.py(t), minorMarks.stroke)
        )
      val numberX = if yRight then yAxisX + markOut + 6.0 * sTick else yAxisX - markOut - 6.0 * sTick
      val numberAnchor = if yRight then "start" else "end"
      inY.foreach((t, label) =>
        sb ++= s"""<text x="${fmt(numberX)}" y="${fmt(
            f.py(t) + 4 * sTick
          )}" text-anchor="$numberAnchor" font-size="$tickSize"${extra(
            TextRole.TickLabel
          )} style="fill:$tickFill;font-variant-numeric:tabular-nums">${body(TextRole.TickLabel, loc(label))}</text>"""
      )
      if fig.y.label.nonEmpty then
        val (cy, anchor) = face(TextRole.AxisLabel).align match
          case Anchor.Start  => (top + h, "start")
          case Anchor.Middle => (top + h / 2, "middle")
          case Anchor.End    => (top, "end")
        val lx =
          if yAxisAt.isDefined then if yRight then yEdgeX + 12.0 * sAxis else yEdgeX - 4.0 * sAxis
          else if yRight then yAxisX + markOut + 42.0 * sTick + 2.0 * sAxis
          else yAxisX - markOut - (42.0 * sTick + 2.0 * sAxis)
        sb ++= s"""<text x="${fmt(lx)}" y="${fmt(cy)}" transform="rotate(-90 ${fmt(lx)} ${fmt(
            cy
          )})" text-anchor="$anchor" font-size="${fmt(px(TextRole.AxisLabel))}"${extra(
            TextRole.AxisLabel
          )} style="fill:${paint(
            face(TextRole.AxisLabel).paint
          )}">${body(TextRole.AxisLabel, fig.y.label)}</text>"""

    // the `-∞` line, as the `∞` line of a diagram: where what is at 0 on a log axis sits
    val minusInfinity = number(Double.NegativeInfinity)
    bandX.foreach { _ =>
      val bx = f.px(0.0)
      sb ++= s"""<line x1="${fmt(bx)}" y1="${fmt(top)}" x2="${fmt(bx)}" y2="${fmt(top + h)}" style="stroke:${paint(
          Paint.Axis
        )}" stroke-width="1"/>"""
      if fig.x.visible then
        if majorMarks.drawn then sb ++= line(bx, xAxisY, bx, markEnd(xAxisY, xOutSign, majorMarks), majorMarks.stroke)
        val labelY = if xTop then xAxisY - markOut - 8.0 * sTick else xAxisY + markOut + 16.0 * sTick
        sb ++= s"""<text x="${fmt(bx)}" y="${fmt(
            labelY
          )}" text-anchor="middle" font-size="$tickSize"${extra(
            TextRole.TickLabel
          )} style="fill:$tickFill;font-variant-numeric:tabular-nums">${loc(minusInfinity)}</text>"""
    }
    bandY.foreach { _ =>
      val by = f.py(0.0)
      sb ++= s"""<line x1="${fmt(left)}" y1="${fmt(by)}" x2="${fmt(left + w)}" y2="${fmt(by)}" style="stroke:${paint(
          Paint.Axis
        )}" stroke-width="1"/>"""
      if fig.y.visible then
        if majorMarks.drawn then sb ++= line(yAxisX, by, markEnd(yAxisX, yOutSign, majorMarks), by, majorMarks.stroke)
        val numberX = if yRight then yAxisX + markOut + 6.0 * sTick else yAxisX - markOut - 6.0 * sTick
        sb ++= s"""<text x="${fmt(numberX)}" y="${fmt(
            by + 4 * sTick
          )}" text-anchor="${if yRight then "start" else "end"}" font-size="$tickSize"${extra(
            TextRole.TickLabel
          )} style="fill:$tickFill;font-variant-numeric:tabular-nums">${loc(minusInfinity)}</text>"""
    }

    // marks
    sb ++= s"""<g clip-path="url(#$id-clip)">"""
    def tip(m: Mark, i: Int): String = m.tipAt(i).fold("")(t => s"<title>${escape(t)}</title>")
    def item(open: String, m: Mark, i: Int): Unit =
      m.tipAt(i) match
        case Some(_) if open.endsWith("/>") =>
          sb ++= open.stripSuffix("/>") + ">" + tip(m, i) + "</" + open.drop(1).takeWhile(_ != ' ') + ">"
        case Some(_) => sb ++= "<g>" + tip(m, i) + open + "</g>" // a text, which has content of its own
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
            // an axis that runs backwards puts the larger value first, so the corners are sorted on the page
            val (ax, bx) = (f.px(bx0), f.px(bx1))
            val (ay, by) = (f.py(by0), f.py(by1))
            val (px0, px1) = (math.min(ax, bx), math.max(ax, bx))
            val (py0, py1) = (math.min(ay, by), math.max(ay, by))
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
            val face = faceOf(m.shape, m.radius, m.paintAt(i))
            item(marker(f.px(x), f.py(y), face.size * ink, face, paint(m.paintAt(i)), paint(face.edge.paint)), m, i)
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
        val placed = if m.vertical then okAt(xt, zeroOkX)(m.at) else okAt(yt, zeroOkY)(m.at)
        if placed then
          val (x1p, y1p, x2p, y2p) =
            if m.vertical then (f.px(m.at), top, f.px(m.at), top + h)
            else (left, f.py(m.at), left + w, f.py(m.at))
          sb ++= s"""<line x1="${fmt(x1p)}" y1="${fmt(y1p)}" x2="${fmt(x2p)}" y2="${fmt(y2p)}" style="stroke:${paint(
              m.paint
            )}" stroke-width="${fmt(m.width * ink)}"${dash(m.dashed)}/>"""
      case m: Mark.Diagonal =>
        // t runs over the values for which both x = t and y = t + offset lie on the plot
        val (xa, xb) = (xt.inverse(startX), xt.inverse(x1))
        val (ya, yb) = (yt.inverse(startY), yt.inverse(y1))
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
              // with an axis running backwards the plot is its mirror image: shade in the usual one, and mirror back
              def mirror(p: XY): XY =
                (
                  if fig.x.reverse then 2 * left + w - p._1 else p._1,
                  if fig.y.reverse then 2 * top + h - p._2 else p._2
                )
              val usual = line.map(mirror)
              val corners =
                Seq((left + w + far, top - far), (left + w + far, top + h + far), (usual.head._1, top + h + far))
              val pts = (usual ++ corners).map(mirror).map((px, py) => s"${fmt(px)},${fmt(py)}").mkString(" ")
              sb ++= s"""<polygon points="$pts" style="fill:${paint(shade)};fill-opacity:${m.shadeOpacity}"/>"""
            }
            val pts = line.map((px, py) => s"${fmt(px)},${fmt(py)}").mkString(" ")
            sb ++= s"""<polyline points="$pts" style="fill:none;stroke:${paint(m.paint)}" stroke-width="${fmt(
                m.width * ink
              )}"${dash(m.dashed)}/>"""
    }
    sb ++= "</g>"
    // the frame, over the marks
    val frameStroke = el.frameStroke
    if frameStroke.visible then
      sb ++= s"""<rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(w)}" height="${fmt(h)}" fill="none" ${stroke(
          frameStroke
        )}/>"""

    // legend: beside the plot, or in rows below it
    def legendKey(item: LegendItem, lx: Double, cy: Double): String = item.key match
      case LegendItem.Key.Dot =>
        val face = faceOf(item.shape, 4.5, item.paint)
        marker(lx + 6 * sLegend, cy, 4.5 * sLegend, face, paint(item.paint), paint(face.edge.paint))
      case LegendItem.Key.Line =>
        s"""<line x1="${fmt(lx)}" y1="${fmt(cy)}" x2="${fmt(lx + 14 * sLegend)}" y2="${fmt(cy)}" style="stroke:${paint(
            item.paint
          )}" stroke-width="${fmt(2.5 * sLegend)}" stroke-linecap="round"/>"""
      case LegendItem.Key.Swatch =>
        s"""<rect x="${fmt(lx)}" y="${fmt(cy - 6 * sLegend)}" width="${fmt(12 * sLegend)}" height="${fmt(
            12 * sLegend
          )}" rx="2" style="fill:${paint(
            item.paint
          )}"/>"""
    def legendText(text: String, lx: Double, cy: Double, role: TextRole): String =
      s"""<text x="${fmt(lx)}" y="${fmt(cy + 4 * sc(role))}" font-size="${fmt(px(role))}"${extra(
          role
        )} style="fill:${paint(face(role).paint)}">${body(role, text)}</text>"""
    var lx = left + w + rightAxisBand + 18
    // the box behind the entries, if there is one: its fill, then its frame
    def legendBackdrop(x: Double, y: Double, width: Double, height: Double): Unit =
      if boxed then
        val rect = s"""x="${fmt(x)}" y="${fmt(y)}" width="${fmt(width)}" height="${fmt(height)}" rx="3""""
        el.legendBox.fill.foreach { (fill, opacity) =>
          sb ++= s"""<rect $rect style="fill:${paint(fill)};fill-opacity:$opacity"/>"""
        }
        val frame = el.legendFrameStroke
        if frame.visible then sb ++= s"""<rect $rect fill="none" ${stroke(frame)}/>"""
    // the entries in columns from `x`, the title's line (if there is a title) centred at `firstCy` over them
    def legendList(columns: Vector[Vector[LegendItem]], x: Double, firstCy: Double): Unit =
      val titleRows = if fig.legendTitle.isEmpty then 0 else 1
      if titleRows == 1 then sb ++= legendText(fig.legendTitle, x, firstCy, TextRole.LegendTitle)
      var cx = x
      columns.foreach { column =>
        column.zipWithIndex.foreach { (entry, k) =>
          val cy = firstCy + 20 * sLegends * (k + titleRows)
          sb ++= legendKey(entry, cx, cy)
          sb ++= legendText(entry.label, cx + 20 * sLegend, cy, TextRole.Legend)
        }
        cx += pitch(column)
      }
    // the entries in a box (if any) with its top left corner at `boxX`, `boxY`
    def legendBoxedList(columns: Vector[Vector[LegendItem]], boxX: Double, boxY: Double): Unit =
      val rows = columns.head.size + (if fig.legendTitle.isEmpty then 0 else 1)
      legendBackdrop(boxX, boxY, legendBlock(columns) + 2 * boxPad, 20 * sLegends * rows + 2 * boxPad)
      legendList(columns, boxX + boxPad, boxY + boxPad + 10 * sLegends)
    // the entries in rows, from `x` along `y0`
    def legendRowsAt(y0: Double): Unit =
      val step: (Int, LegendItem) => Double =
        if gridColumns.nonEmpty then (c, _) => pitch(gridColumns(c)) else (_, entry) => itemWidth(entry)
      // in a grid the rows after the first keep clear of the title's column
      val titleShift = if gridColumns.nonEmpty then titleWidth else 0.0
      def extent(row: Vector[LegendItem], r: Int): Double =
        (if r == 0 then titleWidth else titleShift) + row.zipWithIndex.map((entry, c) => step(c, entry)).sum -
          10 * sLegend
      legendBackdrop(
        left,
        y0,
        legendRows.zipWithIndex.map(extent).maxOption.getOrElse(0.0) + 2 * boxPad,
        20 * sLegends * legendRows.size + 2 * boxPad
      )
      legendRows.zipWithIndex.foreach { (row, r) =>
        val cy = y0 + boxPad + 10 * sLegends + 20 * sLegends * r
        var x = left + boxPad
        if r == 0 && fig.legendTitle.nonEmpty then
          sb ++= legendText(fig.legendTitle, x, cy, TextRole.LegendTitle)
          x += titleWidth
        else x += titleShift
        row.zipWithIndex.foreach { (entry, c) =>
          sb ++= legendKey(entry, x, cy)
          sb ++= legendText(entry.label, x + 20 * sLegend, cy, TextRole.Legend)
          x += step(c, entry)
        }
      }
    legendPlace match
      case LegendPosition.Right =>
        if boxed then legendBoxedList(sideColumns, lx, top)
        else legendList(sideColumns, lx, top + 8 * sLegends)
        lx += legendWidth
      case LegendPosition.Left =>
        if boxed then legendBoxedList(sideColumns, 14.0, top) else legendList(sideColumns, 14.0, top + 8 * sLegends)
      case LegendPosition.Bottom => legendRowsAt(top + h + axisBand)
      case LegendPosition.Top    => legendRowsAt(head)
      case LegendPosition.Hidden => ()
      case corner                =>
        // inside the plot area, at a corner, clear of its edges
        val pad = 8 * sLegends
        val columns = if columnsAsked > 1 then columnsIn(w - 2 * pad - 2 * boxPad) else inColumns(1)
        val rowCount = columns.head.size + (if fig.legendTitle.isEmpty then 0 else 1)
        val blockWidth = legendBlock(columns)
        val onRight = corner == LegendPosition.UpperRight || corner == LegendPosition.LowerRight
        val onTop = corner == LegendPosition.UpperLeft || corner == LegendPosition.UpperRight
        if boxed then
          val boxWidth = blockWidth + 2 * boxPad
          val boxHeight = 20 * sLegends * rowCount + 2 * boxPad
          legendBoxedList(
            columns,
            if onRight then left + w - pad - boxWidth else left + pad,
            if onTop then top + pad else top + h - pad - boxHeight
          )
        else
          val x = if onRight then left + w - pad - blockWidth else left + pad
          val firstCy =
            if onTop then top + pad + 8 * sLegends else top + h - pad - 8 * sLegends - 20 * sLegends * (rowCount - 1)
          legendList(columns, x, firstCy)

    // color bar
    fig.colorScale.filter(_.guide).foreach { cb =>
      val steps = 48
      val bh = math.min(h, 220.0 * ts)
      val bx = lx + 4
      val numberStyle = s"""font-size="$tickSize"${extra(TextRole.TickLabel)} style="fill:$tickFill""""
      (0 until steps).foreach { s =>
        val t = (s + 0.5) / steps
        val yTop = top + bh * (1 - (s + 1.0) / steps)
        sb ++= s"""<rect x="${fmt(bx)}" y="${fmt(yTop)}" width="${fmt(12 * ts)}" height="${fmt(
            bh / steps + 0.5
          )}" style="fill:${cb.barColor(t, pal)}"/>"""
      }
      sb ++= s"""<text x="${fmt(bx + 16 * ts)}" y="${fmt(top + 4 * sTick)}" $numberStyle>${escape(
          loc(number(cb.hi))
        )}</text>"""
      sb ++= s"""<text x="${fmt(bx + 16 * ts)}" y="${fmt(top + bh + 4 * sTick)}" $numberStyle>${escape(
          loc(number(cb.lo))
        )}</text>"""
      // the numbers between the ends of a transformed bar, top down, each clear of the one above and of the end below
      val gap = 1.5 * px(TextRole.TickLabel)
      cb.barTicks.sortBy(-_._2).foldLeft(top) { case (above, (v, t)) =>
        val at = top + bh * (1 - t)
        if at - above >= gap && top + bh - at >= gap then
          sb ++= s"""<text x="${fmt(bx + 16 * ts)}" y="${fmt(at + 4 * sTick)}" $numberStyle>${escape(
              loc(cb.transform.tickLabel(v))
            )}</text>"""
          at
        else above
      }
      if cb.label.nonEmpty then
        sb ++= s"""<text x="${fmt(bx)}" y="${fmt(top + bh + 22 * sc(TextRole.ColorTitle))}" font-size="${fmt(
            px(TextRole.ColorTitle)
          )}"${extra(TextRole.ColorTitle)} style="fill:${paint(face(TextRole.ColorTitle).paint)}">${body(
            TextRole.ColorTitle,
            cb.label
          )}</text>"""
    }

    // the small print, one line each, the last at the bottom edge
    val sNote = sc(TextRole.Note)
    val (noteX, noteAnchor) = alignedAt(TextRole.Note)
    notes.zipWithIndex.foreach { (note, k) =>
      val baseline = fig.height - captionBand - 4 * sNote - 14 * sNote * (notes.size - 1 - k)
      sb ++= s"""<text x="${fmt(noteX)}" y="${fmt(baseline)}"$noteAnchor font-size="${fmt(px(TextRole.Note))}"${extra(
          TextRole.Note
        )} style="fill:${paint(face(TextRole.Note).paint)}">${body(TextRole.Note, note)}</text>"""
    }
    if fig.caption.nonEmpty then
      val (cx, anchor) = alignedAt(TextRole.Caption)
      sb ++= s"""<text x="${fmt(cx)}" y="${fmt(fig.height - 4 * sc(TextRole.Caption))}"$anchor font-size="${fmt(
          px(TextRole.Caption)
        )}"${extra(TextRole.Caption)} style="fill:${paint(face(TextRole.Caption).paint)}">${body(
          TextRole.Caption,
          fig.caption
        )}</text>"""
    // the letter of a panel of a board, in the corner
    if fig.tag.nonEmpty then
      sb ++= s"""<text x="6" y="${fmt(6 + px(TextRole.Tag))}" font-size="${fmt(px(TextRole.Tag))}"${extra(
          TextRole.Tag
        )} style="fill:${paint(face(TextRole.Tag).paint)}">${body(TextRole.Tag, fig.tag)}</text>"""
    sb ++= "</svg>"
    sb.toString

  /** The minor ticks between `majors`, inside `seen` (the visible range in data units): on a log axis whose ticks are
    * all a whole number times a power of ten (the decades, or 1, 2, 5, 10, ...), the whole numbers of each decade that
    * are not ticks; otherwise the halves between two ticks, in the axis's own scale, one step beyond the first and the
    * last. None for fewer than two ticks.
    */
  private[plot] def minorPositions(tr: Transform, majors: Seq[Double], seen: (Double, Double)): Seq[Double] =
    val ms = majors.filter(v => tr(v).isFinite).distinct.sorted
    if ms.size < 2 then Seq.empty
    else
      val (lo, hi) = (math.min(seen._1, seen._2), math.max(seen._1, seen._2))
      def wholeMultiple(v: Double): Boolean =
        v > 0 && {
          val m = v / math.pow(10, math.floor(math.log10(v) + 1e-12))
          math.abs(m - math.rint(m)) < 1e-9
        }
      val candidates =
        if tr == Transform.Log10 && ms.forall(wholeMultiple) then
          val (k0, k1) = (math.floor(math.log10(math.max(lo, 1e-300))).toInt, math.ceil(math.log10(hi)).toInt)
          for k <- k0 to k1; j <- 1 to 9 yield j * math.pow(10, k)
        else
          val us = ms.map(tr(_))
          val steps = us.zip(us.tail).map((a, b) => b - a)
          val edges = (us.head - steps.head) +: us :+ (us.last + steps.last)
          edges.zip(edges.tail).map((a, b) => tr.inverse((a + b) / 2))
      candidates.filter { v =>
        tr(v).isFinite && v >= lo && v <= hi && !ms.exists(m => math.abs(tr(m) - tr(v)) < 1e-9 * (1 + math.abs(tr(m))))
      }

  /** A marker of `r` pixels centred at `(cx, cy)`: `face`'s shape, filled with `fill` or open, with its edge in `edge`.
    */
  private def marker(cx: Double, cy: Double, r: Double, face: MarkerFace, fill: String, edge: String): String =
    val attrs = MarkerStyle.attributes(face, fill, edge, fmt)
    def points(ps: Seq[(Double, Double)]): String = ps.map((a, b) => s"${fmt(a)},${fmt(b)}").mkString(" ")
    def polygon(ps: Seq[(Double, Double)]): String = s"""<polygon points="${points(ps)}" $attrs/>"""
    // the corners of a regular polygon of `n` corners and radius `rad`, the first one up (or `turn` radians round)
    def regular(n: Int, rad: Double, turn: Double = 0.0): Seq[(Double, Double)] =
      (0 until n).map { i =>
        val a = -math.Pi / 2 + turn + 2 * math.Pi * i / n
        (cx + rad * math.cos(a), cy + rad * math.sin(a))
      }
    def strokes(d: String): String =
      if face.edge.visible then
        val dash = face.edge.dash.pattern.fold("")(p => s""" stroke-dasharray="$p"""")
        val o = face.opacity * face.edge.opacity
        val opacity = if o == 1.0 then "" else s";stroke-opacity:$o"
        s"""<path d="$d" style="fill:none;stroke:$edge$opacity" stroke-width="${fmt(
            face.edge.width
          )}" stroke-linecap="round"$dash/>"""
      else ""
    face.shape match
      case Shape.Circle => s"""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r)}" $attrs/>"""
      case Shape.Dot    => s"""<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r * 0.55)}" $attrs/>"""
      case Shape.Square =>
        val s = r * 0.9
        s"""<rect x="${fmt(cx - s)}" y="${fmt(cy - s)}" width="${fmt(2 * s)}" height="${fmt(2 * s)}" $attrs/>"""
      case Shape.Triangle =>
        val s = r * 1.25
        polygon(Seq((cx, cy - s), (cx + s * 0.866, cy + s * 0.5), (cx - s * 0.866, cy + s * 0.5)))
      case Shape.TriangleDown =>
        val s = r * 1.25
        polygon(Seq((cx, cy + s), (cx + s * 0.866, cy - s * 0.5), (cx - s * 0.866, cy - s * 0.5)))
      case Shape.TriangleLeft =>
        val s = r * 1.25
        polygon(Seq((cx - s, cy), (cx + s * 0.5, cy - s * 0.866), (cx + s * 0.5, cy + s * 0.866)))
      case Shape.TriangleRight =>
        val s = r * 1.25
        polygon(Seq((cx + s, cy), (cx - s * 0.5, cy - s * 0.866), (cx - s * 0.5, cy + s * 0.866)))
      case Shape.Diamond =>
        val s = r * 1.2
        polygon(Seq((cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)))
      case Shape.Pentagon => polygon(regular(5, r * 1.2))
      case Shape.Hexagon  => polygon(regular(6, r * 1.15))
      case Shape.Star     =>
        val (outer, inner) = (r * 1.5, r * 0.6)
        polygon((0 until 10).map { i =>
          val a = -math.Pi / 2 + math.Pi * i / 5
          val rad = if i % 2 == 0 then outer else inner
          (cx + rad * math.cos(a), cy + rad * math.sin(a))
        })
      case Shape.Plus =>
        val s = r * 1.1
        strokes(
          s"M${fmt(cx - s)},${fmt(cy)} L${fmt(cx + s)},${fmt(cy)} M${fmt(cx)},${fmt(cy - s)} L${fmt(cx)},${fmt(cy + s)}"
        )
      case Shape.Cross =>
        val s = r * 0.85
        strokes(
          s"M${fmt(cx - s)},${fmt(cy - s)} L${fmt(cx + s)},${fmt(cy + s)} M${fmt(cx - s)},${fmt(cy + s)} L${fmt(cx + s)},${fmt(cy - s)}"
        )
      case Shape.VLine       => strokes(s"M${fmt(cx)},${fmt(cy - r * 1.2)} L${fmt(cx)},${fmt(cy + r * 1.2)}")
      case Shape.HLine       => strokes(s"M${fmt(cx - r * 1.2)},${fmt(cy)} L${fmt(cx + r * 1.2)},${fmt(cy)}")
      case Shape.Glyph(text) =>
        s"""<text x="${fmt(cx)}" y="${fmt(cy)}" text-anchor="middle" dominant-baseline="central" font-size="${fmt(
            r * 2.4
          )}" $attrs>${escape(text)}</text>"""
