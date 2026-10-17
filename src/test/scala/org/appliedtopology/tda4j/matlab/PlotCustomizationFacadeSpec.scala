package org.appliedtopology.tda4j
package matlab

import org.appliedtopology.tda4j.plot.*
import org.specs2.mutable.Specification

import java.io.{ByteArrayInputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/** What MATLAB and Java call to change a plot: the `with...` methods take only strings, ints and doubles, and each is
  * one of the tweaks Scala adds with `+`. A result's plot is customized the way the user guide's MATLAB tab does it.
  */
class PlotCustomizationFacadeSpec extends Specification:
  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private lazy val result = TDA4j.computeFromPoints(circle, Array("maxDimension", "1"))

  private def texts(svg: String): Seq[String] =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)))
    val nodes = doc.getElementsByTagName("text")
    (0 until nodes.getLength).map(nodes.item(_).getTextContent)

  private def normalized(svg: String): String = svg.replaceAll("tda4j(3d)?[0-9]+", "tda4jN")

  "the plain-argument methods of a figure" should {
    "each be the tweak they stand for" in {
      val fig = result.plotDiagram()
      fig.withTitle("T") must beEqualTo(fig + Labels(title = "T"))
      fig.withSubtitle("S") must beEqualTo(fig + Labels(subtitle = "S"))
      fig.withXLabel("born") must beEqualTo(fig + Labels(x = "born"))
      fig.withYLabel("dies") must beEqualTo(fig + Labels(y = "dies"))
      fig.withSize(800, 300) must beEqualTo(fig + Size(800, 300))
      fig.withXScale("log") must beEqualTo(fig + Scale.x(Transform.Log10))
      fig.withYScale("SQRT") must beEqualTo(fig + Scale.y(Transform.Sqrt))
      fig.withXRange(0, 2) must beEqualTo(fig + Scale.x(min = 0, max = 2))
      fig.withYRange(0.5, 3) must beEqualTo(fig + Scale.y(min = 0.5, max = 3))
      fig.withPalette("reference") must beEqualTo(fig + Palette.reference)
      fig.withPalette("print") must beEqualTo(fig + Palette.print)
      fig.withStyle("slides") must beEqualTo(fig + Style.slides)
      fig.withStyle("print") must beEqualTo(fig + Style.print)
      fig.withPalette("clear") must beEqualTo(fig + Palette.clear)
      fig.withStyle("clear") must beEqualTo(fig + Style.clear)
      fig.withSetting("grid.dash=dotted") must beEqualTo(fig + Lines.grid(dash = Dash.Dotted))
      fig.withSetting("title.size=18").withSetting("x-axis=top") must
        beEqualTo(fig + Text(TextRole.Title, size = 18) + Scale.x(position = Side.Top))
      fig.withDegreeColor(1, "#d62728") must beEqualTo(fig + Scale.degree(1, color = "#d62728"))
      fig.withDegreeLabel(1, "loops") must beEqualTo(fig + Scale.degree(1, label = "loops"))
      fig.withTextScale(1.5) must beEqualTo(fig + fig.style.copy(textScale = 1.5))
      fig.withMarkScale(2) must beEqualTo(fig + fig.style.copy(markScale = 2))
      fig.withLegend("bottom") must beEqualTo(fig + fig.style.copy(legend = LegendPosition.Bottom))
      fig.withoutGrid() must beEqualTo(fig + fig.style.copy(grid = false))
      fig.withTransparentBackground() must beEqualTo(fig + fig.style.copy(transparent = true))
    }
    "have, for each name the user guide lists, an overload that takes only what MATLAB can pass" in {
      val plain = Set[Class[?]](classOf[String], classOf[Int], classOf[Double])
      def callable(c: Class[?], name: String) =
        c.getMethods.exists(m => m.getName == name && m.getParameterTypes.forall(plain))
      val figureNames = Seq(
        "withTitle",
        "withSubtitle",
        "withXLabel",
        "withYLabel",
        "withLegendTitle",
        "withColorLabel",
        "withSize",
        "withXScale",
        "withYScale",
        "withXRange",
        "withYRange",
        "withPalette",
        "withStyle",
        "withSetting",
        "withDegreeColor",
        "withDegreeLabel",
        "withTextScale",
        "withMarkScale",
        "withLegend",
        "withoutGrid",
        "withTransparentBackground"
      )
      val sceneNames = Seq(
        "withTitle",
        "withSubtitle",
        "withSize",
        "withPalette",
        "withStyle",
        "withSetting",
        "withDegreeColor",
        "withTextScale",
        "withMarkScale",
        "withTransparentBackground"
      )
      figureNames.filterNot(callable(classOf[Figure], _)) must beEmpty
      sceneNames.filterNot(callable(classOf[Scene], _)) must beEmpty
      classOf[Plot].getMethod("save", classOf[String], classOf[String]) must not(beNull)
    }
    "chain as MATLAB writes them, and draw the result" in {
      val svg = result.plotDiagram().withTitle("A circle").withXLabel("born").withSize(800, 300).svg()
      texts(svg) must contain(allOf("A circle", "born"))
      svg must contain("""width="800" height="300"""")
    }
    "draw a representative with its degree renamed and recolored" in {
      val loop = result.mostPersistentBar(1)
      val fig = result.plotRepresentative(loop).withDegreeColor(1, "#d62728").withDegreeLabel(1, "the loop")
      val svg = fig.svg(Theme.Light)
      svg must contain("#d62728")
      texts(svg) must contain("the loop")
    }
    "name the words that are wrong, and what to say instead" in {
      val fig = result.plotDiagram()
      fig.withXScale("cubic") must throwAn[IllegalArgumentException](
        "unknown scale 'cubic'; expected one of linear, log, sqrt, symlog"
      )
      fig.withPalette("neon") must throwAn[IllegalArgumentException]("expected one of brand, reference, print, clear")
      fig.withStyle("poster") must throwAn[IllegalArgumentException]("expected one of default, slides, print, clear")
      fig.withSetting("grid.dash=wavy") must throwAn[IllegalArgumentException]("expected one of solid, dashed, dotted")
      fig.withSetting("title.size") must throwAn[IllegalArgumentException]("should be element.property=value")
      fig.withLegend("left") must throwAn[IllegalArgumentException]("expected one of right, bottom, none")
      fig.withSize(20, 20) must throwAn[IllegalArgumentException]("between 100 and 20000")
      fig.withTextScale(0) must throwAn[IllegalArgumentException]("textScale")
    }
    "save in a theme that is named" in {
      val fig = result.plotDiagram()
      val path = File.createTempFile("facade-theme", ".svg")
      path.deleteOnExit()
      fig.save(path.getAbsolutePath, "dark")
      normalized(Files.readString(path.toPath)) must beEqualTo(normalized(fig.svgFile(Theme.Dark)))
      fig.save(path.getAbsolutePath, "sepia") must throwAn[IllegalArgumentException](
        "expected one of auto, light, dark"
      )
    }
  }

  "the plain-argument methods of a 3-D scene" should {
    "change the title, size and look, and refuse what needs axes" in {
      val scene = result.plotInput3D()
      scene.withTitle("T") must beEqualTo(scene + Labels(title = "T"))
      scene.withSize(300, 200).width must beEqualTo(300)
      scene.withMarkScale(2).style.markScale must beEqualTo(2.0)
      scene.withPalette("reference").palette must beEqualTo(Palette.reference)
      scene.withStyle("print").style must beEqualTo(Style.print)
      scene.withSetting("title.size=18") must beEqualTo(scene + Text(TextRole.Title, size = 18))
      scene.withSetting("tick-label.size=9") must throwAn[UnsupportedOperationException]("a 3-D scene has a title")
    }
  }
