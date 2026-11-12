package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** The facade's plots draw what the result reports: one mark per reported bar, one per cell of a representative, over
  * the input it was computed from; and they say what they cannot draw.
  */
class PersistenceResultPlotSpec extends Specification:
  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))

  /** Tooltips that start with `prefix`: one per drawn item. */
  private def tips(svg: String, prefix: String): Seq[String] =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)))
    val ts = doc.getElementsByTagName("title")
    (0 until ts.getLength).map(ts.item(_).getTextContent).filter(_.startsWith(prefix))

  "plotBarcode and plotDiagram" should {
    "draw one mark per reported bar, and say how many the threshold hid" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "minPersistenceFraction", "0.5"))
      (r.hiddenCount() must beGreaterThan(0)) and
        (tips(r.plotBarcode().svg(), "H").size must beEqualTo(r.size())) and
        (tips(r.plotDiagram().svg(), "H").size must beEqualTo(r.size())) and
        (r.plotBarcode().subtitle must contain(s"${r.hiddenCount()} no longer than"))
    }
    "keep the longest bars of each dimension when asked" >> {
      val r = FullBarcode.computeFromPoints(circle, Array("maxDimension", "1"))
      tips(r.plotBarcode(2).svg(), "H0").size must beEqualTo(2)
    }
  }

  "mostPersistentBar" should
    "find the circle's loop, and name the dimensions there are when there is none" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      val loop = r.mostPersistentBar(1)
      (r.dimension(loop) must beEqualTo(1)) and
        (r.mostPersistentBar(0) must beEqualTo((0 until r.size()).find(i => r.death(i).isPosInfinity).get)) and
        (r.mostPersistentBar(2) must throwA[IllegalArgumentException](message = "reported dimensions: 0, 1"))
    }

  "plotRepresentative" should {
    "draw a cycle cell by cell over the points, with balanced F_17 coefficients" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      val loop = r.mostPersistentBar(1)
      val cells = tips(r.plotRepresentative(loop).svg(), "∆(")
      (cells.size must beEqualTo(r.cycleVertices(loop).length)) and
        (cells.size must beEqualTo(16)) and // the 16 sides of the polygon
        (cells.forall(t => t.endsWith(": 1") || t.endsWith(": -1")) must beTrue) and
        (tips(r.plotRepresentative(loop).svg(), "point ").size must beEqualTo(16))
    }
    "draw a cycle over the complex at a scale" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      val loop = r.mostPersistentBar(1)
      val svg = r.plotRepresentative(loop, r.birth(loop)).svg()
      // the cycle's 16 edges, and the complex's 16 edges (each side of the polygon) underneath
      tips(svg, "∆(").size must beEqualTo(32)
    }
    "color a cocycle by value, and restrict it to the complex at a scale" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "representativeType", "cocycles"))
      val loop = r.mostPersistentBar(1)
      val all = tips(r.plotRepresentative(loop, Double.PositiveInfinity).svg(), "∆(")
      val atBirth = tips(r.plotRepresentative(loop, r.birth(loop)).svg(), "∆(")
      (all.size must beEqualTo(r.cycleVertices(loop).length)) and
        (atBirth.size must beGreaterThan(0)) and (atBirth.size must beLessThan(all.size)) and
        (r.plotRepresentative(loop).colorScale must beSome)
    }
    "draw a cocycle restricted to its bar's birth unless it is asked for whole" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "representativeType", "cocycles"))
      val loop = r.mostPersistentBar(1)
      val byDefault = tips(r.plotRepresentative(loop).svg(), "∆(")
      val whole = tips(r.plotRepresentative(loop, Double.PositiveInfinity).svg(), "∆(")
      (byDefault must beEqualTo(tips(r.plotRepresentative(loop, r.birth(loop)).svg(), "∆("))) and
        (byDefault.size must beLessThan(whole.size)) and
        (whole.size must beEqualTo(r.cycleVertices(loop).length)) and
        // a cycle has no complex under it unless one is asked for
        (TDA4j.computeFromPoints(circle, Array("maxDimension", "1")).plotRepresentative(loop).title must
          contain("Cycle"))
    }
    "restrict a Cech or alpha cocycle in the complex's own units (radii): at its birth, the edges that close the loop" >>
      forall(Seq("cech", "alpha")) { complex =>
        val r = TDA4j.computeFromPoints(
          circle,
          Array("complex", complex, "maxDimension", "1", "representativeType", "cocycles")
        )
        val loop = r.mostPersistentBar(1)
        tips(r.plotRepresentative(loop, r.birth(loop)).svg(), "∆(").size must beGreaterThan(0)
      }
    "draw a witness complex's representative on its landmarks" >> {
      val landmarks = Array(6, 2, 10, 14, 0, 4, 8, 12)
      val r = TDA4j.computeFromPointsAndLandmarks(circle, landmarks, Array("maxDimension", "1"))
      val loop = r.mostPersistentBar(1)
      val vertices = tips(r.plotRepresentative(loop).svg(), "∆(").flatMap(t =>
        t.stripPrefix("∆(").takeWhile(_ != ')').split(",").map(_.trim.toInt)
      )
      (vertices must not(beEmpty)) and (vertices.forall(landmarks.contains) must beTrue)
    }
    "refuse a bar that is not there, saying how many there are" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      r.plotRepresentative(r.size()) must throwA[IllegalArgumentException](message = s"reports ${r.size()} bars")
    }
    "draw a cubical representative over its image" >> {
      // a dark ring around a bright pixel: a loop born at 0 that dies at 1
      val pixels = Array(
        Array(1.0, 1.0, 1.0, 1.0, 1.0),
        Array(1.0, 0.0, 0.0, 0.0, 1.0),
        Array(1.0, 0.0, 1.0, 0.0, 1.0),
        Array(1.0, 0.0, 0.0, 0.0, 1.0),
        Array(1.0, 1.0, 1.0, 1.0, 1.0)
      )
      val r = FullBarcode.computeFromImage(pixels)
      val loop = r.mostPersistentBar(1)
      (r.birth(loop) must beEqualTo(0.0)) and (r.death(loop) must beEqualTo(1.0)) and
        (tips(r.plotRepresentative(loop).svg(), "Cube(").size must beEqualTo(r.cycleVertices(loop).length)) and
        (tips(r.plotInput().svg(), "pixel").size must beEqualTo(25))
    }
    "say why it cannot draw on a distance matrix or a relation, and what it can draw" >> {
      val distances = Array.tabulate(5, 5)((i, j) => math.abs(i - j).toDouble)
      val fromDistances = TDA4j.computeFromDistanceMatrix(distances)
      val fromRelation = TDA4j.computeFromRelation(distances)
      (fromDistances.plotRepresentative(0) must throwA[UnsupportedOperationException](message = "distance matrix")) and
        (fromDistances.plotInput() must throwA[UnsupportedOperationException](message = "the barcode")) and
        (fromRelation.plotRepresentative(0) must throwA[UnsupportedOperationException](message = "a relation"))
    }
  }

  "plotComplex" should {
    "draw the result's own complex at a scale: the sides of a unit square at 1" >> {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      (tips(TDA4j.computeFromPoints(square).plotComplex(1.0).svg(), "∆(").size must beEqualTo(4)) and
        (TDA4j.computeFromPoints(square, Array("complex", "cech")).plotComplex(0.5).title must contain("Cech"))
    }
    "draw every complex of a point cloud, as the computation built it" >> {
      val landmarks = Array(0, 4, 8, 12)
      val witness = TDA4j.computeFromPointsAndLandmarks(circle, landmarks, Array("maxDimension", "1"))
      val figure = witness.plotComplex(2.0)
      // the cells of the witness complex, named by the points that are landmarks
      val cells =
        tips(figure.svg(), "∆(").flatMap(t => t.stripPrefix("∆(").takeWhile(_ != ')').split(",").map(_.trim.toInt))
      val direct = LazyWitnessSimplexStream(
        EuclideanMetricSpace(circle),
        landmarks.toIndexedSeq,
        2,
        maxFiltrationValue = Some(2.0)
      )
      val expected = direct.iterator.filter(c => c.dim >= 1 && c.dim <= 2 && direct.filtrationValue(c) <= 2.0).size
      (figure.title must contain("Witness")) and (cells must not(beEmpty)) and (cells.forall(
        landmarks.contains
      ) must beTrue) and
        (tips(figure.svg(), "∆(").size must beEqualTo(expected)) and
        (witness.plotComplex3D(2.0).title must contain("Witness"))
    }
    "draw a DTM-Rips, sparse Rips and DTM-alpha complex too" >> {
      val dtm = TDA4j.computeFromPoints(circle, Array("complex", "dtm-rips", "dtmK", "2", "maxDimension", "1"))
      val sparse =
        TDA4j.computeFromPoints(circle, Array("complex", "sparse-rips", "sparseEpsilon", "0.5", "maxDimension", "1"))
      val dtmAlpha = TDA4j.computeFromPoints(circle, Array("complex", "dtm-alpha", "dtmK", "2", "maxDimension", "1"))
      (dtm.plotComplex(1.0).title must contain("DTM-Rips")) and (sparse.plotComplex(1.0).title must contain(
        "Sparse Rips"
      )) and
        (dtmAlpha.plotComplex(1.0).title must contain("DTM-alpha")) and
        (tips(sparse.plotComplex(1.0).svg(), "∆(").size must beGreaterThan(0))
    }
    "stop where the computation did, and say so" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "maxFiltrationValue", "0.5"))
      val figure = r.plotComplex(2.0)
      (figure.subtitle must contain("the computation stopped at 0.5")) and
        (figure.title must contain("0.5")) and
        (tips(figure.svg(), "∆(") must beEqualTo(tips(r.plotComplex(0.5).svg(), "∆("))) and
        (r.plotComplex(0.4).subtitle must not(contain("stopped")))
    }
    "use the alpha backend the result was computed with" >> {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      val dqp =
        TDA4j.computeFromPoints(square, Array("complex", "alpha", "alphaBackend", "dqp", "maxFiltrationValue", "2"))
      tips(dqp.plotComplex(0.6).svg(), "∆(").size must beEqualTo(4) // the sides, before the diagonals at 0.71
    }
  }

  "plotFiltration" should {
    val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
    "show, at each scale, the cells plotComplex draws there: none under the side 0.39, the 16 sides after it" >> {
      val page = r.plotFiltration(1.0)
      def cellsAt(t: Double) = tips(page.at(t).svgFile(plot.Theme.Light), "∆(").sorted
      val sides = tips(r.plotComplex(0.5).svg(), "∆(").sorted
      (cellsAt(0.3) must beEmpty) and
        (cellsAt(0.5) must beEqualTo(sides)) and
        (sides.size must beGreaterThanOrEqualTo(16)) and
        (cellsAt(0.9) must beEqualTo(tips(r.plotComplex(0.9).svg(), "∆(").sorted))
    }
    "run its slider over the scales the computation reached, and say which complex it draws" >> {
      val stopped = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "maxFiltrationValue", "0.5"))
      val page = stopped.plotFiltration(2.0)
      (page.controls.head.max must beCloseTo(0.5, 1e-12)) and (page.controls.head.min must beEqualTo(0.0)) and
        (page.title must contain("Vietoris-Rips"))
    }
    "keep the hook of the other plots: a chained tweak and a theme reach every panel, and the page holds the diagram" >> {
      val page = r.plotFiltration(1.0).withPalette("print").withTitle("A circle").withPlay(5.0)
      (page.html() must contain("A circle")) and (page.panels.size must beEqualTo(2)) and
        (page.controls.head.play must beSome(5.0))
    }
    "write its frames from a word and a path, as MATLAB and Python call it" >> {
      val dir = java.nio.file.Files.createTempDirectory("tda4j-facade-frames").toString
      val written = r.plotFiltration(1.0).saveFrames(dir, 3, "light")
      (written.length must beEqualTo(3)) and (written.forall(p => new java.io.File(p).length() > 1000) must beTrue) and
        (r.plotFiltration(1.0).saveFrames(dir, 2).length must beEqualTo(2))
    }
    "draw 3-D with a scene panel, and refuse a result that has no points to draw" >> {
      val torusLike = Array.tabulate(30)(i =>
        val t = 2 * math.Pi * i / 30
        Array(math.cos(t), math.sin(t), 0.3 * math.sin(3 * t))
      )
      val r3 = TDA4j.computeFromPoints(torusLike, Array("maxDimension", "1"))
      val distances = Array(Array(0.0, 1.0, 1.0), Array(1.0, 0.0, 1.0), Array(1.0, 1.0, 0.0))
      (r3.plotFiltration3D(0.5).html() must contain("<canvas")) and
        (TDA4j.computeFromDistanceMatrix(distances).plotFiltration(1.0) must throwA[UnsupportedOperationException](
          message = "distance matrix"
        ))
    }
  }

  "plotExplore" should {
    // a circle with every bar of a different length, so a key taken from another order is another cycle
    val wobbly = Array.tabulate(24) { i =>
      val t = 2 * math.Pi * i / 24 + 0.06 * math.cos(3.0 * i)
      val rad = 1.0 + 0.15 * math.sin(5.0 * i)
      Array(rad * math.cos(t), rad * math.sin(t))
    }
    val r = TDA4j.computeFromPoints(wobbly, Array("maxDimension", "1", "minPersistenceFraction", "0.1"))
    val page = r.plotExplore()

    "give every reported bar a class, and draw the cycle of the class chosen: that bar's representative" >> {
      val reported = r.size()
      val drawn = (0 until reported).map { k =>
        val chosen = tips(page.at(plot.PageState().select("class", k)).svgFile(plot.Theme.Light), "∆(").sorted
        chosen === tips(r.plotRepresentative(k).svg(), "∆(").sorted
      }
      (reported must beGreaterThan(10)) and (r.hiddenCount() must beGreaterThan(0)) and
        (drawn.forall(_.isSuccess) must beTrue)
    }

    "zoom and brush its diagram and barcode, and name its classes by bar" >> {
      val html = page.html()
      (html must contain("data-zoomable=\"b\"")) and (html must contain("data-brush=\"class\"")) and
        (page.selections.head.labels.size must beEqualTo(r.size()))
    }

    "refuse a result with no points to draw them on" >> {
      val distances = Array(Array(0.0, 1.0, 1.0), Array(1.0, 0.0, 1.0), Array(1.0, 1.0, 0.0))
      TDA4j.computeFromDistanceMatrix(distances).plotExplore() must throwA[UnsupportedOperationException](
        message = "distance matrix"
      )
    }
  }

  "plotLinkedFiltration" should {
    val wobbly = Array.tabulate(24) { i =>
      val t = 2 * math.Pi * i / 24 + 0.06 * math.cos(3.0 * i)
      val rad = 1.0 + 0.15 * math.sin(5.0 * i)
      Array(rad * math.cos(t), rad * math.sin(t))
    }
    val r = TDA4j.computeFromPoints(wobbly, Array("maxDimension", "1", "minPersistenceFraction", "0.1"))

    "be the slider page with its views linked: the loop's cycle appears on the complex when the class is chosen" >> {
      val page = r.plotLinkedFiltration(1.5)
      val loop = r.mostPersistentBar(1)
      val state = plot.PageState().value("t", 1.4).select("class", loop)
      val on = tips(page.at(state).svgFile(plot.Theme.Light), "∆(").sorted
      val off = tips(page.at(plot.PageState().value("t", 1.4)).svgFile(plot.Theme.Light), "∆(").sorted
      val cycle = tips(r.plotRepresentative(loop).svg(), "∆(").sorted
      (page.panels.size must beEqualTo(3)) and (page.selections.size must beEqualTo(1)) and
        (cycle.size must beGreaterThan(10)) and ((on.diff(off)) must beEqualTo(cycle))
    }
  }

  "vectorization plots" should
    "use every bar, as the numeric landscape does" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      (r.plotLandscape(1).marks.size must beEqualTo(3)) and
        (r.plotPersistenceImage(1).svg() must contain("<svg"))
    }

  "3-D plots" should {
    "draw a point cloud, a complex and a representative as scenes" >> {
      val torusLike = Array.tabulate(30)(i =>
        val t = 2 * math.Pi * i / 30
        Array(math.cos(t), math.sin(t), 0.3 * math.sin(3 * t))
      )
      val r = TDA4j.computeFromPoints(torusLike, Array("maxDimension", "1"))
      (r.plotInput3D().html() must contain("<canvas")) and
        (r.plotComplex3D(0.5).svgFile(plot.Theme.Light) must contain("<svg")) and
        (r.plotRepresentative3D(r.mostPersistentBar(1)).title must startWith("Cycle of bar"))
    }
    "refuse to draw a cocycle without its values" >> {
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1", "representativeType", "cocycles"))
      r.plotRepresentative3D(r.mostPersistentBar(1)) must throwA[UnsupportedOperationException](message = "cycles")
    }
  }

  "a plot from MATLAB or Java" should {
    "have a save method taking only the path" >> {
      classOf[plot.Plot].getMethod("save", classOf[String]).getReturnType must beEqualTo(classOf[String])
    }
    "save with the file type's default theme, with no default argument" >> {
      val path = java.io.File.createTempFile("plot-spec", ".svg")
      path.deleteOnExit()
      val r = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))
      val written = r.plotDiagram().save(path.getAbsolutePath)
      java.nio.file.Files.readString(java.nio.file.Paths.get(written)) must contain(plot.Palette.brand.seriesLight(0))
    }
  }
