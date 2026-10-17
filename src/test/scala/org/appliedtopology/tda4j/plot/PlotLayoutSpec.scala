package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification
import org.w3c.dom.Element

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** Boards on a layout grid: panels that span cells, tracks of relative sizes, and panels that share the scale of their
  * row or column. Each example is built so that the plausible wrong layout fails it (a panel as wide as one cell, a gap
  * that is lost across a span, a shared scale taken across panels of different spans).
  */
class PlotLayoutSpec extends Specification:

  private def elements(svg: String, tag: String): Seq[Element] =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    val nodes =
      f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))).getElementsByTagName(tag)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[Element])

  /** A panel of three points, named by its title. */
  private def panel(name: String, xs: Seq[Double] = Seq(1.0, 2.0, 3.0)): Figure =
    Figure(
      marks = Vector(Mark.Points(xs.map(x => (x, x)).toIndexedSeq, IndexedSeq.fill(xs.size)(Paint.Series(0)))),
      title = name
    )

  /** The nested panels of a board's file: left, top, width and height. */
  private def boxes(board: Board): Seq[(Int, Int, Int, Int)] =
    elements(board.svgFile(Theme.Light), "svg").filter(_.hasAttribute("x")).map { e =>
      (
        e.getAttribute("x").toInt,
        e.getAttribute("y").toInt,
        e.getAttribute("width").toInt,
        e.getAttribute("height").toInt
      )
    }

  // 1000 by 500, a gap of 10: columns of 327, 326 and 327 pixels, rows of 245
  private lazy val grid =
    Plot
      .layout(2, 3, width = 1000, height = 500, gap = 10)
      .place(panel("A"), 0, 0, colSpan = 2)
      .place(panel("B"), 0, 2)
      .place(panel("C"), 1, 0, colSpan = 3)

  "a board on a layout" should {
    "put each panel in its cells, as large as the cells it spans and the gaps between them" in {
      boxes(grid) must beEqualTo(Seq((0, 0, 663, 245), (673, 0, 327, 245), (0, 255, 1000, 245)))
      grid.layout.get.columnWidths must beEqualTo(Vector(327, 326, 327))
      grid.layout.get.rowHeights must beEqualTo(Vector(245, 245))
    }
    "fill the drawing exactly, with the gap between neighbours and nothing lost to rounding" in {
      val Seq((ax, ay, aw, ah), (bx, by, bw, bh), (cx, cy, cw, ch)) = boxes(grid): @unchecked
      (bx + bw) must beEqualTo(1000) // the last column ends at the edge
      (cy + ch) must beEqualTo(500) // and the last row
      (bx - (ax + aw)) must beEqualTo(10) // a gap between A and B
      (cy - (ay + ah)) must beEqualTo(10) // and between the rows
      (cw, by - ay) must beEqualTo((1000, 0))
      val root = elements(grid.svgFile(Theme.Light), "svg").head
      (root.getAttribute("width"), root.getAttribute("height"), root.getAttribute("viewBox")) must
        beEqualTo(("1000", "500", "0 0 1000 500"))
    }
    "draw a panel at the size of its cells, the plot inside it as well" in {
      val a = elements(grid.svgFile(Theme.Light), "svg").filter(_.hasAttribute("x")).head
      a.getAttribute("viewBox") must beEqualTo(
        "0 0 663 245"
      ) // the figure was laid out for its cells, not clipped to them
      // the natural size of a panel is not what it gets
      panel("A").width must not(beEqualTo(663))
    }
    "span rows as well as columns, the gap kept across the span" in {
      val tall = Plot
        .layout(2, 2, width = 600, height = 410, gap = 10)
        .place(panel("L"), 0, 0, rowSpan = 2)
        .place(panel("T"), 0, 1)
        .place(panel("U"), 1, 1)
      val Seq((_, ly, _, lh), (_, ty, _, th), (_, uy, _, uh)) = boxes(tall): @unchecked
      (ly, lh) must beEqualTo((0, 410))
      lh must beEqualTo(th + 10 + uh) // across two rows and the gap between them
      uy must beEqualTo(ty + th + 10)
    }
    "share the drawing by relative sizes" in {
      val weighted = Plot
        .layout(3, 2, width = 1000, height = 400, gap = 0, widths = Seq(1.0, 3.0), heights = Seq(1.0, 1.0, 2.0))
        .place(panel("a"), 0, 0)
        .place(panel("b"), 2, 1)
      boxes(weighted) must beEqualTo(Seq((0, 0, 250, 100), (250, 200, 750, 200)))
      Plot.layout(2, 2).layout.get.columnWidths.distinct.size must beEqualTo(1) // equal unless asked
    }
    "set the sizes of its panels, whatever a Size says" in {
      boxes(grid + Size(500, 500)) must beEqualTo(boxes(grid))
      (grid + Labels(caption = "c")).plots.map(_.caption) must beEqualTo(
        Seq("c", "c", "c")
      ) // the other tweaks reach them
    }
    "be a CSS grid on a page, each panel in its area" in {
      val page = grid.fragment(Theme.Light)
      page must contain(
        "display:grid;grid-template-columns:327px 326px 327px;grid-template-rows:245px 245px;gap:10px"
      )
      page must contain("grid-area:1 / 1 / span 1 / span 2") // A
      page must contain("grid-area:1 / 3 / span 1 / span 1") // B
      page must contain("grid-area:2 / 1 / span 1 / span 3") // C
      page must contain("""width="663" height="245"""")
    }
    "letter its panels in the order they were placed, and keep a row of panels as it was" in {
      grid.tagged().plots.map(_.tag) must beEqualTo(Seq("(a)", "(b)", "(c)"))
      val row = Plot.side(panel("A"), panel("B"))
      boxes(row) must beEqualTo(
        Seq(
          (0, 0, row.plots.head.width, row.plots.head.height),
          (row.plots.head.width, 0, row.plots(1).width, row.plots(1).height)
        )
      )
    }
    "keep its background unless every panel has none" in {
      elements(grid.svgFile(Theme.Light), "rect").head.getAttribute("width") must beEqualTo("1000")
      elements((grid + Style.clear).svgFile(Theme.Light), "rect").filter(_.getAttribute("width") == "1000") must beEmpty
    }
  }

  "a place that is not on the grid" should {
    val board = Plot.layout(2, 3, width = 900, height = 500).place(panel("A"), 0, 0, colSpan = 2)

    "be refused, naming the panel in the way" in {
      board.place(panel("B"), 0, 1) must throwAn[IllegalArgumentException](
        "the panel at row 0, column 1 overlaps \"A\" at row 0, columns 0-1"
      )
      board.place(panel("B"), 0, 1, rowSpan = 2) must throwAn[IllegalArgumentException]("overlaps \"A\"")
      board.place(panel("B"), 0, 2).place(panel("C"), 1, 1, colSpan = 2) must not(throwAn[IllegalArgumentException])
    }
    "be refused when it runs off the grid, in words" in {
      board.place(panel("B"), 1, 2, colSpan = 2) must throwAn[IllegalArgumentException](
        "the panel at row 1, columns 2-3 does not fit a layout of 2 rows by 3 columns"
      )
      board.place(panel("B"), 2, 0) must throwAn[IllegalArgumentException]("does not fit a layout of 2 rows")
      Slot(-1, 0) must throwAn[IllegalArgumentException]("rows and columns count from 0")
      Slot(0, 0, colSpan = 0) must throwAn[IllegalArgumentException]("spans at least one row and one column")
    }
    "be refused on a board that is a row, saying where to start" in {
      Plot.side(panel("A")).place(panel("B"), 0, 0) must throwAn[IllegalArgumentException](
        "start from Plot.layout"
      )
    }
    "be a layout that cannot be drawn, refused when it is made" in {
      Plot.layout(0, 2) must throwAn[IllegalArgumentException]("at least one row and one column")
      Plot.layout(2, 2, widths = Seq(1.0)) must throwAn[IllegalArgumentException](
        "1 column weights for 2 columns"
      )
      Plot.layout(2, 2, heights = Seq(1.0, -1.0)) must throwAn[IllegalArgumentException]("a positive number")
      Plot.layout(1, 20, width = 400) must throwAn[IllegalArgumentException]("a column would be")
      Plot.layout(2, 2, width = 50) must throwAn[IllegalArgumentException]("the width should be between 100 and 20000")
    }
  }

  "the panels of a layout that share a scale" should {
    // five panels on 3 rows by 2 columns; a and c are over column 0, b and d over column 1, w across both
    def at(name: String, lo: Double): Figure = panel(name, Seq(lo, lo + 1, lo + 2))
    val board = Plot
      .layout(3, 2, width = 800, height = 900)
      .place(at("a", 1), 0, 0)
      .place(at("b", 10), 0, 1)
      .place(at("c", 100), 1, 0)
      .place(at("d", 1000), 1, 1)
      .place(at("w", 5000), 2, 0, colSpan = 2)
    def x(b: Board, i: Int) = (b.plots(i).x.min, b.plots(i).x.max)

    "take the union of their column, over the same columns only" in {
      val shared = board.sharedColumns()
      x(shared, 0) must beEqualTo(x(shared, 2)) // a and c: over column 0
      x(shared, 0)._1.get must beCloseTo(0.92, 1e-9) // a's own range, with its padding
      x(shared, 0)._2.get must beGreaterThan(102.0) // wide enough for c
      x(shared, 1) must beEqualTo(x(shared, 3)) // b and d: over column 1
      x(shared, 1)._1.get must beCloseTo(9.92, 1e-9) // b's
      x(shared, 0) must not(beEqualTo(x(shared, 1))) // each column its own
      x(shared, 4) must beEqualTo((None, None)) // w spans both columns: of a kind of its own, untouched
      shared.plots.map(_.y.min) must beEqualTo(Seq.fill(5)(None)) // y is not shared by columns
    }
    "take the union of their row, over the same rows only" in {
      val shared = board.sharedRows()
      shared.plots(0).y.min must beEqualTo(shared.plots(1).y.min) // a and b: row 0
      shared.plots(0).y.max must beEqualTo(shared.plots(1).y.max)
      shared.plots(1).y.max.get must beGreaterThan(11.0)
      shared.plots(2).y.min must beEqualTo(shared.plots(3).y.min) // c and d: row 1
      shared.plots(0).y.max must not(beEqualTo(shared.plots(2).y.max))
      shared.plots(4).y.min must beEqualTo(None)
      shared.plots.map(_.x.min) must beEqualTo(Seq.fill(5)(None))
    }
    "all share one scale with shared(), as a row of panels does" in {
      val everything = board.shared()
      everything.plots.map(p => (p.x.min, p.x.max)).distinct.size must beEqualTo(1)
      val row = Plot.side(at("a", 1), at("b", 10)).sharedColumns() // a row: one panel in each column, nothing to share
      row.plots.map(p => (p.x.min, p.x.max)) must beEqualTo(Seq((None, None), (None, None)))
      Plot.side(at("a", 1), at("b", 10)).sharedRows().plots.map(_.y.min).distinct.size must beEqualTo(1) // one row
    }
  }
