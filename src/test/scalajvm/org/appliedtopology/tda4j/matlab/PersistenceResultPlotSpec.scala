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
      val all = tips(r.plotRepresentative(loop).svg(), "∆(")
      val atBirth = tips(r.plotRepresentative(loop, r.birth(loop)).svg(), "∆(")
      (all.size must beEqualTo(r.cycleVertices(loop).length)) and
        (atBirth.size must beGreaterThan(0)) and (atBirth.size must beLessThan(all.size)) and
        (r.plotRepresentative(loop).colorScale must beSome)
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
    "say which complexes it draws" >> {
      TDA4j.computeFromPointsAndLandmarks(circle, Array(0, 4, 8, 12)).plotComplex(1.0) must
        throwA[UnsupportedOperationException](message = "vr, cech and alpha")
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
