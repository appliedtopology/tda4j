package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

/** Zooming and panning: what the page is told to move, and what a still picture of a zoomed page shows. Each example is
  * built so that the plausible wrong implementation fails it: a window ignored by the still, ticks left at the whole
  * range, a point that is moved by the zoom's scale instead of keeping its size, a page that carries zoom attributes
  * nobody asked for.
  */
class ZoomSpec extends Specification:
  sequential

  private val dots = Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 2.0), (4.0, 3.0)), IndexedSeq(Paint.Ink))
  private val bar = Mark.Segments(IndexedSeq(((0.0, 0.0), (4.0, 3.0))), IndexedSeq(Paint.Ink))
  private val figure = Figure(marks = Vector(bar, dots))
  private val zoomable = Panel(figure).zoomable()

  private def tags(zoom: Option[String]): Svg.Tags = Svg.Tags((_, _) => "", false, None, zoom)

  /** The two axes of a tagged picture: offset, length, low, high of each. */
  private def frameOf(svg: String): Seq[Seq[Double]] =
    val spec = """data-fr="([^"]*)"""".r.findFirstMatchIn(svg).get.group(1)
    spec.split(";").toSeq.map(_.split(",").toSeq.take(4).map(_.toDouble))

  private def attribute(open: String, name: String): Option[String] =
    s"""$name="([^"]*)"""".r.findFirstMatchIn(open).map(_.group(1))

  private def labels(svg: String): Seq[String] =
    """<text[^>]*>([^<]*)</text>""".r.findAllMatchIn(svg).map(_.group(1)).toSeq

  "a picture rendered for a zoomable page" should {
    val plain = Svg.render(figure, Theme.Light)
    val tagged = Svg.renderTagged(figure, Theme.Light, false, tags(Some("b")))

    "carry no zoom attribute when nobody asked for one" in
      Seq("data-z", "data-k", "data-c", "data-zm", "data-fr").map(a => plain.contains(a) must beFalse).reduce(_.and(_))

    "wrap marks by kind, so strokes can keep their width and points their size" in {
      val kinds = """<g data-z="(.)">""".r.findAllMatchIn(tagged).map(_.group(1)).toSeq
      kinds === Seq("s", "p")
    }

    "give every point its own centre, where the frame puts its data" in {
      val ax = frameOf(tagged)
      def at(v: Double, a: Seq[Double]) = a(0) + (v - a(2)) / (a(3) - a(2)) * a(1)
      val centres = """<circle[^>]*data-c="([^"]*)"""".r.findAllMatchIn(tagged).map(_.group(1)).toSeq
      val expected = dots.at.map((x, y) => (at(x, ax(0)), ax(1)(0) + ax(1)(1) - (at(y, ax(1)) - ax(1)(0))))
      def near(a: String, b: (Double, Double)) =
        val Array(x, y) = a.split(",").map(_.toDouble)
        math.abs(x - b._1) < 0.01 && math.abs(y - b._2) < 0.01
      (centres.size === 3).and(centres.zip(expected).forall(near) must beTrue)
    }

    "tell the page how to draw each axis again: the kind of every tick element and the rule for the ticks" in {
      val kinds = """data-k="([a-z]+)"""".r.findAllMatchIn(tagged).map(_.group(1)).toSet
      val zm = """data-zm="([^"]*)"""".r.findFirstMatchIn(tagged).get.group(1)
      (kinds must contain(allOf("gx", "gy", "lx", "ly"))).and(zm must contain("&quot;m&quot;:&quot;a&quot;"))
    }

    "keep a diagram's infinity label out of the rule, as a fixed extra" in {
      val fig = figure.copy(y = Axis(extra = IndexedSeq((3.0, "∞"))))
      val svg = Svg.renderTagged(fig, Theme.Light, false, tags(Some("y")))
      val zm = """data-zm="([^"]*)"""".r.findFirstMatchIn(svg).get.group(1)
      (zm must contain("∞")).and(zm must contain("&quot;ax&quot;:&quot;y&quot;"))
    }
  }

  "a zoomable panel" should {
    def one(panel: Panel) = Interactive().withPanel(panel)

    "mark its own box on the page, and no other" in {
      val html = Interactive().withPanel(zoomable).withPanel(Panel(figure)).fragment(Theme.Light)
      ("data-zoomable=\"b\"".r.findAllIn(html).size === 1).and("data-zm=\"".r.findAllIn(html).size === 1)
    }

    "need no slider or selection: a plot that only zooms is a page" in {
      val html = one(zoomable).fragment(Theme.Light)
      (html must contain("data-zoomable=\"b\"")).and(html must contain("tda4j-zoombar"))
    }

    "leave a page without one exactly as it was: no marker, no zoom style" in {
      val html = Interactive().withSlider("t", 0, 1).withPanel(Panel(figure)).fragment(Theme.Light)
      (html must not(contain("data-zoomable=\"")))
        .and(html must not(contain("data-zm=\"")))
        .and(html must not(contain("data-z=\"")))
    }

    "say which axes it zooms" in {
      val x = one(Panel(figure).zoomable(ZoomAxes.X)).fragment(Theme.Light)
      val y = one(Panel(figure).zoomable(ZoomAxes.Y)).fragment(Theme.Light)
      (x must contain("data-zoomable=\"x\"")).and(y must contain("data-zoomable=\"y\""))
    }

    "be refused for an axis the page cannot draw the ticks of, saying which and why" in {
      // 0 on a log axis sits on a `-inf` line the window could not move; a transform of one's own has ticks the page cannot make
      val log = Figure(marks = Vector(dots), x = Axis(transform = Transform.Log10))
      val cube = Transform.Custom("cube root", math.cbrt, v => v * v * v)
      val own = Figure(marks = Vector(dots), y = Axis(transform = cube))
      val text = Figure(marks = Vector(dots), x = Axis(format = Some("%s items")))
      (one(Panel(log).zoomable()).fragment() must throwAn[IllegalArgumentException](
        ".*panel 0 is zoomable, but its x axis is logarithmic with a .*line for what is at 0.*"
      ))
        .and(
          one(Panel(own).zoomable()).fragment() must throwAn[IllegalArgumentException](
            ".*panel 0 is zoomable, but its y axis uses a transform of your own .cube root. that the page cannot.*"
          )
        )
        .and(
          one(Panel(text).zoomable()).fragment() must throwAn[IllegalArgumentException](
            ".*zoomable, but its x axis has the number format %s items.*"
          )
        )
    }

    "be accepted for the axes the page can draw again: log, square-root, symmetric log, reversed, formatted, slanted" in {
      val positive = Mark.Points(IndexedSeq((1.0, 10.0), (10.0, 100.0), (100.0, 1000.0)), IndexedSeq(Paint.Ink))
      val mixed = Mark.Points(IndexedSeq((-5.0, -3.0), (0.0, 0.0), (50.0, 80.0)), IndexedSeq(Paint.Ink))
      val figures = Seq(
        Figure(marks = Vector(positive), x = Axis(transform = Transform.Log10), y = Axis(transform = Transform.Log10)),
        Figure(marks = Vector(dots), x = Axis(transform = Transform.Sqrt)),
        Figure(marks = Vector(mixed), x = Axis(transform = Transform.Log10)),
        Figure(marks = Vector(dots), y = Axis(reverse = true), x = Axis(reverse = true)),
        Figure(marks = Vector(dots), x = Axis(format = Some("%,.1f")), y = Axis(format = Some("%08.2e"))),
        Figure(marks = Vector(dots)) + Text(TextRole.TickLabel, angle = 30)
      )
      figures.flatMap(f => Interactive.zoomProblem(f)) must beEmpty
    }

    "tell the page how to write the numbers: the format, the separators, the slant" in {
      val styled = Figure(marks = Vector(dots), x = Axis(format = Some("%.1f"))) +
        Text(TextRole.TickLabel, angle = 30) + Numbers(decimal = ",", minus = "\u2212")
      val spec = """data-zm="([^"]*)"""".r
        .findFirstMatchIn(
          Svg.renderTagged(styled, Theme.Light, false, tags(Some("b")))
        )
        .get
        .group(1)
      (spec must contain("&quot;f&quot;:&quot;%.1f&quot;"))
        .and(spec must contain("&quot;a&quot;:30.0"))
        .and(spec must contain("&quot;n&quot;:[&quot;,&quot;,null,&quot;\u2212&quot;]"))
    }
  }

  "Interactive.zoomable" should {
    val log = Figure(marks = Vector(dots), x = Axis(transform = Transform.Log10))
    val page = Interactive().withPanel(Panel(figure)).withPanel(Panel(log)).withPanel(Panel(figure))

    "zoom the panels whose axes the page can draw again, and leave the others as they are" in
      page.zoomable().panels.collect { case Panel.Flat(_, _, _, zoom) => zoom } ===
      Vector(Some(ZoomAxes.Both), None, Some(ZoomAxes.Both))

    "say which axes it zooms" in
      page.zoomable(ZoomAxes.X).panels.collect { case Panel.Flat(_, _, _, zoom) => zoom }.flatten.distinct ===
      Vector(ZoomAxes.X)
  }

  "the pages that link their views" should {
    val noisy = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
    val computed = Persistence(noisy, maxDimension = 1, maxFiltrationValue = 1.0)
    def zooms(i: Interactive) = i.panels.collect { case Panel.Flat(_, _, _, zoom) => zoom }

    "zoom the cloud and the diagram in both axes and the barcode along the filtration" in
      zooms(Plot.exploreOn(noisy, computed.bars)) === Vector(Some(ZoomAxes.Both), Some(ZoomAxes.Both), Some(ZoomAxes.X))

    "zoom a linked sweep, and leave an unlinked one exactly as it was" in {
      val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
      val linked = Plot.filtration(circle, link = true, max = 2.0, barcode = true)
      val plain = Plot.filtration(circle, max = 2.0, barcode = true)
      (zooms(linked) === Vector(Some(ZoomAxes.Both), Some(ZoomAxes.Both), Some(ZoomAxes.X)))
        .and(zooms(plain).flatten must beEmpty)
    }
  }

  "a still of a zoomed page" should {
    val page = Interactive().withPanel(zoomable)
    val window = Window(1.0, 3.0, 1.0, 2.5)
    val state = PageState().zoom(0, window)
    val zoomed = page.at(state).svgFile(Theme.Light)
    val whole = page.at(PageState()).svgFile(Theme.Light)
    val figureZoomed = Interactive.still(figure, Map.empty, Map.empty, Map.empty, Some(window))

    "show the window's range, not the data's" in {
      val fr = frameOf(Svg.renderTagged(figureZoomed, Theme.Light, false, tags(Some("b"))))
      ((fr(0)(2), fr(0)(3)) === ((1.0, 3.0))).and((fr(1)(2), fr(1)(3)) === ((1.0, 2.5)))
    }

    "write ticks for that range: round values inside it, none outside" in {
      val numbers = labels(zoomed).map(_.toDouble)
      val inside = numbers.forall(v => v >= 1.0 && v <= 3.0)
      (inside must beTrue)
        .and(labels(zoomed) must contain(allOf("1", "1.5", "2", "2.5", "3")))
        .and(labels(zoomed) must not(contain("0")))
        .and(labels(whole) must contain(allOf("0", "4")))
    }

    "keep what is inside the window where the window puts it" in {
      val fr = frameOf(Svg.renderTagged(figureZoomed, Theme.Light, false, tags(Some("b"))))
      val svg = Svg.renderTagged(figureZoomed, Theme.Light, false, tags(Some("b")))
      // the point (1, 2) sits on the left edge of the picture's plot area, at the height 2 of 1 to 2.5
      val Array(cx, cy) =
        """<circle[^>]*data-c="([^"]*)"""".r.findAllMatchIn(svg).map(_.group(1)).toSeq(1).split(",").map(_.toDouble)
      val x = fr(0)(0)
      val y = fr(1)(0) + fr(1)(1) * (1.0 - (2.0 - 1.0) / (2.5 - 1.0))
      (math.abs(cx - x) < 0.01).and(math.abs(cy - y) < 0.01)
    }

    "be refused for a panel that does not zoom, naming the ones that do" in {
      val mixed = Interactive().withPanel(Panel(figure)).withPanel(zoomable)
      (mixed.at(PageState().zoom(0, window)) must throwAn[IllegalArgumentException](
        ".*panel 0 is not a zoomable panel.*zoomable panels: 1.*"
      ))
        .and(mixed.at(PageState().zoom(1, window)).svgFile(Theme.Light) must contain("<svg"))
    }

    "leave every other panel alone" in {
      val two = Interactive().withPanel(zoomable).withPanel(zoomable)
      def axes(shot: Snapshot, panel: Int) =
        frameOf(Svg.renderTagged(shot.parts(panel).asInstanceOf[Figure], Theme.Light, false, tags(Some("b"))))
      val whole = two.at(PageState())
      val shot = two.at(PageState().zoom(1, window))
      (axes(shot, 0) === axes(whole, 0)).and(axes(shot, 1) !== axes(whole, 1))
    }
  }

  "a window" should {
    "have its low ends below its high ends" in
      (Window(1.0, 1.0, 0.0, 1.0) must throwAn[IllegalArgumentException](".*x0 < x1.*"))
        .and(Window(0.0, 1.0, 2.0, 1.0) must throwAn[IllegalArgumentException](".*y0 < y1.*"))
        .and(Window(0.0, Double.PositiveInfinity, 0.0, 1.0) must throwAn[IllegalArgumentException](".*all numbers.*"))
  }
