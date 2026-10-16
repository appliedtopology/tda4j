package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification
import org.w3c.dom.Element

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** The customizations of a plot, as tweaks added with `+`: each example is built so that the plausible wrong
  * implementation fails it (a log axis that spaces 1, 10 and 100 evenly, a palette swap that leaves a heatmap in the
  * old colors, a diagonal that stops being `y = x` on a curved axis).
  */
class PlotGrammarSpec extends Specification:

  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private lazy val circleDiagram = Persistence(circle, maxDimension = 1)

  private def parse(svg: String) =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)))

  private def elements(svg: String, tag: String): Seq[Element] =
    val nodes = parse(svg).getElementsByTagName(tag)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[Element])

  private def count(svg: String, tag: String): Int = elements(svg, tag).size
  private def words(svg: String): Seq[String] = elements(svg, "text").map(_.getTextContent)
  private def number(e: Element, name: String): Double = e.getAttribute(name).toDouble
  private def fontSizes(svg: String): Seq[Double] = elements(svg, "text").map(number(_, "font-size")).sorted
  private def sameUpToIds(a: String, b: String) =
    a.replaceAll("tda4j[0-9]+", "N") must beEqualTo(b.replaceAll("tda4j[0-9]+", "N"))

  /** Dots at x = 1, 2, 3, ... and the given heights. */
  private def dots(ys: Double*): Figure =
    Figure(marks = Vector(Mark.Points(ys.indices.map(i => (i + 1.0, ys(i))), IndexedSeq(Paint.Series(0)))))

  private def polyline(svg: String): Seq[(Double, Double)] =
    elements(svg, "polyline").head.getAttribute("points").split(" ").toSeq.map { p =>
      val Array(x, y) = p.split(","); (x.toDouble, y.toDouble)
    }

  private def distanceToSegment(p: (Double, Double), a: (Double, Double), b: (Double, Double)): Double =
    val (dx, dy) = (b._1 - a._1, b._2 - a._2)
    val len2 = dx * dx + dy * dy
    val t = if len2 == 0 then 0.0 else math.max(0.0, math.min(1.0, ((p._1 - a._1) * dx + (p._2 - a._2) * dy) / len2))
    math.hypot(p._1 - (a._1 + t * dx), p._2 - (a._2 + t * dy))

  private def distanceToPolyline(p: (Double, Double), line: Seq[(Double, Double)]): Double =
    line.zip(line.tail).map((a, b) => distanceToSegment(p, a, b)).min

  "a transform" should {
    "invert itself wherever it gives a position" in {
      val values = Seq(1e-6, 0.03, 0.5, 1.0, 2.5, 40.0, 1e4)
      val transforms = Seq(Transform.Identity, Transform.Log10, Transform.Sqrt, Transform.SymLog(0.1))
      (for t <- transforms; v <- values yield t.inverse(t(v)) / v)
        .map(r => math.abs(r - 1) < 1e-9)
        .forall(identity) must beTrue
      Transform.SymLog(0.1).inverse(Transform.SymLog(0.1)(-3.0)) must beCloseTo(-3.0, 1e-9)
      (for t <- Seq(Transform.Sqrt, Transform.SymLog(0.1)); v <- values.map(-_) yield t.inverse(t(v)) / v)
        .map(r => math.abs(r - 1) < 1e-9)
        .forall(identity) must beTrue
    }
    "keep the sign of a square root, and give the log of zero and of a negative number no position of its own" in {
      Transform.Sqrt(-4.0) must beEqualTo(-2.0)
      Transform.Sqrt.inverse(Transform.Sqrt(-9.0)) must beCloseTo(-9.0, 1e-9)
      Transform.Log10(0.0).isNaN must beTrue // the renderer gives these a place; the function does not invent one
      Transform.Log10(-1.0).isNaN must beTrue
      Transform.SymLog(1.0)(0.0) must beEqualTo(0.0)
    }
    "put round ticks on a symmetric log axis, whatever its stretch" in {
      Transform.SymLog(1.351).ticks(-2.3, 2.3) must contain(allOf(-2.0, -1.0, 0.0, 1.0, 2.0))
      Transform.SymLog(1.0).ticks(-1000, 1000) must contain(allOf(-1000.0, -100.0, -10.0, 0.0, 10.0, 100.0, 1000.0))
    }
    "put log ticks on powers of ten, with 2 and 5 between when there are few decades" in {
      Transform.Log10.ticks(1, 1e4) must beEqualTo(IndexedSeq(1.0, 10.0, 100.0, 1000.0, 10000.0))
      Transform.Log10.ticks(1, 100) must contain(allOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0))
      Transform.Log10.tickLabel(1e-4) must beEqualTo("1e-4")
      Transform.Log10.tickLabel(0.01) must beEqualTo("0.01")
    }
  }

  "a log axis" should {
    "space equal ratios equally, and a linear one does not" in {
      val fig = dots(1, 10, 100)
      def heights(f: Figure) = elements(f.svg(), "circle").map(number(_, "cy"))
      val Seq(c1, c2, c3) = heights(fig + Scale.y(Transform.Log10))
      math.abs((c1 - c2) - (c2 - c3)) must beLessThan(0.05)
      val Seq(l1, l2, l3) = heights(fig)
      math.abs((l1 - l2) - (l2 - l3)) must beGreaterThan(50.0)
    }
    "label its ticks at powers of ten" in {
      val labels = words((dots(1, 10, 100, 1000) + Scale.y(Transform.Log10)).svg())
      labels must contain(allOf("1", "10", "100", "1000"))
    }
    val onePoint = (xs: Seq[Double]) =>
      Figure(marks = Vector(Mark.Points(xs.map(x => (x, 1.0)).toIndexedSeq, IndexedSeq(Paint.Series(0)))))
    "put what is at 0 on a -∞ line below the lowest number, and leave nothing out" in {
      val svg = (onePoint(Seq(0.0, 1.0, 10.0, 100.0)) + Scale.x(Transform.Log10)).svg()
      val xs = elements(svg, "circle").map(number(_, "cx"))
      xs.size must beEqualTo(4)
      words(svg) must contain("-∞")
      words(svg).exists(_.contains("not shown")) must beFalse
      (xs(1) - xs(0)) must beGreaterThan(30.0) // the line is a place of its own, clear of the lowest number
      xs(0) must beGreaterThan(58.0 + 8) // and of the edge, so its marker is whole
      (xs(3) - xs(2)) must beCloseTo(xs(2) - xs(1), 0.05) // the numbers keep equal steps for equal ratios
      elements(svg, "line").exists { l =>
        l.getAttribute("x1") == l.getAttribute("x2") && math.abs(number(l, "x1") - xs(0)) < 0.01
      } must beTrue
    }
    "show a bar born at 0 from the -∞ line, as an essential bar runs to ∞" in {
      val bars =
        List(PersistenceBar[Double](0, 0.0, 1.0), PersistenceBar[Double](1, 0.5, 2.0), PersistenceBar[Double](0, 0.0))
      val svg = (Plot.barcode(bars) + Scale.x(Transform.Log10)).svg()
      val drawn = elements(svg, "line").filter(_.getAttribute("stroke-linecap") == "butt")
      drawn.size must beEqualTo(3)
      val label = elements(svg, "text").find(_.getTextContent == "-∞").get
      val starts = drawn.filter(_.getElementsByTagName("title").item(0).getTextContent.startsWith("H0"))
      starts.map(number(_, "x1")).foreach(x => x must beCloseTo(number(label, "x"), 0.01))
      words(svg).exists(_.contains("not shown")) must beFalse
    }
    "plot a negative value: the axis is a symmetric log about zero, and says so" in {
      val xs = Seq(-100.0, -10.0, -1.0, 0.0, 1.0, 10.0, 100.0)
      val svg = (onePoint(xs) + Scale.x(Transform.Log10)).svg()
      val cx = elements(svg, "circle").map(number(_, "cx"))
      cx.size must beEqualTo(7)
      cx must beEqualTo(cx.sorted)
      (cx(4) - cx(3)) must beCloseTo(cx(3) - cx(2), 0.05) // mirrored about zero
      (cx(6) - cx(3)) must beCloseTo(cx(3) - cx(0), 0.05)
      words(svg) must contain("negative values: the x axis is a symmetric log scale, linear within ±1")
      words(svg).exists(_.contains("not shown")) must beFalse
      words(svg) must not(contain("-∞")) // zero has its own place here, there is no line
    }
    "give two axes that are both switched one stretch and one note, so that one scale reads alike along both" in {
      val both = Figure(
        marks = Vector(Mark.Points(IndexedSeq((-2.0, -0.2), (0.5, 4.0), (3.0, 1.0)), IndexedSeq(Paint.Series(0)))),
        equalAspect = true
      ) + Scale.xy(Transform.Log10)
      val (xt, yt) = Svg.resolved(both)
      xt must beEqualTo(yt)
      xt must beEqualTo(Transform.SymLog(0.2)) // the smaller of x's 0.5 and y's 0.2
      val notes = words(both.svg()).filter(_.contains("symmetric log"))
      notes must beEqualTo(Seq("negative values: the x and y axes are symmetric log scales, linear within ±0.2"))
    }
    "not become a symmetric log for a limit alone, nor for the lower bound a plot pads below zero" in {
      val bars = List(PersistenceBar[Double](0, 0.0, 1.0), PersistenceBar[Double](1, 0.5, 2.0))
      words((Plot.barcode(bars) + Scale.x(Transform.Log10)).svg()).exists(_.contains("symmetric log")) must beFalse
      val limited = onePoint(Seq(1.0, 10.0, 100.0)) + Scale.x(Transform.Log10, min = -5)
      limited.x.min must beSome(-5.0) // a limit that no value reaches is allowed
      words(limited.svg()).exists(_.contains("symmetric log")) must beFalse
    }
    "leave out only what is not a number, and say how much" in {
      val fig = Figure(marks =
        Vector(Mark.Points(IndexedSeq((1.0, 1.0), (2.0, Double.NaN), (3.0, 3.0)), IndexedSeq(Paint.Series(0))))
      )
      val svg = (fig + Scale.y(Transform.Log10)).svg()
      count(svg, "circle") must beEqualTo(2)
      words(svg) must contain("1 mark not shown: a position that is not a number")
    }
    "leave the line off when asked, counting what is at 0 instead, or keep it with nothing on it" in {
      val withZero = onePoint(Seq(0.0, 1.0, 10.0))
      val off = (withZero + Scale.x(Transform.Log10, minusInfinity = false)).svg()
      count(off, "circle") must beEqualTo(2)
      words(off) must contain("1 mark not shown: at 0 on a log axis drawn without a -∞ line")
      words(off) must not(contain("-∞"))
      words((onePoint(Seq(1.0, 10.0, 100.0)) + Scale.x(Transform.Log10, minusInfinity = true)).svg()) must contain("-∞")
    }
  }

  "a square-root axis" should {
    "show a negative value mirrored, and leave nothing out" in {
      val xs = Seq(-9.0, -4.0, -1.0, 0.0, 1.0, 4.0, 9.0)
      val fig = Figure(marks = Vector(Mark.Points(xs.map(x => (x, 1.0)).toIndexedSeq, IndexedSeq(Paint.Series(0))))) +
        Scale.x(Transform.Sqrt)
      val svg = fig.svg()
      val cx = elements(svg, "circle").map(number(_, "cx"))
      cx.size must beEqualTo(7)
      cx must beEqualTo(cx.sorted)
      (cx(5) - cx(3)) must beCloseTo(cx(3) - cx(1), 0.05) // -4 and 4 are as far from 0, on either side
      (cx(5) - cx(3)) must beCloseTo(2 * (cx(4) - cx(3)), 0.05) // 2 and 1: the root of 4 and of 1
      words(svg).exists(_.contains("not shown")) must beFalse
    }
  }

  "the diagonal of a diagram" should {
    val fig = Figure(
      marks = Vector(Mark.Diagonal(), Mark.Points(IndexedSeq((0.3, 0.3), (2.0, 5.0)), IndexedSeq(Paint.Series(0)))),
      x = Axis(min = Some(0.01), max = Some(10.0)),
      y = Axis(min = Some(0.01), max = Some(10.0))
    )
    "stay a straight line through (v, v) on log-log axes" in {
      val svg = (fig + Scale.xy(Transform.Log10)).svg()
      val line = polyline(svg)
      val dot = elements(svg, "circle").head
      val p = (number(dot, "cx"), number(dot, "cy"))
      distanceToPolyline(p, line) must beLessThan(0.6)
      line.map(distanceToSegment(_, line.head, line.last)).max must beLessThan(0.05)
    }
    "follow the curve y = x when only one axis is logarithmic" in {
      val svg = (fig + Scale.x(Transform.Log10)).svg()
      val line = polyline(svg)
      val dot = elements(svg, "circle").head
      distanceToPolyline((number(dot, "cx"), number(dot, "cy")), line) must beLessThan(0.6)
      line.map(distanceToSegment(_, line.head, line.last)).max must beGreaterThan(5.0) // it is bent
    }
    "stop at the numbers when the x axis has a -∞ line" in {
      val withLine = Figure(
        marks = Vector(Mark.Diagonal(), Mark.Points(IndexedSeq((0.0, 0.01), (1.0, 2.0)), IndexedSeq(Paint.Series(0))))
      ) + Scale.xy(Transform.Log10) // y reaches lower than the numbers of x, so x is what the diagonal stops at
      val svg = withLine.svg()
      val band = number(elements(svg, "circle").head, "cx")
      polyline(svg).map(_._1).min must beGreaterThan(band + 20)
    }
    "be a line across the whole plot, however the range is set" in {
      val svg = (fig + Scale.xy(min = 1, max = 4)).svg()
      val line = polyline(svg)
      line.size must beEqualTo(2)
      line.head._1 must beCloseTo(58.0, 0.5)
      line.last._2 must beCloseTo(16.0, 0.5)
    }
  }

  "equal aspect" should {
    val born = Plot.diagram(circleDiagram)
    "keep a decade as long along x as along y on log-log axes" in {
      val fig = Figure(
        marks = Vector(Mark.Points(IndexedSeq((1.0, 1.0), (100.0, 100.0)), IndexedSeq(Paint.Series(0)))),
        equalAspect = true
      ) + Scale.xy(Transform.Log10)
      val Seq(a, b) = elements(fig.svg(), "circle")
      number(b, "cx") - number(a, "cx") must beCloseTo(number(a, "cy") - number(b, "cy"), 0.05)
    }
    "not apply between different scales, and stop a margin below what a scale can place" in {
      // H0 bars are born at 0: on a square-root axis they sit just inside the left edge (58), not in a widened range
      val onlyX = born + Scale.x(Transform.Sqrt)
      val zero = elements(onlyX.svg(), "circle").map(number(_, "cx")).min
      (zero must beGreaterThan(58.0)) and (zero must beLessThan(58.0 + 30))
      onlyX.ranges._1._1 must beLessThanOrEqualTo(0.0)
      val both = (born + Scale.xy(Transform.Sqrt)).svg()
      val bothZero = elements(both, "circle").map(number(_, "cx")).min
      (bothZero must beGreaterThan(58.0)) and (bothZero must beLessThan(58.0 + 30))
    }
  }

  "axis limits and ticks" should {
    val ends = Figure(marks = Vector(Mark.Points(IndexedSeq((0.0, 0.0), (4.0, 1.0)), IndexedSeq(Paint.Series(0)))))
    "fix the visible range, and move one end at a time" in {
      val svg = (ends + Scale.x(min = 0, max = 4)).svg()
      val Seq(first, second) = elements(svg, "circle").map(number(_, "cx"))
      first must beCloseTo(58.0, 0.01) // the plot area is 58 to 624
      second must beCloseTo(624.0, 0.01)
      val barcode = Plot.barcode(circleDiagram)
      val moved = barcode + Scale.x(max = 3)
      moved.x.min must beEqualTo(barcode.x.min)
      moved.x.max must beSome(3.0)
    }
    "take ticks where asked, formatted as asked" in {
      val svg = (ends + Scale.x(min = 0, max = 4, breaks = Seq(0, 2), format = "%.1f")).svg()
      words(svg) must contain(allOf("0.0", "2.0"))
      words(svg) must not(contain("4.0"))
    }
    "turn an axis or its gridlines off" in {
      val plain = dots(1, 2, 3)
      count((plain + Scale.x(grid = false)).svg(), "line") must beLessThan(count(plain.svg(), "line"))
      count((plain + Style(grid = false)).svg(), "line") must beEqualTo(2) // the two axis lines
      elements((plain + Scale.y(visible = false)).svg(), "text").size must beLessThan(count(plain.svg(), "text"))
    }
    "refuse limits the scale cannot show or that cross, saying what to do" in {
      (dots(1, 2) + Scale.x(min = Double.NaN)) must throwAn[IllegalArgumentException](
        "Scale.x: min = NaN is not a number"
      )
      (dots(1, 2) + Scale.x(Transform.Log10, min = 0, max = 5)).x.min must beSome(0.0) // zero and below are allowed
      (dots(1, 2) + Scale.x(min = 5, max = 3)) must throwAn[IllegalArgumentException]("min \\(5\\) must be less")
      (dots(1, 2) + Scale.y(format = "%d")) must throwAn[IllegalArgumentException]("printf pattern")
      (dots(1, 2) + Scale.x(Transform.SymLog(-1.0))) must throwAn[IllegalArgumentException]("positive linear")
    }
    "complain, when drawn, of a range that is empty" in {
      (dots(1, 2) + Scale.x(min = 10, max = 20) + Scale.x(max = 5)) must throwAn[IllegalArgumentException]
      Figure(marks = dots(1, 2).marks, x = Axis(min = Some(10.0), max = Some(5.0))).svg() must throwAn[
        IllegalArgumentException
      ]("x axis would run from 10 to 5")
    }
  }

  "a color scale" should {
    val image = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05)
    "follow the palette the figure is given afterwards, heatmaps included" in {
      val top = Palette.brand.sequentialAt(1.0)
      val reference = Palette.reference.sequentialAt(1.0)
      image.svg(Theme.Light) must contain(top)
      val swapped = (image + Palette.reference).svg(Theme.Light)
      swapped must contain(reference)
      swapped must not(contain(top))
    }
    "recolor a cocycle's values and keep them symmetric about zero" in {
      val d = Persistence(circle, maxDimension = 1, representatives = Representatives.Cocycles)
      import d.given
      val fig = Plot.cocycle(circle, d.dim(1).bars.head.representative)
      val swapped = (fig + Palette.reference).svg(Theme.Light)
      val m = fig.colorScale.get.hi
      // the largest value is at one end of the diverging scale or the other
      Seq(1.0, -1.0).exists(t => swapped.contains(Palette.reference.divergingAt(t))) must beTrue
      Seq(1.0, -1.0).exists(t => swapped.contains(Palette.brand.divergingAt(t))) must beFalse
      (fig + Scale.color(max = 4 * m)).colorScale.get must beEqualTo(fig.colorScale.get.copy(lo = -4 * m, hi = 4 * m))
    }
    "put two figures on one scale when both are given the same limits" in {
      def cell(value: Double, hi: Double) = Figure(
        marks = Vector(Mark.Boxes(IndexedSeq((0.0, 0.0, 1.0, 1.0)), IndexedSeq(Paint.Value(value)))),
        colorScale = Some(ColorScale(Ramp.Sequential, 0.0, hi, "weight"))
      )
      def fill(f: Figure) =
        elements(f.svg(Theme.Light), "rect").map(_.getAttribute("style")).filter(_.contains("fill-opacity")).head
      fill(cell(2, 4)) must not(beEqualTo(fill(cell(2, 8))))
      val shared = Scale.color(min = 0, max = 10)
      fill(cell(2, 4) + shared) must beEqualTo(fill(cell(2, 8) + shared))
      words((cell(2, 4) + shared).svg()) must contain("10")
    }
    "take another ramp, a transform, and no bar" in {
      val fig = image + Scale.color(stops = ColorScale.viridis)
      val svg = fig.svg(Theme.Light)
      svg must contain(Palette.along(ColorScale.viridis, 1.0))
      svg must not(contain(Palette.brand.sequentialAt(1.0)))
      (image + Scale.color(transform = Transform.Sqrt)).colorScale.get.transform must beEqualTo(Transform.Sqrt)
      val half = ColorScale(Ramp.Sequential, 0.0, 4.0, "v", Transform.Sqrt)
      half.hex(1.0, Palette.brand) must beEqualTo(Palette.along(Palette.brand.sequential, 0.5)) // sqrt(1)/sqrt(4)
      count((image + Scale.color(guide = false)).svg(), "rect") must beLessThan(count(image.svg(), "rect"))
    }
    "refuse changes that mean nothing to its kind of ramp" in {
      val cyc = Plot.circularCoordinates(circle, circle.indices.map(i => i -> i / 16.0).toMap)
      (cyc + Scale.color(max = 2)) must throwAn[IllegalArgumentException]("one turn")
      val d = Persistence(circle, maxDimension = 1, representatives = Representatives.Cocycles)
      import d.given
      (Plot.cocycle(circle, d.dim(1).bars.head.representative) + Scale.color(min = -1)) must throwAn[
        IllegalArgumentException
      ]("symmetric")
    }
    "give every value a color on a log scale that starts at 0 or below" in {
      val p = Palette.brand
      val fromZero = ColorScale(Ramp.Sequential, 0.0, 10.0, transform = Transform.Log10)
      val colors = Seq(0.0, 0.001, 0.1, 1.0, 10.0).map(fromZero.hex(_, p))
      colors.forall(_.matches("#[0-9a-f]{6}")) must beTrue
      colors.distinct.size must beEqualTo(5)
      colors.head must beEqualTo(Palette.along(p.sequential, 0.0))
      colors.last must beEqualTo(Palette.along(p.sequential, 1.0))
      val signed = ColorScale(Ramp.Sequential, -5.0, 5.0, transform = Transform.Log10)
      signed.hex(-5.0, p) must beEqualTo(Palette.along(p.sequential, 0.0))
      signed.hex(5.0, p) must beEqualTo(Palette.along(p.sequential, 1.0))
      signed.hex(0.0, p) must beEqualTo(Palette.along(p.sequential, 0.5))
      (image + Scale.color(transform = Transform.Log10, min = 0)).colorScale.get.transform must beEqualTo(
        Transform.Log10
      )
    }
    "leave a figure without one as it is" in {
      val fig = Plot.barcode(circleDiagram)
      (fig + Scale.color(max = 3)) must beEqualTo(fig)
    }
  }

  "the color, label and shape of a degree" should {
    val fig = Plot.diagram(circleDiagram)
    val loops = Scale.degree(1, color = "#d62728", label = "loops", shape = Shape.Square)
    "change in every part of the figure that shows it" in {
      val svg = (fig + loops).svg(Theme.Light)
      svg must contain("#d62728")
      svg must not(contain(Palette.brand.seriesLight(1)))
      words(svg) must contain("loops")
      words(svg) must not(contain("H1"))
      words(svg) must contain("H0") // the other degrees are as they were
    }
    "change the marker shape: a square, not the degree's triangle" in {
      val before = count(fig.svg(), "polygon")
      count((fig + loops).svg(), "polygon") must beEqualTo(before - 2) // the bar's marker and the legend's
    }
    "follow the degree through light and dark, in the page's CSS variables" in {
      val dark = fig + Scale.degree(1, color = "#d62728", darkColor = "#ff8080")
      val css = elements(dark.svg(Theme.Auto), "style").head.getTextContent
      val (lightBlock, darkBlock) = css.splitAt(css.indexOf("@media"))
      lightBlock must contain("--s1:#d62728")
      darkBlock must contain("--s1:#ff8080")
    }
    "not depend on the order tweaks are added in" in {
      sameUpToIds((fig + loops + Palette.reference).svg(), (fig + Palette.reference + loops).svg())
      (fig + Scale.degree(1, color = "#111111") + Scale.degree(1, label = "x")).degrees(1) must
        beEqualTo(DegreeStyle(color = Some("#111111"), label = Some("x")))
    }
    "take a new color for both modes, even after a dark one" in {
      val s = (fig + Scale.degree(1, color = "#111111", darkColor = "#eeeeee") + Scale.degree(1, color = "#222222"))
        .degrees(1)
      s.color must beSome("#222222")
      s.darkColor must beNone
    }
    "refuse what is not a color" in {
      Scale.degree(1, color = "red\"/><script") must throwAn[IllegalArgumentException]("not a CSS color")
      Scale.degree(-1) must throwAn[IllegalArgumentException]
    }
    "apply to a 3-D scene's colors too" in {
      val scene = Plot.cycle3D(
        Array(Array(0.0, 0.0, 0.0), Array(1.0, 0.0, 0.0), Array(0.0, 1.0, 0.0)),
        Seq((Simplex(0, 1), 1.0))
      )
      (scene + Scale.degree(1, color = "#d62728")).svgFile(Theme.Light) must contain("#d62728")
    }
  }

  "the style of a figure" should {
    val fig = Plot.diagram(circleDiagram)
    "scale every text size, and make room for it" in {
      val big = (fig + Style(textScale = 2)).svg()
      fontSizes(big) must beEqualTo(fontSizes(fig.svg()).map(_ * 2))
      big must contain("""font-size="30"""") // the title, 15 at the default
    }
    "scale the markers and the lines" in {
      val radii = elements((dots(1, 2) + Style(markScale = 2)).svg(), "circle").map(number(_, "r"))
      radii must beEqualTo(Seq(8.0, 8.0))
      val curve =
        Figure(marks = Vector(Mark.Line(IndexedSeq((0.0, 0.0), (1.0, 1.0)), Paint.Series(0)))) // 2 pixels wide
      elements((curve + Style(markScale = 2)).svg(), "polyline").map(number(_, "stroke-width")) must beEqualTo(Seq(4.0))
    }
    "set the font" in {
      (fig + Style(font = "Georgia, serif")).svg() must contain("""font-family="Georgia, serif"""")
      Style(font = "") must throwAn[IllegalArgumentException]
    }
    "move the legend below the plot, or take it away" in {
      def legendAt(f: Figure) = elements(f.svg(), "text").find(_.getTextContent == "H0").get
      val beside = legendAt(fig)
      val below = legendAt(fig + Style(legend = LegendPosition.Bottom))
      number(beside, "x") must beGreaterThan(400.0)
      number(below, "x") must beLessThan(150.0)
      number(below, "y") must beGreaterThan(number(beside, "y") + 300)
      number(below, "y") must beLessThan(520.0)
      words((fig + Style(legend = LegendPosition.Hidden)).svg()) must not(contain("H0"))
    }
    "wrap a long legend into rows rather than off the page" in {
      val many = Figure(
        marks = dots(1, 2).marks,
        legend = Vector.tabulate(6)(k => LegendItem(s"a rather long label $k", Paint.Series(k))),
        width = 400
      ) + Style(legend = LegendPosition.Bottom)
      val rows =
        elements(many.svg(), "text").filter(_.getTextContent.startsWith("a rather")).map(number(_, "y")).distinct
      rows.size must beGreaterThan(1)
      elements(many.svg(), "text").forall(e => number(e, "x") < 400)
    }
    "draw no background when transparent" in {
      val full =
        elements(fig.svg(), "rect").count(r => r.getAttribute("width") == "560" && r.getAttribute("height") == "520")
      val clear = elements((fig + Style(transparent = true)).svg(), "rect").count(r => r.getAttribute("width") == "560")
      full must beEqualTo(1)
      clear must beEqualTo(0)
    }
    "replace the figure's style as a whole, and a preset can be adjusted with copy" in {
      (fig + Style.slides + Style(grid = false)).style must beEqualTo(Style(grid = false))
      (fig + Style.slides.copy(grid = false)).style must beEqualTo(
        Style(textScale = 1.3, markScale = 1.3, grid = false)
      )
    }
    "refuse sizes that mean nothing" in {
      Style(textScale = 0) must throwAn[IllegalArgumentException]("textScale")
      Style(markScale = -1) must throwAn[IllegalArgumentException]("markScale")
    }
  }

  "labels and size" should {
    val fig = Plot.diagram(circleDiagram)
    "put the words given in the places they belong" in {
      val svg =
        (fig + Labels(title = "A circle", subtitle = "16 points", x = "born", y = "dies", legend = "degree")).svg()
      words(svg) must contain(allOf("A circle", "16 points", "born", "dies", "degree"))
    }
    "change only what they name" in {
      val titled = fig + Labels(title = "Only the title")
      titled.x must beEqualTo(fig.x)
      titled.subtitle must beEqualTo(fig.subtitle)
      (fig + Labels()) must beEqualTo(fig)
    }
    "title a color bar" in {
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05)
      words((image + Labels(color = "density")).svg()) must contain("density")
    }
    "set the width and the height, either alone" in {
      val svg = (fig + Size(900, 300)).svg()
      elements(svg, "svg").head.getAttribute("width") must beEqualTo("900")
      elements(svg, "svg").head.getAttribute("height") must beEqualTo("300")
      val wider = fig + Size(width = 800)
      wider.width must beEqualTo(800)
      wider.height must beEqualTo(fig.height)
      Size(width = 20) must throwAn[IllegalArgumentException]("between 100 and 20000")
    }
    "apply to a 3-D scene, except what needs axes" in {
      val scene = Plot.points3D(Array(Array(0.0, 0.0, 0.0), Array(1.0, 1.0, 1.0)))
      val tweaked = scene + Size(300, 200) + Labels(title = "A cloud") + Style(markScale = 2)
      tweaked.width must beEqualTo(300)
      tweaked.title must beEqualTo("A cloud")
      elements(tweaked.svgFile(Theme.Light), "circle").map(number(_, "r")) must beEqualTo(Seq(6.0, 6.0))
      (scene + Labels(x = "no axis")) must throwAn[UnsupportedOperationException]("3-D scene has no axes")
      (scene + Scale.x(Transform.Log10)) must throwAn[UnsupportedOperationException]("3-D scene")
    }
  }

  "tweaks" should {
    val fig = Plot.diagram(circleDiagram)
    val (labels, size, scale, look) =
      (Labels(title = "T"), Size(700, 500), Scale.x(Transform.Sqrt), Style.slides + Palette.reference)
    "add up: a sum applied is the tweaks applied in turn" in {
      (fig + (labels + size + scale + look)) must beEqualTo(fig + labels + size + scale + look)
    }
    "commute when they change different parts of the figure" in {
      (fig + labels + size + scale + look) must beEqualTo(fig + look + scale + size + labels)
    }
    "let the later one win on the same part, and keep the rest of the earlier one" in {
      val both = fig + Labels(title = "A", x = "x label") + Labels(title = "B")
      both.title must beEqualTo("B")
      both.x.label must beEqualTo("x label")
    }
    "be kept in a val and used on any plot: a palette and style on a figure, a scene and a board" in {
      val house = Style.slides + Palette.reference + Size(500, 400)
      val board = Plot.side(fig, Plot.barcode(circleDiagram)) + house
      board.plots.map(p => (p.style, p.palette, p.width)).distinct must
        beEqualTo(Seq((Style.slides, Palette.reference, 500)))
      val scene = Plot.points3D(circle) + house
      scene.palette must beEqualTo(Palette.reference)
      scene.style must beEqualTo(Style.slides)
    }
    "title a board, not its panels" in {
      val board = Plot.side(fig, fig) + Labels(title = "Two views", x = "birth")
      board.title must beEqualTo("Two views")
      board.plots.map(_.title).distinct must beEqualTo(Seq(fig.title))
      board.plots.map(_.x.label).distinct must beEqualTo(Seq("birth"))
    }
    "be made from a function when none fits" in {
      (fig + Tweak(_.copy(equalAspect = false))).equalAspect must beFalse
      (fig + Tweak(_.copy(equalAspect = false))) must not(beEqualTo(fig))
    }
  }

  "the methods to chain" should {
    val fig = Plot.diagram(circleDiagram)
    "each be the tweak they stand for, with typed arguments" in {
      fig.withLabels(title = "T", x = "born") must beEqualTo(fig + Labels(title = "T", x = "born"))
      fig.withSize(width = 900) must beEqualTo(fig + Size(width = 900))
      fig.withSize(800, 300) must beEqualTo(fig + Size(800, 300))
      fig.withXScale(Transform.Log10) must beEqualTo(fig + Scale.x(Transform.Log10))
      fig.withXScale(min = 0, max = 2, format = "%.1f") must beEqualTo(fig + Scale.x(min = 0, max = 2, format = "%.1f"))
      fig.withYScale(Transform.Sqrt, grid = false) must beEqualTo(fig + Scale.y(Transform.Sqrt, grid = false))
      fig.withXYScale(Transform.Log10) must beEqualTo(fig + Scale.xy(Transform.Log10))
      fig.withXScale("log") must beEqualTo(
        fig.withXScale(Transform.Log10)
      ) // the word and the value are one overload apart
      fig.withDegree(1, color = "#d62728", label = "loops", shape = Shape.Square) must
        beEqualTo(fig + Scale.degree(1, color = "#d62728", label = "loops", shape = Shape.Square))
      fig.withPalette(Palette.reference) must beEqualTo(fig + Palette.reference)
      fig.withPalette("reference") must beEqualTo(fig.withPalette(Palette.reference))
      fig.withStyle(Style.slides) must beEqualTo(fig + Style.slides)
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05)
      image.withColorScale(max = 0.5, stops = ColorScale.viridis) must
        beEqualTo(image + Scale.color(max = 0.5, stops = ColorScale.viridis))
    }
    "customize a plot in one chained expression, and draw it" in {
      val chained = Plot
        .diagram(circleDiagram.dim(1))
        .withLabels(title = "The loop", x = "born", y = "dies")
        .withXYScale(Transform.Log10)
        .withDegree(1, label = "loop")
        .withLegend("bottom")
        .withSize(700, 500)
      chained must beEqualTo(
        Plot.diagram(circleDiagram.dim(1)) + Labels(title = "The loop", x = "born", y = "dies") +
          Scale.xy(Transform.Log10) + Scale.degree(1, label = "loop") + Style(legend = LegendPosition.Bottom) + Size(
            700,
            500
          )
      )
      words(chained.svg()) must contain(allOf("The loop", "born", "dies", "loop"))
    }
    "reach the heading of a legend and the title of a color bar by name, for MATLAB" in {
      fig.withLegendTitle("degree") must beEqualTo(fig + Labels(legend = "degree"))
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05)
      image.withColorLabel("density") must beEqualTo(image + Labels(color = "density"))
    }
    "be on a 3-D scene where it has a meaning" in {
      val scene = Plot.points3D(circle)
      scene.withLabels(title = "A cloud") must beEqualTo(scene + Labels(title = "A cloud"))
      scene.withSize(width = 300).width must beEqualTo(300)
      scene.withStyle(Style.slides).style must beEqualTo(Style.slides)
      scene.withPalette(Palette.reference).palette must beEqualTo(Palette.reference)
      scene.withDegree(1, color = "#d62728") must beEqualTo(scene + Scale.degree(1, color = "#d62728"))
    }
  }

  "reference lines" should {
    "be drawn across the plot without changing its range" in {
      val fig = dots(1, 2, 3)
      val ruled = fig + Mark.Rule.horizontal(2.5) + Mark.Rule.vertical(1.5)
      ruled.ranges must beEqualTo(fig.ranges)
      val lines = elements(ruled.svg(), "line")
      lines.count(l =>
        l.getAttribute("x1") == "58" && l.getAttribute("x2") == "624" && l.getAttribute("y1") == l.getAttribute("y2")
      ) must
        beGreaterThanOrEqualTo(1)
    }
    "be dashed when asked" in {
      (dots(1, 2) + Mark.Rule(1.5, dashed = true)).svg() must contain("stroke-dasharray")
      (dots(1, 2) + Mark.Diagonal(offset = 0.5, dashed = true)).svg() must contain("stroke-dasharray")
    }
  }

  "a board on shared axes" should {
    "give every panel the union of the panels' ranges" in {
      val (a, b) = (dots(1, 2, 3), dots(10, 20, 30))
      val board = Plot.side(a, b)
      board.plots.map(_.ranges._2).distinct.size must beEqualTo(2)
      val same = board.shared()
      same.plots.map(_.ranges).distinct.size must beEqualTo(1)
      val (lo, hi) = same.plots.head.ranges._2
      lo must beLessThanOrEqualTo(a.ranges._2._1)
      hi must beGreaterThanOrEqualTo(b.ranges._2._2)
    }
    "share one axis and leave the other" in {
      val (a, b) = (dots(1, 2, 3), dots(10, 20, 30))
      val onlyY = Plot.side(a, b).shared(x = false)
      onlyY.plots.map(_.ranges._2).distinct.size must beEqualTo(1)
      onlyY.plots.map(_.x) must beEqualTo(Seq(a.x, b.x))
    }
    "put the ∞ of barcodes at the shared end, where the essential bars run to" in {
      def barcode(ends: Double*) =
        Plot.barcode(ends.map(d => PersistenceBar[Double](0, 0.0, d)) :+ PersistenceBar[Double](1, 0.0))
      val board = Plot.side(barcode(1.0, 2.0), barcode(5.0, 6.0))
      board.plots.map(p => p.x.extra.head._1).distinct.size must beEqualTo(2)
      val same = board.shared(y = false)
      same.plots.map(p => p.x.extra.head._1 == p.x.max.get) must beEqualTo(Seq(true, true))
      same.plots.map(_.x.extra.head._1).distinct.size must beEqualTo(1)
    }
    "draw a log axis the same in every panel when one has to be a symmetric log" in {
      def panel(xs: Double*) =
        Figure(marks = Vector(Mark.Points(xs.map(x => (x, 1.0)).toIndexedSeq, IndexedSeq(Paint.Series(0))))) +
          Scale.x(Transform.Log10)
      val board = Plot.side(panel(1, 10), panel(-1, 5)).shared(y = false)
      board.plots.map(_.x.transform).distinct must beEqualTo(Seq(Transform.SymLog(1.0)))
    }
    "keep room for the -∞ line in every panel when one panel has it, so the numbers line up" in {
      def panel(xs: Double*) =
        Figure(marks = Vector(Mark.Points(xs.map(x => (x, 1.0)).toIndexedSeq, IndexedSeq(Paint.Series(0))))) +
          Scale.x(Transform.Log10)
      val board = Plot.side(panel(0, 10), panel(1, 10)).shared(y = false)
      board.plots.map(_.x.minusInfinity) must beEqualTo(Seq(Some(true), Some(true)))
      val tens = board.plots.map(p => elements(p.svg(), "circle").map(number(_, "cx")).last)
      tens(0) must beCloseTo(tens(1), 0.05)
    }
    "refuse axes that are not on the same scale" in {
      Plot.side(dots(1, 2, 3), dots(1, 2, 3) + Scale.y(Transform.Sqrt)).shared() must throwAn[IllegalArgumentException](
        "different scales"
      )
    }
  }

  "automatic ticks" should {
    "keep clear of an extra tick, so that two labels never overprint" in {
      val axis = Axis(min = Some(0.0), max = Some(10.0), extra = IndexedSeq((9.8, "∞")))
      val fig = Figure(marks = dots(1, 2).marks, y = axis.copy(min = Some(0.0)))
      val labels = words(fig.svg())
      labels must contain("∞") // 10 would sit on the ∞ at 9.8
      labels must not(contain("10"))
      labels must contain(allOf("0", "2", "4", "6", "8"))
    }
  }

  "the defaults" should {
    "need no tweak: a figure is drawn as before, and an empty tweak changes nothing" in {
      val fig = Plot.barcode(circleDiagram)
      sameUpToIds((fig + Labels() + Scale.x() + Scale.y() + Scale.color()).svg(), fig.svg())
      sameUpToIds((fig + Style.default).svg(), fig.svg())
    }
  }
