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
    "label the middle of a transformed bar where the transform puts each number, and a linear bar by its ends" in {
      val sqrt = ColorScale(Ramp.Sequential, 0.0, 4.0, "v", Transform.Sqrt)
      sqrt.barTicks.map(_._1) must contain(1.0) // a round number between the ends
      sqrt.barTicks.toMap.apply(1.0) must beCloseTo(0.5, 1e-12) // half way up, as sqrt(1)/sqrt(4): not a quarter
      sqrt.barTicks.map(_._1).filter(v => v <= 0.0 || v >= 4.0) must beEmpty // the ends say their own numbers
      val log = ColorScale(Ramp.Sequential, 0.01, 100.0, "v", Transform.Log10)
      log.barTicks.toMap.apply(1.0) must beCloseTo(0.5, 1e-12) // a decade is a quarter of four
      ColorScale(Ramp.Sequential, 0.0, 4.0).barTicks must beEmpty
      ColorScale(Ramp.Diverging, -4.0, 4.0, transform = Transform.Sqrt).barTicks must beEmpty // always linear
      val plain = image + Scale.color(max = 4)
      val squared = image + Scale.color(max = 4, transform = Transform.Sqrt)
      words(squared.svg()).count(_ == "1") must beGreaterThan(words(plain.svg()).count(_ == "1"))
      words(squared.svg()) must contain("4") // and the ends are still there
      sameUpToIds(plain.svg(), (plain + Scale.color(transform = Transform.Identity)).svg())
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
      val beside = legendAt(fig + Style(legend = LegendPosition.Right))
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

  /** The luminance and contrast of colors, as the dataviz validator and WCAG compute them. */
  private def luminance(hex: String): Double =
    val v = Integer.parseInt(hex.stripPrefix("#"), 16)
    def linear(c: Int): Double =
      val s = c / 255.0
      if s <= 0.04045 then s / 12.92 else math.pow((s + 0.055) / 1.055, 2.4)
    0.2126 * linear((v >> 16) & 255) + 0.7152 * linear((v >> 8) & 255) + 0.0722 * linear(v & 255)
  private def contrast(a: String, b: String): Double =
    (math.max(luminance(a), luminance(b)) + 0.05) / (math.min(luminance(a), luminance(b)) + 0.05)

  /** The rectangles of a figure's own size: its background, if it has one. */
  private def backgrounds(svg: String, f: Figure): Seq[Element] =
    elements(svg, "rect").filter(r =>
      r.getAttribute("width") == f.width.toString && r.getAttribute("height") == f.height.toString
    )
  private def fontFamily(svg: String): String = parse(svg).getDocumentElement.getAttribute("font-family")

  "the print look" should {
    val fig = Plot.diagram(circleDiagram)
    val paper = Palette.print

    "paint a background of exactly white, never the brand's cream that prints as a grey patch" in {
      val svg = (fig + paper).svg(Theme.Light)
      backgrounds(svg, fig).map(_.getAttribute("style")) must beEqualTo(Seq("fill:#ffffff"))
      svg must not(contain(Palette.brand.light.surface))
    }
    "keep that white background with its fonts, which are named families rather than system-ui" in {
      val svg = (fig + paper + Style.print).svg(Theme.Light)
      backgrounds(svg, fig).map(_.getAttribute("style")) must beEqualTo(Seq("fill:#ffffff"))
      fontFamily(svg) must contain("Helvetica")
      fontFamily(svg) must not(contain("system-ui"))
    }
    "draw its text in black, and every other color so that it can be read on white" in {
      val svg = (fig + paper).svg(Theme.Light)
      elements(svg, "text").map(_.getAttribute("style")) must contain("fill:#000000")
      val surface = paper.light.surface
      contrast(paper.light.ink, surface) must beGreaterThanOrEqualTo(7.0)
      contrast(paper.light.secondary, surface) must beGreaterThanOrEqualTo(7.0)
      contrast(paper.light.muted, surface) must beGreaterThanOrEqualTo(4.5) // the tick labels
      contrast(paper.light.axis, surface) must beGreaterThanOrEqualTo(3.0)
      paper.seriesLight.map(contrast(_, surface)).min must beGreaterThanOrEqualTo(3.0) // a mark must show on paper
    }
    "have one look, so that the dark mode draws the same figure" in {
      paper.dark must beEqualTo(paper.light)
      paper.seriesDark must beEqualTo(paper.seriesLight)
      sameUpToIds((fig + paper).svg(Theme.Dark), (fig + paper).svg(Theme.Light))
    }
    "color a heatmap in a ramp that prints" in {
      val lightnessFalls =
        (0 to 10).map(i => luminance(paper.sequentialAt(i / 10.0))).sliding(2).forall(w => w(1) < w(0))
      lightnessFalls must beTrue // darker for larger, so a grayscale printer keeps the order
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05)
      (image + paper).svg(Theme.Light) must contain(paper.sequentialAt(1.0))
      (image + paper).svg(Theme.Light) must not(contain(Palette.brand.sequentialAt(1.0)))
    }
    "change nothing but the font of the default style" in {
      Style.print.copy(font = Style.DefaultFont) must beEqualTo(Style.default)
    }
  }

  "the clear look" should {
    val fig = Plot.diagram(circleDiagram)
    val clear = Palette.clear + Style.clear

    "draw no background in either mode, and name its fonts" in {
      val light = (fig + clear).svg(Theme.Light)
      val dark = (fig + clear).svg(Theme.Dark)
      backgrounds(light, fig) must beEmpty
      backgrounds(dark, fig) must beEmpty
      fontFamily(light) must contain("Helvetica")
      light must not(contain(Palette.brand.light.surface))
      dark must not(contain(Palette.brand.dark.surface))
    }
    "draw black elements for a light page and white ones for a dark page" in {
      def inks(svg: String) = elements(svg, "text").map(_.getAttribute("style")).toSet
      val light = (fig + clear).svg(Theme.Light)
      val dark = (fig + clear).svg(Theme.Dark)
      inks(light) must contain("fill:#000000")
      inks(light) must not(contain("fill:#ffffff"))
      inks(dark) must contain("fill:#ffffff")
      inks(dark) must not(contain("fill:#000000"))
      elements(dark, "line").map(_.getAttribute("style")) must contain("stroke:#ffffff") // the axes
    }
    "read on pure white and on pure black, each with colors of its own" in {
      val p = Palette.clear
      contrast(p.light.ink, p.light.surface) must beGreaterThanOrEqualTo(7.0)
      contrast(p.light.secondary, p.light.surface) must beGreaterThanOrEqualTo(7.0)
      contrast(p.dark.ink, p.dark.surface) must beGreaterThanOrEqualTo(7.0)
      contrast(p.dark.secondary, p.dark.surface) must beGreaterThanOrEqualTo(7.0)
      contrast(p.light.muted, p.light.surface) must beGreaterThanOrEqualTo(4.5)
      contrast(p.dark.muted, p.dark.surface) must beGreaterThanOrEqualTo(4.5)
      p.seriesLight.map(contrast(_, "#ffffff")).min must beGreaterThanOrEqualTo(3.0)
      p.seriesDark.map(contrast(_, "#000000")).min must beGreaterThanOrEqualTo(3.0)
      p.seriesDark must not(beEqualTo(p.seriesLight)) // a color that reads on white is not the one that reads on black
    }
    "follow the reader's setting under the automatic theme" in {
      val auto = (fig + clear).svg(Theme.Auto)
      auto must contain("prefers-color-scheme: dark")
      auto must contain("--ink:#000000")
      auto must contain("--ink:#ffffff")
    }
    "differ from the print look only in having a dark mode and no background" in {
      Palette.clear.light must beEqualTo(Palette.print.light)
      Palette.clear.dark must not(beEqualTo(Palette.print.dark))
      Style.clear must beEqualTo(Style.print.copy(transparent = true))
    }
    "reach a 3-D scene, which takes the white and the black as well" in {
      val scene = Plot.points3D(circle)
      def rects(svg: String) = elements(svg, "rect").map(_.getAttribute("style"))
      rects((scene + Palette.print).staticSvg(Theme.Light)) must contain("fill:#ffffff")
      rects((scene + clear).staticSvg(Theme.Light)) must beEmpty
      rects((scene + clear).staticSvg(Theme.Dark)) must beEmpty
      rects((scene + Palette.clear).staticSvg(Theme.Dark)) must contain("fill:#000000")
      fontFamily((scene + clear).staticSvg(Theme.Light)) must contain("Helvetica")
    }
  }

  "the named looks" should {
    val fig = Plot.diagram(circleDiagram)
    "be reached by name, in any case, from a figure and a scene" in {
      Palette.named("Print") must beEqualTo(Palette.print)
      Palette.named("clear") must beEqualTo(Palette.clear)
      Style.named(" print ") must beEqualTo(Style.print)
      Style.named("CLEAR") must beEqualTo(Style.clear)
      Style.named("slides") must beEqualTo(Style.slides)
      Style.named("default") must beEqualTo(Style.default)
      fig.withStyle("print") must beEqualTo(fig + Style.print)
      fig.withStyle("clear") must beEqualTo(fig + Style.clear)
      fig.withPalette("clear") must beEqualTo(fig + Palette.clear)
      Plot.points3D(circle).withStyle("slides").style must beEqualTo(Style.slides)
    }
    "refuse a name they do not have, and say which they have" in {
      Style.named("poster") must throwAn[IllegalArgumentException]("expected one of default, slides, print, clear")
      Palette.named("poster") must throwAn[IllegalArgumentException]("expected one of brand, reference, print, clear")
    }
  }

  /** The plot area of a rendered figure: the rectangle its marks are clipped to. */
  private def plotArea(svg: String): (Double, Double, Double, Double) =
    val r = elements(svg, "rect").find(_.getParentNode.getNodeName == "clipPath").get
    (number(r, "x"), number(r, "y"), number(r, "width"), number(r, "height"))

  /** Whether every horizontal text of a rendering lies on the page: a baseline with its ascent above and descent below.
    */
  private def textsOnPage(svg: String): Boolean =
    val root = parse(svg).getDocumentElement
    val (w, h) = (number(root, "width"), number(root, "height"))
    elements(svg, "text").filter(_.getAttribute("transform").isEmpty).forall { t =>
      val size = number(t, "font-size")
      val (x, y) = (number(t, "x"), number(t, "y"))
      x >= 0 && x <= w && y - 0.8 * size >= -0.01 && y + 0.25 * size <= h + 0.01
    }

  private def near(a: Double, b: Double): Boolean = math.abs(a - b) < 0.02

  /** The rectangles that are direct children of the document's `<svg>`: a board's own background. */
  private def topRects(svg: String): Seq[Element] =
    val children = parse(svg).getDocumentElement.getChildNodes
    (0 until children.getLength).map(children.item).collect { case e: Element if e.getTagName == "rect" => e }
  private def textStyles(svg: String): Seq[String] = elements(svg, "text").map(_.getAttribute("style"))
  private def lines(svg: String): Seq[Element] = elements(svg, "line")
  private def stroked(svg: String, color: String): Seq[Element] =
    lines(svg).filter(_.getAttribute("style").startsWith(s"stroke:$color"))

  "the text of a plot" should {
    val fig = Plot.diagram(circleDiagram) + Labels(subtitle = "twelve")
    val plain = fig.svg(Theme.Light)
    val (x0, y0, w0, h0) = plotArea(plain)
    def sizeOf(svg: String, words: String): Double =
      number(elements(svg, "text").find(_.getTextContent == words).get, "font-size")

    "make room for a larger title: the plot area starts lower by what the title grew" in {
      val big = (fig + Text(TextRole.Title, size = 30)).svg(Theme.Light)
      sizeOf(big, "Persistence diagram") must beEqualTo(30.0)
      sizeOf(big, "twelve") must beEqualTo(sizeOf(plain, "twelve"))
      plotArea(big)._2 must beCloseTo(y0 + 22.0, 0.02) // 22 px of margin per 15 px of title, so 15 px more is 22 more
      textsOnPage(big) must beTrue
    }
    "make room for larger tick labels below and beside the axes" in {
      val big = (fig + Text(TextRole.TickLabel, size = 22)).svg(Theme.Light)
      val (x1, y1, w1, h1) = plotArea(big)
      x1 must beCloseTo(x0 + 42.0, 0.02) // the numbers beside the y axis: 42 px at 11 px, 84 at 22
      h1 must beCloseTo(h0 - 30.0, 0.02) // and 30 px below the x axis, 60 at 22
      fontSizes(big).count(_ == 22.0) must beGreaterThan(3)
      textsOnPage(big) must beTrue
    }
    "make room for larger axis labels, and for nothing else" in {
      val big = (fig + Text(TextRole.AxisLabel, size = 24)).svg(Theme.Light)
      val (x1, y1, w1, h1) = plotArea(big)
      x1 must beCloseTo(x0 + 16.0, 0.02)
      h1 must beCloseTo(h0 - 16.0, 0.02)
      y1 must beCloseTo(y0, 0.02)
      textsOnPage(big) must beTrue
    }
    "weigh, slant and typeset one text without touching the others" in {
      val svg = (fig + Text(TextRole.Subtitle, weight = Weight.Bold, italic = true, font = "Georgia, serif") +
        Text(TextRole.Title, weight = Weight.Normal)).svg(Theme.Light)
      val sub = elements(svg, "text").find(_.getTextContent == "twelve").get
      sub.getAttribute("font-weight") must beEqualTo("700")
      sub.getAttribute("font-style") must beEqualTo("italic")
      sub.getAttribute("font-family") must beEqualTo("Georgia, serif")
      val title = elements(svg, "text").find(_.getTextContent == "Persistence diagram").get
      title.getAttribute("font-weight") must beEqualTo("400") // the title was semibold, and now is not
      elements(svg, "text").count(_.getAttribute("font-family") == "Georgia, serif") must beEqualTo(1)
      parse(svg).getDocumentElement.getAttribute("font-family") must beEqualTo(Style.DefaultFont)
    }
    "colour a text with a palette role or a colour of its own" in {
      val svg = (fig + Text(TextRole.Title, color = "#aa0000") + Text(TextRole.Subtitle, paint = Paint.Ink))
        .svg(Theme.Light)
      elements(svg, "text").find(_.getTextContent == "Persistence diagram").get.getAttribute("style") must
        beEqualTo("fill:#aa0000")
      elements(svg, "text").find(_.getTextContent == "twelve").get.getAttribute("style") must
        beEqualTo(s"fill:${Palette.brand.light.ink}")
    }
    "align a title, a subtitle and an axis label along the plot area" in {
      def at(svg: String, words: String) = elements(svg, "text").find(_.getTextContent == words).get
      val centered = (fig + Text(TextRole.Title, align = Anchor.Middle)).svg(Theme.Light)
      at(centered, "Persistence diagram").getAttribute("text-anchor") must beEqualTo("middle")
      number(at(centered, "Persistence diagram"), "x") must beCloseTo(x0 + w0 / 2, 0.02)
      val right = (fig + Text(TextRole.Subtitle, align = Anchor.End)).svg(Theme.Light)
      at(right, "twelve").getAttribute("text-anchor") must beEqualTo("end")
      number(at(right, "twelve"), "x") must beCloseTo(x0 + w0, 0.02)
      at(plain, "Persistence diagram").getAttribute("text-anchor") must beEmpty // the default is the left edge
      val labelled =
        (fig + Labels(x = "born", y = "dies") + Text(TextRole.AxisLabel, align = Anchor.End)).svg(Theme.Light)
      number(at(labelled, "born"), "x") must beCloseTo(x0 + w0, 0.02)
      at(labelled, "born").getAttribute("text-anchor") must beEqualTo("end")
      number(at(labelled, "dies"), "y") must beCloseTo(y0, 0.02) // the end of a y axis is its top
      at(labelled, "dies").getAttribute("text-anchor") must beEqualTo("end")
    }
    "add up over time, keep the rest of what an earlier one set, and leave a style to be added in any order" in {
      val a = fig + Text(TextRole.Title, size = 18) + Text(TextRole.Title, align = Anchor.Middle)
      a.elements.face(TextRole.Title).size must beEqualTo(18.0)
      a.elements.face(TextRole.Title).align must beEqualTo(Anchor.Middle)
      (a + Text(TextRole.Title, size = 20)).elements.face(TextRole.Title).size must beEqualTo(20.0)
      sameUpToIds(
        (fig + Text(TextRole.Title, size = 18) + Style.slides).svg(Theme.Light),
        (fig + Style.slides + Text(TextRole.Title, size = 18)).svg(Theme.Light)
      )
      sizeOf((fig + Style.slides + Text(TextRole.Title, size = 18)).svg(Theme.Light), "Persistence diagram") must
        beCloseTo(18.0 * 1.3, 1e-9) // the style's text scale still applies to a size given in pixels
    }
    "slant the tick labels of the x axis only, towards their ticks, and make room for them" in {
      val slanted = (fig + Text(TextRole.TickLabel, angle = 45)).svg(Theme.Light)
      val turned = elements(slanted, "text").filter(_.getAttribute("transform").startsWith("rotate(-45"))
      turned.size must beGreaterThan(3)
      turned.map(_.getAttribute("text-anchor")).toSet must beEqualTo(Set("end"))
      val backwards = (fig + Text(TextRole.TickLabel, angle = -30)).svg(Theme.Light)
      elements(backwards, "text")
        .filter(_.getAttribute("transform").startsWith("rotate(30"))
        .map(_.getAttribute("text-anchor"))
        .toSet must
        beEqualTo(Set("start"))
      val above = (fig + Text(TextRole.TickLabel, angle = 45) + Scale.x(position = Side.Top)).svg(Theme.Light)
      elements(above, "text")
        .filter(_.getAttribute("transform").startsWith("rotate(-45"))
        .map(_.getAttribute("text-anchor"))
        .toSet must
        beEqualTo(Set("start"))
      plotArea(slanted)._4 must beLessThan(h0 - 5) // room: the plot is shorter for it
      elements(slanted, "text").count(t =>
        t.getAttribute("text-anchor") == "end" && t.getAttribute("transform").isEmpty
      ) must
        beGreaterThan(3) // the numbers beside the y axis stay level
    }
    "refuse what has no meaning, saying what to use" in {
      Text(TextRole.TickLabel, align = Anchor.Middle) must throwAn[IllegalArgumentException](
        "align has no meaning here"
      )
      Text(TextRole.Title, angle = 45) must throwAn[IllegalArgumentException]("only tick labels can be slanted")
      Text(TextRole.TickLabel, angle = 120) must throwAn[IllegalArgumentException]("between -90 and 90")
      Text(TextRole.Title, size = -3) must throwAn[IllegalArgumentException]("size must be a positive number")
      Text(TextRole.Title, color = "#fff", paint = Paint.Ink) must throwAn[IllegalArgumentException](
        "color or paint, not both"
      )
      Text(TextRole.Title, color = "<script>") must throwAn[IllegalArgumentException]("not a CSS color")
      Text(TextRole.Title, font = "") must throwAn[IllegalArgumentException]("CSS font-family")
    }
    "style the title and subtitle of a 3-D scene, and refuse the rest" in {
      val scene = Plot.points3D(circle).copy(title = "A cloud", subtitle = "sixteen points")
      def at(svg: String, words: String) = elements(svg, "text").find(_.getTextContent == words).get
      val svg = (scene + Text(TextRole.Title, size = 24, align = Anchor.Middle) +
        Text(TextRole.Subtitle, italic = true)).staticSvg(Theme.Light)
      number(at(svg, "A cloud"), "font-size") must beEqualTo(24.0)
      at(svg, "A cloud").getAttribute("text-anchor") must beEqualTo("middle")
      number(at(svg, "A cloud"), "x") must beEqualTo(scene.width / 2.0)
      at(svg, "sixteen points").getAttribute("font-style") must beEqualTo("italic")
      scene + Text(TextRole.TickLabel, size = 9) must throwAn[UnsupportedOperationException](
        "tick-label text needs axes"
      )
      (scene + Text(TextRole.Note, size = 14)).fragment(Theme.Light) must contain("font-size:14px")
    }
  }

  "the lines of a plot" should {
    val fig = Plot.diagram(circleDiagram)
    val plain = fig.svg(Theme.Light)
    val gridColor = Palette.brand.light.grid
    val axisColor = Palette.brand.light.axis
    val (x0, y0, w0, h0) = plotArea(plain)
    def count(svg: String, color: String) = stroked(svg, color).size

    "restyle the gridlines, and only them" in {
      val svg = (fig + Lines.grid(width = 2.5, color = "#aa0000", dash = Dash.Dotted, opacity = 0.5)).svg(Theme.Light)
      val grid = stroked(svg, "#aa0000")
      grid.size must beEqualTo(count(plain, gridColor)) // every gridline, none missed, none new
      grid.map(_.getAttribute("stroke-width")).toSet must beEqualTo(Set("2.5"))
      grid.map(_.getAttribute("stroke-dasharray")).toSet must beEqualTo(Set("1.5 3"))
      grid.forall(_.getAttribute("style").contains("stroke-opacity:0.5")) must beTrue
      count(svg, axisColor) must beEqualTo(count(plain, axisColor)) // the axes are as they were
      stroked(svg, axisColor).map(_.getAttribute("stroke-width")).toSet must beEqualTo(Set("1"))
    }
    "restyle the axis lines, or drop them and keep the numbers" in {
      val styled = (fig + Lines.axis(width = 3, color = "#00aa00")).svg(Theme.Light)
      stroked(styled, "#00aa00").size must beEqualTo(2)
      stroked(styled, "#00aa00").map(_.getAttribute("stroke-width")).toSet must beEqualTo(Set("3"))
      val bare = (fig + Lines.axis(visible = false)).svg(Theme.Light)
      count(bare, axisColor) must beEqualTo(count(plain, axisColor) - 2) // the two axes; the `∞` line is not one
      elements(bare, "text").size must beEqualTo(elements(plain, "text").size)
    }
    "draw tick marks of a length and a direction, and push the numbers out of their way" in {
      def tickLabelY(svg: String) = elements(svg, "text").filter(_.getTextContent == "0.5").map(number(_, "y"))
      val muted = Palette.brand.light.muted
      val out = (fig + Lines.ticks(length = 5)).svg(Theme.Light)
      val (ox, oy, ow, oh) = plotArea(out) // the marks take 5 px of the margins, so the plot area is smaller
      val marks = stroked(out, muted).filter(l => near(number(l, "y1"), oy + oh))
      marks.size must beGreaterThan(3) // one for each tick of the x axis
      marks.forall(l => near(number(l, "y2"), oy + oh + 5)) must beTrue
      stroked(out, muted).count(l => near(number(l, "x1"), ox)) must beGreaterThan(3) // and of the y axis
      tickLabelY(out).head - (oy + oh) must beCloseTo(tickLabelY(plain).head - (y0 + h0) + 5, 0.02) // 5 px farther out
      val in = (fig + Lines.ticks(length = 5, direction = TickDirection.In)).svg(Theme.Light)
      val (ix, iy, iw, ih) = plotArea(in)
      val inside = stroked(in, muted).filter(l => near(number(l, "y1"), iy + ih))
      inside.size must beGreaterThan(3)
      inside.forall(l => near(number(l, "y2"), iy + ih - 5)) must beTrue
      tickLabelY(in).head - (iy + ih) must beCloseTo(
        tickLabelY(plain).head - (y0 + h0),
        0.02
      ) // marks inside leave them
      stroked(plain, muted) must beEmpty // none unless asked for
      stroked((fig + Lines.ticks(length = 0)).svg(Theme.Light), muted) must beEmpty
    }
    "give tick marks a length of their own when asked for with none" in {
      val svg = (fig + Lines.ticks(color = "#123456")).svg(Theme.Light)
      val (ax, ay, aw, ah) = plotArea(svg)
      val bottom = stroked(svg, "#123456").filter(l => near(number(l, "y1"), ay + ah))
      bottom must not(beEmpty)
      bottom.forall(l => near(number(l, "y2"), ay + ah + 4)) must beTrue
    }
    "put minor ticks halfway between the majors of a linear axis" in {
      val fig = dots(1, 2, 3, 4, 5) + Scale.x(breaks = Seq(1.0, 2.0, 3.0, 4.0, 5.0), min = 1, max = 5) +
        Lines.minorTicks(color = "#123456", length = 2)
      val svg = fig.svg(Theme.Light)
      val (ax, ay, aw, ah) = plotArea(svg)
      val atBottom = stroked(svg, "#123456").filter(l => near(number(l, "y1"), ay + ah))
      val xs = atBottom.map(number(_, "x1")).sorted
      xs.size must beEqualTo(4)
      xs.zipWithIndex.foreach((x, i) => x must beCloseTo(ax + aw * (i + 0.5) / 4, 0.02))
      atBottom.forall(l => near(number(l, "y2"), ay + ah + 2)) must beTrue
    }
    "put the 2 to 9 of each decade on a log axis whose ticks are the decades" in {
      val fig = dots(1, 10, 100, 1000) + Scale.x(Transform.Log10, min = 1, max = 1000, breaks = Seq(1, 10, 100, 1000)) +
        Lines.minorGrid(color = "#123456")
      val svg = fig.svg(Theme.Light)
      val (ax, ay, aw, ah) = plotArea(svg)
      val minors =
        stroked(svg, "#123456").filter(l => l.getAttribute("x1") == l.getAttribute("x2")).map(number(_, "x1")).sorted
      minors.size must beEqualTo(24) // 2..9, 20..90, 200..900
      minors.head must beCloseTo(ax + aw * math.log10(2.0) / 3, 0.02)
      minors(8) must beCloseTo(ax + aw * (1 + math.log10(2.0)) / 3, 0.02) // 20 starts the second decade
      stroked(plain, "#123456") must beEmpty
    }
    "put the whole numbers between the ticks of a log axis that has 1, 2, 5 as its ticks" in {
      val fig = dots(1, 10, 100, 1000) + Scale.x(Transform.Log10, min = 1, max = 1000) +
        Lines.minorGrid(color = "#123456")
      val svg = fig.svg(Theme.Light)
      val (ax, ay, aw, ah) = plotArea(svg)
      val minors =
        stroked(svg, "#123456").filter(l => l.getAttribute("x1") == l.getAttribute("x2")).map(number(_, "x1"))
      minors.size must beEqualTo(18) // 3, 4, 6, 7, 8, 9 of each decade: 1, 2 and 5 are ticks
      val atMajor =
        Seq(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0).map(v => ax + aw * math.log10(v) / 3)
      minors.exists(m => atMajor.exists(near(_, m))) must beFalse
      minors.exists(near(_, ax + aw * math.log10(3.0) / 3)) must beTrue
    }
    "frame the plot area over the marks, and only when asked" in {
      val svg = (fig + Lines.frame(width = 3, color = "#aa0000")).svg(Theme.Light)
      val frame = elements(svg, "rect").filter(_.getAttribute("fill") == "none")
      frame.size must beEqualTo(1)
      (number(frame.head, "x"), number(frame.head, "y"), number(frame.head, "width"), number(frame.head, "height")) must
        beEqualTo((x0, y0, w0, h0))
      frame.head.getAttribute("stroke-width") must beEqualTo("3")
      frame.head.getAttribute("style") must beEqualTo("stroke:#aa0000")
      elements(plain, "rect").count(_.getAttribute("fill") == "none") must beEqualTo(0)
      elements((fig + Lines.frame(width = 3) + Lines.frame(visible = false)).svg(Theme.Light), "rect")
        .count(_.getAttribute("fill") == "none") must beEqualTo(0)
    }
    "fill the plot area behind everything else" in {
      val svg = (fig + Guide.panel(color = "#eeeeee", opacity = 1.0)).svg(Theme.Light)
      val all = elements(svg, "*")
      val panel = all.indexWhere(e => e.getTagName == "rect" && e.getAttribute("style").startsWith("fill:#eeeeee"))
      panel must beGreaterThan(-1)
      panel must beLessThan(all.indexWhere(_.getTagName == "line")) // before the gridlines, which are before the marks
      elements(svg, "rect")
        .find(_.getAttribute("style").startsWith("fill:#eeeeee"))
        .map(r => (number(r, "x"), number(r, "y"), number(r, "width"), number(r, "height"))) must
        beSome((x0, y0, w0, h0))
      elements((fig + Guide.panel(color = "#eeeeee") + Guide.panel(visible = false)).svg(Theme.Light), "rect")
        .count(_.getAttribute("style").startsWith("fill:#eeeeee")) must beEqualTo(0)
    }
    "refuse numbers that mean nothing" in {
      Lines.grid(width = 0) must throwAn[IllegalArgumentException]("width must be a positive number")
      Lines.grid(opacity = 2) must throwAn[IllegalArgumentException]("opacity must be between 0 and 1")
      Lines.ticks(length = -1) must throwAn[IllegalArgumentException]("length must be 0 or more")
      Lines.frame(color = "red", paint = Paint.Ink) must throwAn[IllegalArgumentException]("color or paint, not both")
    }
  }

  "the diagonal of a diagram" should {
    val fig = Plot.diagram(circleDiagram)
    def shades(svg: String) = elements(svg, "polygon").count(_.getAttribute("style").contains("fill-opacity:0.6"))
    "lose its shaded half plane, or take a colour and an opacity of its own" in {
      shades(fig.svg(Theme.Light)) must beEqualTo(1)
      shades((fig + Guide.diagonal(shade = false)).svg(Theme.Light)) must beEqualTo(0)
      val tinted = (fig + Guide.diagonal(shadeColor = "#c2185b", shadeOpacity = 0.15)).svg(Theme.Light)
      elements(tinted, "polygon").count(_.getAttribute("style") == "fill:#c2185b;fill-opacity:0.15") must beEqualTo(1)
      val noneThenBack = (fig + Guide.diagonal(shade = false) + Guide.diagonal(shade = true)).svg(Theme.Light)
      shades(noneThenBack) must beEqualTo(1) // the default shade comes back
    }
    "restyle the line, wherever it is, and leave a figure without one as it is" in {
      val svg = (fig + Guide.diagonal(color = "#00aa00", width = 3, dashed = true)).svg(Theme.Light)
      val line = elements(svg, "polyline").filter(_.getAttribute("style") == "fill:none;stroke:#00aa00")
      line.size must beEqualTo(1)
      line.head.getAttribute("stroke-width") must beEqualTo("3")
      line.head.getAttribute("stroke-dasharray") must beEqualTo("5 4")
      val barcode = Plot.barcode(circleDiagram)
      barcode + Guide.diagonal(shade = false) must beEqualTo(barcode)
    }
  }

  "the sides of the axes" should {
    val fig = Plot.diagram(circleDiagram) + Labels(x = "born", y = "dies")
    val plain = fig.svg(Theme.Light)
    val (x0, y0, w0, h0) = plotArea(plain)

    "put the x axis above the plot, its numbers and its label above that, and the room for them there" in {
      val svg = (fig + Scale.x(position = Side.Top)).svg(Theme.Light)
      val (x1, y1, w1, h1) = plotArea(svg)
      y1 must beCloseTo(y0 + 46.0, 0.02) // the axis band moved from under the plot to over it
      val across = lines(svg).filter(l =>
        l.getAttribute("style") == s"stroke:${Palette.brand.light.axis}" && l.getAttribute("y1") == l.getAttribute(
          "y2"
        ) && near(number(l, "x1"), x1) && near(number(l, "x2"), x1 + w1)
      )
      across.exists(l => near(number(l, "y1"), y1)) must beTrue // an axis line is the top of the plot
      across.exists(l => near(number(l, "y1"), y1 + h1)) must beFalse // and none is at the bottom
      val numbers = elements(svg, "text").filter(t =>
        t.getAttribute("text-anchor") == "middle" && t.getAttribute("transform").isEmpty && t.getTextContent != "born"
      )
      numbers.forall(number(_, "y") < y1) must beTrue
      number(elements(svg, "text").find(_.getTextContent == "born").get, "y") must beLessThan(
        numbers.map(number(_, "y")).min
      )
      textsOnPage(svg) must beTrue
    }
    "put the y axis at the right, its numbers and label beyond it, and the legend beyond them" in {
      val svg = (fig + Scale.y(position = Side.Right) + Style(legend = LegendPosition.Right)).svg(Theme.Light)
      val (x1, y1, w1, h1) = plotArea(svg)
      x1 must beCloseTo(14.0, 0.02) // nothing at the left now
      val numbers = elements(svg, "text").filter(t =>
        t.getAttribute("text-anchor") == "start" && t.getTextContent.matches("[0-9.]+")
      )
      numbers.size must beGreaterThan(3)
      numbers.forall(number(_, "x") > x1 + w1) must beTrue
      number(elements(svg, "text").find(_.getTextContent == "dies").get, "x") must beGreaterThan(
        numbers.map(number(_, "x")).max
      )
      val legend = elements(svg, "text").find(_.getTextContent == "H1").get
      number(legend, "x") must beGreaterThan(x1 + w1 + 42)
      textsOnPage(svg) must beTrue
    }
    "carry the tick marks and the -∞ line with them" in {
      val fig = Figure(marks =
        Vector(
          Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 1.0), (10.0, 10.0), (100.0, 100.0)), IndexedSeq(Paint.Series(0)))
        )
      ) +
        Scale.xy(Transform.Log10) + Lines.ticks(length = 4) + Scale.x(position = Side.Top) + Scale.y(position =
          Side.Right
        )
      val svg = fig.svg(Theme.Light)
      val (ax, ay, aw, ah) = plotArea(svg)
      val marks = stroked(svg, Palette.brand.light.muted)
      marks
        .filter(l => near(number(l, "y1"), ay))
        .forall(l => near(number(l, "y2"), ay - 4)) must beTrue // above the top axis
      marks
        .filter(l => near(number(l, "x1"), ax + aw))
        .forall(l => near(number(l, "x2"), ax + aw + 4)) must beTrue // right of the right axis
      elements(svg, "text").count(_.getTextContent == "-∞") must beEqualTo(2) // both bands have their label
      marks.count(l => near(number(l, "y1"), ay)) must beGreaterThan(2) // the -∞ line has its mark too
    }
    "refuse a side the axis cannot be on" in {
      Scale.x(position = Side.Left) must throwAn[IllegalArgumentException](
        "the x axis is at the bottom or the top, not the left"
      )
      Scale.y(position = Side.Top) must throwAn[IllegalArgumentException](
        "the y axis is at the left or the right, not the top"
      )
    }
  }

  "settings written as text" should {
    val fig = Plot.diagram(circleDiagram)
    "stand for the tweaks they name" in {
      def same(spec: String, tweak: Tweak) = (fig + Settings.parse(spec)) must beEqualTo(fig + tweak)
      same("title.size=18", Text(TextRole.Title, size = 18))
      same("Axis-Label.Weight=Bold", Text(TextRole.AxisLabel, weight = Weight.Bold))
      same("tick-label.angle=45", Text(TextRole.TickLabel, angle = 45))
      same("subtitle.align=center", Text(TextRole.Subtitle, align = Anchor.Middle))
      same("legend.font=Georgia, serif", Text(TextRole.Legend, font = "Georgia, serif"))
      same("note.italic=true", Text(TextRole.Note, italic = true))
      same("title.color=#aa0000", Text(TextRole.Title, color = "#aa0000"))
      same("grid.dash=dotted", Lines.grid(dash = Dash.Dotted))
      same("minor-grid.width=0.5", Lines.minorGrid(width = 0.5))
      same("axis-line.visible=false", Lines.axis(visible = false))
      same("frame.color=#333333", Lines.frame(color = "#333333"))
      same("ticks.length=4", Lines.ticks(length = 4))
      same("minor-ticks.direction=in", Lines.minorTicks(direction = TickDirection.In))
      same("panel.color=#f4f4f4", Guide.panel(color = "#f4f4f4"))
      same("diagonal.shade=none", Guide.diagonal(shade = false))
      same("diagonal.shade=#c2185b", Guide.diagonal(shadeColor = "#c2185b"))
      same("diagonal.dashed=true", Guide.diagonal(dashed = true))
      same("x-axis=top", Scale.x(position = Side.Top))
      same("y-axis=RIGHT", Scale.y(position = Side.Right))
    }
    "be added to a figure, a scene and a list in order" in {
      fig.withSetting("title.size=18") must beEqualTo(fig + Text(TextRole.Title, size = 18))
      val scene = Plot.points3D(circle)
      scene.withSetting("title.size=18") must beEqualTo(scene + Text(TextRole.Title, size = 18))
      scene.withSetting("tick-label.size=9") must throwAn[UnsupportedOperationException]("a 3-D scene has a title")
      Settings.parseAll(Seq("title.size=18", "title.size=20")).map(fig + _).get.elements.face(TextRole.Title).size must
        beEqualTo(20.0) // the later wins
      Settings.parseAll(Nil) must beNone
    }
    "say what is wrong, and what to say instead" in {
      Settings.parse("title.size") must throwAn[IllegalArgumentException]("should be element.property=value")
      Settings.parse("size=18") must throwAn[IllegalArgumentException]("should be element.property=value")
      Settings.parse("titel.size=18") must throwAn[IllegalArgumentException](
        "unknown element 'titel'; expected one of title, subtitle"
      )
      Settings.parse("title.colour=red") must throwAn[IllegalArgumentException](
        "unknown property 'colour' of title; expected one of size, weight"
      )
      Settings.parse("title.size=big") must throwAn[IllegalArgumentException]("'big' is not a number")
      Settings.parse("note.italic=maybe") must throwAn[IllegalArgumentException]("should be true or false")
      Settings.parse("title.weight=heavy") must throwAn[IllegalArgumentException]("expected one of light, normal")
      Settings.parse("grid.dash=wavy") must throwAn[IllegalArgumentException]("expected one of solid, dashed, dotted")
      Settings.parse("x-axis=left") must throwAn[IllegalArgumentException]("the x axis is at the bottom or the top")
      Settings.parse("x-axis=middle") must throwAn[IllegalArgumentException]("expected one of bottom, top, left, right")
    }
  }

  "a board of panels with no background" should {
    "have none either, and keep its own when one panel has a background" in {
      val a = Plot.diagram(circleDiagram)
      val b = Plot.barcode(circleDiagram)
      val clear = (Plot.side(a, b) + Style.clear).svgFile(Theme.Light)
      topRects(clear) must beEmpty
      val mixed = Plot.side(a + Style.clear, b).svgFile(Theme.Light)
      topRects(mixed).size must beEqualTo(1)
    }
  }

  /** The marks group of a rendering: what is drawn inside the plot area. */
  private def drawnMarks(svg: String): Seq[Element] =
    val group = elements(svg, "g").find(_.getAttribute("clip-path").nonEmpty).get
    val kids = group.getChildNodes
    (0 until kids.getLength).map(kids.item).collect { case e: Element => e }

  private def vertices(e: Element): Seq[(Double, Double)] =
    e.getAttribute("points").split(" ").toSeq.map { p =>
      val Array(x, y) = p.split(","); (x.toDouble, y.toDouble)
    }

  "the markers of a plot" should {
    val point = IndexedSeq((1.0, 1.0))
    def one(tweaks: Tweak*): Figure =
      tweaks.foldLeft(
        Figure(marks = Vector(Mark.Points(point, IndexedSeq(Paint.Series(0)), radius = 4.0)))
      )(_ + _) + Scale.xy(min = 0, max = 2)
    def marker(tweaks: Tweak*): Element = drawnMarks(one(tweaks*).svg(Theme.Light)).head
    val series = Palette.brand.seriesLight(0)
    val ring = Palette.brand.light.surface
    val circle = marker()
    val (cx, cy) = (number(circle, "cx"), number(circle, "cy"))
    def center(ps: Seq[(Double, Double)]) = (ps.map(_._1).sum / ps.size, ps.map(_._2).sum / ps.size)

    "draw a filled circle with a 2-pixel ring in the surface color, as they always did" in {
      circle.getTagName must beEqualTo("circle")
      circle.getAttribute("style") must beEqualTo(s"fill:$series;stroke:$ring")
      circle.getAttribute("stroke-width") must beEqualTo("2")
      number(circle, "r") must beEqualTo(4.0)
    }
    "draw each shape as what it is, about the same point" in {
      def polygon(shape: Shape, corners: Int) =
        val e = marker(Geom.points(shape = shape))
        e.getTagName must beEqualTo("polygon")
        val vs = vertices(e)
        vs.size must beEqualTo(corners)
        val (mx, my) = center(vs)
        mx must beCloseTo(cx, 0.05)
        my must beCloseTo(cy, 0.05)
        vs
      val up = polygon(Shape.Triangle, 3)
      up.head._2 must beLessThan(up.tail.map(_._2).min) // the apex is up
      val down = polygon(Shape.TriangleDown, 3)
      down.head._2 must beGreaterThan(down.tail.map(_._2).max)
      polygon(Shape.TriangleLeft, 3).head._1 must beLessThan(cx)
      polygon(Shape.TriangleRight, 3).head._1 must beGreaterThan(cx)
      polygon(Shape.Diamond, 4)
      polygon(Shape.Pentagon, 5)
      polygon(Shape.Hexagon, 6)
      val star = polygon(Shape.Star, 10)
      val radii = star.map((x, y) => math.hypot(x - cx, y - cy))
      radii.zipWithIndex.collect { case (r, i) if i % 2 == 0 => r }.forall(r => math.abs(r - 6.0) < 0.05) must beTrue
      radii.zipWithIndex.collect { case (r, i) if i % 2 == 1 => r }.forall(r => math.abs(r - 2.4) < 0.05) must beTrue
      marker(Geom.points(shape = Shape.Square)).getTagName must beEqualTo("rect")
      number(marker(Geom.points(shape = Shape.Dot)), "r") must beCloseTo(2.2, 0.01)
    }
    "draw a plus, a cross and a bar as strokes in the marker's own color" in {
      def strokes(shape: Shape) = marker(Geom.points(shape = shape))
      val plus = strokes(Shape.Plus)
      plus.getTagName must beEqualTo("path")
      plus.getAttribute("d").count(_ == 'M') must beEqualTo(2)
      plus.getAttribute("style") must beEqualTo(s"fill:none;stroke:$series")
      plus.getAttribute("stroke-width") must beEqualTo("2")
      strokes(Shape.Cross).getAttribute("d").count(_ == 'M') must beEqualTo(2)
      strokes(Shape.VLine).getAttribute("d").count(_ == 'M') must beEqualTo(1)
      strokes(Shape.HLine).getAttribute("d") must contain("L")
      marker(Geom.points(shape = Shape.Plus, edgeWidth = 3, edgeColor = "#aa0000")).getAttribute("stroke-width") must
        beEqualTo("3")
    }
    "draw text as a marker, in the marker's color, with a tooltip that keeps the page well-formed" in {
      val g = marker(Geom.points(shape = Shape.Glyph("★")))
      g.getTagName must beEqualTo("text")
      g.getTextContent must beEqualTo("★")
      g.getAttribute("text-anchor") must beEqualTo("middle")
      g.getAttribute("style") must beEqualTo(s"fill:$series")
      number(g, "font-size") must beCloseTo(9.6, 0.01)
      val tipped =
        Figure(marks = Vector(Mark.Points(point, IndexedSeq(Paint.Series(0)), tips = IndexedSeq("a star")))) +
          Geom.points(shape = Shape.Glyph("<&>")) + Scale.xy(min = 0, max = 2)
      val shown = drawnMarks(tipped.svg(Theme.Light)).head // parses: the glyph is escaped, the tip is inside a group
      shown.getTagName must beEqualTo("g")
      shown.getTextContent must contain("a star")
    }
    "take the edge away, or change its width, color, opacity and dash" in {
      marker(Geom.points(edgeWidth = 0)).getAttribute("style") must beEqualTo(s"fill:$series")
      marker(Geom.points(edgeWidth = 0)).hasAttribute("stroke-width") must beFalse
      marker(Geom.points(edgeVisible = false)).getAttribute("style") must beEqualTo(s"fill:$series")
      val styled = marker(Geom.points(edgeWidth = 3, edgeColor = "#aa0000", edgeOpacity = 0.5, edgeDash = Dash.Dashed))
      styled.getAttribute("style") must beEqualTo(s"fill:$series;stroke:#aa0000;stroke-opacity:0.5")
      styled.getAttribute("stroke-width") must beEqualTo("3")
      styled.getAttribute("stroke-dasharray") must beEqualTo("5 4")
      marker(Geom.points(edgePaint = Paint.Ink)).getAttribute("style") must
        beEqualTo(s"fill:$series;stroke:${Palette.brand.light.ink}")
    }
    "draw an open marker as an outline in its own color" in {
      val open = marker(Geom.points(fill = false))
      open.getAttribute("style") must beEqualTo(s"fill:none;stroke:$series")
      open.getAttribute("stroke-width") must beEqualTo("1.5")
      marker(Geom.points(fill = false, edgeWidth = 3)).getAttribute("stroke-width") must beEqualTo("3")
      marker(Geom.points(fill = false, edgeColor = "#aa0000")).getAttribute("style") must
        beEqualTo("fill:none;stroke:#aa0000")
    }
    "change the size and the opacity, and scale with the style" in {
      number(marker(Geom.points(size = 8)), "r") must beEqualTo(8.0)
      number(marker(Geom.points(size = 8), Style(markScale = 2)), "r") must beEqualTo(16.0)
      marker(Geom.points(opacity = 0.5)).getAttribute("style") must
        beEqualTo(s"fill:$series;fill-opacity:0.5;stroke:$ring;stroke-opacity:0.5")
    }
    "let a degree's markers differ from the figure's, which differ from the mark's own" in {
      val two = Figure(
        marks = Vector(
          Mark.Points(IndexedSeq((0.5, 0.5)), IndexedSeq(Paint.Series(0)), radius = 4.0),
          Mark.Points(IndexedSeq((1.5, 1.5)), IndexedSeq(Paint.Series(1)), radius = 4.0)
        ),
        legend = Vector(LegendItem("H0", Paint.Series(0)), LegendItem("H1", Paint.Series(1)))
      ) + Scale.xy(min = 0, max = 2) + Geom.points(size = 6, shape = Shape.Square) +
        Scale.degree(1, shape = Shape.Diamond, marker = MarkerStyle(size = Some(9), fill = Some(false)))
      val svg = two.svg(Theme.Light)
      val (h0, h1) = (drawnMarks(svg)(0), drawnMarks(svg)(1))
      h0.getTagName must beEqualTo("rect") // the figure's shape and size over the mark's
      number(h0, "width") must beCloseTo(2 * 0.9 * 6, 0.01)
      h1.getTagName must beEqualTo("polygon") // the degree's shape over the figure's
      h1.getAttribute("style") must startWith("fill:none;stroke:") // and the degree's open markers
      // the legend's keys follow: the first is a filled square, the second an open diamond
      val keys = elements(svg, "rect").filter(_.getAttribute("rx").isEmpty).filter(_.getParentNode.getNodeName == "svg")
      elements(svg, "polygon").filter(_.getParentNode.getNodeName == "svg").map(_.getAttribute("style")).head must
        startWith("fill:none")
      keys.exists(_.getAttribute("style").startsWith("fill:")) must beTrue
      val starred = (two + Scale.degree(1, marker = MarkerStyle(shape = Some(Shape.Star)))).svg(Theme.Light)
      vertices(drawnMarks(starred)(1)).size must beEqualTo(10) // a marker's shape over the degree's own
    }
    "be written as a string: names, matplotlib codes, R's pch numbers, glyphs" in {
      MarkerStyle.named("Triangle-Down").shape must beSome[Shape](Shape.TriangleDown)
      Seq(
        "o" -> Shape.Circle,
        "^" -> Shape.Triangle,
        "v" -> Shape.TriangleDown,
        "<" -> Shape.TriangleLeft,
        ">" -> Shape.TriangleRight,
        "s" -> Shape.Square,
        "D" -> Shape.Diamond,
        "p" -> Shape.Pentagon,
        "h" -> Shape.Hexagon,
        "*" -> Shape.Star,
        "+" -> Shape.Plus,
        "x" -> Shape.Cross,
        "|" -> Shape.VLine,
        "_" -> Shape.HLine,
        "." -> Shape.Dot
      ).foreach((code, shape) => MarkerStyle.named(code).shape must beSome[Shape](shape))
      MarkerStyle.named("pch:1") must beEqualTo(MarkerStyle(shape = Some(Shape.Circle), fill = Some(false)))
      MarkerStyle.named("16") must beEqualTo(MarkerStyle(shape = Some(Shape.Circle), fill = Some(true)))
      MarkerStyle.named("0") must beEqualTo(MarkerStyle(shape = Some(Shape.Square), fill = Some(false)))
      MarkerStyle.named("17") must beEqualTo(MarkerStyle(shape = Some(Shape.Triangle), fill = Some(true)))
      MarkerStyle.named("glyph:★").shape must beSome[Shape](Shape.Glyph("★"))
      MarkerStyle.named("★").shape must beSome[Shape](Shape.Glyph("★"))
      MarkerStyle.named("x").shape must beSome[Shape](Shape.Cross) // a code wins over the letter
      MarkerStyle.named("glyph:x").shape must beSome[Shape](Shape.Glyph("x"))
      Shape.named("D") must beEqualTo(Shape.Diamond)
      MarkerStyle.named("banana") must throwAn[IllegalArgumentException](
        "unknown marker 'banana'; expected one of names"
      )
      MarkerStyle.named("pch:26") must throwAn[IllegalArgumentException]("unknown pch 26; expected 0 to 25")
      MarkerStyle.named("glyph:") must throwAn[IllegalArgumentException]("one to four characters")
    }
    "keep the shape a degree has, and a degree's shape stays the first four in order" in {
      (0 until 6).map(Shape.ofDegree) must beEqualTo(
        Seq(Shape.Circle, Shape.Triangle, Shape.Square, Shape.Diamond, Shape.Circle, Shape.Triangle)
      )
    }
    "be the points of a 3-D scene too, with a size, an opacity and an edge, but round and filled" in {
      val scene = Plot.points3D(circle3)
      val plain = elements(scene.staticSvg(Theme.Light), "circle").head
      plain.getAttribute("stroke-width") must beEqualTo("1.5")
      val changed = elements(
        (scene + Geom.points(size = 6, opacity = 0.5, edgeVisible = false)).staticSvg(Theme.Light),
        "circle"
      ).head
      number(changed, "r") must beEqualTo(6.0)
      changed.getAttribute("style") must contain("fill-opacity:0.5")
      changed.hasAttribute("stroke-width") must beFalse
      scene + Geom.points(shape = Shape.Star) must throwAn[UnsupportedOperationException]("round, filled points")
      scene + Geom.points(fill = false) must throwAn[UnsupportedOperationException]("round, filled points")
    }
    "be written as settings, with the same checks" in {
      def markerOf(spec: String) = (Plot.diagram(circleDiagram) + Settings.parse(spec)).elements.marker
      markerOf("marker.shape=pch:1") must beEqualTo(MarkerStyle(shape = Some(Shape.Circle), fill = Some(false)))
      markerOf("marker.size=6") must beEqualTo(MarkerStyle(size = Some(6)))
      markerOf("marker.edge-width=0") must beEqualTo(MarkerStyle(edge = LineStyle(width = Some(0))))
      markerOf("marker.edge-visible=false") must beEqualTo(MarkerStyle(edge = LineStyle(visible = Some(false))))
      markerOf("marker.opacity=0.4") must beEqualTo(MarkerStyle(opacity = Some(0.4)))
      (Plot.diagram(circleDiagram) + Settings.parse("degree-1.size=8")).degrees(1).marker must
        beEqualTo(MarkerStyle(size = Some(8)))
      (Plot.diagram(circleDiagram) + Settings.parse("degree-1.color=#c2185b")).degrees(1).color must beSome("#c2185b")
      Settings.parse("marker.size=0") must throwAn[IllegalArgumentException]("size must be a positive number")
      Settings.parse("marker.opacity=2") must throwAn[IllegalArgumentException]("opacity must be between 0 and 1")
      Settings.parse("marker.shape=banana") must throwAn[IllegalArgumentException]("unknown marker 'banana'")
      Settings.parse("marker.colour=red") must throwAn[IllegalArgumentException](
        "unknown property 'colour' of marker; expected one of shape, size"
      )
      Settings.parse("degree-x.size=8") must throwAn[IllegalArgumentException]("a degree is written degree-0, degree-1")
    }
  }

  private lazy val circle3 =
    Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16), 0.0))

  "the legend of a plot" should {
    val diagram = Plot.diagram(circleDiagram)
    val barcode = Plot.barcode(circleDiagram)
    val (dx, dy, dw, dh) = plotArea(diagram.svg(Theme.Light))
    def entry(svg: String, words: String) =
      elements(svg, "text").filter(_.getTextContent == words).last // the legend comes after the axes

    "sit at the lower right of a diagram, inside the shaded half plane, with the plot taking the room" in {
      val svg = diagram.svg(Theme.Light)
      val h1 = entry(svg, "H1")
      number(h1, "x") must beGreaterThan(dx + dw / 2)
      number(h1, "x") must beLessThan(dx + dw)
      number(h1, "y") must beGreaterThan(dy + dh / 2)
      number(h1, "y") must beLessThan(dy + dh)
      // below the diagonal: on the side of it that the lower right corner is on
      val diagonal = elements(svg, "polyline").find(_.getAttribute("style").startsWith("fill:none;stroke:")).get
      val Seq((ax, ay), (bx, by)) = vertices(diagonal)
      def side(px: Double, py: Double) = math.signum((bx - ax) * (py - ay) - (by - ay) * (px - ax))
      side(number(h1, "x"), number(h1, "y")) must beEqualTo(side(dx + dw, dy + dh))
      // no column beside the plot: it is wider than when the legend is beside it
      val beside = plotArea((diagram + Style(legend = LegendPosition.Right)).svg(Theme.Light))
      dw must beGreaterThan(beside._3 + 40)
    }
    "stay where the plot puts it when a style is added, and go where it is told" in {
      val slides = (diagram + Style.slides).svg(Theme.Light)
      val (sx, sy, sw, sh) = plotArea(slides)
      number(entry(slides, "H1"), "x") must beGreaterThan(sx + sw / 2)
      number(entry(slides, "H1"), "x") must beLessThan(sx + sw)
      val right = (diagram + Style(legend = LegendPosition.Right)).svg(Theme.Light)
      val (rx, ry, rw, rh) = plotArea(right)
      number(entry(right, "H1"), "x") must beGreaterThan(rx + rw)
      diagram.withLegend("right") must beEqualTo(diagram + Style(legend = LegendPosition.Right))
      diagram.withLegend("lower-right") must beEqualTo(diagram + Style(legend = LegendPosition.LowerRight))
    }
    "go above or beside the plot, taking room, or in a corner of it, taking none" in {
      val none = plotArea((barcode + Style(legend = LegendPosition.Hidden)).svg(Theme.Light))
      def at(position: LegendPosition) =
        val svg = (barcode + Style(legend = position)).svg(Theme.Light)
        (plotArea(svg), entry(svg, "H0"), svg)
      val ((tx, ty, tw, th), top, topSvg) = at(LegendPosition.Top)
      ty must beCloseTo(none._2 + 30.0, 0.02) // one row of the legend, 10 + 20 px
      number(top, "y") must beLessThan(ty)
      textsOnPage(topSvg) must beTrue
      val ((lx, ly, lw, lh), left, leftSvg) = at(LegendPosition.Left)
      lx must beCloseTo(none._1 + 34.0 + TextWidth.of("H0", 12.0), 0.02) // the column: 34 and the widest label
      number(left, "x") must beEqualTo(34.0) // the column starts at 14, the text is 20 px after its key
      textsOnPage(leftSvg) must beTrue
      Seq(LegendPosition.UpperLeft, LegendPosition.UpperRight, LegendPosition.LowerLeft, LegendPosition.LowerRight)
        .foreach { corner =>
          val ((cx, cy, cw, ch), text, svg) = at(corner)
          (cx, cy, cw, ch) must beEqualTo(none) // a corner takes no room
          val right = corner == LegendPosition.UpperRight || corner == LegendPosition.LowerRight
          val upper = corner == LegendPosition.UpperLeft || corner == LegendPosition.UpperRight
          (number(text, "x") > cx + cw / 2) must beEqualTo(right)
          (number(text, "y") < cy + ch / 2) must beEqualTo(upper)
          number(text, "x") must beGreaterThan(cx)
          number(text, "y") must beLessThan(cy + ch)
          textsOnPage(svg) must beTrue
        }
      words((diagram + Style(legend = LegendPosition.Hidden)).svg()) must not(
        contain("H1")
      ) // the axes of a diagram are numbers
    }
    "be named, in any case, and refused when it is not a place" in {
      LegendPosition.named("Upper-Right") must beEqualTo(LegendPosition.UpperRight)
      LegendPosition.named("bottom-left") must beEqualTo(LegendPosition.LowerLeft)
      LegendPosition.named("left") must beEqualTo(LegendPosition.Left)
      LegendPosition.named("none") must beEqualTo(LegendPosition.Hidden)
      LegendPosition.named("middle") must throwAn[IllegalArgumentException](
        "expected one of right, left, top, bottom, upper-left, upper-right, lower-left, lower-right, none"
      )
    }
  }

  "the bars of a barcode" should {
    val bars = Persistence(circle, maxDimension = 1).bars
    def ends(f: Figure) = f.marks.collectFirst { case m: Mark.Segments => m.ends }.get
    "be in the order asked for within each degree: by birth, by persistence, or by death" in {
      val byBirth = ends(Plot.barcode(bars)).map(_._1._1)
      byBirth must beEqualTo(byBirth.sorted)
      val byLength = ends(Plot.barcode(bars, order = BarOrder.Persistence)).map((a, b) => b._1 - a._1)
      byLength must beEqualTo(byLength.sorted.reverse) // the longest first
      val byDeath = ends(Plot.barcode(bars, order = BarOrder.Death)).map(_._2._1)
      byDeath must beEqualTo(byDeath.sorted)
      byLength must not(beEqualTo(ends(Plot.barcode(bars)).map((a, b) => b._1 - a._1)))
      BarOrder.named("Persistence") must beEqualTo(BarOrder.Persistence)
      BarOrder.named("fastest") must throwAn[IllegalArgumentException]("expected one of birth, persistence, death")
    }
    "be thicker or fainter on request, and nothing else is" in {
      val bc = Plot.barcode(bars)
      (bc + Geom.segments(width = 3, opacity = 0.5)).marks.collect { case m: Mark.Segments =>
        (m.width, m.opacity)
      }.toSet must beEqualTo(Set((3.0, 0.5)))
      val diagram = Plot.diagram(bars)
      diagram + Geom.segments(width = 3) must beEqualTo(diagram)
      Geom.segments(width = 0) must throwAn[IllegalArgumentException]("width must be a positive number")
      (Plot.complex(circle, 0.5) + Geom.polygons(opacity = 0.4) + Geom.discs(opacity = 0.2)).marks.collect {
        case m: Mark.Polygons => m.opacity
      }.toSet must beEqualTo(Set(0.4))
      (bc + Settings.parse("segments.width=3")).marks.collect { case m: Mark.Segments => m.width }.toSet must
        beEqualTo(Set(3.0))
    }
  }

  /** Whether the point lies inside the polygon (an even-odd count of the edges a ray to the right crosses). */
  private def inside(p: (Double, Double), poly: Seq[(Double, Double)]): Boolean =
    poly.zip(poly.tail :+ poly.head).count { case ((x1, y1), (x2, y2)) =>
      (y1 > p._2) != (y2 > p._2) && p._1 < (x2 - x1) * (p._2 - y1) / (y2 - y1) + x1
    } % 2 == 1

  "the axes of a plot" should {
    val three =
      Figure(marks = Vector(Mark.Points(IndexedSeq((1.0, 1.0), (2.0, 2.0), (3.0, 3.0)), IndexedSeq(Paint.Series(0)))))
    def xs(svg: String) = drawnMarks(svg).map(number(_, "cx"))
    def ys(svg: String) = drawnMarks(svg).map(number(_, "cy"))

    "run backwards on request, and keep the boxes of a heatmap and the shade of a diagram where they belong" in {
      val fig = three + Scale.xy(min = 0, max = 4)
      val (xa, xb) = (xs(fig.svg(Theme.Light)), xs((fig + Scale.x(reverse = true)).svg(Theme.Light)))
      xa must beEqualTo(xa.sorted) // 1, 2, 3 left to right
      xb must beEqualTo(xb.sorted.reverse) // and right to left
      ys((fig + Scale.y(reverse = true)).svg(Theme.Light)) must beEqualTo(
        ys(fig.svg(Theme.Light)).sorted
      ) // y runs down
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 6, sigma = 0.05)
      val boxes = drawnMarks((image + Scale.x(reverse = true) + Scale.y(reverse = true)).svg(Theme.Light))
      boxes.forall(b => number(b, "width") > 0 && number(b, "height") > 0) must beTrue
      boxes.size must beEqualTo(drawnMarks(image.svg(Theme.Light)).size)
      // the half plane below the diagonal is where the data are below it, whichever way the axes run
      val diagram = Plot.diagram(circleDiagram)
      def shade(f: Figure) =
        vertices(elements(f.svg(Theme.Light), "polygon").find(_.getAttribute("style").contains("fill-opacity:0.6")).get)
      def corner(f: Figure, xBig: Boolean, yBig: Boolean) =
        val (x0, y0, w0, h0) = plotArea(f.svg(Theme.Light))
        val flipX = f.x.reverse
        val flipY = f.y.reverse
        val right = xBig != flipX // the large x is at the right, unless the axis runs backwards
        val up = yBig != flipY
        (if right then x0 + w0 - 1 else x0 + 1, if up then y0 + 1 else y0 + h0 - 1)
      Seq((false, false), (true, false), (false, true), (true, true)).foreach { (rx, ry) =>
        val f = diagram + Scale.x(reverse = rx) + Scale.y(reverse = ry)
        inside(corner(f, xBig = true, yBig = false), shade(f)) must beTrue // large x, small y: below the diagonal
        inside(corner(f, xBig = false, yBig = true), shade(f)) must beFalse // small x, large y: above it
      }
      success
    }
    "leave more or less room around the data when asked" in {
      val fixed = three + Scale.y(min = 0, max = 4)
      def first(f: Figure) =
        val svg = f.svg(Theme.Light)
        val (x0, y0, w0, h0) = plotArea(svg)
        ((xs(svg).head - x0) / w0, xs(svg).last)
      first(fixed)._1 must beCloseTo(0.04 * 2 / (2 + 2 * 0.04 * 2), 1e-3) // the default: 4% of the extent each side
      first(fixed + Scale.x(padding = 0.25))._1 must beCloseTo(0.25 * 2 / (2 + 2 * 0.25 * 2), 1e-3)
      first(fixed + Scale.x(padding = 0))._1 must beCloseTo(0.0, 1e-3) // the first point on the axis
      (fixed + Scale.x(padding = -1)) must throwAn[IllegalArgumentException]("padding is a fraction")
    }
    "keep a ratio of the length of a y unit to an x unit, and give it up" in {
      def unit(f: Figure) =
        val unitSquare = f.copy(marks =
          Vector(Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 0.0), (0.0, 1.0)), IndexedSeq(Paint.Series(0))))
        )
        val svg = unitSquare.svg(Theme.Light)
        val (cx, cy) = (xs(svg), ys(svg))
        (cx(1) - cx(0), cy(0) - cy(2)) // the pixels of a unit of x, of a unit of y
      val loose = Figure(width = 640, height = 300)
      val (lx, ly) = unit(loose)
      (ly / lx) must not(beCloseTo(2.0, 0.05)) // it is not so by itself
      val (ax, ay) = unit(loose + Scale.aspect(2))
      (ay / ax) must beCloseTo(2.0, 0.01)
      val (hx, hy) = unit(loose + Scale.aspect(0.5))
      (hy / hx) must beCloseTo(0.5, 0.01)
      val equal = Plot.diagram(circleDiagram)
      val (ex, ey) = unit(equal)
      (ey / ex) must beCloseTo(1.0, 0.01) // a diagram begins with equal units
      val (fx, fy) = unit(equal + Scale.aspect(free = true))
      (fy / fx) must not(beCloseTo(1.0, 0.01))
      Scale.aspect(0) must throwAn[IllegalArgumentException]("ratio must be a positive number")
      Scale.aspect(2, free = true) must throwAn[IllegalArgumentException]("a ratio or free = true, not both")
      loose.withAspect(2) must beEqualTo(loose + Scale.aspect(2))
    }
    "be drawn on a scale of your own" in {
      val cube = Transform.Custom("cube root", math.cbrt, v => v * v * v)
      val fig = Figure(marks =
        Vector(Mark.Points(IndexedSeq((1.0, 1.0), (8.0, 1.0), (27.0, 1.0)), IndexedSeq(Paint.Series(0))))
      ) +
        Scale.x(cube, min = 1, max = 27)
      val at = xs(fig.svg(Theme.Light))
      (at(1) - at(0)) must beCloseTo(at(2) - at(1), 0.05) // 1, 8 and 27 are one cube root apart
      val plain = xs((fig + Scale.x(Transform.Identity)).svg(Theme.Light))
      (plain(1) - plain(0)) must not(beCloseTo(plain(2) - plain(1), 0.5))
      cube.label must beEqualTo("cube root")
      cube.inverse(cube(8.0)) must beCloseTo(8.0, 1e-9)
    }
    "be written as settings" in {
      val f = three
      def axes(spec: String) = f + Settings.parse(spec)
      axes("x-axis.reverse=true").x.reverse must beTrue
      axes("y-axis.padding=0.1").y.padding must beSome(0.1)
      axes("x-axis.side=top").x.side must beSome(Side.Top)
      axes("y-axis.min=0").y.min must beSome(0.0)
      axes("x-axis.max=5").x.max must beSome(5.0)
      axes("x-axis.format=%.1f").x.format must beSome("%.1f")
      axes("aspect=2").aspect must beSome(2.0)
      axes("aspect=free").equalAspect must beFalse
      Settings.parse("x-axis.side=left") must throwAn[IllegalArgumentException](
        "the x axis is at the bottom or the top"
      )
      Settings.parse("x-axis.colour=red") must throwAn[IllegalArgumentException](
        "unknown property 'colour' of x-axis; expected one of side, reverse"
      )
      Settings.parse("aspect=wide") must throwAn[IllegalArgumentException]("'wide' is not a number")
    }
  }

  "the numbers of a plot" should {
    val signed = Figure(marks = Vector(Mark.Points(IndexedSeq((-1.0, -1.0), (1.0, 1.0)), IndexedSeq(Paint.Series(0)))))
    def tickWords(svg: String) = elements(svg, "text").filter(_.getTextContent.exists(_.isDigit)).map(_.getTextContent)

    "be written with the decimal separator and the minus sign asked for, and nothing else" in {
      val plain = tickWords(signed.svg(Theme.Light))
      plain must contain("-0.5")
      val german = tickWords((signed + Numbers(decimal = ",", minus = "−")).svg(Theme.Light))
      german must contain("−0,5")
      german must not(contain("-0.5"))
      german.size must beEqualTo(plain.size)
      german.forall(w => !w.contains("-") && !w.contains(".")) must beTrue
      tickWords((signed + Numbers.locale("sv")).svg(Theme.Light)) must contain(
        "−0,5"
      ) // Swedish: a comma and a true minus
      words((Plot.barcode(circleDiagram) + Numbers(minus = "−", decimal = ",")).svg()) must contain("H0") // not numbers
    }
    "group the digits of a large number, and write the color bar's numbers the same" in {
      val big =
        Figure(marks = Vector(Mark.Points(IndexedSeq((0.0, 0.0), (30000.0, 30000.0)), IndexedSeq(Paint.Series(0)))))
      tickWords((big + Numbers(grouping = " ")).svg(Theme.Light)) must contain("10 000")
      val image = Plot.persistenceImage(circleDiagram, 0, resolution = 6, sigma = 0.05)
      words((image + Numbers(decimal = ",")).svg()).exists(w => w.matches("0,[0-9]+")) must beTrue
      words(image.svg()).exists(w => w.matches("0\\.[0-9]+")) must beTrue
    }
    "be a style that writes numbers, and leaves anything that is not one" in {
      val de = NumberStyle(decimal = Some(","), minus = Some("−"))
      de("-0.25") must beEqualTo("−0,25")
      de("1e-4") must beEqualTo("1e−4")
      de("∞") must beEqualTo("∞")
      de("-∞") must beEqualTo("−∞")
      de("H0") must beEqualTo("H0")
      NumberStyle(grouping = Some(" "), decimal = Some(","))("1234567.5") must beEqualTo("1 234 567,5")
      NumberStyle()("-0.25") must beEqualTo("-0.25")
      Numbers(decimal = "1") must throwAn[IllegalArgumentException]("not a digit")
      Numbers(minus = "toolong") must throwAn[IllegalArgumentException]("one to three characters")
    }
    "be written as settings" in {
      (three + Settings.parse("numbers.locale=sv")).elements.numbers.decimal must beSome(",")
      (three + Settings.parse("numbers.minus=−")).elements.numbers.minus must beSome("−")
      (three + Settings.parse("numbers.grouping= ")).elements.numbers.grouping must beSome(" ")
      (three + Settings.parse("numbers.grouping=space")).elements.numbers.grouping must beSome(" ")
      Settings.parse("numbers.separator=,") must throwAn[IllegalArgumentException](
        "expected one of locale, decimal, grouping, minus"
      )
    }
  }

  private lazy val three =
    Figure(marks = Vector(Mark.Points(IndexedSeq((1.0, 1.0), (2.0, 2.0), (3.0, 3.0)), IndexedSeq(Paint.Series(0)))))

  "a long text" should {
    val fig = Plot.diagram(circleDiagram)
    def at(svg: String, start: String) =
      elements(svg, "text").find(_.getFirstChild.getTextContent.startsWith(start)).get

    "be cut to its limit, ending in an ellipsis, with the whole text as its tooltip" in {
      val cut = at((fig + Text(TextRole.Title, limit = 100)).svg(Theme.Light), "Persistence…")
      cut.getFirstChild.getTextContent must beEqualTo("Persistence…") // the letters that fit in 100 px with the dots
      elements((fig + Text(TextRole.Title, limit = 100)).svg(Theme.Light), "title").map(_.getTextContent) must
        contain("Persistence diagram")
      at((fig + Text(TextRole.Title, limit = 400)).svg(Theme.Light), "Persistence").getTextContent must
        beEqualTo("Persistence diagram") // fits: nothing cut, no tooltip
      val legend = Figure(marks = three.marks, legend = Vector(LegendItem("a rather long label", Paint.Series(0)))) +
        Text(TextRole.Legend, limit = 60)
      at(legend.svg(Theme.Light), "a rather").getFirstChild.getTextContent must beEqualTo("a rather l…")
    }
    "be cut by the width of its letters, not by how many there are" in {
      def shown(text: String, limit: Double) =
        val f = Figure(marks = three.marks, title = text) +
          Text(TextRole.Title, size = 15, limit = limit, weight = Weight.Normal)
        elements(f.svg(Theme.Light), "text").map(_.getFirstChild.getTextContent).find(_.startsWith(text.take(1))).get
      val narrow = "i" * 24
      val wide = "W" * 24
      shown(narrow, 120) must beEqualTo(narrow) // 24 narrow letters are 80 px: nothing cut
      shown(wide, 120) must not(beEqualTo(wide)) // 24 wide ones are 340 px
      shown(wide, 120) must endWith("…")
      // and what is kept fits the limit with its dots, one letter fewer would leave room for another
      val kept = shown(wide, 120).dropRight(1)
      TextWidth.of(kept + "…", 15) must beLessThanOrEqualTo(120.0)
      TextWidth.of(kept + "W…", 15) must beGreaterThan(120.0)
      // a bold face is wider, so it is cut sooner
      shown("n" * 10, 85) must beEqualTo("n" * 10)
      val bold = Figure(marks = three.marks, title = "n" * 10) + Text(
        TextRole.Title,
        size = 15,
        limit = 85,
        weight = Weight.Bold
      )
      elements(bold.svg(Theme.Light), "text").map(_.getFirstChild.getTextContent).find(_.startsWith("n")).get must
        not(beEqualTo("n" * 10))
    }
    "make a legend as wide as its widest label as drawn" in {
      def area(label: String) =
        plotArea(Figure(marks = three.marks, legend = Vector(LegendItem(label, Paint.Series(0)))).svg(Theme.Light))._3
      area("W" * 20) must beLessThan(area("i" * 20)) // a legend of capitals is wider than one of narrow letters
      area("W" * 20) must beLessThan(area("H0") - 100)
    }
    "refuse a limit that means nothing" in {
      Text(TextRole.Title, limit = 0) must throwAn[IllegalArgumentException]("limit must be a positive number")
      Settings.parse("title.limit=big") must throwAn[IllegalArgumentException]("'big' is not a number")
      (fig + Settings.parse("legend.limit=80")).elements.face(TextRole.Legend).limit must beSome(80.0)
    }
  }

  "the estimate of a text's width" should {
    "take a face from the first known name of a font list, and sans-serif is not serif" in {
      import TextWidth.Family
      Family.of(Style.DefaultFont) must beEqualTo(Family.Sans)
      Family.of("sans-serif") must beEqualTo(Family.Sans)
      Family.of("Georgia, serif") must beEqualTo(Family.Serif)
      Family.of("'Courier New', monospace") must beEqualTo(Family.Mono)
      Family.of("'Some Unknown Face', Times New Roman, monospace") must beEqualTo(Family.Serif) // the first it knows
      Family.of("Zapfino") must beEqualTo(Family.Sans) // when none is known
    }
    "add up the advance widths of Helvetica, and Times for a serif face" in {
      TextWidth.of("Hello", 10) must beCloseTo(22.78, 1e-9) // 722 + 556 + 222 + 222 + 556
      TextWidth.of("Hello", 10, TextWidth.Family.Serif) must beCloseTo(22.22, 1e-9) // 722 + 444 + 278 + 278 + 500
      TextWidth.of("Hello", 10, TextWidth.Family.Mono) must beCloseTo(30.0, 1e-9) // 0.6 em a character
      TextWidth.of("", 10) must beEqualTo(0.0)
      (' ' to '~').map(c => TextWidth.of(c.toString, 1000)).forall(_ > 0) must beTrue // the whole table is filled in
      TextWidth.of("W" * 10, 10) must beGreaterThan(TextWidth.of("i" * 10, 10) * 4) // letter by letter
    }
    "make bold letters wider and leave digits and spaces" in {
      TextWidth.of("persistence", 12, bold = true) must beGreaterThan(TextWidth.of("persistence", 12))
      TextWidth.of("123 456", 12, bold = true) must beEqualTo(TextWidth.of("123 456", 12))
      TextWidth.of("…∞", 10) must beCloseTo(20.0, 1e-9)
    }
  }

  "the caption, tag and description of a plot" should {
    val fig = Plot.diagram(circleDiagram)
    val plain = fig.svg(Theme.Light)
    val (x0, y0, w0, h0) = plotArea(plain)
    def at(svg: String, words: String) = elements(svg, "text").find(_.getTextContent == words).get

    "put a caption under the plot, and take its room" in {
      val svg = (fig + Labels(caption = "Source: a noisy circle")).svg(Theme.Light)
      val caption = at(svg, "Source: a noisy circle")
      number(caption, "y") must beCloseTo(fig.height - 4.0 * 1.0, 0.02)
      number(caption, "x") must beCloseTo(x0, 0.02) // at the left of the plot area
      plotArea(svg)._4 must beCloseTo(h0 - 16.0, 0.02) // 16 px of room
      at((fig + Labels(caption = "c") + Text(TextRole.Caption, align = Anchor.End)).svg(Theme.Light), "c")
        .getAttribute("text-anchor") must
        beEqualTo("end")
      textsOnPage(svg) must beTrue
      // the small print rises above it
      val noted = Figure(marks =
        Vector(Mark.Points(IndexedSeq((1.0, Double.NaN), (2.0, 2.0)), IndexedSeq(Paint.Series(0))))
      ) + Labels(caption = "cap")
      val ns = noted.svg(Theme.Light)
      number(elements(ns, "text").find(_.getTextContent.contains("not shown")).get, "y") must beLessThan(
        number(at(ns, "cap"), "y") - 10
      )
    }
    "put a tag in the corner, and a description where a screen reader finds it" in {
      val tagged = (fig + Labels(tag = "(a)")).svg(Theme.Light)
      val tag = at(tagged, "(a)")
      (number(tag, "x"), number(tag, "y"), number(tag, "font-size")) must beEqualTo((6.0, 22.0, 16.0))
      tag.getAttribute("font-weight") must beEqualTo("600")
      elements(plain, "desc").map(_.getTextContent).head must contain("Persistence diagram: 17 points (H0: 16, H1: 1)")
      elements((fig + Labels(description = "Loops of a circle")).svg(Theme.Light), "desc").map(_.getTextContent) must
        beEqualTo(Seq("Loops of a circle"))
      elements(dots(1).svg(Theme.Light), "desc") must beEmpty // a plot made by hand has none unless given
      elements(Plot.barcode(circleDiagram).svg(Theme.Light), "desc").head.getTextContent must contain(
        "17 bars (H0: 16, H1: 1)"
      )
    }
    "letter the panels of a board" in {
      val board = Plot.side(Plot.diagram(circleDiagram), Plot.barcode(circleDiagram))
      board.tagged().plots.map(_.tag) must beEqualTo(Seq("(a)", "(b)"))
      board.tagged("%s.", "A").plots.map(_.tag) must beEqualTo(Seq("A.", "B."))
      board.tagged("%s)", "1").plots.map(_.tag) must beEqualTo(Seq("1)", "2)"))
      board.tagged().svgFile(Theme.Light) must contain(">(b)</text>")
      board.tagged("tag") must throwAn[IllegalArgumentException]("has no %s for the letter")
      board.tagged("%s", "i") must throwAn[IllegalArgumentException]("should be a, A or 1")
    }
    "belong to a figure, not to a scene" in {
      Plot.points3D(circle) + Labels(caption = "c") must throwAn[UnsupportedOperationException]("(caption)")
      fig.withCaption("c") must beEqualTo(fig + Labels(caption = "c"))
      fig.withTag("(a)") must beEqualTo(fig + Labels(tag = "(a)"))
      fig.withDescription("d") must beEqualTo(fig + Labels(description = "d"))
    }
  }

  "a size in physical units" should {
    "be that many pixels at 96 to the inch" in {
      Size.inches(3.4, 2.6) must beEqualTo(Size(326, 250))
      Size.cm(8.6, 6) must beEqualTo(Size(325, 227))
      Size.mm(86) must beEqualTo(Size(width = 325))
      Size.pt(245) must beEqualTo(Size(width = 327)) // a journal column
      Size.inches(height = 3) must beEqualTo(Size(height = 288))
      Plot.diagram(circleDiagram).withSize(Size.inches(3.4, 2.6).width.getOrElse(0), 250).width must beEqualTo(326)
    }
    "be written with a unit in a setting" in {
      val fig = Plot.diagram(circleDiagram)
      val sized = fig + Settings.parse("size.width=3.4in") + Settings.parse("size.height=6.5cm")
      (sized.width, sized.height) must beEqualTo((326, 246))
      (fig + Settings.parse("size.width=300")).width must beEqualTo(300)
      (fig + Settings.parse("size.height=245pt")).height must beEqualTo(327)
      (fig + Settings.parse("size.width=86mm")).width must beEqualTo(325)
      Settings.parse("size.width=wide") must throwAn[IllegalArgumentException]("is not a number")
      Settings.parse("size.depth=3") must throwAn[IllegalArgumentException]("expected one of width, height")
    }
  }

  "the camera of a 3-D scene" should {
    // A (1,0,0), B (-1,0,0) and C (0,0,1): from the front (azimuth 0, elevation 0) A and B are apart and C is above them;
    // from the side (azimuth 90) A and B are in line with the viewer; from above (elevation 90) the three are level
    val cloud = Array(Array(1.0, 0.0, 0.0), Array(-1.0, 0.0, 0.0), Array(0.0, 0.0, 1.0))
    val scene = Plot.points3D(cloud)
    // the points drawn, in the order of the cloud (the order of drawing is by depth, so find each by its own tooltip-free
    // position: left to right and bottom to top are what the checks use)
    def dots(s: Scene) = elements(s.staticSvg(Theme.Light), "circle").map(c => (number(c, "cx"), number(c, "cy")))
    def spread(values: Seq[Double]) = values.max - values.min
    def spreadX(s: Scene) = spread(dots(s).map(_._1))
    def spreadY(s: Scene) = spread(dots(s).map(_._2))

    "look from where it is told to, in degrees" in {
      val front = scene + Camera(azimuth = 0, elevation = 0)
      spreadX(front) must beGreaterThan(100.0) // A and B are apart
      spreadY(front) must beGreaterThan(50.0) // and C is above
      val side = scene + Camera(azimuth = 90, elevation = 0)
      spreadX(side) must beCloseTo(0.0, 1e-6) // A and B are in line, C is between them
      val above = scene + Camera(azimuth = 0, elevation = 90)
      spreadY(above) must beCloseTo(0.0, 1e-6) // from above the height does not show
      spreadX(above) must beCloseTo(spreadX(front), 1e-6)
      (scene.azimuth, scene.elevation, scene.zoom) must beEqualTo((-35.0, 25.0, 1.0)) // what a scene begins with
      scene.withCamera(azimuth = 10, elevation = 20, zoom = 2).azimuth must beEqualTo(10.0)
    }
    "come closer by its zoom, round the middle of the plot" in {
      val one = scene + Camera(azimuth = 0, elevation = 0, zoom = 1)
      val two = scene + Camera(zoom = 2) + Camera(azimuth = 0, elevation = 0)
      (spreadX(two) / spreadX(one)) must beCloseTo(2.0, 1e-3) // positions are written to two decimals
      (spreadY(two) / spreadY(one)) must beCloseTo(2.0, 1e-3)
      val xsOne = dots(one).map(_._1)
      val xsTwo = dots(two).map(_._1)
      ((xsOne.max + xsOne.min) / 2) must beCloseTo(scene.width / 2.0, 1e-6)
      ((xsTwo.max + xsTwo.min) / 2) must beCloseTo(scene.width / 2.0, 1e-6) // the middle stays
    }
    "start a page's canvas there, which can still be turned and zoomed" in {
      val page = (scene + Camera(azimuth = 90, elevation = 10, zoom = 1.5)).fragment(Theme.Light)
      page must contain(s"let az=${math.toRadians(90.0)},el=${math.toRadians(10.0)},zoom=1.5;")
      scene.fragment(Theme.Light) must contain("zoom=1;") // as it always was
      page must contain("drag to turn, scroll to zoom")
    }
    "change only what is given, and commute with the rest" in {
      val a = scene + Camera(azimuth = 40) + Camera(zoom = 3)
      (a.azimuth, a.elevation, a.zoom) must beEqualTo((40.0, 25.0, 3.0))
      val b = scene + Camera(azimuth = 40) + Style.print + Size(500, 500) + Camera(zoom = 3) + Palette.print
      val c = scene + Palette.print + Camera(zoom = 3) + Size(500, 500) + Style.print + Camera(azimuth = 40)
      b must beEqualTo(c)
    }
    "refuse a view that means nothing, in words" in {
      Camera(elevation = 100) must throwAn[IllegalArgumentException]("the elevation is an angle from -90")
      Camera(zoom = 0) must throwAn[IllegalArgumentException]("the zoom should be between 0.2 and 8, not 0")
      Camera(zoom = 9) must throwAn[IllegalArgumentException]("the zoom should be between 0.2 and 8, not 9")
      Camera(azimuth = Double.NaN) must throwAn[IllegalArgumentException]("the azimuth is an angle in degrees")
      Plot.diagram(circleDiagram) + Camera(zoom = 2) must throwAn[UnsupportedOperationException](
        "is the camera of a 3-D scene; a 2-D figure has none"
      )
      Plot.side(Plot.diagram(circleDiagram)) + Camera(zoom = 2) must throwAn[UnsupportedOperationException](
        "a 2-D figure has none"
      )
    }
    "be written as settings" in {
      scene.withSetting("camera.azimuth=90") must beEqualTo(scene + Camera(azimuth = 90))
      scene.withSetting("camera.elevation=-10").elevation must beEqualTo(-10.0)
      scene.withSetting("camera.zoom=1.5").zoom must beEqualTo(1.5)
      Settings.parse("camera.roll=3") must throwAn[IllegalArgumentException](
        "unknown property 'roll' of camera; expected one of azimuth, elevation, zoom"
      )
      Settings.parse("camera.zoom=close") must throwAn[IllegalArgumentException]("'close' is not a number")
      Plot.diagram(circleDiagram).withSetting("camera.zoom=2") must throwAn[UnsupportedOperationException](
        "a 2-D figure has none"
      )
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
