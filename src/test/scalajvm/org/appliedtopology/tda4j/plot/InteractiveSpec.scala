package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification
import org.w3c.dom.Element

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.xml.parsers.DocumentBuilderFactory

/** The interactive plots: what a control does to the items of a panel, the same behaviors as still frames, and the
  * facts a script on the page relies on. Each example is built so that the plausible wrong implementation fails it (`<`
  * for `<=` at the exact value a cell enters, a line placed by a different formula than the renderer's).
  */
class InteractiveSpec extends Specification:
  sequential

  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private lazy val stream = VietorisRips.fromPoints(circle, 1, None)
  private lazy val cells = stream.iterator.toVector
  private lazy val diagram = Persistence(circle, maxDimension = 1)
  private lazy val page = Plot.filtration(circle)
  private def value(c: Simplex[Int]): Double = stream.filtrationValue(c)
  private lazy val edges = cells.filter(_.dim == 1)
  private lazy val triangles = cells.filter(_.dim == 2)
  private lazy val points: IndexedSeq[(Double, Double)] = circle.toIndexedSeq.map(p => (p(0), p(1)))

  private def figureOf(snapshot: Snapshot, panel: Int): Figure = snapshot.parts(panel).asInstanceOf[Figure]
  private def segmentsOf(f: Figure): Seq[Mark.Segments] = f.marks.collect { case s: Mark.Segments => s }
  private def pointsOf(f: Figure): Seq[Mark.Points] = f.marks.collect { case s: Mark.Points => s }
  private def rulesOf(f: Figure): Seq[Mark.Rule] = f.marks.collect { case r: Mark.Rule => r }

  /** The `data-fr` of each panel that has one, in page order. */
  private def frames(page: String): Seq[String] = """data-fr="([^"]+)"""".r.findAllMatchIn(page).map(_.group(1)).toSeq

  private def parse(svg: String) =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)))

  private def elements(svg: String, tag: String): Seq[Element] =
    val nodes = parse(svg).getElementsByTagName(tag)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[Element])

  /** The value at which each of the edges and triangles enters, a few chosen to include the boundary cases. */
  private lazy val probes: Seq[Double] =
    val all = (edges ++ triangles).map(value).distinct.sorted
    Seq(all.head, all(all.size / 3), all(2 * all.size / 3), all.last)

  "the complex panel of a filtration" should {
    "be, at any value, the cells of the stream with value at most that, cell for cell" in {
      val results = probes.flatMap { t =>
        val f = figureOf(page.at(t), 0)
        val shownEdges = segmentsOf(f).head.ends.toSet
        val shownTriangles = f.marks.collect { case p: Mark.Polygons => p }.head.corners.toSet
        Seq(
          shownEdges === edges.filter(value(_) <= t).map(e => (points(e.first), points(e.last))).toSet,
          shownTriangles === triangles.filter(value(_) <= t).map(c => c.toIndexedSeq.map(points)).toSet
        )
      }
      results.map(_.isSuccess).forall(identity) must beTrue
    }

    "include a cell at exactly the value it enters, and not just below it" in {
      val e = edges.maxBy(value)
      val t = value(e)
      val at = segmentsOf(figureOf(page.at(t), 0)).head.ends
      val below = segmentsOf(figureOf(page.at(math.nextDown(t)), 0)).head.ends
      (at.size - below.size) must beGreaterThanOrEqualTo(1)
      at must contain((points(e.first), points(e.last)))
      below must not(contain((points(e.first), points(e.last))))
    }

    "draw balls of radius half the scale for Vietoris-Rips, the whole scale for Cech" in {
      val rips = figureOf(page.at(0.8), 0).marks.collect { case d: Mark.Discs => d }.head
      val cech = figureOf(Plot.filtration(circle, Cech).at(0.8), 0).marks.collect { case d: Mark.Discs => d }.head
      (rips.radius must beCloseTo(0.4, 1e-12)) and (cech.radius must beCloseTo(0.8, 1e-12))
    }

    "keep its range as the balls grow" in {
      val a = figureOf(page.at(0.1), 0)
      val b = figureOf(page.at(1.9), 0)
      Svg.ranges(a) === Svg.ranges(b)
    }

    "refuse a page that would hold more cells than allowed, and say what to do" in {
      Plot.filtration(circle, maxCells = 10) must throwAn[IllegalArgumentException](".*lower max.*")
    }
  }

  "the diagram panel of a filtration" should {
    "show, at value t, a point for each class dead by t and a segment up to the line for each one alive" in {
      val checks = probes.map { t =>
        val f = figureOf(page.at(t), 1)
        // a list, not a set: at small t sixteen H0 classes sit at the same place
        val dead = diagram.bars.filter(b => b.birth <= t && b.death <= t).map(b => (b.birth, b.death)).sorted
        val alive =
          diagram.bars.filter(b => b.birth <= t && b.death > t).map(b => ((b.birth, b.birth), (b.birth, t))).sorted
        val shownPoints = pointsOf(f).flatMap(_.at).sorted
        val shownSegments = segmentsOf(f).flatMap(_.ends).sorted
        // the diagram's own account of the same moment: every class born by t, those alive cut off at t
        val cut = diagram.at(t).bars
        (shownPoints === dead, shownSegments === alive, cut.size === (dead.size + alive.size))
      }
      checks.map((a, b, c) => a.isSuccess && b.isSuccess && c.isSuccess).forall(identity) must beTrue
    }

    "draw the movable line at the value of the slider" in {
      probes
        .map(t => rulesOf(figureOf(page.at(t), 1)).exists(r => !r.vertical && r.at == t))
        .forall(identity) must beTrue
    }

    "never turn an essential class into a point: its segment grows with the line" in {
      val essential = diagram.bars.filter(_.death.isPosInfinity)
      val t = probes.last
      val f = figureOf(page.at(t), 1)
      val ends = segmentsOf(f).flatMap(_.ends).toSet
      essential.map(b => ends.contains(((b.birth, b.birth), (b.birth, t)))).forall(identity) must beTrue
    }
  }

  "the diagram of a sweep" should {
    "keep an essential class on its segment when the slider goes past the line that stands for infinity" in {
      val plain = Plot.diagram(diagram)(using Look.none)
      val top = rulesOf(plain).filterNot(_.vertical).head.at
      val sweep = Interactive().withSlider("t", 0, 2 * top).withPanel(Panel.sweepDiagram(plain))
      val ends = segmentsOf(figureOf(sweep.at(1.8 * top), 0)).flatMap(_.ends).toSet
      diagram.bars
        .filter(_.death.isPosInfinity)
        .map(b => ends.contains(((b.birth, b.birth), (b.birth, top))))
        .forall(identity) must beTrue
    }

    "hold a line that follows a slider inside the range it was given, at both ends" in {
      val plain = Plot.diagram(diagram)(using Look.none)
      val held = Interactive()
        .withSlider("t", 0, 1)
        .withPanel(Panel(plain).react(Mark.Rule.horizontal(0.0), Behavior.followsY("t", 0.2, 0.6)))
      def at(t: Double) = rulesOf(figureOf(held.at(t), 0)).filterNot(_.vertical).map(_.at)
      (at(0.1) must contain(0.2)) and (at(0.4) must contain(0.4)) and (at(0.9) must contain(0.6))
    }
  }

  "the page of a filtration" should {
    val html = page.fragment(Theme.Light)

    "carry every cell once, with the value it enters at, for the script" in {
      val complexPanel = html.split("</svg>").head // the first panel; a diagram's triangle markers are polygons too
      val entering = """<polygon data-ia="v,t,([^,]+),Infinity"""".r
        .findAllMatchIn(complexPanel)
        .map(_.group(1).toDouble)
        .toSeq
        .sorted
      (entering == triangles.map(value).sorted) must beTrue
      val lines =
        """<line data-ia="v,t,([^,]+),Infinity"""".r.findAllMatchIn(complexPanel).map(_.group(1).toDouble).toSeq.sorted
      (lines == edges.map(value).sorted) must beTrue
    }

    "tell the script where the diagram puts a value: its frame agrees with the renderer's line" in {
      // JavaScript's `py` from the `data-fr` of the diagram panel, written out here, must land on the y of the line
      val Array(xs, ys) = frames(html).last.split(";")
      val Array(o, n, lo, hi, rev, code, param) = ys.split(",")
      def position(v: Double): Double =
        val u = code match
          case "i" => v
          case "l" => math.log10(v)
          case "s" => math.signum(v) * math.sqrt(math.abs(v))
          case _   => math.signum(v) * math.log10(1 + math.abs(v) / param.toDouble)
        val frac = (if rev == "1" then hi.toDouble - u else u - lo.toDouble) / (hi.toDouble - lo.toDouble) * n.toDouble
        o.toDouble + n.toDouble - frac
      val t = 0.6
      val still = page.at(t).svgFile(Theme.Light)
      val movable = elements(still, "line").filter(l => l.getAttribute("stroke-width") == "1.5")
      val line = movable.filter(l => l.getAttribute("y1") == l.getAttribute("y2")).map(_.getAttribute("y1").toDouble)
      line.exists(y => math.abs(y - position(t)) < 0.01) must beTrue
    }

    "tell the script the frame on a log-log diagram too" in {
      val logged = Plot.filtration(circle, balls = false) + Scale.xy(Transform.Log10)
      frames(logged.fragment(Theme.Light)).last must contain(",l,")
    }

    "include the script that makes the page move, once, with the controls it was given" in {
      html.split("tda4j:change").length must beGreaterThan(1)
      html must contain(""""n":"t"""")
      html.split("<script>").length === 2 // no scene on this page
    }
  }

  "a behavior" should {
    "combine with + into a reaction, in order" in {
      val r = Behavior.shownFrom("t", 1.0) + Behavior.followsY("t", 0, 1) + Behavior.radius("t")
      r.behaviors.map(_.getClass.getSimpleName.stripSuffix("$")) === Vector("Shown", "Follows", "Radius")
    }

    "reach the page with its arguments escaped, whatever they hold" in {
      val r = Reaction(Vector(Behavior.custom("glow", "t", "a,b", "c;d", "100%")))
      val encoded = Interactive.encode(r)
      encoded must not(contain("a,b"))
      val decoded = encoded.split(";").head.split(",").map(s => java.net.URLDecoder.decode(s, "UTF-8"))
      decoded.toSeq === Seq("glow", "t", "a,b", "c;d", "100%")
    }

    "be refused where the page cannot carry it out, saying so" in {
      val panel = Panel(Figure())
      (panel.react(Mark.Points(IndexedSeq((0.0, 0.0)), IndexedSeq(Paint.Ink)), Behavior.followsY("t")) must throwAn[
        IllegalArgumentException
      ](".*cannot be attached.*")) and
        (panel.react(Mark.Text((0.0, 0.0), "x"), Behavior.shownFrom("t", 0)) must throwAn[IllegalArgumentException](
          ".*cannot react.*"
        ))
    }

    "be refused for a control the plot does not have, naming the ones it has" in {
      val bad = Interactive()
        .withSlider("a", 0, 1)
        .withPanel(Panel(Figure()).react(Mark.Rule.horizontal(0.0), Behavior.followsY("b")))
      bad.fragment() must throwAn[IllegalArgumentException](".*called b.*controls are: a.*")
    }

    "need linear axes for balls that grow" in {
      val ball = Mark.Discs(IndexedSeq((1.0, 1.0)), 0.1, IndexedSeq(Paint.Series(0)))
      val p = Panel(Figure(x = Axis(transform = Transform.Log10))).react(ball, Behavior.radius("t"))
      Interactive().withSlider("t", 0, 1).withPanel(p).fragment() must throwAn[IllegalArgumentException](
        ".*linear axes.*"
      )
    }
  }

  "an interactive plot" should {
    "take a tweak in every panel, as a board does" in {
      val styled = page + Palette.print
      styled.panels.collect { case Panel.Flat(f, _) => f.palette }.distinct === Vector(Palette.print)
    }

    "be built by chaining, each step a new value" in {
      val a = Interactive().withSlider("t", 0, 2)
      val b = a.withPlay(5).withTitle("hello").withScript("tda4j.onChange('t', v => {})")
      (a.controls.head.play === None) and (b.controls.head.play === Some(5.0)) and (b.title === "hello") and
        (b.scripts.size === 1)
    }

    "run a script of the user's on the page, with the controller" in {
      val html = page.withScript("tda4j.reactor('glow', (it, b, v) => {})").fragment(Theme.Light)
      html must contain("tda4j.reactor('glow'")
    }

    "print as one line" in {
      page.toString must startWith("Interactive(")
    }

    "make frames: one still per value, each a well-formed picture with its value in the heading" in {
      val frames = page.frames(4)
      (frames.size === 4) and (frames.map(_.svgFile(Theme.Light)).map(parse(_).getDocumentElement.getNodeName) ===
        Seq.fill(4)("svg")) and (frames(1).heading must contain("ε = "))
    }

    "write its frames to files" in {
      val dir: Path = Files.createTempDirectory("tda4j-frames")
      val written = page.saveFrames(dir.toString, 3)
      (written.size === 3) and (written.forall(p => Files.size(Path.of(p)) > 1000) must beTrue) and
        (Path.of(written.head).getFileName.toString === "frame-0000.svg")
    }

    "step through the stops of its slider, one still for each change of the picture" in {
      val stepped = Plot.filtration(circle, stops = true)
      val stops = stepped.controls.head.stops
      (stops.size must beGreaterThan(10)) and (stepped.framesAtStops().size === stops.size)
    }
  }

  "the calls of the user guide" should {
    val dir = Files.createTempDirectory("tda4j-guide").toString
    val noisy = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")

    "write the page and its frames, white for a video" in {
      val page = Plot.filtration(noisy, max = 1.0).withTitle("A noisy circle").withPlay(10.0)
      val path = page.save(s"$dir/filtration.html")
      val frames =
        Plot.filtration(noisy, max = 1.0).withPalette("print").withStyle("print").saveFrames(s"$dir/frames", 5)
      (Files.readString(Path.of(path)) must contain("A noisy circle")) and (frames.length === 5) and
        (Files.readString(Path.of(frames.head)).contains("fill:#ffffff") must beTrue)
    }

    "reveal a point cloud one point at a time, with a frame for each point" in {
      val points = Array.tabulate(40)(i => Array(math.cos(i * 0.157), math.sin(i * 0.157)))
      val cloud = points.map(p => (p(0), p(1))).toIndexedSeq
      val reveal = Panel(Plot.points(points)).reactEach(
        Mark.Points(cloud, IndexedSeq(Paint.Series(1)), radius = 6),
        cloud.indices.map(i => Reaction(Vector(Behavior.shownFrom("t", i.toDouble))))
      )
      val page = Interactive().withSlider("t", 0, 39).withPanel(reveal).withTitle("One point at a time")
      page.save(s"$dir/reveal.html")
      val written = page.saveFrames(s"$dir/reveal-frames", 40)
      // frame i shows points 0..i of the highlighted mark: i + 1 of them
      def shown(i: Int) = pointsOf(figureOf(page.at(i.toDouble), 0)).last.at.size
      (written.length === 40) and (shown(0) === 1) and (shown(17) === 18) and (shown(39) === 40)
    }

    "run a behavior of one's own, which a frame shows as drawn" in {
      val points = Array.tabulate(40)(i => Array(math.cos(i * 0.157), math.sin(i * 0.157)))
      val cloud = points.map(p => (p(0), p(1))).toIndexedSeq
      val glowing = Panel(Plot.points(points)).react(
        Mark.Points(cloud, IndexedSeq(Paint.Series(2)), radius = 8),
        Behavior.custom("glow", "t")
      )
      val page = Interactive()
        .withSlider("t", 0, 1)
        .withPanel(glowing)
        .withScript("tda4j.reactor('glow', (item, behavior, v) => { item.el.style.opacity = v; })")
      val html = page.html()
      (html must contain("tda4j.reactor('glow'")) and (html must contain("data-ia=\"glow,t\"")) and
        (pointsOf(figureOf(page.at(0.5), 0)).last.at.size === 40)
    }
  }

  "a sweeping barcode" should {
    "grow each bar to the line and stop it at its death, with the line at the value" in {
      val figure = Plot.barcode(diagram)(using Look.none)
      val sweep = Interactive().withSlider("t", 0, 2).withPanel(Panel.sweepBarcode(figure))
      val t = 0.7
      val f = figureOf(sweep.at(t), 0)
      val ends = segmentsOf(f).flatMap(_.ends)
      val expected = diagram.bars.filter(_.birth <= t).map { b =>
        val end = if b.death.isPosInfinity then t else math.min(t, b.death)
        (b.birth, end)
      }
      (ends.map((a, b) => (a._1, b._1)).sorted === expected.sorted) and
        (rulesOf(f).exists(r => r.vertical && r.at == t) must beTrue)
    }
  }

  "the 3-D page of a filtration" should {
    val circle3 = circle.map(p => Array(p(0), p(1), 0.3 * p(0) * p(1)))
    lazy val stream3 = VietorisRips.fromPoints(circle3, 1, None)
    lazy val cells3 = stream3.iterator.toVector
    lazy val sweep3 = Plot.filtration3D(circle3, max = 1.2)
    def sceneOf(s: Snapshot): Scene = s.parts.head.asInstanceOf[Scene]

    "be, at any value, the cells of the stream with value at most that, the points always" in {
      val pts = circle3.toIndexedSeq.map(p => (p(0), p(1), p(2)))
      val checks = Seq(0.4, 0.8, 1.2).map { t =>
        val layers = sceneOf(sweep3.at(t)).layers
        val triangles = layers.collectFirst { case l: Layer.Triangles => l }.get.corners.toSet
        val edges = layers.collectFirst { case l: Layer.Segments => l }.get.ends.toSet
        val vertices = layers.collectFirst { case l: Layer.Points => l }.get.at
        (
          triangles == cells3
            .filter(c => c.dim == 2 && stream3.filtrationValue(c) <= t)
            .map(c => (pts(c.first), pts(c.toIndexedSeq(1)), pts(c.last)))
            .toSet,
          edges == cells3
            .filter(c => c.dim == 1 && stream3.filtrationValue(c) <= t)
            .map(c => (pts(c.first), pts(c.last)))
            .toSet,
          vertices.size == circle3.length
        )
      }
      checks.forall((a, b, c) => a && b && c) must beTrue
    }

    "tell the canvas which values each item is shown for, and listen for the page's changes" in {
      val html = sweep3.fragment(Theme.Light)
      html must contain(""""ia":[[""")
      html must contain("closest('.tda4j-ia')")
      html must contain("tda4j:change")
    }

    "leave a 3-D scene that is not on an interactive page exactly as it was" in {
      val plain = Plot.points3D(circle3).fragment(Theme.Light)
      plain must not(contain("tda4j:change"))
      plain must contain("for(const it of L.items){")
    }

    "refuse a behavior a canvas cannot carry out" in {
      val scene = Plot.points3D(circle3)
      Panel(scene).react(Layer.Points(IndexedSeq((0.0, 0.0, 0.0)), IndexedSeq(Paint.Ink)), Behavior.followsY("t")) must
        throwAn[IllegalArgumentException](".*cannot be attached.*")
    }
  }
