package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import cats.syntax.show.toShow

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.compiletime.asMatchable

/** Anything the viewer can show and `save` can write: a [[Figure]], a [[Scene]] (3-D), a side-by-side [[Board]]. */
trait Plot:
  def title: String

  /** The plot as a fragment to embed in a page (an `<svg>`, or a `<div>` with its own script for 3-D). */
  def fragment(theme: Theme = Theme.Auto): String

  /** A complete HTML page, following the reader's light or dark setting under `Theme.Auto`. */
  def html(theme: Theme = Theme.Auto): String

  /** A complete SVG document in plain colors (for a 3-D scene: one fixed view). */
  def svgFile(theme: Theme = Theme.Light): String

  /** Shows the plot in the live viewer, a browser tab that adds every plot `view()`ed (see [[Viewer]]). */
  def view(): this.type =
    Viewer.show(this)
    this

  /** Writes the plot to `path`: an `.svg` file in plain light colors (another `theme` on request), or an `.html` page
    * (interactive for 3-D) that follows the reader's light or dark setting. Returns the absolute path.
    */
  def save(path: String, theme: Option[Theme] = None): Path =
    val p = Paths.get(path).toAbsolutePath
    val text =
      if path.endsWith(".svg") then svgFile(theme.getOrElse(Theme.Light))
      else html(theme.getOrElse(Theme.Auto))
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))

/** Plots side by side, in one row (small multiples: a barcode next to its diagram, a complex at several scales). */
final case class Board(plots: Seq[Figure], title: String = "") extends Plot:
  def fragment(theme: Theme = Theme.Auto): String =
    plots.map(_.svg(theme)).mkString("""<div style="display:flex;flex-wrap:wrap;gap:12px">""", "", "</div>")
  def html(theme: Theme = Theme.Auto): String =
    Html.page(title, fragment(theme), plots.headOption.fold(Palette.default)(_.palette))
  def svgFile(theme: Theme = Theme.Light): String =
    val w = plots.map(_.width).sum
    val h = plots.map(_.height).maxOption.getOrElse(0)
    val inner = plots
      .scanLeft(0)(_ + _.width)
      .zip(plots)
      .map((x, f) => f.svg(theme).replaceFirst("<svg ", s"""<svg x="$x" y="0" """))
    val surface = plots.headOption.fold(Palette.default)(_.palette).hex(Paint.Surface, theme)
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h"><rect width="$w" height="$h" style="fill:$surface"/>${inner.mkString}</svg>""".stripMargin

/** The plots of persistent homology. Every function returns a [[Figure]] (or a [[Scene]] for 3-D) to `view()`,
  * `save(...)`, or adjust (`copy(title = ...)`, `+ mark`, `over`).
  *
  * Homological degree `k` always has the palette's slot `k` and its own marker shape, so a degree keeps its color and
  * shape from plot to plot whichever degrees are shown. Anything that takes a diagram also takes a list of bars.
  */
