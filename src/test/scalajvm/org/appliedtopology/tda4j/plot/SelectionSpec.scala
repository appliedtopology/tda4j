package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

/** Linked selections: the key a mark's item chooses, what is lit while a key is chosen, and the rules that keep a plot
  * from naming a selection it does not have. Each example is built so that the plausible wrong implementation fails it:
  * a key taken from the position in the barcode's sorted order instead of the list given, a halo that ignores that its
  * item has not yet appeared, a cycle drawn for the neighbouring bar.
  */
class SelectionSpec extends Specification:
  sequential

  private lazy val noisy = CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
  private lazy val computed = Persistence(noisy, maxDimension = 1, maxFiltrationValue = 1.0)

  // the bars in an order that is neither the diagram's nor the barcode's, so a key taken from either position is wrong
  private lazy val bars = computed.bars.reverse
  private lazy val circle =
    Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))

  private type Edge = ((Double, Double), (Double, Double))
  private lazy val page = Plot.exploreOn(noisy, bars)
  private lazy val flat: Seq[Panel.Flat] = page.panels.collect { case p: Panel.Flat => p }
  private lazy val (cloud, diagram, barcode) = (flat(0), flat(1), flat(2))

  private def figureOf(snapshot: Snapshot, panel: Int): Figure = snapshot.parts(panel).asInstanceOf[Figure]

  /** The key each item of each mark chooses, by mark. */
  private def chosenKeys(panel: Panel.Flat): Map[Int, Seq[Int]] =
    panel.reactions.toSeq.flatMap { (m, rs) =>
      val keys: Seq[Option[Int]] = rs.map(_.behaviors.collectFirst { case Behavior.Selects(_, k) => k })
      Option.when(keys.forall(_.isDefined))(m -> keys.flatten)
    }.toMap

  private def halos(f: Figure): Seq[Mark.Segments] = f.marks.collect { case s: Mark.Segments if s.opacity == 0.3 => s }

  "Plot.explore" should {
    "give every bar one key, in the diagram and in the barcode, the position of the bar in the list it was given" in {
      val inDiagram: Seq[Int] = chosenKeys(diagram).values.toSeq.flatMap(ks => ks).sorted
      val inBarcode: Seq[Int] = chosenKeys(barcode).values.toSeq.flatMap(ks => ks).sorted
      (inDiagram === bars.indices).and(inBarcode === bars.indices)
    }

    "put a bar's point and its bar where the bar says, whichever order the views draw them in" in {
      val points: Seq[(Mark.Points, Seq[Int])] = diagram.figure.marks.zipWithIndex.flatMap {
        case (p: Mark.Points, m) => chosenKeys(diagram).get(m).map(keys => (p, keys))
        case _                   => None
      }
      val segments: Seq[(Mark.Segments, Seq[Int])] = barcode.figure.marks.zipWithIndex.flatMap {
        case (s: Mark.Segments, m) => chosenKeys(barcode).get(m).map(keys => (s, keys))
        case _                     => None
      }
      val essentialTop = diagram.figure.marks.collectFirst { case r: Mark.Rule => r.at }
      val pointsRight = points.forall { (mark, keys) =>
        mark.at.zip(keys).forall { case ((x, y), k) =>
          x == bars(k).birth && (y == (if bars(k).death.isPosInfinity then essentialTop.get else bars(k).death))
        }
      }
      val barsRight = segments.forall { (mark, keys) =>
        mark.ends.zip(keys).forall { case (((x, _), _), k) => x == bars(k).birth }
      }
      (pointsRight must beTrue).and(barsRight must beTrue)
    }

    "write the same words for a bar wherever it is pointed at, and in the list" in {
      val labels = page.selections.head.labels
      val tipsOfPoints: Seq[(String, Int)] = diagram.figure.marks.zipWithIndex.flatMap {
        case (p: Mark.Points, m) => p.tips.zip(chosenKeys(diagram).getOrElse(m, Seq.empty))
        case _                   => Seq.empty
      }
      val tipsOfBars: Seq[(String, Int)] = barcode.figure.marks.zipWithIndex.flatMap {
        case (s: Mark.Segments, m) => s.tips.zip(chosenKeys(barcode).getOrElse(m, Seq.empty))
        case _                     => Seq.empty
      }
      val wrongPoints = tipsOfPoints.filter((tip, k) => tip != labels(k))
      val wrongBars = tipsOfBars.filter((tip, k) => tip != labels(k))
      (labels.size === bars.size).and(wrongPoints must beEmpty).and(wrongBars must beEmpty)
    }

    "light up nothing until a key is chosen, and exactly that bar's point and bar when one is" in {
      val none = page.at(PageState())
      val k = bars.indices.maxBy(i => if bars(i).death.isFinite then bars(i).persistence else -1.0)
      val one = page.at(PageState().select("class", k))
      val (lit, other) = (figureOf(one, 1), figureOf(one, 2))
      val pointHalo = halos(lit).flatMap(_.ends)
      val barHalo = halos(other).flatMap(_.ends)
      (halos(figureOf(none, 1)).flatMap(_.ends) must beEmpty)
        .and(halos(figureOf(none, 2)).flatMap(_.ends) must beEmpty)
        .and(pointHalo === Seq(((bars(k).birth, bars(k).death), (bars(k).birth, bars(k).death))))
        .and(barHalo.size === 1)
        .and(barHalo.head._1._1 === bars(k).birth)
    }

    "light up every chosen key, and only those" in {
      val keys = Set(0, 2, bars.size - 1)
      val lit = page.at(PageState().select("class", keys))
      (halos(figureOf(lit, 1)).flatMap(_.ends).size === keys.size)
        .and(halos(figureOf(lit, 2)).flatMap(_.ends).size === keys.size)
    }

    "draw the cycle of the chosen bar on the cloud, and not the cycle of its neighbour" in {
      def thick(f: Figure): Set[Edge] =
        f.marks.flatMap {
          case s: Mark.Segments if s.width == 3.0 => s.ends
          case _                                  => Seq.empty[Edge]
        }.toSet
      def edgesOfCycle(k: Int): Set[Edge] =
        thick(Plot.cycle(noisy, bars(k).representative.asInstanceOf[Chain[Simplex[Int], ?]])(using Look.none))
      def edgesDrawn(k: Int): Set[Edge] = thick(figureOf(page.at(PageState().select("class", k)), 0))
      val loops = bars.indices.filter(i => bars(i).dim == 1 && bars(i).persistence > 0).take(2)
      val points = bars.indices.filter(bars(_).dim == 0).take(1)
      val wrong = (loops ++ points).filter(k => edgesDrawn(k) != edgesOfCycle(k))
      (loops must not(beEmpty))
        .and(wrong must beEmpty)
        .and(edgesDrawn(loops.head).nonEmpty must beTrue)
        .and(edgesDrawn(points.head) must beEmpty)
    }

    "draw no cycle of a bar that did not fit the cell budget, and say so" in {
      val small = Plot.exploreOn(noisy, bars, maxCycleCells = 1)
      val loop = bars.indices.find(bars(_).dim == 1).get
      val drawn = figureOf(small.at(PageState().select("class", loop)), 0)
      val note = small.panels.head.asInstanceOf[Panel.Flat].figure.subtitle
      val cycleEdges = drawn.marks.map {
        case s: Mark.Segments if s.width == 3.0 => s.ends.size
        case _                                  => 0
      }.sum
      (cycleEdges === 0).and(note must contain("most persistent"))
    }

    "refuse points that none of the bars has a cycle on" in {
      val far = Array(Array(0.0, 0.0), Array(1.0, 0.0))
      val lonely = Seq.empty[PersistenceBar[Double, ?]]
      (Plot.explore(lonely) must throwAn[IllegalArgumentException](".*no bars.*")).and(
        Plot.exploreOn(far, computed.bars.map(_.copy(annotation = None))) must throwAn[IllegalArgumentException](
          ".*representative.*"
        )
      )
    }
  }

  "the page of a selection" should {
    lazy val html = page.fragment(Theme.Light)

    "name the key an item chooses and the key that lights an item, in the item's own attribute" in {
      val k = bars.indices.head
      val choosing = s"""data-ia="s,class,$k""""
      val lighting = s"""data-ia="w,class,$k""""
      (html must contain(choosing)).and(html must contain(lighting))
    }

    "hide what is lit by a key until script runs, so that a picture without script shows no halo" in {
      val lit = flat.map(_.reactions.values.map(_.count(_.behaviors.exists(_.isInstanceOf[Behavior.Selected]))).sum).sum
      """display="none"""".r.findAllIn(html).size === lit
    }

    "list what is chosen under the pictures" in
      (html must contain("""class="tda4j-selection"""")).and(html must contain("nothing chosen"))

    "leave a plot with no selection as it was" in {
      val plain = Plot.filtration(circle).fragment(Theme.Light)
      (plain must not(contain("""class="tda4j-selection""""))).and(plain must not(contain("display=\"none\"")))
    }
  }

  "a linked filtration" should {
    lazy val sweep = Plot.filtration(circle, link = true, max = 2.0, barcode = true)
    lazy val loop = sweep.panels(1).asInstanceOf[Panel.Flat]
    lazy val all = Persistence(circle, maxDimension = 1, maxFiltrationValue = 2.0).bars
    lazy val k = all.indices.find(all(_).dim == 1).get

    "light the growing segment of a chosen class while it grows, and its point after it died" in {
      val bar = all(k)
      val mid = (bar.birth + bar.death) / 2
      val growing = figureOf(sweep.at(PageState().value("t", mid).select("class", k)), 1)
      val dead = figureOf(sweep.at(PageState().value("t", bar.death).select("class", k)), 1)
      val growingHalo = halos(growing).flatMap(_.ends)
      val deadHalo = halos(dead).flatMap(_.ends)
      (growingHalo === Seq(((bar.birth, bar.birth), (bar.birth, mid))))
        .and(deadHalo === Seq(((bar.birth, bar.death), (bar.birth, bar.death))))
    }

    "light nothing for a class that has not been born, whatever is chosen" in {
      val bar = all(k)
      val before = figureOf(sweep.at(PageState().value("t", math.max(0.0, bar.birth - 0.01)).select("class", k)), 1)
      if bar.birth > 0.01 then halos(before).flatMap(_.ends) must beEmpty else ok
    }

    "draw the chosen class's cycle on the complex" in {
      val shown = figureOf(sweep.at(PageState().value("t", 2.0).select("class", k)), 0)
      val cycleEdges = shown.marks.map {
        case s: Mark.Segments if s.width == 3.0 => s.ends.size
        case _                                  => 0
      }.sum
      cycleEdges must beGreaterThan(0)
    }
  }

  "a brushable panel" should {
    val points = Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 1.0)), IndexedSeq(Paint.Ink))
    val chosen = Panel(Figure(marks = Vector(points))).selecting(0, "c", IndexedSeq(0, 1))

    "mark its own box on the page, and no other" in {
      val html =
        Interactive().withSelection("c").withPanel(chosen.brushable("c")).withPanel(chosen).fragment(Theme.Light)
      ("data-brush=\"c\"".r.findAllIn(html).size === 1).and(html must contain("class=\"tda4j-panel\" data-brush=\"c\""))
    }

    "be there in the views of explore, and not in the cloud" in
      page.panels.collect { case Panel.Flat(_, _, brush, _) => brush } === Vector(None, Some("class"), Some("class"))

    "be refused for a selection the plot lacks, or one nothing in the panel chooses in" in {
      val missing = Interactive().withSelection("c").withPanel(chosen.brushable("d"))
      val idle = Interactive().withSelection("c").withSelection("d").withPanel(chosen.brushable("d"))
      (missing.fragment() must throwAn[IllegalArgumentException](
        ".*brushable on a selection called d.*selections are: c.*"
      ))
        .and(
          idle.fragment() must throwAn[IllegalArgumentException](
            ".*brushable on d, but none of its items chooses in d.*"
          )
        )
    }

    "bring the brush's style only to a page that has one" in {
      val brushing = Interactive().withSelection("c").withPanel(chosen.brushable("c")).fragment(Theme.Light)
      val plain = Interactive().withSelection("c").withPanel(chosen).fragment(Theme.Light)
      (brushing must contain(".tda4j-brush{")).and(plain must not(contain(".tda4j-brush{")))
    }

    "link the brushable views of a linked filtration too" in {
      val sweep = Plot.filtration(circle, link = true, max = 2.0, barcode = true)
      sweep.panels.collect { case Panel.Flat(_, _, brush, _) => brush } === Vector(None, Some("class"), Some("class"))
    }
  }

  "building a selection" should {
    val fig = Figure()
    val rule = Mark.Rule.horizontal(0.0)

    "refuse to react to a selection the plot does not have, naming the ones it has" in {
      val none = Interactive().withSlider("t", 0, 1).withPanel(Panel(fig).react(rule, Behavior.selects("c", 0)))
      val other = Interactive().withSelection("a").withPanel(Panel(fig).react(rule, Behavior.selected("c", 0)))
      (none.fragment() must throwAn[IllegalArgumentException](".*selection called c.*none.*"))
        .and(other.fragment() must throwAn[IllegalArgumentException](".*selection called c.*selections are: a.*"))
    }

    "refuse a slider behavior on a selection, and a plot with nothing to respond to" in {
      val wrong = Interactive().withSelection("c").withPanel(Panel(fig).react(rule, Behavior.shownFrom("c", 0)))
      (wrong.fragment() must throwAn[IllegalArgumentException](".*control called c.*controls are: none.*"))
        .and(
          Interactive().withPanel(fig).fragment() must throwAn[IllegalArgumentException](
            ".*no control, selection or zoomable panel.*"
          )
        )
    }

    "keep control and selection names apart" in
      (Interactive().withSelection("c").withSlider("c", 0, 1) must throwAn[IllegalArgumentException](
        ".*already a control or selection.*"
      )).and(
        Interactive().withSlider("c", 0, 1).withSelection("c") must throwAn[IllegalArgumentException](
          ".*already a control or selection.*"
        )
      )

    "take one key for each item of the mark, and only a mark with items" in {
      val points = Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 1.0)), IndexedSeq(Paint.Ink))
      val panel = Panel(Figure(marks = Vector(points, Mark.Text((0.0, 0.0), "x"))))
      (panel.selecting(0, "c", IndexedSeq(0)) must throwAn[IllegalArgumentException](".*2 items.*2 keys.*"))
        .and(panel.selecting(1, "c", IndexedSeq(0)) must throwAn[IllegalArgumentException](".*no items.*"))
        .and(panel.selecting(5, "c", IndexedSeq(0)) must throwAn[IllegalArgumentException](".*no mark 5.*"))
    }

    "carry a selection to a 3-D layer, and no slider's coordinate" in {
      val scene = Scene(Vector(Layer.Points(IndexedSeq((0.0, 0.0, 0.0)), IndexedSeq(Paint.Ink), 3.0)))
      val carried = Panel(scene).react(scene.layers.head, Behavior.selected("c", 0))
      (carried.reactions.size === 1).and(
        Panel(scene).react(scene.layers.head, Behavior.followsX("t")) must throwAn[IllegalArgumentException](
          ".*cannot be attached.*"
        )
      )
    }

    "refuse a still of a selection the plot does not have" in {
      val one = Interactive().withSelection("c").withPanel(fig)
      one.at(PageState().select("d", 1)) must throwAn[IllegalArgumentException](".*no selection called d.*c.*")
    }

    "say what is chosen in the heading of a still" in {
      val one = Interactive().withSelection(Selection("c").withLabels(Seq("zero", "one"))).withPanel(fig)
      (one.at(PageState().select("c", 1)).heading must contain("c = one"))
        .and(one.at(PageState()).heading must not(contain("one")))
    }

    "light an item over a mark that appears with a slider only when both say so" in {
      val points = Mark.Points(IndexedSeq((0.0, 0.0), (1.0, 1.0)), IndexedSeq(Paint.Ink))
      val base = Panel(Figure()).react(points, Behavior.shownFrom("t", 0.5))
      val linked = base.selecting(base.figure.marks.size - 1, "c", IndexedSeq(0, 1))
      val page = Interactive().withSlider("t", 0, 1).withSelection("c").withPanel(linked)
      def lit(t: Double, keys: Set[Int]): Int =
        halos(figureOf(page.at(PageState().value("t", t).select("c", keys)), 0)).flatMap(_.ends).size
      (lit(0.4, Set(0)) === 0).and(lit(0.5, Set(0)) === 1).and(lit(0.5, Set(0, 1)) === 2).and(lit(0.9, Set.empty) === 0)
    }
  }
