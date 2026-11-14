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

  "an item of a 3-D layer that follows a slider" should {
    // seen from the side (azimuth 0, elevation 0): x goes right, z goes up, y is depth, and what is farther is drawn first
    def view(layers: Layer*): Scene = Scene(layers.toVector, azimuth = 0, elevation = 0)
    val a: XYZ = (0.0, 2.0, 0.0)
    val b: XYZ = (0.5, 1.0, 0.0)
    val dots = Layer.Points(IndexedSeq(a, b), IndexedSeq(Paint.Ink), 3.5)
    def page(first: Reaction) =
      Interactive()
        .withSlider("t", 0, 5)
        .withPanel(Panel.Spatial(view()).reactEach(dots, IndexedSeq(first, Reaction.none)))
    val rises = page(Reaction(Vector(Behavior.followsZ("t"))))
    val nears = page(Reaction(Vector(Behavior.Follows("t", Coordinate.Y, Ends.Both, 0.0, 4.0))))

    def points(s: Snapshot): IndexedSeq[XYZ] = sceneOf(s).layers.head.asInstanceOf[Layer.Points].at
    // the dots of a still, in drawing order, as (cx, cy)
    def drawn(s: Snapshot): Seq[(Double, Double)] =
      """<circle cx="([^"]+)" cy="([^"]+)" r="3.5"""".r
        .findAllMatchIn(sceneOf(s).staticSvg(Theme.Light))
        .map(m => (m.group(1).toDouble, m.group(2).toDouble))
        .toSeq

    "be at the control's value in that coordinate, held between the least and the greatest" in {
      val held = page(Reaction(Vector(Behavior.Follows("t", Coordinate.Z, Ends.Both, 1.0, 3.0))))
      Seq(0.0, 2.0, 5.0).map(t => points(held.at(t)).head._3) === Seq(1.0, 2.0, 3.0)
      // the other coordinates and the other item stay
      points(held.at(5.0)) === IndexedSeq((0.0, 2.0, 3.0), b)
    }

    "keep every other item where the picture put it: the frame covers the places the item can be at" in {
      // item b is fixed; a rises to z = 5, far outside the picture the points alone would give. A picture fitted to the
      // items as they are at a value would move b as a rises
      val places = Seq(0.0, 1.0, 2.5, 5.0).map(t => drawn(rises.at(t)).maxBy(_._1))
      places.distinct.size === 1
    }

    "be drawn in the depth order of where it is: farther first" in {
      // a moves from in front of b (y = 0 against 1) to behind it (y = 4): the order they are drawn in flips
      val front = drawn(nears.at(0.0)).map(_._1)
      val back = drawn(nears.at(4.0)).map(_._1)
      (front.size === 2)
        .and(back.size === 2)
        .and(front.reverse === back)
        .and(front.head must not(beEqualTo(front.last)))
    }

    "tell the canvas the coordinate, the ends and the limits, and leave the others as they are" in {
      val html = rises.fragment(Theme.Light)
      html must contain(""""fo":[[["t",2,3,-Infinity,Infinity]],null]""")
    }

    "move one end of a segment, or both" in {
      val segment = Layer.Segments(IndexedSeq((a, b)), IndexedSeq(Paint.Ink), 1.0)
      def ends(e: Ends) =
        Interactive()
          .withSlider("t", 0, 5)
          .withPanel(Panel.Spatial(view()).react(segment, Behavior.Follows("t", Coordinate.Z, e, 0.0, 5.0)))
      def at(e: Ends) = sceneOf(ends(e).at(4.0)).layers.head.asInstanceOf[Layer.Segments].ends.head
      at(Ends.First) === ((0.0, 2.0, 4.0), b)
      at(Ends.Second) === (a, (0.5, 1.0, 4.0))
      at(Ends.Both) === ((0.0, 2.0, 4.0), (0.5, 1.0, 4.0))
    }

    "move a triangle's corners, a ball's centre and a halo with the item it rings" in {
      val tri = Layer.Triangles(IndexedSeq((a, b, (1.0, 1.0, 0.0))), IndexedSeq(Paint.Series(0)), 0.2)
      val ball = Layer.Balls(IndexedSeq(a), 0.3, IndexedSeq(Paint.Series(0)))
      val follows = Behavior.Follows("t", Coordinate.Z, Ends.Both, 0.0, 5.0)
      val chosen = Panel
        .Spatial(view())
        .react(tri, follows)
        .react(ball, follows)
        .react(dots, follows)
        .selecting(2, "c", IndexedSeq(0, 1))
      val moved = Interactive()
        .withSlider("t", 0, 5)
        .withSelection("c")
        .withPanel(chosen)
        .at(PageState().value("t", 3.0).select("c", 0))
      val layers = sceneOf(moved).layers
      layers.collect { case l: Layer.Triangles => l.corners.head }.head === (
        (0.0, 2.0, 3.0),
        (0.5, 1.0, 3.0),
        (1.0, 1.0, 3.0)
      )
      layers.collect { case l: Layer.Balls => l.centers }.head === IndexedSeq((0.0, 2.0, 3.0))
      layers.collect { case l: Layer.Halos => l.at }.head === IndexedSeq((0.0, 2.0, 3.0))
    }

    "be refused where it cannot be carried out: one end of a point, a z in a picture" in {
      val point = Layer.Points(IndexedSeq(a), IndexedSeq(Paint.Ink), 3.0)
      Panel
        .Spatial(view())
        .react(point, Behavior.followsX("t", ends = Ends.First)) must throwAn[IllegalArgumentException]
      val figure = Figure(marks = Vector.empty)
      Panel(figure).react(Mark.Rule.horizontal(0.0), Behavior.Follows("t", Coordinate.Z, Ends.Both, 0.0, 1.0)) must
        throwAn[IllegalArgumentException]
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

    "carry a radius only on balls, and a coordinate that follows a control only as a whole (a segment's ends apart)" in {
      val ball = Layer.Balls(points3, 0.2, IndexedSeq(Paint.Series(0)))
      Panel(scene).react(ball, Behavior.radius("t")).scene.layers.size === 4
      Panel(scene).react(scene.layers.head, Behavior.followsY("t")).scene.layers.size === 4
      Panel(scene).react(scene.layers(1), Behavior.followsY("t", ends = Ends.First)).scene.layers.size === 4
      (Panel(scene).react(scene.layers.head, Behavior.radius("t")) must throwAn[IllegalArgumentException](
        ".*cannot be attached.*"
      )).and(
        Panel(scene).react(scene.layers.head, Behavior.followsY("t", ends = Ends.First)) must
          throwAn[IllegalArgumentException](".*cannot be attached.*")
      )
    }

    "give a still with the balls at the radius the control asks for, whatever the starting one" in {
      val ball = Layer.Balls(points3, 0.2, IndexedSeq(Paint.Series(0)))
      val sweep = Interactive().withSlider("t", 0, 2).withPanel(Panel(scene).react(ball, Behavior.radius("t", 0.5)))
      ballsOf(sceneOf(sweep.at(1.4))).head.radius must beCloseTo(0.7, 1e-12)
    }
  }

  "a rectangle on a 3-D panel" should {
    // seen from the side (azimuth 0, elevation 0) the view plane is x to the right and z up; the six anchors make the frame
    // the unit ball around the origin, so that a place's view coordinates are its own x and z
    val anchors = Layer.Points(
      IndexedSeq(
        (1.0, 0.0, 0.0),
        (-1.0, 0.0, 0.0),
        (0.0, 1.0, 0.0),
        (0.0, -1.0, 0.0),
        (0.0, 0.0, 1.0),
        (0.0, 0.0, -1.0)
      ),
      IndexedSeq(Paint.Ink),
      1.0
    )
    val dots = Layer.Points(IndexedSeq((0.0, 0.0, 0.0), (0.4, 0.0, 0.4), (0.0, 0.0, 0.0)), IndexedSeq(Paint.Ink), 3.5)
    val stick = Layer.Segments(IndexedSeq(((-0.7, 0.0, 0.45), (0.7, 0.0, 0.45))), IndexedSeq(Paint.Ink), 1.0)
    // the half of a square below the line x + z = 0: its bounding box is the whole square
    val plate = Layer.Triangles(
      IndexedSeq(((-0.7, 0.0, -0.7), (0.7, 0.0, -0.7), (-0.7, 0.0, 0.7))),
      IndexedSeq(Paint.Series(1)),
      0.3
    )
    val comes = Reaction(Vector(Behavior.shownFrom("t", 0.5)))
    val rises = Reaction(Vector(Behavior.followsZ("t", 0.0, 0.7)))
    def panelAt(azimuth: Double) =
      Panel(Scene(Vector(anchors), azimuth = azimuth, elevation = 0))
        .reactEach(dots, IndexedSeq(Reaction.none, comes, rises))
        .react(stick, Reaction.none)
        .react(plate, Reaction.none)
        .selecting(1, "c", IndexedSeq(0, 1, 2), halo = false)
        .selecting(2, "c", IndexedSeq(10), halo = false)
        .selecting(3, "c", IndexedSeq(20), halo = false)
    def pageOf(panel: Panel) = Interactive().withSlider("t", 0, 1).withSelection("c").withPanel(panel)
    val page = pageOf(panelAt(0).brushable("c"))
    def touched(w: Window, t: Double = 0.0, on: Interactive = page) =
      on.touched(0, w, "c", PageState().value("t", t))

    "choose a segment that crosses it with both ends outside, and not only the points inside" in
      touched(Window(-0.15, 0.15, 0.35, 0.55)) === Set(10)

    "choose a triangle only where it is, not where its bounding box is" in
      // the box 0.3..0.5 by 0.3..0.42 is in the corner of the triangle's bounding box, away from the triangle
      touched(Window(0.3, 0.5, 0.3, 0.42), t = 0.5) === Set(1)

    "choose a triangle that a rectangle lies inside, though none of its edges meets the rectangle" in
      touched(Window(-0.6, -0.4, -0.6, -0.4)) === Set(20)

    "leave a point whose centre is outside, however close, and let the size of its marker not count" in {
      touched(Window(0.41, 0.5, 0.3, 0.39), t = 0.5) must beEmpty
    }

    "count the outline of the rectangle as part of it" in {
      // the point (0.4, 0.4) is at the corner of the rectangle, and the segment at height 0.45 crosses its side
      touched(Window(0.4, 0.5, 0.4, 0.5), t = 0.5) === Set(1, 10)
      touched(Window(0.3, 0.4, 0.3, 0.4), t = 0.5) === Set(1)
    }

    "choose only what the page draws: an item that has not come yet is not there to choose" in {
      val w = Window(0.3, 0.5, 0.3, 0.42)
      touched(w, t = 0.49) must beEmpty
      touched(w, t = 0.5) === Set(1)
    }

    "choose a moving item where the control has put it" in {
      val w = Window(-0.1, 0.1, 0.55, 0.75)
      touched(w, t = 0.0) must beEmpty
      touched(w, t = 0.9) === Set(2)
      // held at the least and the greatest value its coordinate runs over: 0.7, whatever the slider says
      touched(w, t = 1.0) === Set(2)
    }

    "choose in its own selection and leave the keys an item has in another one alone" in {
      val two = Interactive()
        .withSlider("t", 0, 1)
        .withSelection("c")
        .withSelection("d")
        .withPanel(panelAt(0).selecting(1, "d", IndexedSeq(5, 6, 7), halo = false).brushable("c"))
      two.touched(0, Window(0.3, 0.5, 0.3, 0.42), "c", PageState().value("t", 0.5)) === Set(1)
      two.touched(0, Window(0.3, 0.5, 0.3, 0.42), "d", PageState().value("t", 0.5)) === Set(6)
    }

    "be a rectangle in the view the scene is seen from, so that turning the scene chooses something else" in {
      // from the side the point at (0.4, 0, 0.4) is in the rectangle; seen along the x axis it is at the middle, away from it
      val w = Window(0.3, 0.5, 0.3, 0.42)
      touched(w, t = 0.5) === Set(1)
      touched(w, t = 0.5, on = pageOf(panelAt(90).brushable("c"))) must beEmpty
    }

    "draw in a still the rectangle it was given, in the units of the view plane" in {
      val still = sceneOf(page.at(PageState().brush(0, Window(-0.15, 0.15, 0.35, 0.55))))
      still.region === Some(Window(-0.15, 0.15, 0.35, 0.55))
      val svg = still.staticSvg(Theme.Light)
      // 0.46 of the smaller side, the room under a 12 pixel margin: 0.46 * 508, from the middle (320, 12 + 254)
      val scale = 0.46 * 508
      svg must contain(
        f"""<rect x="${320 - 0.15 * scale}%.2f" y="${266 - 0.55 * scale}%.2f" width="${0.3 * scale}%.2f" height="${0.2 * scale}%.2f""""
      )
      svg must contain("stroke-dasharray")
    }

    "draw no rectangle in the picture that was not given one" in {
      sceneOf(page.at(PageState())).staticSvg(Theme.Light) must not(contain("stroke-dasharray"))
    }

    "give the page a box button and a script for it, and a page that does not choose by rectangle neither" in {
      val html = page.fragment(Theme.Light)
      html must contain("""class="tda4j-box"""")
      html must contain("function chooseBox")
      val plain = pageOf(panelAt(0)).fragment(Theme.Light)
      plain must not(contain("tda4j-box"))
      plain must not(contain("chooseBox"))
    }

    "take focus and the keyboard on a canvas that chooses by rectangle, and a plain canvas neither" in {
      val html = page.fragment(Theme.Light)
      html must contain("""tabindex="0"></canvas>""")
      html must contain("function stepTo(dir)")
      html must contain("e.key==='Escape'")
      val plain = pageOf(panelAt(0)).fragment(Theme.Light)
      plain must not(contain("""tabindex="0"></canvas>"""))
      plain must contain("</canvas>")
      plain must not(contain("function stepTo(dir)"))
    }

    "be refused where there is nothing to choose, or no such panel" in {
      pageOf(panelAt(0).brushable("nope")).fragment() must throwAn[IllegalArgumentException](
        ".*selection called nope.*"
      )
      Interactive()
        .withSlider("t", 0, 1)
        .withSelection("c")
        .withPanel(Panel(Scene(Vector(anchors))).brushable("c"))
        .fragment() must throwAn[IllegalArgumentException](".*none of its items chooses in c.*")
      page.at(PageState().brush(1, Window(0, 1, 0, 1))) must throwAn[IllegalArgumentException](
        ".*not a brushable panel.*"
      )
      pageOf(panelAt(0)).at(PageState().brush(0, Window(0, 1, 0, 1))) must throwAn[IllegalArgumentException](
        ".*not a brushable panel.*"
      )
    }

    "say that a 2-D panel's rectangle is the browser's to work out" in {
      val flat = Interactive()
        .withSlider("t", 0, 1)
        .withSelection("c")
        .withPanel(
          Panel(Figure(marks = Vector(Mark.Points(IndexedSeq((0.0, 0.0)), IndexedSeq(Paint.Series(0))))))
            .selecting(0, "c", IndexedSeq(0))
            .brushable("c")
        )
      flat.touched(0, Window(0, 1, 0, 1), "c") must throwAn[IllegalArgumentException](".*2-D panel.*")
    }
  }

  "the axes of a flat picture" should {
    // points on the corners and the middle of a 10 by 4 rectangle, seen from above: x to the right, y up
    val spots: IndexedSeq[XYZ] =
      IndexedSeq((0.0, 0.0, 0.0), (10.0, 0.0, 0.0), (0.0, 4.0, 0.0), (10.0, 4.0, 0.0), (5.0, 2.0, 0.0))
    def flat(more: Scene => Scene = identity) =
      more(Scene(Vector(Layer.Points(spots, IndexedSeq(Paint.Ink), 3.0)), azimuth = 0, elevation = 90, axes = true))
    def dots(svg: String): Seq[(Double, Double)] =
      """<circle cx="([^"]+)" cy="([^"]+)"""".r
        .findAllMatchIn(svg)
        .map(m => (m.group(1).toDouble, m.group(2).toDouble))
        .toSeq
    // the labels written under and beside the plot area: (text, x, y, anchor)
    def labels(svg: String): Seq[(String, Double, Double, String)] =
      """<text x="([^"]+)" y="([^"]+)" text-anchor="([^"]+)" font-size="[^"]+" style="[^"]+">([^<]+)</text>""".r
        .findAllMatchIn(svg)
        .map(m => (m.group(4), m.group(1).toDouble, m.group(2).toDouble, m.group(3)))
        .toSeq

    "number the plane where the points are: a tick is at the place of the data it names" in {
      val svg = flat().staticSvg(Theme.Light)
      val d = dots(svg)
      // the pixel of a data x and y, from the points at the ends of the ranges (the picture draws them in depth order)
      val (left, right, top, bottom) = (d.map(_._1).min, d.map(_._1).max, d.map(_._2).min, d.map(_._2).max)
      def px(v: Double) = left + (right - left) * v / 10
      def py(v: Double) = bottom - (bottom - top) * v / 4
      val ls = labels(svg)
      val xs = ls.filter(_._4 == "middle")
      val ys = ls.filter(_._4 == "end")
      xs.map(_._1).toSet must contain("0")
      xs.map(_._1).toSet must contain("10")
      ys.map(_._1).toSet must contain("4")
      xs.map(l => math.abs(l._2 - px(l._1.toDouble))).max must beLessThan(0.02)
      // a label's baseline is a third of its height below the tick it names
      ys.map(l => math.abs(l._3 - 0.35 * 11 - py(l._1.toDouble))).max must beLessThan(0.02)
    }

    "keep the picture inside the axes, in the room the margins leave" in {
      val d = dots(flat().staticSvg(Theme.Light))
      // 48 pixels on the left for the numbers, a margin of 8 on the right, and 26 under the picture
      d.map(_._1).min must beGreaterThan(48.0)
      d.map(_._1).max must beLessThan(640.0 - 8.0)
      d.map(_._2).max must beLessThan(520.0 - 26.0)
    }

    "make room for the numbers as the text grows" in {
      // the axes lie along the edges of the plot area: the first line of the group is the x axis (its y is the foot of the
      // area), the second the y axis (its x is the left edge)
      def edges(svg: String): (Double, Double) =
        val g =
          """<g class="tda4j-axes"[^>]*><line x1="[^"]+" y1="([^"]+)" x2="[^"]+" y2="[^"]+"/><line x1="([^"]+)"""".r
        val m = g.findFirstMatchIn(svg).get
        (m.group(2).toDouble, m.group(1).toDouble)
      edges(flat().staticSvg(Theme.Light)) === (48.0, 520.0 - 26.0)
      edges(flat(_.withTextScale(2.0)).staticSvg(Theme.Light)) === (96.0, 520.0 - 52.0)
      val d = dots(flat(_.withTextScale(2.0)).staticSvg(Theme.Light))
      d.map(_._1).min must beGreaterThan(96.0)
      d.map(_._2).max must beLessThan(520.0 - 52.0)
    }

    "be numbered again for what a zoomed picture shows" in {
      val whole = labels(flat().staticSvg(Theme.Light)).filter(_._4 == "middle").map(_._1)
      val closer = labels(flat(_.copy(zoom = 4.0)).staticSvg(Theme.Light)).filter(_._4 == "middle").map(_._1)
      // four times closer shows a quarter of the range, so its ticks are finer and fewer of the points' numbers are among them
      (closer.toSet != whole.toSet) must beTrue
      closer.size must beGreaterThan(0)
    }

    "clip the picture to the plot area, and leave the axes out of the clip" in {
      val svg = flat().staticSvg(Theme.Light)
      svg must contain("<clipPath")
      svg must contain("""<g class="tda4j-axes"""")
      val clipped = svg.indexOf("""<g clip-path=""")
      val axes = svg.indexOf("""<g class="tda4j-axes"""")
      (clipped >= 0 && axes > clipped) must beTrue
    }

    "leave a scene that has none as it was" in {
      val svg = flat(_.copy(axes = false)).staticSvg(Theme.Light)
      svg must not(contain("tda4j-axes"))
      svg must not(contain("clipPath"))
      // a picture without axes is centred in the whole picture, not in the room inside the axes
      val xs = dots(svg).map(_._1)
      ((xs.min + xs.max) / 2) must beCloseTo(320.0, 0.01)
      val inside = dots(flat().staticSvg(Theme.Light)).map(_._1)
      ((inside.min + inside.max) / 2) must beCloseTo((48.0 + 632.0) / 2, 0.01)
    }

    "be refused for a picture that is not seen from above, with the way out" in {
      val tilted = flat(_.copy(elevation = 25))
      tilted.staticSvg(Theme.Light) must throwAn[IllegalArgumentException](".*azimuth = 0 and elevation = 90.*")
      tilted.fragment(Theme.Light) must throwAn[IllegalArgumentException](".*azimuth = 0 and elevation = 90.*")
    }

    "be asked for with withAxes, and be on a flat canvas of Plot.filtration but not on a turning one" in {
      Scene().withAxes.axes must beTrue
      Scene().axes must beFalse
      val ring = Array.tabulate(22)(i => Array(math.cos(2 * math.Pi * i / 22), math.sin(2 * math.Pi * i / 22)))
      val canvas = Plot.filtration(ring, max = 1.5, canvas = true).panels.head.asInstanceOf[Panel.Spatial].scene
      canvas.axes must beTrue
      val ring3 = ring.map(p => Array(p(0), p(1), 0.3 * p(0) * p(1)))
      Plot.filtration3D(ring3, max = 1.5).panels.head.asInstanceOf[Panel.Spatial].scene.axes must beFalse
    }

    "give the canvas the same tick rules as the zoomed figures: one script for both" in {
      val canvas = Interactive()
        .withSlider("t", 0, 1)
        .withPanel(Panel(flat()).react(Layer.Points(spots, IndexedSeq(Paint.Ink), 3.0), Behavior.shownFrom("t", 0.0)))
        .fragment(Theme.Light)
      canvas must contain(Scene.tickScript)
      canvas must contain("function paintAxes")
      // the page's own script has the same text
      val page = Interactive().withPanel(Panel(Figure()).zoomable()).fragment(Theme.Light)
      page must contain(Scene.tickScript)
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