object Plot:
  /** A diagram or a list of bars. */
  type Bars = PersistenceDiagram[?] | Seq[PersistenceBar[Double, ?]]

  private def barsOf(source: Bars): Seq[PersistenceBar[Double, ?]] = source match
    case d: PersistenceDiagram[?] => d.bars
    case s: Seq[?]                => s.asInstanceOf[Seq[PersistenceBar[Double, ?]]]

  private def degreeLegend(degrees: Seq[Int], key: LegendItem.Key = LegendItem.Key.Dot): Vector[LegendItem] =
    degrees.distinct.sorted.map(k => LegendItem(s"H$k", Paint.Series(k), key, Shape.ofDegree(k))).toVector

  private def span(lo: Double, hi: Double): Double = if hi > lo then hi - lo else math.max(math.abs(hi), 1.0)

  private def interval(b: PersistenceBar[Double, ?]): String =
    s"H${b.dim}: [${Svg.number(b.birth)}, ${Svg.number(b.death)})" +
      (if b.death.isFinite then s", persistence ${Svg.number(b.persistence)}" else ", essential")

  // ----- barcodes, diagrams and their vectorizations

  /** The barcode: one horizontal bar per class from its birth to its death, grouped by degree (lowest on top), each
    * group sorted by birth. An essential bar runs off the right edge, marked `∞`. Hover a bar for its numbers.
    *
    * @param longest
    *   show only the `n` most persistent bars of each degree (a cloud's thousand short `H0` bars hide everything else).
    */
  def barcode(
    bars: Bars,
    title: String = "Barcode",
    longest: Option[Int] = None,
    palette: Palette = Palette.default
  ): Figure =
    val all = barsOf(bars)
    val byDegree = all.groupBy(_.dim).toSeq.sortBy(_._1).map { (k, bs) =>
      val kept = longest.fold(bs)(n => bs.sortBy(b => -b.persistence).take(n))
      k -> kept.sortBy(b => (b.birth, b.death))
    }
    val finite = all.flatMap(b => Seq(b.birth, b.death)).filter(_.isFinite)
    val lo = finite.minOption.getOrElse(0.0)
    val hi = finite.maxOption.getOrElse(1.0)
    val essential = all.exists(_.death.isPosInfinity)
    val right = if essential then hi + 0.08 * span(lo, hi) else hi + 0.02 * span(lo, hi)
    val rows = byDegree.map(_._2.size).sum + 2 * math.max(0, byDegree.size - 1)
    val height = math.max(240, math.min(760, 110 + rows * 7))
    val plotHeight = height - 100.0
    val thickness = math.max(1.2, math.min(8.0, 0.7 * plotHeight / math.max(rows, 1)))
    var row = 0
    val marks = Vector.newBuilder[Mark]
    val labels = IndexedSeq.newBuilder[(Double, String)]
    byDegree.foreach { (k, bs) =>
      val first = row
      val ends = bs.zipWithIndex.map { (b, i) =>
        val y = -(row + i).toDouble
        ((b.birth, y), (if b.death.isPosInfinity then right * 1.5 else b.death, y))
      }
      marks += Mark.Segments(
        ends.toIndexedSeq,
        IndexedSeq(Paint.Series(k)),
        width = thickness,
        roundCaps = false,
        tips = bs.map(interval).toIndexedSeq
      )
      row += bs.size
      labels += ((-(first + row - 1) / 2.0, s"H$k"))
      row += 2
    }
    val left = lo - 0.02 * span(lo, hi)
    val xTicks = Svg.niceTicks(left, if essential then hi else right).map(t => (t, Svg.number(t))) ++
      (if essential then IndexedSeq((right, "∞")) else IndexedSeq.empty)
    Figure(
      marks = marks.result(),
      title = title,
      subtitle = s"${all.size} bars" + longest.fold("")(n => s", the $n longest of each degree shown"),
      x = Axis(label = "filtration value", range = Some((left, right)), ticks = Some(xTicks)),
      y = Axis(range = Some((-(row - 2).toDouble - 0.5, 0.5)), ticks = Some(labels.result()), grid = false),
      legend = degreeLegend(byDegree.map(_._1), LegendItem.Key.Line),
      height = height,
      palette = palette
    )

  /** The persistence diagram: a point `(birth, death)` per bar, colored and shaped by degree, above the diagonal (which
    * is drawn, with the region below it shaded). Essential classes sit on the line marked `∞` at the top. Points far
    * from the diagonal are the persistent features.
    */
  def diagram(bars: Bars, title: String = "Persistence diagram", palette: Palette = Palette.default): Figure =
    val all = barsOf(bars)
    val finite = all.flatMap(b => Seq(b.birth, b.death)).filter(_.isFinite)
    val lo = math.min(0.0, finite.minOption.getOrElse(0.0))
    val hi = finite.maxOption.getOrElse(1.0)
    val essential = all.exists(_.death.isPosInfinity)
    val top = if essential then hi + 0.1 * span(lo, hi) else hi + 0.04 * span(lo, hi)
    val pad = 0.04 * span(lo, hi)
    val (a, z) = (lo - pad, top + pad)
    val marks = Vector.newBuilder[Mark]
    marks += Mark.Polygons(IndexedSeq(IndexedSeq((a, a), (z * 2, z * 2), (z * 2, a - z))), IndexedSeq(Paint.Grid), 0.6)
    marks += Mark.Segments(IndexedSeq(((a, a), (z * 2, z * 2))), IndexedSeq(Paint.Axis), width = 1)
    if essential then marks += Mark.Segments(IndexedSeq(((a, top), (z * 2, top))), IndexedSeq(Paint.Axis), width = 1)
    all.groupBy(_.dim).toSeq.sortBy(_._1).foreach { (k, bs) =>
      marks += Mark.Points(
        bs.map(b => (b.birth, if b.death.isPosInfinity then top else b.death)).toIndexedSeq,
        IndexedSeq(Paint.Series(k)),
        radius = 5,
        shape = Shape.ofDegree(k),
        tips = bs.map(interval).toIndexedSeq
      )
    }
    val ticks = Svg.niceTicks(a, if essential then hi else z).map(t => (t, Svg.number(t)))
    Figure(
      marks = marks.result(),
      title = title,
      subtitle = s"${all.size} bars",
      x = Axis(label = "birth", range = Some((a, z)), ticks = Some(ticks)),
      y = Axis(
        label = "death",
        range = Some((a, z)),
        ticks = Some(ticks ++ (if essential then Seq((top, "∞")) else Nil))
      ),
      legend = degreeLegend(all.map(_.dim)),
      width = 560,
      height = 520,
      equalAspect = true,
      palette = palette
    )

  /** A persistence image (`Vectorization.persistenceImage`'s `image(r)(c)`: `r` along `birthRange`, `c` along
    * `persistenceRange`) as a heatmap on the palette's sequential ramp.
    */
  def persistenceImage(
    image: Array[Array[Double]],
    birthRange: (Double, Double),
    persistenceRange: (Double, Double),
    title: String = "Persistence image",
    palette: Palette = Palette.default
  ): Figure =
    val (b0, b1) = birthRange
    val (p0, p1) = persistenceRange
    val nb = image.length
    val np = image.headOption.fold(0)(_.length)
    val maxValue = image.flatten.maxOption.getOrElse(0.0)
    val boxes = for r <- 0 until nb; c <- 0 until np
    yield (
      b0 + r * (b1 - b0) / nb,
      p0 + c * (p1 - p0) / np,
      b0 + (r + 1) * (b1 - b0) / nb,
      p0 + (c + 1) * (p1 - p0) / np
    )
    val values = for r <- 0 until nb; c <- 0 until np yield image(r)(c)
    Figure(
      marks = Vector(
        Mark.Boxes(
          boxes,
          values.map(v => Paint.Fixed(palette.sequentialAt(if maxValue > 0 then v / maxValue else 0))),
          tips = values.map(v => s"value ${Svg.number(v)}")
        )
      ),
      title = title,
      x = Axis(label = "birth", range = Some(birthRange), grid = false),
      y = Axis(label = "persistence", range = Some(persistenceRange), grid = false),
      colorbar = Some(Colorbar("weight", 0.0, maxValue, palette.sequentialAt)),
      width = 520,
      height = 460,
      palette = palette
    )

  /** The persistence image of the finite bars of degree `degree`, `resolution` pixels a side, `sigma` (default: a tenth
    * of the largest persistence) as `Vectorization.persistenceImage` computes it.
    */
  def persistenceImage(bars: Bars, degree: Int): Figure =
    persistenceImageOf(barsOf(bars).filter(_.dim == degree), 20, None, s"Persistence image, H$degree")

  /** As `persistenceImage(bars, degree)`, with the resolution and `sigma` chosen. */
  def persistenceImage(bars: Bars, degree: Int, resolution: Int, sigma: Double): Figure =
    persistenceImageOf(barsOf(bars).filter(_.dim == degree), resolution, Some(sigma), s"Persistence image, H$degree")

  private def persistenceImageOf(
    bs: Seq[PersistenceBar[Double, ?]],
    resolution: Int,
    sigma: Option[Double],
    title: String
  ) =
    val finite = bs.filter(_.death.isFinite)
    require(finite.nonEmpty, "Plot.persistenceImage: no finite bars in this degree")
    val bLo = finite.map(_.birth).min
    val bHi = finite.map(_.birth).max
    val pHi = finite.map(_.persistence).max
    val s = sigma.getOrElse(math.max(pHi, 1e-12) / 10)
    val birthRange = (bLo - 3 * s, bHi + 3 * s)
    val persistenceRange = (0.0, pHi + 3 * s)
    val img = Vectorization.persistenceImage(finite, s, birthRange, persistenceRange, resolution, resolution)
    persistenceImage(img, birthRange, persistenceRange, title)

  /** The first `levels` persistence landscapes of the bars of degree `degree` (`Vectorization.landscape`). */
  def landscape(bars: Bars, degree: Int, levels: Int = 3, resolution: Int = 200): Figure =
    val bs = barsOf(bars).filter(b => b.dim == degree && b.death.isFinite)
    require(bs.nonEmpty, "Plot.landscape: no finite bars in this degree")
    val lo = bs.map(_.birth).min
    val hi = bs.map(_.death).max
    val values = Vectorization.landscape(bs, levels, lo, hi, resolution)
    val ts = (0 until resolution).map(j => lo + j * (hi - lo) / (resolution - 1))
    Figure(
      marks = values.indices.map(k => Mark.Line(ts.zip(values(k)), Paint.Series(k), tip = s"λ${k + 1}")).toVector,
      title = s"Persistence landscapes, H$degree",
      x = Axis(label = "filtration value"),
      y = Axis(label = "λ"),
      legend = values.indices.map(k => LegendItem(s"λ${k + 1}", Paint.Series(k), LegendItem.Key.Line)).toVector
    )

  // ----- images and cubical representatives

  private final case class Grid(rows: Int, cols: Int, value: (Int, Int) => Double)

  private def heat(g: Grid, palette: Palette): (Mark, Colorbar) =
    val vs = for i <- 0 until g.rows; j <- 0 until g.cols yield g.value(i, j)
    val finite = vs.filter(_.isFinite)
    val lo = finite.minOption.getOrElse(0.0)
    val hi = finite.maxOption.getOrElse(1.0)
    def t(v: Double) = if hi > lo then (v - lo) / (hi - lo) else 0.0
    val boxes = for i <- 0 until g.rows; j <- 0 until g.cols
    yield (j.toDouble, -(i + 1).toDouble, (j + 1).toDouble, -i.toDouble)
    val paints = vs.map(v => if v.isFinite then Paint.Fixed(palette.sequentialAt(t(v))) else Paint.Surface)
    val tips = for i <- 0 until g.rows; j <- 0 until g.cols yield s"pixel ($i, $j): ${Svg.number(g.value(i, j))}"
    (Mark.Boxes(boxes, paints, tips = tips), Colorbar("value", lo, hi, palette.sequentialAt))

  private def imageFigure(g: Grid, title: String, palette: Palette): Figure =
    val (m, cb) = heat(g, palette)
    val scale = math.max(1.0, math.min(14.0, 480.0 / math.max(g.rows, g.cols)))
    Figure(
      marks = Vector(m),
      title = title,
      x = Axis(range = Some((0.0, g.cols.toDouble)), visible = false),
      y = Axis(range = Some((-g.rows.toDouble, 0.0)), visible = false),
      colorbar = Some(cb),
      width = (g.cols * scale + 120).toInt.max(320),
      height = (g.rows * scale + 70).toInt.max(200),
      equalAspect = true,
      palette = palette
    )

  /** A 2-D image (row 0 on top) on the sequential ramp; masked (infinite) pixels left blank. */
  def image(img: Image, title: String = "Image", palette: Palette = Palette.default): Figure =
    require(img.shape.size == 2, s"Plot.image: a 2-D image is needed, this one has shape ${img.shape.mkString("x")}")
    val cols = img.shape(1)
    imageFigure(Grid(img.shape(0), cols, (i, j) => img.values(i * cols + j)), title, palette)

  /** A 2-D cubical grid's top-cell values, as [[image]] draws an image (in the grid's own, sublevel, units). */
  def grid(grid: CubicalGridStream, title: String = "Cubical grid", palette: Palette = Palette.default): Figure =
    require(grid.shape.size == 2, s"Plot.grid: a 2-D grid is needed, this one has shape ${grid.shape.mkString("x")}")
    val vs = grid.topValues
    val cols = grid.shape(1)
    imageFigure(Grid(grid.shape(0), cols, (i, j) => vs(i * cols + j)), title, palette)

  /** The point of a 2-D grid's doubled coordinates in the image's plotting frame (column right, row down). */
  private def gridPoint(a0: Double, a1: Double): XY = (a1, -a0)

  /** A chain of cubes (a cycle or cocycle of an image's diagram) drawn over the image: edges as thick segments,
    * vertices as dots, squares filled; coefficients in the tooltips. Its degree's color, gold on the slate ramp in the
    * brand palette.
    */
  def cubicalChain(img: Image, chain: Chain[Cube, ?], title: String = "Representative"): Figure =
    val base = image(img, title)
    val terms = lifted(chain)
    val color = Paint.Series(terms.headOption.fold(1)(_._1.dim))
    val (vertices, edges, squares) =
      (terms.filter(_._1.dim == 0), terms.filter(_._1.dim == 1), terms.filter(_._1.dim == 2))
    def corners(c: Cube): (Double, Double, Double, Double) =
      val e = c.encoded
      (
        Math.floorDiv(e(0), 2).toDouble,
        Math.floorDiv(e(1), 2).toDouble,
        ((e(0) + 1) / 2).toDouble,
        ((e(1) + 1) / 2).toDouble
      )
    base ++ Seq(
      Mark.Boxes(
        squares.map { (c, _) =>
          val (r0, c0, r1, c1) = corners(c); (c0, -r1, c1, -r0)
        }.toIndexedSeq,
        IndexedSeq(color),
        opacity = 0.55,
        tips = squares.map((c, x) => s"${c.show}: ${Svg.number(x)}").toIndexedSeq
      ),
      Mark.Segments(
        edges.map { (c, _) =>
          val (r0, c0, r1, c1) = corners(c); (gridPoint(r0, c0), gridPoint(r1, c1))
        }.toIndexedSeq,
        IndexedSeq(color),
        width = 3,
        tips = edges.map((c, x) => s"${c.show}: ${Svg.number(x)}").toIndexedSeq
      ),
      Mark.Points(
        vertices.map { (c, _) =>
          val (r0, c0, _, _) = corners(c); gridPoint(r0, c0)
        }.toIndexedSeq,
        IndexedSeq(color),
        radius = 4,
        tips = vertices.map((c, x) => s"${c.show}: ${Svg.number(x)}").toIndexedSeq
      )
    )

  // ----- point clouds, complexes and simplicial representatives

  private def xy(points: PointCloud): IndexedSeq[XY] =
    points.points.toIndexedSeq.map { p =>
      require(p.length >= 2, "Plot: points need at least two coordinates (the first two are drawn)")
      (p(0), p(1))
    }

  private def cloudFigure(points: PointCloud, title: String, palette: Palette): Figure =
    Figure(
      marks = Vector(
        Mark
          .Points(xy(points), IndexedSeq(Paint.Secondary), radius = 3, tips = xy(points).indices.map(i => s"point $i"))
      ),
      title = title,
      x = Axis(grid = false),
      y = Axis(grid = false),
      width = 560,
      height = 520,
      equalAspect = true,
      palette = palette
    )

  /** A point cloud (its first two coordinates). */
  def points(points: PointCloud, title: String = "Point cloud", palette: Palette = Palette.default): Figure =
    cloudFigure(points, title, palette)

  /** The cells of `stream` with filtration value at most `at`, over the points they are built on: triangles filled,
    * edges and vertices drawn. With `ballRadius`, the balls around the points too (the union whose nerve the complex
    * approximates).
    */
  def complex(
    points: PointCloud,
    stream: CellStream[Simplex[Int], Double],
    at: Double,
    ballRadius: Option[Double] = None,
    title: String = "",
    palette: Palette = Palette.default
  ): Figure =
    val pts = xy(points)
    val cells = stream.iterator.filter(c => c.dim <= 2 && stream.filtrationValue(c) <= at).toVector
    val tris = cells.filter(_.dim == 2)
    val edges = cells.filter(_.dim == 1)
    val marks = Vector.newBuilder[Mark]
    ballRadius.foreach(r => marks += Mark.Discs(pts, r, IndexedSeq(Paint.Series(0)), opacity = 0.09))
    marks += Mark.Polygons(
      tris.map(t => t.toIndexedSeq.map(pts)),
      IndexedSeq(Paint.Series(0)),
      opacity = 0.22,
      tips = tris.map(t => s"${t.show} enters at ${Svg.number(stream.filtrationValue(t))}")
    )
    marks += Mark.Segments(
      edges.map(e => (pts(e.first), pts(e.last))),
      IndexedSeq(Paint.Secondary),
      width = 1.2,
      tips = edges.map(e => s"${e.show} enters at ${Svg.number(stream.filtrationValue(e))}")
    )
    marks += Mark.Points(pts, IndexedSeq(Paint.Ink), radius = 2.5, tips = pts.indices.map(i => s"point $i"))
    Figure(
      marks = marks.result(),
      title = if title.nonEmpty then title else s"Complex at ${Svg.number(at)}",
      subtitle = s"${edges.size} edges, ${tris.size} triangles",
      x = Axis(grid = false),
      y = Axis(grid = false),
      width = 560,
      height = 520,
      equalAspect = true,
      palette = palette
    )

  /** The complex `kind` (`VietorisRips`, `Cech` or `AlphaShapes`) of `points` at scale `at`, with the balls whose
    * overlaps it records: radius `at / 2` for Vietoris-Rips (an edge enters at the distance of its points), `at` for
    * Cech and alpha (values are radii).
    */
  def complex(points: PointCloud, at: Double, kind: PointCloudComplex): Figure =
    val stream =
      if kind eq AlphaShapes then kind.fromPoints(points, 1, None)
      else kind.fromPoints(points, 1, Some(at))
    val radius = if kind eq VietorisRips then at / 2 else at
    val name = if kind eq VietorisRips then "Vietoris-Rips" else if kind eq Cech then "Cech" else "Alpha"
    complex(points, stream, at, Some(radius), s"$name complex at ${Svg.number(at)}")

  /** The Vietoris-Rips complex of `points` at `at`, with balls of radius `at / 2`. */
  def complex(points: PointCloud, at: Double): Figure = complex(points, at, VietorisRips)

  /** A simplicial chain's terms with their coefficients as numbers (exact small integers for `Z/p`, the value for the
    * reals).
    */
  private def lifted[CellT](chain: Chain[CellT, ?]): Seq[(CellT, Double)] = liftedAs(chain)

  private def liftedAs[CellT, C](chain: Chain[CellT, C]): Seq[(CellT, Double)] =
    given f: (C is Field) = chain.coefficientField
    chain.terms.map((cell, c) => (cell, Plot.number(c)))

  /** A field element as a number: itself for `Double`, else the integer `k` (`|k| <= 64`, the smallest first) with
    * `k * 1 == x` in the field (the balanced representative for `Z/p`); `NaN` when there is none.
    */
  def number[C](x: C)(using f: C is Field): Double = x.asMatchable match
    case d: Double => d
    case _         =>
      if f.isEqual(x, f.zero) then 0.0
      else
        var pos = f.zero
        var k = 1
        var found = Double.NaN
        while found.isNaN && k <= 64 do
          pos = f.plus(pos, f.one)
          if f.isEqual(x, pos) then found = k
          else if f.isEqual(x, f.negate(pos)) then found = -k
          k += 1
        found

  /** A simplicial cycle (a bar's representative) over the point cloud it lives on: its edges thick in its degree's
    * color, its triangles filled, its vertices dotted; each cell's coefficient in its tooltip. Draw it over
    * `complex(...)` with `over` to see it in context.
    */
  def cycle(points: PointCloud, chain: Chain[Simplex[Int], ?], title: String = "Cycle"): Figure =
    val pts = xy(points)
    val terms = lifted(chain)
    val dim = terms.map(_._1.dim).maxOption.getOrElse(1)
    val color = Paint.Series(dim)
    def tip(s: Simplex[Int], c: Double) = s"${s.show}: ${Svg.number(c)}"
    val tris = terms.filter(_._1.dim == 2)
    val edges = terms.filter(_._1.dim == 1)
    val vertices = terms.filter(_._1.dim == 0)
    cloudFigure(points, title, Palette.default) ++ Seq(
      Mark.Polygons(
        tris.map((t, _) => t.toIndexedSeq.map(pts)).toIndexedSeq,
        IndexedSeq(color),
        0.45,
        tris.map(tip).toIndexedSeq
      ),
      Mark.Segments(
        edges.map((e, _) => (pts(e.first), pts(e.last))).toIndexedSeq,
        IndexedSeq(color),
        width = 3,
        tips = edges.map(tip).toIndexedSeq
      ),
      Mark.Points(
        vertices.map((v, _) => pts(v.first)).toIndexedSeq,
        IndexedSeq(color),
        5,
        tips = vertices.map(tip).toIndexedSeq
      )
    ) match
      case f =>
        f.copy(
          subtitle = s"${terms.size} cells, degree $dim",
          legend = Vector(LegendItem(s"H$dim representative", color, LegendItem.Key.Line))
        )

  /** A simplicial cocycle over its point cloud: each edge (or vertex, triangle) with a nonzero value, colored by that
    * value on the palette's diverging pair (negative one way, positive the other; for `Z/p`, the balanced
    * representative). A 1-cocycle's edges cut across the loop it detects.
    *
    * A bar's cocycle is a cocycle of the complex where the bar dies, so it has long edges right across the data; `at`
    * restricts it to the cells of diameter at most `at` (its restriction to the Vietoris-Rips complex there, still a
    * representative for `at` inside the bar). `at = bar.birth` shows the few edges that cut the loop as it closes.
    */
  def cocycle(
    points: PointCloud,
    chain: Chain[Simplex[Int], ?],
    at: Optional[Double] = Optional.empty,
    title: String = "Cocycle"
  ): Figure =
    val pts = xy(points)
    val coords = points.points
    def diameter(s: Simplex[Int]): Double =
      val vs = s.toIndexedSeq
      (for i <- vs; j <- vs if i < j
      yield math.sqrt(coords(i).indices.map(k => math.pow(coords(i)(k) - coords(j)(k), 2)).sum)).maxOption
        .getOrElse(0.0)
    val terms = lifted(chain).filter((s, _) => at.toOption.forall(r => diameter(s) <= r + 1e-12))
    val m = terms.map(t => math.abs(t._2)).filter(_.isFinite).maxOption.getOrElse(1.0) max 1e-12
    val pal = Palette.default
    def paint(c: Double) = Paint.Fixed(pal.divergingAt(c / m))
    def tip(s: Simplex[Int], c: Double) = s"${s.show}: ${Svg.number(c)}"
    val tris = terms.filter(_._1.dim == 2)
    val edges = terms.filter(_._1.dim == 1)
    val vertices = terms.filter(_._1.dim == 0)
    cloudFigure(points, title, pal) ++ Seq(
      Mark.Polygons(
        tris.map((t, _) => t.toIndexedSeq.map(pts)).toIndexedSeq,
        tris.map((_, c) => paint(c)).toIndexedSeq,
        0.5,
        tris.map(tip).toIndexedSeq
      ),
      Mark.Segments(
        edges.map((e, _) => (pts(e.first), pts(e.last))).toIndexedSeq,
        edges.map((_, c) => paint(c)).toIndexedSeq,
        width = 3,
        tips = edges.map(tip).toIndexedSeq
      ),
      Mark.Points(
        vertices.map((v, _) => pts(v.first)).toIndexedSeq,
        vertices.map((_, c) => paint(c)).toIndexedSeq,
        5,
        tips = vertices.map(tip).toIndexedSeq
      )
    ) match
      case f =>
        f.copy(
          subtitle = s"${terms.size} cells" + at.toOption.fold("")(r => s" of diameter at most ${Svg.number(r)}"),
          colorbar = Some(Colorbar("value", -m, m, t => pal.divergingAt(2 * t - 1)))
        )

  /** Circular coordinates (`CircularCoordinates.compute(...).theta`, angles as fractions of a turn) on the points, on a
    * cyclic color map: points of one color are at one angle, and the colors go round once along the loop. Points with
    * no coordinate (another connected component) are drawn hollow-gray.
    */
  def circularCoordinates(points: PointCloud, theta: Map[Int, Double], title: String = "Circular coordinates"): Figure =
    val pts = xy(points)
    val (on, off) = pts.indices.partition(theta.contains)
    Figure(
      marks = Vector(
        Mark.Points(off.map(pts), IndexedSeq(Paint.Grid), radius = 3, tips = off.map(i => s"point $i: no coordinate")),
        Mark.Points(
          on.map(pts),
          on.map(i => Paint.Fixed(Palette.cyclicAt(theta(i)))),
          radius = 4.5,
          tips = on.map(i => s"point $i: θ = ${Svg.number(theta(i))}")
        )
      ),
      title = title,
      x = Axis(grid = false),
      y = Axis(grid = false),
      colorbar = Some(Colorbar("θ (turns)", 0.0, 1.0, Palette.cyclicAt)),
      width = 600,
      height = 520,
      equalAspect = true
    )

  /** Two circular coordinates against each other, on the torus `[0, 1) x [0, 1)` (`CircularCoordinates.computeToroidal`
    * gives one map per class): points tracing a closed curve that wraps the square are a torus knot of the data.
    */
  def torus(theta1: Map[Int, Double], theta2: Map[Int, Double], title: String = "Toroidal coordinates"): Figure =
    val ids = theta1.keySet.intersect(theta2.keySet).toIndexedSeq.sorted
    Figure(
      marks = Vector(
        Mark.Points(
          ids.map(i => (theta1(i), theta2(i))),
          IndexedSeq(Paint.Series(0)),
          radius = 3.5,
          tips = ids.map(i => s"point $i: (${Svg.number(theta1(i))}, ${Svg.number(theta2(i))})")
        )
      ),
      title = title,
      x = Axis(label = "θ₁ (turns)", range = Some((0.0, 1.0))),
      y = Axis(label = "θ₂ (turns)", range = Some((0.0, 1.0))),
      width = 520,
      height = 520,
      equalAspect = true
    )

  /** Figures side by side in one row. */
  def side(figures: Figure*): Board = Board(figures, figures.map(_.title).filter(_.nonEmpty).mkString(" | "))

  // ----- 3-D

  private def xyz(points: PointCloud): IndexedSeq[XYZ] =
    points.points.toIndexedSeq.map(p => (p(0), if p.length > 1 then p(1) else 0.0, if p.length > 2 then p(2) else 0.0))

  /** A point cloud in 3-D (its first three coordinates), to turn and zoom. */
  def points3D(points: PointCloud, title: String = "Point cloud"): Scene =
    Scene(Vector(Layer.Points(xyz(points), IndexedSeq(Paint.Secondary), radius = 3)), title = title)

  /** The cells of `stream` with value at most `at` in 3-D: triangles translucent, edges, vertices. */
  def complex3D(points: PointCloud, stream: CellStream[Simplex[Int], Double], at: Double, title: String = ""): Scene =
    val pts = xyz(points)
    val cells = stream.iterator.filter(c => c.dim <= 2 && stream.filtrationValue(c) <= at).toVector
    val tris = cells.filter(_.dim == 2)
    val edges = cells.filter(_.dim == 1)
    Scene(
      Vector(
        Layer.Triangles(
          tris.map(t => (pts(t.first), pts(t.toIndexedSeq(1)), pts(t.last))),
          IndexedSeq(Paint.Series(0)),
          0.2
        ),
        Layer.Segments(edges.map(e => (pts(e.first), pts(e.last))), IndexedSeq(Paint.Secondary), 1),
        Layer.Points(pts, IndexedSeq(Paint.Ink), 2.5)
      ),
      title = if title.nonEmpty then title else s"Complex at ${Svg.number(at)}",
      subtitle = s"${edges.size} edges, ${tris.size} triangles"
    )

  /** The complex `kind` (`VietorisRips`, `Cech` or `AlphaShapes`) of `points` at `at`, in 3-D. */
  def complex3D(points: PointCloud, at: Double, kind: PointCloudComplex): Scene =
    val stream =
      if kind eq AlphaShapes then kind.fromPoints(points, 2, None)
      else kind.fromPoints(points, 1, Some(at))
    complex3D(points, stream, at)

  /** A simplicial representative in 3-D, over its point cloud: edges thick, triangles filled, in its degree's color. */
  def cycle3D(points: PointCloud, chain: Chain[Simplex[Int], ?], title: String = "Cycle"): Scene =
    val pts = xyz(points)
    val terms = lifted(chain)
    val dim = terms.map(_._1.dim).maxOption.getOrElse(1)
    val color = Paint.Series(dim)
    val tris = terms.map(_._1).filter(_.dim == 2)
    val edges = terms.map(_._1).filter(_.dim == 1)
    Scene(
      Vector(
        Layer.Points(pts, IndexedSeq(Paint.Muted), 2),
        Layer.Triangles(
          tris.map(t => (pts(t.first), pts(t.toIndexedSeq(1)), pts(t.last))).toIndexedSeq,
          IndexedSeq(color),
          0.22
        ),
        // a 2-cycle's triangles outlined, so a closed surface reads as one, not as a solid blob
        Layer.Segments(
          tris
            .flatMap { t =>
              val v = t.toIndexedSeq; Seq((v(0), v(1)), (v(1), v(2)), (v(0), v(2)))
            }
            .distinct
            .map((a, b) => (pts(a), pts(b)))
            .toIndexedSeq,
          IndexedSeq(color),
          0.8
        ),
        Layer.Segments(edges.map(e => (pts(e.first), pts(e.last))).toIndexedSeq, IndexedSeq(color), 3)
      ),
      title = title,
      subtitle = s"${terms.size} cells, degree $dim"
    )
