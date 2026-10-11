package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** The figures say what the data says (one mark per bar, per edge, per cell), as well-formed SVG, in both theme modes;
  * the live viewer serves, lists and announces every plot.
  */
class PlotSpec extends Specification:
  sequential

  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private lazy val circleDiagram = Persistence(circle, maxDimension = 1)

  private def parse(svg: String) =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)))

  private def count(svg: String, element: String): Int = parse(svg).getElementsByTagName(element).getLength

  /** Tooltips that start with `prefix`: one per drawn item. */
  private def tips(svg: String, prefix: String): Int =
    val ts = parse(svg).getElementsByTagName("title")
    (0 until ts.getLength).count(i => ts.item(i).getTextContent.startsWith(prefix))

  "a barcode" should {
    "draw one bar per bar of the diagram, each with its numbers on hover, as well-formed SVG" in {
      val d = circleDiagram
      val svg = Plot.barcode(d).svg()
      tips(svg, "H0:") must beEqualTo(d.dim(0).size)
      tips(svg, "H1:") must beEqualTo(d.dim(1).size)
      svg must contain("∞")
    }
    "keep only the longest bars of each degree when asked" in {
      val svg = Plot.barcode(circleDiagram, longest = Some(3)).svg()
      tips(svg, "H0:") must beEqualTo(3)
      tips(svg, "H1:") must beEqualTo(1)
    }
    "take a plain list of bars as well as a diagram" in {
      val bars =
        List(PersistenceBar[Double](0, 0.0, 1.0), PersistenceBar[Double](1, 0.5, 2.0), PersistenceBar[Double](0, 0.0))
      tips(Plot.barcode(bars).svg(), "H") must beEqualTo(3)
    }
  }

  "a persistence diagram" should {
    "draw one marker per bar, essential ones on the infinity line, in each degree's own shape" in {
      val d = circleDiagram
      val svg = Plot.diagram(d).svg()
      tips(svg, "H0:") must beEqualTo(d.dim(0).size)
      tips(svg, "H1:") must beEqualTo(1)
      count(svg, "polygon") must beGreaterThan(1) // the shaded half-plane and the H1 triangle marker
      svg must contain("essential")
    }
  }

  "colors" should {
    "resolve through CSS variables in both modes under Auto, and as plain hex otherwise" in {
      val fig = Plot.barcode(circleDiagram)
      val auto = fig.svg(Theme.Auto)
      auto must contain("var(--s0)")
      auto must contain("prefers-color-scheme: dark")
      val light = fig.svg(Theme.Light)
      light must not(contain("var(--"))
      light must contain(Palette.brand.seriesLight(0)) // degree 0 in slate
      light must contain(Palette.brand.seriesLight(1)) // degree 1 in gold
      fig.svg(Theme.Dark) must contain(Palette.brand.seriesDark(1))
    }
    "follow the figure's palette" in {
      Plot.barcode(circleDiagram).copy(palette = Palette.reference).svg(Theme.Light) must contain(
        Palette.reference.seriesLight(0)
      )
    }
    "go round the cyclic map without a seam" in {
      Palette.cyclicAt(0.0) must beEqualTo(Palette.cyclicAt(1.0))
      Palette.cyclicAt(0.25) must not(beEqualTo(Palette.cyclicAt(0.75)))
    }
  }

  "vectorizations" should {
    "draw one pixel per persistence-image cell" in {
      val svg = Plot.persistenceImage(circleDiagram, 0, resolution = 8, sigma = 0.05).svg()
      tips(svg, "value ") must beEqualTo(64)
    }
    "draw one line per landscape" in {
      tips(Plot.landscape(circleDiagram, 0, levels = 2).svg(), "λ") must beEqualTo(2)
    }
  }

  "complexes" should {
    "show the Vietoris-Rips complex at a scale: the sides of a unit square at 1, all of it past the diagonal" in {
      val at1 = Plot.complex(square, 1.0).svg()
      tips(at1, "∆(") must beEqualTo(4)
      val at2 = Plot.complex(square, 1.5).svg()
      tips(at2, "∆(") must beEqualTo(6 + 4)
      count(at2, "circle") must beGreaterThanOrEqualTo(4 + 4) // balls and vertices
    }
    "draw a representative cycle edge by edge" in {
      val d = circleDiagram
      import d.given
      val loop = d.dim(1).bars.head.representative
      val svg = Plot.cycle(circle, loop).svg()
      tips(svg, "∆(") must beEqualTo(loop.terms.size)
    }
    "color a cocycle by the sign of its values" in {
      val d = Persistence(circle, maxDimension = 1, representatives = Representatives.Cocycles)
      import d.given
      val cocycle = d.dim(1).bars.head.representative
      val svg = Plot.cocycle(circle, cocycle).svg(Theme.Light)
      tips(svg, "∆(") must beEqualTo(cocycle.terms.size)
    }
    "draw circular coordinates on the cyclic map" in {
      val theta = circle.indices.map(i => i -> i / 16.0).toMap
      val svg = Plot.circularCoordinates(circle, theta).svg(Theme.Light)
      tips(svg, "point ") must beEqualTo(16)
      svg must contain(Palette.cyclicAt(0.5))
    }
  }

  "images" should {
    "draw one box per pixel, and a cubical representative over them" in {
      val img = Image(IndexedSeq(0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0).map(_.toDouble), IndexedSeq(4, 4))
      tips(Plot.image(img).svg(), "pixel") must beEqualTo(16)
      val d = Persistence(img)
      import d.given
      val rep = d.bars.maxBy(_.dim).representative
      tips(Plot.cubicalChain(img, rep).svg(), "Cube(") must beEqualTo(rep.terms.size)
    }
  }

  "field elements as numbers" should {
    "be the balanced integer for Z/p and the value itself for the reals" in {
      val f17 = FiniteField(17)
      import f17.given
      Plot.number(f17.Fp(16)) must beEqualTo(-1.0)
      Plot.number(f17.Fp(3)) must beEqualTo(3.0)
      given Double is Field = Field.DoubleApproximated(1e-9)
      Plot.number(0.25) must beEqualTo(0.25)
    }
  }

  "3-D scenes" should {
    "give a canvas viewer in a page and a well-formed static view as SVG" in {
      val sphere = Array.tabulate(40) { i =>
        val z = -1 + 2.0 * (i + 0.5) / 40
        val r = math.sqrt(1 - z * z)
        Array(r * math.cos(2.4 * i), r * math.sin(2.4 * i), z)
      }
      val scene = Plot.complex3D(sphere, 0.8, VietorisRips)
      scene.html() must contain("<canvas")
      count(scene.svgFile(Theme.Light), "circle") must beEqualTo(40)
    }
  }

  "side by side" should {
    "make one SVG file of several figures" in {
      val board = Plot.side(Plot.barcode(circleDiagram), Plot.diagram(circleDiagram))
      count(board.svgFile(), "svg") must beEqualTo(3)
    }
  }

  "the live viewer" should {
    "serve, list and announce every plot, on daemon threads" in {
      sys.props("tda4j.plot.port") = "0"
      sys.props("tda4j.plot.browser") = "none" // never open a tab on a developer's desktop
      Viewer.stop()
      val http = HttpClient.newHttpClient()
      def get(path: String) =
        http.send(HttpRequest.newBuilder(URI(Viewer.url + path)).build(), HttpResponse.BodyHandlers.ofString()).body()
      Plot.barcode(circleDiagram).copy(title = "first plot").view()
      get("list") must contain("\"title\":\"first plot\"")
      get("plot/1") must contain("<svg")
      get("") must contain("EventSource")

      // A second view() reaches a page that is listening.
      val events = http.send(
        HttpRequest.newBuilder(URI(Viewer.url + "events")).build(),
        HttpResponse.BodyHandlers.ofLines()
      )
      val lines = events.body().iterator()
      // Read on another thread with a deadline: a lost event must fail the spec, not hang the build.
      val firstEvent = scala.concurrent.Future {
        var seen = ""
        while !seen.startsWith("data:") do seen = lines.next()
        seen
      }(using scala.concurrent.ExecutionContext.global)
      Plot.diagram(circleDiagram).copy(title = "second plot").view()
      scala.concurrent.Await.result(firstEvent, scala.concurrent.duration.Duration(10, "s")) must beEqualTo("data: 2")
      get("list") must contain("second plot")

      import scala.jdk.CollectionConverters.*
      val serverThreads = Thread.getAllStackTraces.keySet.asScala.filter(t =>
        t.getName.contains("HTTP-Dispatcher") || t.getName.contains("tda4j-plot-viewer")
      )
      serverThreads.nonEmpty must beTrue
      serverThreads.forall(_.isDaemon) must beTrue
      Viewer.stop()
      ok
    }
  }
