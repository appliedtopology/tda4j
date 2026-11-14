package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

/** The 3-D panels of an interactive page: balls that grow with a slider, and the linked selections of a canvas. Each
  * example is built so that the plausible wrong implementation fails it: a ball whose radius is in the units of the
  * data and not of the picture, a frame that follows the balls, a halo that is there before its key is chosen, a cycle
  * drawn for the neighbouring bar.
  */
class SpatialSpec extends Specification:
  sequential

  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private val circle3 = circle.map(p => Array(p(0), p(1), 0.3 * p(0) * p(1)))
  private val points3: IndexedSeq[XYZ] = circle3.toIndexedSeq.map(p => (p(0), p(1), p(2)))

  private def sceneOf(s: Snapshot, panel: Int = 0): Scene = s.parts(panel).asInstanceOf[Scene]
  private def ballsOf(s: Scene): Seq[Layer.Balls] = s.layers.collect { case b: Layer.Balls => b }

  // the centres of the circles in an svg whose radius is `r`
  private def circlesOfRadius(svg: String, r: String): Seq[(String, String)] =
    s"""<circle cx="([^"]+)" cy="([^"]+)" r="${java.util.regex.Pattern.quote(r)}"""".r
      .findAllMatchIn(svg)
      .map(m => (m.group(1), m.group(2)))
      .toSeq

  "the balls of a 3-D filtration" should {
    lazy val rips = Plot.filtration3D(circle3, max = 1.2)

    "be half the scale for Vietoris-Rips and the whole scale for Cech, around every point" in {
      val r = ballsOf(sceneOf(rips.at(0.8))).head
      val c = ballsOf(sceneOf(Plot.filtration3D(circle3, Cech, max = 1.2).at(0.8))).head
      r.radius must beCloseTo(0.4, 1e-12)
      c.radius must beCloseTo(0.8, 1e-12)
      r.centers === points3
    }

    "grow with the slider and be left out when not asked for" in {
      val sizes = Seq(0.2, 0.6, 1.0).map(t => ballsOf(sceneOf(rips.at(t))).head.radius)
      val none = Plot.filtration3D(circle3, max = 1.2, balls = false)
      sizes.zip(Seq(0.1, 0.3, 0.5)).forall((a, b) => math.abs(a - b) < 1e-12) must beTrue
      ballsOf(sceneOf(none.at(0.6))) must beEmpty
    }

    "tell the canvas which control sizes them, in the units of the picture" in {
      val html = rips.fragment(Theme.Light)
      (html must contain(""""ra":["t",""")).and(html must contain(""""k":"b""""))
    }

    "be drawn as a disc as wide as the ball, in the picture's own scale" in {
      // two points 4 apart: the frame is centred between them with radius 2, and a ball of radius 0.5 is a quarter of it
      // (a unit frame would not tell a ball in the picture's units from one in the data's)
      val ball = Layer.Balls(IndexedSeq((0.0, 0.0, 0.0)), 0.5, IndexedSeq(Paint.Series(0)))
      val dots = Layer.Points(IndexedSeq((0.0, 0.0, 0.0), (4.0, 0.0, 0.0)), IndexedSeq(Paint.Ink), 2.5)
      val scene = Scene(Vector(ball, dots), width = 640, height = 520, azimuth = 0, elevation = 0)
      // the scene's scale is 0.46 of the smaller side, the room under a 12 pixel margin: 0.46 * 508
      val expected = f"${0.5 / 2 * 0.46 * 508}%.2f"
      circlesOfRadius(scene.staticSvg(Theme.Light), expected).size === 1
    }

    "give the canvas the factor in the picture's units: the page's radius is the slider's value times it" in {
      // the same cloud four times as wide: the factor per unit of the control shrinks to a quarter, the picture does not change
      def factor(scale: Double) =
        val html = Plot.filtration3D(circle3.map(_.map(_ * scale)), max = 1.2 * scale).fragment(Theme.Light)
        """"ra":\["t",([0-9.]+)\]""".r.findFirstMatchIn(html).get.group(1).toDouble
      factor(4.0) must beCloseTo(factor(1.0) / 4, 0.001)
    }

    "leave the picture where it is as they grow: the frame comes from the centres" in {
      def svg(radius: Double) =
        Scene(
          Vector(
            Layer.Balls(points3, radius, IndexedSeq(Paint.Series(0))),
            Layer.Points(points3, IndexedSeq(Paint.Ink), 2.5)
          )
        )
          .staticSvg(Theme.Light)
      // the points' circles, whose radius does not change; a ball's does
      def dots(svg: String) =
        """<circle cx="([^"]+)" cy="([^"]+)" r="2.5"""".r.findAllMatchIn(svg).map(_.group(0)).toSeq
      dots(svg(0.05)).size === 16
      dots(svg(0.05)) === dots(svg(40.0))
    }

    "keep their translucent fill, so that what is inside shows" in {
      val svg = sceneOf(rips.at(0.6)).staticSvg(Theme.Light)
      svg must contain("fill-opacity:0.09")
    }
  }

  "a layer of a canvas" should {
    val scene = Scene(
      Vector(
        Layer.Points(points3.take(3), IndexedSeq(Paint.Ink), 3.0),
        Layer.Segments(IndexedSeq((points3(0), points3(1)), (points3(1), points3(2))), IndexedSeq(Paint.Ink), 1.0),
        Layer.Triangles(IndexedSeq((points3(0), points3(1), points3(2))), IndexedSeq(Paint.Series(0)), 0.2)
      )
    )
    val panel = Panel(scene)
      .selecting(0, "c", IndexedSeq(10, 11, 12))
      .selecting(1, "c", IndexedSeq(10, 11))
      .selecting(2, "c", IndexedSeq(12))
    val page = Interactive().withSelection("c").withPanel(panel)

    def halos(s: Scene): (Seq[XYZ], Seq[(XYZ, XYZ)], Seq[(XYZ, XYZ, XYZ)]) =
      (
        s.layers.collect { case l: Layer.Halos => l.at }.flatten,
        s.layers.collect { case l: Layer.Segments if l.opacity == 0.3 => l.ends }.flatten,
        s.layers.collect { case l: Layer.Triangles if l.opacity > 0.3 => l.corners }.flatten
      )

    "carry a halo for each item, lit only while the item's key is chosen" in {
      val none = halos(sceneOf(page.at(PageState())))
      val one = halos(sceneOf(page.at(PageState().select("c", 11))))
      val both = halos(sceneOf(page.at(PageState().select("c", 11, 12))))
      none._1 must beEmpty
      none._2 must beEmpty
      none._3 must beEmpty
      one._1 === Seq(points3(1))
      one._2 === Seq((points3(1), points3(2)))
      one._3 must beEmpty
      both._1.toSet === Set(points3(1), points3(2))
      both._3 === Seq((points3(0), points3(1), points3(2)))
    }

    "name the keys an item chooses, and the keys that light the halos, for the canvas" in {
      val html = page.fragment(Theme.Light)
      (html must contain(""""pk":[[["c",10]],[["c",11]],[["c",12]]]""")).and(html must contain("""["w","c",11]"""))
    }

    "write a position for every distinct place, coordinates that repeat included" in {
      // three cells on a grid: x = 0 and z = 0 repeat in every corner, so a table that kept distinct numbers would be short
      val grid: IndexedSeq[XYZ] = IndexedSeq((0.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 2.0, 0.0), (0.0, 3.0, 0.0))
      val flat = Scene(
        Vector(
          Layer.Points(grid, IndexedSeq(Paint.Ink), 3.0),
          Layer.Segments(IndexedSeq((grid(0), grid(1)), (grid(2), grid(3))), IndexedSeq(Paint.Ink), 1.0)
        )
      )
      val html = Interactive().withSlider("t", 0, 1).withPanel(Panel(flat)).fragment(Theme.Light)
      val table = """const P=\[([^\]]*)\]""".r.findFirstMatchIn(html).get.group(1).split(',').toSeq
      table.size === 3 * grid.size
    }

    "refuse a selection the page does not have, as a flat panel does" in {
      Interactive().withSlider("t", 0, 1).withPanel(panel).fragment() must throwAn[IllegalArgumentException](
        ".*selection called c.*none.*"
      )
    }

    "carry a radius only on balls, and no coordinate that follows a control" in {
      val ball = Layer.Balls(points3, 0.2, IndexedSeq(Paint.Series(0)))
      Panel(scene).react(ball, Behavior.radius("t")).scene.layers.size === 4
      (Panel(scene).react(scene.layers.head, Behavior.radius("t")) must throwAn[IllegalArgumentException](
        ".*cannot be attached.*"
      )).and(
        Panel(scene).react(scene.layers.head, Behavior.followsY("t")) must throwAn[IllegalArgumentException](
          ".*cannot be attached.*"
        )
      )
    }

    "give a still with the balls at the radius the control asks for, whatever the starting one" in {
      val ball = Layer.Balls(points3, 0.2, IndexedSeq(Paint.Series(0)))
      val sweep = Interactive().withSlider("t", 0, 2).withPanel(Panel(scene).react(ball, Behavior.radius("t", 0.5)))
      ballsOf(sceneOf(sweep.at(1.4))).head.radius must beCloseTo(0.7, 1e-12)
    }
  }

  "a linked 3-D filtration" should {
    lazy val noisy = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
    lazy val bars = Persistence(noisy, maxDimension = 1, maxFiltrationValue = 1.0).bars.reverse
    lazy val stream = VietorisRips.fromPoints(noisy, 1, Some(1.0))
    lazy val flat = Plot.filtrationOf(noisy, stream, bars, link = true, barcode = true)
    lazy val space = Plot.filtration3DOf(noisy, stream, bars, link = true, barcode = true)
    type Edge = ((Double, Double), (Double, Double))

    def thick2(s: Snapshot): Set[Edge] =
      s.parts.head
        .asInstanceOf[Figure]
        .marks
        .flatMap {
          case m: Mark.Segments if m.width == 3.0 => m.ends
          case _                                  => Seq.empty[Edge]
        }
        .toSet
    def thick3(s: Snapshot): Set[Edge] =
      sceneOf(s).layers.flatMap {
        case l: Layer.Segments if l.width == 3.0 => l.ends.map { case ((a, b, _), (c, d, _)) => ((a, b), (c, d)) }
        case _                                   => Seq.empty[Edge]
      }.toSet

    "draw the cycle of the chosen bar in the canvas, the cells the plane's page draws, and not its neighbour's" in {
      val loops = bars.indices.filter(i => bars(i).dim == 1 && bars(i).persistence > 0).take(2)
      val points = bars.indices.filter(bars(_).dim == 0).take(1)
      def at(page: Interactive, k: Int) = page.at(PageState().value("t", 1.0).select("class", k))
      val wrong = (loops ++ points).filter(k => thick3(at(space, k)) != thick2(at(flat, k)))
      loops must not(beEmpty)
      wrong must beEmpty
      // a loop has edges to draw, a connected component none
      thick3(at(space, loops.head)).nonEmpty must beTrue
      thick3(at(space, points.head)) must beEmpty
    }

    "light the diagram's point and the barcode's bar of the chosen class, as in the plane" in {
      val k = bars.indices.find(bars(_).dim == 1).get
      val chosen = space.at(PageState().value("t", 1.0).select("class", k))
      def lit(f: Figure) = f.marks.collect { case s: Mark.Segments if s.opacity == 0.3 => s.ends.size }.sum
      lit(chosen.parts(1).asInstanceOf[Figure]) must beGreaterThan(0)
      lit(chosen.parts(2).asInstanceOf[Figure]) must beGreaterThan(0)
    }

    "keep the plane's page as it was, with its own cycles" in {
      flat.panels.head must beAnInstanceOf[Panel.Flat]
      space.panels.head must beAnInstanceOf[Panel.Spatial]
    }

  }

  "a complex too large for an SVG" should {
    type Edge = ((Double, Double), (Double, Double))
    // a ring of 22 points with a wiggle: 1,540 triangles, enough to tell the routes apart with a small limit
    val ring = Array.tabulate(22)(i =>
      Array(math.cos(2 * math.Pi * i / 22) * (1 + 0.1 * math.sin(i)), math.sin(2 * math.Pi * i / 22))
    )
    def corners(s: Snapshot): Set[Set[(Double, Double)]] =
      val found: Seq[Set[(Double, Double)]] = s.parts(0) match
        case f: Figure =>
          f.marks.flatMap {
            case p: Mark.Polygons => p.corners.map(c => Set.from(c))
            case _                => Seq.empty[Set[(Double, Double)]]
          }
        case sc: Scene =>
          sc.layers.flatMap {
            case l: Layer.Triangles =>
              l.corners.map { case (a, b, c) => Set((a._1, a._2), (b._1, b._2), (c._1, c._2)) }
            case _ => Seq.empty[Set[(Double, Double)]]
          }
        case other => sys.error(s"unexpected panel $other")
      Set.from(found)

    "be drawn on a flat canvas that pans, with the triangles of the SVG page at the same places" in {
      val svg = Plot.filtration(ring, max = 1.5, canvas = false)
      val canvas = Plot.filtration(ring, max = 1.5, canvas = true)
      svg.panels.head must beAnInstanceOf[Panel.Flat]
      canvas.panels.head must beAnInstanceOf[Panel.Spatial]
      val scene = canvas.panels.head.asInstanceOf[Panel.Spatial].scene
      (scene.azimuth, scene.elevation, scene.turns) === (0.0, 90.0, false)
      val a = corners(svg.at(0.9))
      val b = corners(canvas.at(0.9))
      a.nonEmpty must beTrue
      (a == b) must beTrue
    }

    "be the choice for more cells than an SVG page holds, and the SVG below that" in {
      // 52 points have 22,100 triangles, 40 have 9,880 (all of them, at a scale past every edge)
      def cloud(n: Int) = Array.tabulate(n)(i => Array(math.cos(2.4 * i) * (1 + 0.1 * math.sin(i)), math.sin(2.4 * i)))
      Plot.svgCells === 20000
      Plot.filtration(cloud(40), max = 10.0).panels.head must beAnInstanceOf[Panel.Flat]
      Plot.filtration(cloud(52), max = 10.0).panels.head must beAnInstanceOf[Panel.Spatial]
      // what the caller says wins either way
      Plot.filtration(cloud(52), max = 10.0, canvas = false).panels.head must beAnInstanceOf[Panel.Flat]
      Plot.filtration(cloud(12), max = 10.0, canvas = true).panels.head must beAnInstanceOf[Panel.Spatial]
    }

    "weigh a fraction of the SVG page and say how it moves" in {
      val svg = Plot.filtration(ring, max = 3.0, canvas = false).fragment(Theme.Light)
      val canvas = Plot.filtration(ring, max = 3.0, canvas = true).fragment(Theme.Light)
      (canvas.length * 3 < svg.length) must beTrue
      canvas must contain("drag to pan, scroll to zoom")
      canvas must contain("const turns=false")
      canvas must not(contain("drag to turn"))
    }

    "link its cycles like the SVG page, on the same cells" in {
      val bars = Persistence(ring, maxDimension = 1, maxFiltrationValue = 1.5).bars
      val k = bars.indices.filter(bars(_).dim == 1).maxBy(bars(_).persistence)
      val svg = Plot.filtration(ring, max = 1.5, link = true, canvas = false)
      val canvas = Plot.filtration(ring, max = 1.5, link = true, canvas = true)
      def thick(s: Snapshot): Set[Edge] =
        val found: Seq[Edge] = s.parts(0) match
          case f: Figure =>
            f.marks.flatMap {
              case m: Mark.Segments if m.width == 3.0 => m.ends
              case _                                  => Seq.empty[Edge]
            }
          case sc: Scene =>
            sc.layers.flatMap {
              case l: Layer.Segments if l.width == 3.0 => l.ends.map { case ((a, b, _), (c, d, _)) => ((a, b), (c, d)) }
              case _                                   => Seq.empty[Edge]
            }
          case other => sys.error(s"unexpected panel $other")
        Set.from(found)
      val state = PageState().value("t", 1.5).select("class", k)
      thick(svg.at(state)).nonEmpty must beTrue
      (thick(svg.at(state)) == thick(canvas.at(state))) must beTrue
    }
  }
