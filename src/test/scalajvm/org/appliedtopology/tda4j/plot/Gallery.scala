package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import scala.util.Random

/** Writes one file per kind of plot into a directory, for looking at them (`sbt "plot/Test/runMain
  * org.appliedtopology.tda4j.plot.Gallery out/"`): `.svg` files in the light and dark themes, `.html` for 3-D.
  */
object Gallery:
  def noisyCircle(
    n: Int,
    noise: Double,
    seed: Long,
    cx: Double = 0,
    cy: Double = 0,
    r: Double = 1
  ): Array[Array[Double]] =
    val rng = Random(seed)
    Array.tabulate(n) { i =>
      val t = 2 * math.Pi * i / n
      Array(cx + r * math.cos(t) + noise * rng.nextGaussian(), cy + r * math.sin(t) + noise * rng.nextGaussian())
    }

  def main(args: Array[String]): Unit =
    val dir = args.headOption.getOrElse("target/plot-gallery")
    def out(name: String) = s"$dir/$name"

    val circle = noisyCircle(40, 0.08, 1)
    val d = Persistence(circle, maxDimension = 1)
    import d.given
    Plot.barcode(d).save(out("barcode.svg"))
    Plot.barcode(d).save(out("barcode-dark.svg"), Some(Theme.Dark))
    Plot.diagram(d).save(out("diagram.svg"))
    Plot.diagram(d).save(out("diagram-dark.svg"), Some(Theme.Dark))
    Plot.side(Plot.barcode(d, longest = Some(8)), Plot.diagram(d)).save(out("side.svg"))

    val two = noisyCircle(30, 0.06, 2) ++ noisyCircle(24, 0.05, 3, cx = 2.6, r = 0.7)
    val d2 = Persistence(two, maxDimension = 1)
    Plot.persistenceImage(d2, 1, resolution = 24, sigma = 0.08).save(out("persistence-image.svg"))
    Plot.landscape(d2, 0, levels = 3).save(out("landscape.svg"))
    Plot.barcode(d2).save(out("barcode-two-circles.svg"))

    Plot.complex(circle, 0.45).save(out("complex-vr.svg"))
    Plot.complex(circle, 0.25, Cech).save(out("complex-cech.svg"))
    Plot.complex(circle, 0.25, AlphaShapes).save(out("complex-alpha.svg"))

    val loop = Persistence(circle, maxDimension = 1, engine = Persistence.Engine.Chunks)
    import loop.given
    val cycle = loop.dim(1).longest.get.representative
    Plot.cycle(circle, cycle).over(Plot.complex(circle, loop.dim(1).longest.get.birth)).save(out("cycle.svg"))
    val cocycles = Persistence(circle, maxDimension = 1, representatives = Representatives.Cocycles)
    import cocycles.given
    val h1 = cocycles.dim(1).longest.get
    Plot.cocycle(circle, h1.representative).save(out("cocycle-at-death.svg"))
    Plot.cocycle(circle, h1.representative, at = h1.birth).over(Plot.complex(circle, h1.birth)).save(out("cocycle.svg"))

    val ms = EuclideanMetricSpace(circle)
    val cc = CircularCoordinates.compute(ms, 0.6, 0)
    Plot.circularCoordinates(circle, cc.theta).save(out("circular.svg"))

    val (h, w) = (24, 32)
    val img = Image(
      for i <- 0 until h; j <- 0 until w
      yield
        val (y, x) = (i - h / 2.0, j - w / 2.0)
        val ring = math.abs(math.sqrt(x * x / 1.6 + y * y) - 7)
        math.min(ring, 4.0) + 0.3 * math.sin(i * 0.9 + j * 0.7)
      ,
      IndexedSeq(h, w)
    )
    Plot.image(img).save(out("image.svg"))
    val di = Persistence(img)
    import di.given
    Plot.cubicalChain(img, di.dim(1).longest.get.representative, "Image loop").save(out("image-cycle.svg"))

    val sphere = Array.tabulate(60) { i =>
      val z = -1 + 2.0 * (i + 0.5) / 60
      val r = math.sqrt(1 - z * z)
      Array(r * math.cos(2.4 * i), r * math.sin(2.4 * i), z)
    }
    val scene = Plot.complex3D(sphere, 0.55, VietorisRips).copy(title = "Vietoris-Rips complex of a sphere at 0.55")
    scene.save(out("sphere.html"))
    scene.save(out("sphere.svg"))
    val ds = Persistence(sphere, maxDimension = 2)
    import ds.given
    ds.dim(2)
      .longest
      .foreach(b => Plot.cycle3D(sphere, b.representative, "H2 representative").save(out("sphere-cycle.svg")))
    // ----- customized: the tweaks of the grammar, one file each
    val cloud = Random(7)
    val scattered = Array.fill(70)(Array(cloud.nextDouble() * 4, cloud.nextDouble() * 4))
    val rough = Persistence(scattered, maxDimension = 1)
    val loops = rough.dim(1).bars
    Plot.diagram(loops).save(out("custom-diagram-linear.svg"))
    (Plot.diagram(loops) + Scale.xy(Transform.Log10) + Labels(title = "Loops, log-log", x = "birth", y = "death") +
      Mark.Diagonal(offset = 0.0, paint = Paint.Muted, dashed = true)).save(out("custom-diagram-loglog.svg"))
    Plot.barcode(d).withXScale(Transform.Log10).save(out("custom-barcode-log.svg"))
    Plot.diagram(d).withXYScale(Transform.Log10).save(out("custom-diagram-loglog-h0.svg"))
    Plot.diagram(rough).withXYScale(Transform.Log10).withLegend("bottom").save(out("custom-diagram-loglog-all.svg"))
    // a signed filtration: an image with negative pixel values
    val signedImg = Image(img.values.map(_ - 2.0), img.shape)
    val signed = Persistence(signedImg)
    Plot.barcode(signed).save(out("custom-signed-barcode-linear.svg"))
    Plot.barcode(signed).withXScale(Transform.Log10).save(out("custom-signed-barcode-log.svg"))
    Plot.diagram(signed).withXYScale(Transform.Log10).save(out("custom-signed-diagram-log.svg"))
    (Plot.barcode(d) + Style.slides + Size(960, 540) + Labels(
      title = "For a slide",
      subtitle = "bigger text and marks"
    ))
      .save(out("custom-barcode-slides.svg"))
    (Plot.diagram(d) + Style(legend = LegendPosition.Bottom) + Labels(legend = "Homology") +
      Scale
        .degree(0, label = "components") + Scale.degree(1, color = "#c2185b", label = "loops", shape = Shape.Diamond))
      .save(out("custom-diagram-degrees.svg"))
    val image1 = Plot.persistenceImage(d2, 1, resolution = 24, sigma = 0.08)
    (image1 + Scale.color(stops = ColorScale.viridis) + Labels(color = "density")).save(out("custom-image-viridis.svg"))
    (image1 + Palette.reference + Style(transparent = true)).save(out("custom-image-reference.svg"))
    // for a paper: a white background, black elements, fonts a converter knows
    val paper = Palette.print + Style.print
    (Plot.barcode(d) + paper).save(out("custom-print-barcode.svg"))
    (Plot.diagram(loops) + paper + Size(326, 300)).save(out("custom-print-diagram.svg"))
    (image1 + paper).save(out("custom-print-image.svg"))
    // for a page of either color: no background, black elements for a light page and white ones for a dark page
    val clear = Palette.clear + Style.clear
    (Plot.diagram(loops) + clear).save(out("custom-clear-diagram-light.svg"), Some(Theme.Light))
    (Plot.diagram(loops) + clear).save(out("custom-clear-diagram-dark.svg"), Some(Theme.Dark))
    (Plot.barcode(d) + clear).save(out("custom-clear-barcode-dark.svg"), Some(Theme.Dark))
    // the elements: each piece of text, the lines, tick marks, a frame, the plot area, the sides of the axes
    (Plot.diagram(loops) + Labels(subtitle = "text roles", x = "birth", y = "death") +
      Text(TextRole.Title, size = 22, align = Anchor.Middle) +
      Text(TextRole.Subtitle, italic = true, align = Anchor.Middle) +
      Text(TextRole.AxisLabel, size = 14, weight = Weight.Bold) + Text(TextRole.TickLabel, size = 9) +
      Text(TextRole.Legend, size = 14)).save(out("elements-text.svg"))
    (Plot.diagram(loops) + Lines.ticks(length = 4) + Lines.minorTicks(length = 2) + Lines.frame() +
      Guide.panel(color = "#f4f4f4", opacity = 1.0) + Lines.grid(dash = Dash.Dotted)).save(out("elements-frame.svg"))
    (Plot.diagram(loops) + Scale.xy(Transform.Log10) + Lines.minorGrid(opacity = 0.5) + Lines.minorTicks(length = 3) +
      Lines.ticks(length = 5) + Lines.frame()).save(out("elements-log-minor.svg"))
    (Plot.diagram(loops) + Scale.x(position = Side.Top) + Scale.y(position = Side.Right) +
      Lines.ticks(length = 5, direction = TickDirection.In) + Labels(x = "birth", y = "death"))
      .save(out("elements-axes-sides.svg"))
    (Plot.barcode(d) + Scale.x(breaks = Seq(0.0, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5), format = "%.2f") +
      Text(TextRole.TickLabel, angle = 45) + Labels(x = "filtration value")).save(out("elements-slanted.svg"))
    (Plot.diagram(loops) + Guide.diagonal(shade = false, dashed = true)).save(out("elements-diagonal-bare.svg"))
    (Plot.diagram(loops) + Guide.diagonal(shadeColor = "#c2185b", shadeOpacity = 0.15, width = 2))
      .save(out("elements-diagonal-tint.svg"))
    (image1 + Text(TextRole.ColorTitle, size = 14, weight = Weight.Bold) + Text(TextRole.TickLabel, size = 9) +
      Labels(color = "density")).save(out("elements-colorbar.svg"))
    (scene + Text(TextRole.Title, size = 22, align = Anchor.Middle) + Text(TextRole.Subtitle, italic = true))
      .save(out("elements-scene.svg"))
    (Plot.side(Plot.barcode(d), Plot.diagram(d)) + Palette.clear + Style.clear).save(out("elements-board-clear.svg"))
    // markers: every shape, open and filled, the edge, per degree; the places of a legend
    val shapes = Seq(
      Shape.Circle,
      Shape.Dot,
      Shape.Square,
      Shape.Triangle,
      Shape.TriangleDown,
      Shape.TriangleLeft,
      Shape.TriangleRight,
      Shape.Diamond,
      Shape.Pentagon,
      Shape.Hexagon,
      Shape.Star,
      Shape.Plus,
      Shape.Cross,
      Shape.VLine,
      Shape.HLine,
      Shape.Glyph("★"),
      Shape.Glyph("α")
    )
    val shapeMarks = shapes.zipWithIndex.flatMap { (shape, i) =>
      Seq(
        Mark.Points(IndexedSeq((i.toDouble, 1.0)), IndexedSeq(Paint.Series(0)), radius = 7, shape = shape),
        Mark.Points(IndexedSeq((i.toDouble, 0.0)), IndexedSeq(Paint.Series(1)), radius = 7, shape = shape)
      )
    }
    (Figure(marks = Vector.from(shapeMarks), title = "Markers", width = 760, height = 240) + Geom.points() +
      Scale.x(visible = false) + Scale.y(visible = false) + Scale.degree(1, marker = MarkerStyle(fill = Some(false))))
      .save(out("markers-shapes.svg"))
    (Plot.diagram(loops) + Geom.points(size = 6, opacity = 0.6, edgeWidth = 0) +
      Scale.degree(1, marker = MarkerStyle(fill = Some(false), edge = LineStyle(width = Some(2)))))
      .save(out("markers-open.svg"))
    (Plot.diagram(loops) + Geom.points(shape = Shape.Glyph("★"), size = 7, edgeVisible = false) +
      Labels(subtitle = "text glyphs")).save(out("markers-glyph.svg"))
    Seq("top", "left", "upper-right", "lower-left").foreach { place =>
      Plot.barcode(d).withLegend(place).save(out(s"legend-$place.svg"))
    }
    Plot.diagram(loops).withLegend("right").save(out("legend-diagram-right.svg"))
    // ----- the axes, numbers, caption and tags, units of a paper
    (Plot.barcode(d) + Scale.x(reverse = true) + Labels(subtitle = "the x axis reversed"))
      .save(out("axes-reversed-barcode.svg"))
    (Plot.diagram(loops) + Scale.x(reverse = true) + Scale.y(reverse = true) +
      Labels(subtitle = "both axes reversed: the shade is still where death < birth"))
      .save(out("axes-reversed-diagram.svg"))
    (Plot.barcode(d) + Scale.x(padding = 0) + Scale.y(padding = 0.2) + Labels(subtitle = "padding 0 and 0.2"))
      .save(out("axes-padding.svg"))
    (Plot.diagram(loops) + Scale.aspect(free = true) + Size(760, 300) + Labels(subtitle = "units freed"))
      .save(out("axes-aspect-free.svg"))
    (Plot.diagram(loops) + Scale.aspect(ratio = 2) + Labels(subtitle = "a y unit twice an x unit"))
      .save(out("axes-aspect-two.svg"))
    (Plot.barcode(d) + Scale.x(Transform.Custom("cube root", math.cbrt, v => v * v * v)) +
      Labels(subtitle = "a cube-root scale of our own")).save(out("axes-cube-root.svg"))
    (Plot.diagram(loops) + Numbers.locale("de") + Labels(subtitle = "German numbers"))
      .save(out("numbers-german.svg"))
    val large = Persistence(scattered.map(_.map(_ * 10000)), maxDimension = 1)
    (Plot.barcode(large) + Numbers(grouping = " ", decimal = ",", minus = "\u2212") +
      Labels(subtitle = "grouped digits"))
      .save(out("numbers-grouped.svg"))
    (image1 + Numbers.locale("sv") + Labels(color = "densitet")).save(out("numbers-colorbar-swedish.svg"))
    (Plot.diagram(loops) + Text(TextRole.Legend, limit = 60) + Text(TextRole.Title, limit = 90))
      .save(out("text-limit.svg"))
    (Plot.diagram(loops) + Labels(caption = "Source: a noisy sample of a circle, 70 points", tag = "(a)") +
      Text(TextRole.Caption, italic = true)).save(out("caption-tag.svg"))
    Plot.side(Plot.diagram(d), Plot.barcode(d)).tagged().save(out("board-tagged.svg"))
    (Plot.diagram(loops) + paper + Size.inches(3.4, 2.6) + Labels(tag = "(b)", caption = "a column wide"))
      .save(out("size-column.svg"))
    // ----- panels on a grid, some spanning cells
    Plot
      .layout(2, 3, widths = Seq(2.0, 2.0, 1.0))
      .place(Plot.diagram(d) + Scale.aspect(free = true), 0, 0, colSpan = 2)
      .place(Plot.barcode(d), 0, 2)
      .place(Plot.barcode(d) + Scale.x(Transform.Sqrt), 1, 0, colSpan = 3)
      .tagged()
      .save(out("layout-grid.svg"))
    Plot
      .layout(2, 2, width = 800, height = 560)
      .place(Plot.diagram(d), 0, 0, rowSpan = 2)
      .place(Plot.barcode(d), 0, 1)
      .place(Plot.persistenceImage(d, 1), 1, 1)
      .tagged("%s.", "A")
      .save(out("layout-tall.svg"))
    val before = Plot.barcode(Persistence(noisyCircle(30, 0.03, 4), maxDimension = 1)).copy(title = "tighter")
    val after = Plot.barcode(Persistence(noisyCircle(30, 0.2, 5), maxDimension = 1)).copy(title = "noisier")
    (Plot.side(before, after).shared(y = false) + Style(grid = false)).save(out("custom-board-shared.svg"))
    (Plot.diagram(loops) + Mark.Diagonal(offset = 0.4, paint = Paint.Muted, dashed = true) +
      Mark.Rule.vertical(0.6)).save(out("custom-diagram-threshold.svg"))
    (scene + Style.slides + Palette.reference + Labels(subtitle = "slides style, reference palette"))
      .save(out("custom-sphere.svg"))
    // interactive pages: the page itself and its stills at three scales
    val sweep = Plot.filtration(circle, barcode = true).withTitle("Noisy circle").withPlay(8)
    sweep.save(out("interactive-filtration.html"))
    Seq(0.15, 0.3, 0.5).foreach(t => sweep.at(t).save(out(f"interactive-still-$t%.2f.svg")))
    // four times the unit sphere: a ball's radius in the picture's units is not its radius in the data's
    val bigSphere = sphere.map(_.map(_ * 4))
    val sphere3 = Plot.filtration3D(bigSphere, max = 3.2, title = "Sphere").withPlay(8)
    sphere3.save(out("interactive-sphere.html"))
    Seq(1.2, 2.4).foreach(t => sphere3.at(t).save(out(f"interactive-sphere-still-$t%.2f.svg")))
    // a canvas whose items choose keys: a triangle, two segments and three points, far enough apart to point at
    val pickScene = Scene(
      Vector(
        Layer.Triangles(
          IndexedSeq(((-2.0, 0.0, 0.0), (-1.0, 0.0, 0.0), (-1.5, 1.0, 0.0))),
          IndexedSeq(Paint.Series(0)),
          0.35
        ),
        Layer.Segments(
          IndexedSeq(((0.0, -1.0, 0.0), (0.0, 1.0, 0.0)), ((0.8, 0.0, -1.0), (0.8, 0.0, 1.0))),
          IndexedSeq(Paint.Secondary),
          2.0
        ),
        Layer.Points(IndexedSeq((2.0, -0.5, 0.0), (2.2, 0.5, 0.0), (2.2, -1.0, 0.5)), IndexedSeq(Paint.Ink), 4.0)
      ),
      title = "Pointing in 3-D"
    )
    val picking = Interactive()
      .withSelection(Selection.listing("item", Seq.tabulate(21)(i => s"item $i")))
      .withPanel(
        Panel(pickScene)
          .selecting(2, "item", IndexedSeq(0, 1, 2))
          .selecting(1, "item", IndexedSeq(10, 11))
          .selecting(0, "item", IndexedSeq(20))
      )
      .withTitle("Pointing in 3-D")
    picking.save(out("interactive-pick-3d.html"))
    picking.at(PageState().select("item", 10)).save(out("interactive-pick-3d-still.svg"))
    // a linked 3-D filtration: the diagram, the barcode and a canvas that draws the chosen class's cycle
    val ring3 = circle.map(p => Array(p(0), p(1), 0.3 * p(0) * p(1)))
    val ringBars = Persistence(ring3, maxDimension = 1, maxFiltrationValue = 1.0).bars
    val ringKey = ringBars.indices.filter(ringBars(_).dim == 1).maxBy(ringBars(_).persistence)
    val ringT = (ringBars(ringKey).birth + math.min(ringBars(ringKey).death, 1.0)) / 2
    val linked3 = Plot.filtration3D(ring3, max = 1.0, link = true, barcode = true, title = "Noisy circle in space")
    linked3.save(out("interactive-linked-3d.html"))
    linked3.at(PageState().value("t", ringT).select("class", ringKey)).save(out("interactive-linked-3d-still.svg"))
    java.nio.file.Files
      .writeString(java.nio.file.Path.of(out("interactive-linked-3d.json")), s"""{"key":$ringKey,"t":$ringT}""")
    // a complex with too many cells for an SVG: a flat canvas to pan and zoom, linked to the diagram and the barcode
    val ringLarge = noisyCircle(60, 0.1, 3)
    val largeBars = Persistence(ringLarge, maxDimension = 1, maxFiltrationValue = 2.5).bars
    val largeKey = largeBars.indices.filter(largeBars(_).dim == 1).maxBy(largeBars(_).persistence)
    val largeT = (largeBars(largeKey).birth + math.min(largeBars(largeKey).death, 2.5)) / 2
    val manyCells = Plot.filtration(ringLarge, max = 2.5, barcode = true, link = true, title = "Sixty points")
    manyCells.save(out("interactive-large.html"))
    manyCells.at(PageState().value("t", largeT).select("class", largeKey)).save(out("interactive-large-still.svg"))
    java.nio.file.Files.writeString(
      java.nio.file.Path.of(out("interactive-large.json")),
      s"""{"key":$largeKey,"t":$largeT}"""
    )
    // linked views: pointing at a class in one picture lights it up in the others, with its cycle on the input
    val exploreBars = Persistence(circle, maxDimension = 1).bars
    val exploreKey = exploreBars.indices.filter(exploreBars(_).dim == 1).maxBy(exploreBars(_).persistence)
    val explored = Plot.exploreOn(circle, exploreBars).withTitle("Noisy circle")
    explored.save(out("interactive-explore.html"))
    explored.at(PageState().select("class", exploreKey)).save(out("interactive-explore-still.svg"))
    val barList =
      exploreBars.map(b => s"[${b.dim},${b.birth},${if b.death.isFinite then b.death.toString else "null"}]")
    java.nio.file.Files.writeString(
      java.nio.file.Path.of(out("interactive-explore.json")),
      s"""{"key":$exploreKey,"bars":${barList.mkString("[", ",", "]")}}"""
    )
    val linked = Plot.filtration(circle, barcode = true, link = true).withTitle("Noisy circle")
    linked.save(out("interactive-linked.html"))
    linked.at(PageState().value("t", 0.5).select("class", exploreKey)).save(out("interactive-linked-still.svg"))
    // zoom and pan: a page that only zooms, with numbers of several magnitudes, and stills of windows the check script
    // brings the page to; the explore page zoomed into a region of its diagram
    val wide = Figure(
      marks = Vector(
        Mark.Segments(
          IndexedSeq(((0.0, 0.0), (270000.0, 0.002)), ((0.0, 0.002), (270000.0, 0.0))),
          IndexedSeq(Paint.Secondary)
        ),
        Mark.Points(IndexedSeq.tabulate(10)(i => (i * 30000.0, ((i * 37) % 50) * 4e-5)), IndexedSeq(Paint.Ink))
      ),
      x = Axis(label = "x"),
      y = Axis(label = "y"),
      title = "Numbers of every size"
    )
    val zoomPage = Interactive().withPanel(Panel(wide).zoomable()).withTitle("Zoom and pan")
    zoomPage.save(out("interactive-zoom.html"))
    val zoomWindows = Seq(
      Window(50000.0, 150000.0, 4e-4, 1.2e-3),
      Window(0.0, 30000.0, 0.0, 1e-4),
      Window(120000.0, 130000.0, 1e-3, 1.1e-3)
    )
    zoomWindows.zipWithIndex.foreach { (w, i) =>
      zoomPage.at(PageState().zoom(0, w)).save(out(s"interactive-zoom-$i.svg"))
    }
    def listed(w: Window) = s"[${w.x0},${w.x1},${w.y0},${w.y1}]"
    def json2(t: String) = "\"" + t.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case c    => c.toString
    } + "\""
    // sixty windows of every size and place, with the words the still writes on them: the page must write the same
    val random = scala.util.Random(7)
    def inside(lo: Double, hi: Double): (Double, Double) =
      val width = (hi - lo) * math.pow(10, -2.1 * random.nextDouble())
      val start = lo + random.nextDouble() * (hi - lo - width)
      (start, start + width)
    val manyWindows = Seq.fill(60) {
      val ((x0, x1), (y0, y1)) = (inside(0.0, 270000.0), inside(0.0, 0.002))
      Window(x0, x1, y0, y1)
    }
    val words = manyWindows.map { w =>
      val svg = zoomPage.at(PageState().zoom(0, w)).svgFile(Theme.Light)
      """<text[^>]*>([^<]*)</text>""".r.findAllMatchIn(svg).map(m => "\"" + m.group(1) + "\"").mkString("[", ",", "]")
    }
    java.nio.file.Files.writeString(
      java.nio.file.Path.of(out("interactive-zoom-words.json")),
      s"""{"windows":${manyWindows.map(listed(_)).mkString("[", ",", "]")},"words":${words.mkString("[", ",", "]")}}"""
    )
    val diagramWindow = Window(-0.1, 0.6, 0.1, 0.8)
    explored.at(PageState().zoom(1, diagramWindow)).save(out("interactive-explore-zoomed.svg"))
    java.nio.file.Files.writeString(
      java.nio.file.Path.of(out("interactive-zoom.json")),
      s"""{"windows":${zoomWindows.map(listed).mkString("[", ",", "]")},"diagram":${listed(diagramWindow)}}"""
    )
    // zoom on every kind of axis: log, square-root, symmetric log, reversed, and one with a number format, separators and
    // slanted labels, each with minor ticks. For thirty windows of every size and place the still of the window is saved
    // with the page; the check script brings the page to the same windows and compares the axes element for element
    val axisRandom = scala.util.Random(11)
    def axisWindows(fig: Figure, n: Int): Seq[Window] =
      val (xt, yt) = Svg.resolved(fig)
      val ((xl, xh), (yl, yh)) = Svg.ranges(fig)
      def pick(t: Transform, lo: Double, hi: Double): (Double, Double) =
        val (a, b) = (t(lo), t(hi))
        val width = (b - a) * math.pow(10, -2.1 * axisRandom.nextDouble())
        val start = a + axisRandom.nextDouble() * (b - a - width)
        (t.inverse(start), t.inverse(start + width))
      Seq.fill(n) {
        val ((x0, x1), (y0, y1)) = (pick(xt, xl, xh), pick(yt, yl, yh))
        Window(x0, x1, y0, y1)
      }
    def zoomAxes(name: String, fig: Figure): Unit =
      val styled = fig + Lines.minorGrid() + Lines.minorTicks(length = 3)
      val page = Interactive().withPanel(Panel(styled).zoomable()).withTitle(name)
      page.save(out(s"zoomaxes-$name.html"))
      val windows = axisWindows(styled, 30)
      val stills = windows.map(w => page.at(PageState().zoom(0, w)).svgFile(Theme.Light))
      def quoted(t: String) = "\"" + t.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case '\n' => "\\n"
        case c    => c.toString
      } + "\""
      java.nio.file.Files.writeString(
        java.nio.file.Path.of(out(s"zoomaxes-$name.json")),
        s"""{"windows":${windows.map(listed).mkString("[", ",", "]")},"stills":${stills
            .map(quoted)
            .mkString("[", ",", "]")}}"""
      )
    val axisRng = scala.util.Random(5)
    val decades = Seq.fill(14)(math.pow(10, -2 + 5 * axisRng.nextDouble()))
    zoomAxes(
      "log",
      Figure(
        marks =
          Vector(Mark.Points(decades.map(x => (x, 3 * math.pow(x, 0.7) + 0.2)).toIndexedSeq, IndexedSeq(Paint.Ink))),
        x = Axis(label = "x", transform = Transform.Log10),
        y = Axis(label = "y", transform = Transform.Log10)
      )
    )
    zoomAxes(
      "sqrt",
      Figure(
        marks = Vector(
          Mark.Points(IndexedSeq.tabulate(25)(i => (i * i * 0.16, i * 0.2 + (i % 3))), IndexedSeq(Paint.Ink))
        ),
        x = Axis(label = "area", transform = Transform.Sqrt),
        y = Axis(label = "y")
      )
    )
    zoomAxes(
      "symlog",
      Figure(
        marks = Vector(
          Mark.Points(
            IndexedSeq((-40.0, 1.0), (-3.0, 2.0), (0.0, 3.0), (0.2, 4.0), (5.0, 5.0), (90.0, 6.0), (800.0, 7.0)),
            IndexedSeq(Paint.Ink)
          )
        ),
        x = Axis(label = "signed", transform = Transform.Log10),
        y = Axis(label = "y")
      )
    )
    zoomAxes(
      "reversed",
      Figure(
        marks = Vector(
          Mark.Points(IndexedSeq.tabulate(20)(i => (i * 13.0, 1000.0 - i * i * 2.5)), IndexedSeq(Paint.Ink))
        ),
        x = Axis(label = "depth", reverse = true),
        y = Axis(label = "height", reverse = true)
      )
    )
    zoomAxes(
      "format",
      Figure(
        marks = Vector(
          Mark.Points(IndexedSeq.tabulate(12)(i => (i * 11000.0, (i % 5) * 6e-4)), IndexedSeq(Paint.Ink))
        ),
        x = Axis(label = "count", format = Some("%,.1f")),
        y = Axis(label = "rate", format = Some("%+.2e"))
      ) + Numbers(decimal = ",", grouping = " ", minus = "\u2212") + Text(TextRole.TickLabel, angle = 40)
    )
    val farRng = scala.util.Random(11)
    zoomAxes(
      "logfar",
      Figure(
        marks = Vector(
          Mark.Points(
            IndexedSeq.fill(16)(
              (math.pow(10, -7 + 4 * farRng.nextDouble()), math.pow(10, 5 + 4 * farRng.nextDouble()))
            ),
            IndexedSeq(Paint.Ink)
          )
        ),
        x = Axis(label = "tiny", transform = Transform.Log10),
        y = Axis(label = "huge", transform = Transform.Log10)
      )
    )
    zoomAxes(
      "extras",
      Figure(
        marks = Vector(Mark.Points(IndexedSeq.tabulate(14)(i => (i * 0.7, (i * 5 % 8) * 1.0)), IndexedSeq(Paint.Ink))),
        x = Axis(label = "birth", extra = IndexedSeq((10.0, "\u221e"))),
        y = Axis(label = "death", ticks = Ticks.At(IndexedSeq(0.0, 2.5, 5.0, 7.5)))
      )
    )
    // brushing: a page of forty points, and stills of rectangles (unzoomed and zoomed) for the check script to compare the
    // page's rectangle with, drawn by a drag and by the keyboard; `keys` are what a rectangle touches, as the page chooses them
    {
      val brushRng = scala.util.Random(13)
      val cloud = IndexedSeq.fill(40)((10 * brushRng.nextDouble(), 10 * brushRng.nextDouble()))
      val brushPage = Interactive()
        .withSelection("c")
        .withPanel(
          Panel(
            Figure(
              marks = Vector(Mark.Points(cloud, IndexedSeq(Paint.Ink))),
              x = Axis(label = "x"),
              y = Axis(label = "y")
            )
          )
            .selecting(0, "c", IndexedSeq.range(0, 40))
            .brushable("c")
            .zoomable()
        )
        .withTitle("Brush")
      brushPage.save(out("interactive-brush.html"))
      def box(r: scala.util.Random, lo: Double, hi: Double) =
        val (a, b) = (lo + (hi - lo) * r.nextDouble(), lo + (hi - lo) * r.nextDouble())
        (math.min(a, b), math.max(a, b) + 0.05)
      val brushCases = Seq.fill(12) {
        val zoomed = brushRng.nextBoolean()
        val zoom = Option.when(zoomed) {
          val (x0, x1) = (1 + 3 * brushRng.nextDouble(), 6 + 3 * brushRng.nextDouble())
          Window(x0, x1, 1 + 3 * brushRng.nextDouble(), 6 + 3 * brushRng.nextDouble())
        }
        val (bx0, bx1) = zoom.fold(box(brushRng, 0, 10))(w => box(brushRng, w.x0, w.x1))
        val (by0, by1) = zoom.fold(box(brushRng, 0, 10))(w => box(brushRng, w.y0, w.y1))
        val brush = Window(bx0, bx1, by0, by1)
        val state = zoom.fold(PageState())(PageState().zoom(0, _)).brush(0, brush)
        s"""{"zoom":${zoom.fold("null")(listed)},"brush":${listed(brush)},"still":${json2(
            brushPage.at(state).svgFile(Theme.Light)
          )}}"""
      }
      java.nio.file.Files.writeString(
        java.nio.file.Path.of(out("interactive-brush.json")),
        s"""{"cloud":${cloud.map((x, y) => s"[$x,$y]").mkString("[", ",", "]")},"cases":${brushCases
            .mkString("[", ",", "]")}}"""
      )
    }
    // the page writes the numbers of a `%f`/`%e` axis itself: the cases below are what `String.format` says, for the page's
    // `javaFormat` to match (`check-interactive-page.mjs`)
    {
      val patterns = Seq(
        "%f",
        "%.0f",
        "%.1f",
        "%.3f",
        "%,.2f",
        "%+.1f",
        "%08.2f",
        "%10.3f",
        "%-10.1f|",
        "%,.0f",
        "%05.1f%%",
        "x=%.2f units",
        "%e",
        "%.2e",
        "%+.3E",
        "%12.4e",
        "%.0e",
        "%-12.2e|",
        "%+,.1f"
      )
      val values = Seq(
        0.0,
        -0.0,
        1.0,
        -1.0,
        0.5,
        1.5,
        2.5,
        0.125,
        0.135,
        1.005,
        2.675,
        9.9995,
        999.9996,
        123456.789,
        1234567.891,
        -1234.5678,
        0.30000000000000004,
        1e-5,
        0.00001234,
        1e-10,
        1e10,
        123456789012345.0,
        2.2250738585072014e-308,
        Double.MaxValue,
        Double.NaN,
        Double.PositiveInfinity,
        Double.NegativeInfinity
      )
      def json(t: String) = "\"" + t.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case c    => c.toString
      } + "\""
      val cases = for p <- patterns; v <- values yield
        val text = RootText.format(p, java.lang.Double.valueOf(v))
        s"""{"p":${json(p)},"v":${json(v.toString)},"out":${json(text)}}"""
      java.nio.file.Files
        .writeString(java.nio.file.Path.of(out("formats.json")), cases.mkString("{\"cases\":[", ",", "]}"))
    }
    println(s"wrote the gallery to $dir")
