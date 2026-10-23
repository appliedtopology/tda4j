package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

/** `given Look`: the starting look of every plot a constructor makes. The first example is a table of EVERY plot
  * constructor, because a constructor that forgets to pass the look along would silently use the empty one (there is
  * always a `given Look` to find), and nothing else would notice.
  */
class PlotLookSpec extends Specification:

  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private val circle3 = circle.map(p => Array(p(0), p(1), 0.0))
  private lazy val d = Persistence(circle, maxDimension = 1)
  private val img = Image(IndexedSeq(0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0).map(_.toDouble), IndexedSeq(4, 4))
  private val loop = Seq((∆(0, 1), 1.0), (∆(1, 2), 1.0), (∆(0, 2), -1.0))
  private val theta = circle.indices.map(i => i -> i / 16.0).toMap

  /** Every constructor of `Plot`, built under the look that is given where this is called. */
  private def everyPlot(using Look): Seq[(String, Plot)] =
    val di = Persistence(img)
    import di.given
    val rep = di.bars.maxBy(_.dim).representative
    Seq(
      "barcode" -> Plot.barcode(d),
      "diagram" -> Plot.diagram(d),
      "persistenceImage(bars, degree)" -> Plot.persistenceImage(d, 1),
      "persistenceImage(bars, degree, resolution, sigma)" -> Plot.persistenceImage(d, 1, 10, 0.05),
      "persistenceImage(image, ranges)" -> Plot
        .persistenceImage(Array(Array(0.0, 1.0), Array(1.0, 0.0)), (0.0, 1.0), (0.0, 1.0)),
      "landscape" -> Plot.landscape(d, 1),
      "image" -> Plot.image(img),
      "grid" -> Plot.grid(CubicalGridStream(IndexedSeq(5, 5), idx => if idx.sum % 2 == 0 then 1.0 else 0.0)),
      "cubicalChain" -> Plot.cubicalChain(img, rep),
      "points" -> Plot.points(circle),
      "complex(points, at)" -> Plot.complex(circle, 0.8),
      "complex(points, at, kind)" -> Plot.complex(circle, 0.8, Cech),
      "cycle" -> Plot.cycle(circle, loop),
      "cocycle" -> Plot.cocycle(circle, loop),
      "circularCoordinates" -> Plot.circularCoordinates(circle, theta),
      "torus" -> Plot.torus(theta, theta),
      "points3D" -> Plot.points3D(circle3),
      "complex3D(points, at, kind)" -> Plot.complex3D(circle3, 0.8, VietorisRips),
      "cycle3D" -> Plot.cycle3D(circle3, loop)
    )

  private def styleOf(p: Plot): Style = p match
    case f: Figure => f.style
    case s: Scene  => s.style
    case other     => throw IllegalArgumentException(s"$other is not a figure or a scene")

  private def paletteOf(p: Plot): Palette = p match
    case f: Figure => f.palette
    case s: Scene  => s.palette
    case other     => throw IllegalArgumentException(s"$other is not a figure or a scene")

  "a given Look" should {
    "start every plot constructor, a figure or a scene, from the look" in {
      val plain = everyPlot(using Look.none)
      plain.map(_._2).map(styleOf).distinct must beEqualTo(
        Seq(Style.default)
      ) // without one, as the constructors make them
      val looked =
        given Look = Look.print
        everyPlot
      looked.map(_._1) must beEqualTo(plain.map(_._1))
      looked.filter((_, p) => styleOf(p) != Style.print).map(_._1) must beEmpty
      looked.filter((_, p) => paletteOf(p) != Palette.print).map(_._1) must beEmpty
      looked.size must beGreaterThanOrEqualTo(19)
    }
    "be applied once to a plot, however it is made from other plots" in {
      // a tweak that is not idempotent: a look applied twice would add 20
      val wider = Look() + Tweak(f => f.copy(width = f.width + 10))
      val plain = everyPlot(using Look.none)
      val looked =
        given Look = wider
        everyPlot
      plain
        .zip(looked)
        .collect { case ((name, f: Figure), (_, g: Figure)) => name -> (g.width - f.width) }
        .filter(_._2 != 10) must
        beEmpty
    }
    "give way to a tweak chained on afterwards, and to a palette named in the call" in {
      given Look = Look.print
      (Plot.barcode(d) + Style.slides).style must beEqualTo(Style.slides)
      (Plot.barcode(d) + Palette.reference).palette must beEqualTo(Palette.reference)
      Plot.barcode(d, palette = Palette.reference).palette must beEqualTo(Palette.reference) // named in the call
      Plot.barcode(d, palette = Palette.reference).style must beEqualTo(Style.print) // the rest of the look stays
      Plot.diagram(d, "Loops", Palette.reference).palette must beEqualTo(Palette.reference)
      Plot.image(img, palette = Palette.reference).palette must beEqualTo(Palette.reference)
      Plot.points(circle, palette = Palette.reference).palette must beEqualTo(Palette.reference)
      Plot.barcode(d).palette must beEqualTo(Palette.print) // and what is not named is the look's
    }
    "leave a scene the tweaks that apply to it, and take the rest for a figure" in {
      given Look = Look() + Text(TextRole.TickLabel, size = 9) + Text(TextRole.Title, size = 21) + Style.print
      val scene = Plot.points3D(circle3)
      scene.style must beEqualTo(Style.print)
      scene.elements.face(TextRole.Title).size must beEqualTo(21.0) // a scene has a title
      Plot.barcode(d).elements.face(TextRole.TickLabel).size must beEqualTo(9.0) // a figure has tick labels
    }
    "be the user's: the nearest given wins whole, and the default in the companion never overrides one" in {
      given Look = Look.print + Text(TextRole.Title, size = 21)
      val outer = Plot.barcode(d)
      outer.style must beEqualTo(Style.print)
      outer.elements.face(TextRole.Title).size must beEqualTo(21.0)
      val inner =
        given Look = Look.clear // an inner scope replaces the outer look, it does not add to it
        Plot.barcode(d)
      inner.style must beEqualTo(Style.clear)
      inner.elements.face(TextRole.Title).size must beEqualTo(
        Plot.barcode(d)(using Look.none).elements.face(TextRole.Title).size
      )
      Plot.barcode(d).style must beEqualTo(Style.print) // and the outer one is back
      // the library's own given is the empty look, found only when none is in scope
      summon[Look](using Look.standard) must beEqualTo(Look.none)
    }
    "be built on with summon[Look] + tweaks, in an inner scope: the look of the scope around, with one more tweak" in {
      def titleSize(f: Figure) = f.elements.face(TextRole.Title).size
      def legendSize(f: Figure) = f.elements.face(TextRole.Legend).size
      val outerLook = Look.print + Text(TextRole.Legend, size = 9)
      // a block inside a scope with a look
      val nested =
        given Look = outerLook
        locally {
          given Look = summon[Look] + Text(TextRole.Title, size = 21)
          Plot.barcode(d)
        }
      (nested.style, legendSize(nested), titleSize(nested)) must beEqualTo((Style.print, 9.0, 21.0))
      // with no look of the user's, it adds to the default, which is nothing
      val fromDefault =
        given Look = summon[Look] + Text(TextRole.Title, size = 21)
        Plot.barcode(d)
      (fromDefault.style, titleSize(fromDefault)) must beEqualTo((Style.default, 21.0))
      // a method that takes the look as a parameter
      def drawn(using Look): Figure =
        given Look = summon[Look] + Text(TextRole.Title, size = 21)
        Plot.barcode(d)
      val viaMethod = drawn(using outerLook)
      (viaMethod.style, legendSize(viaMethod), titleSize(viaMethod)) must beEqualTo((Style.print, 9.0, 21.0))
      // a shared look imported from another object
      val imported =
        import PlotLookSpec.House.given
        locally {
          given Look = summon[Look] + Text(TextRole.Title, size = 21)
          Plot.barcode(d)
        }
      (imported.style, legendSize(imported), titleSize(imported)) must beEqualTo((Style.print, 9.0, 21.0))
    }
    "be nothing at all unless you give one, and a block's own look is its own" in {
      Plot.barcode(d).style must beEqualTo(Style.default)
      {
        given Look = Look.clear
        Plot.barcode(d).style must beEqualTo(Style.clear)
      }
      Plot.barcode(d).style must beEqualTo(Style.default) // outside the block again
    }
  }

  "the looks you can name" should {
    "be a palette and a style together, and none" in {
      Look.print must beEqualTo(Look() + Palette.print + Style.print)
      Look.clear must beEqualTo(Look() + Palette.clear + Style.clear)
      Look.named("PRINT") must beEqualTo(Look.print)
      Look.named("default") must beEqualTo(Look.none)
      Look.none.isEmpty must beTrue
      Look.named("poster") must throwWith[IllegalArgumentException](
        "unknown look 'poster'; expected one of default, print, clear"
      )
    }
    "be written as settings, in order, with palette and style among them" in {
      Look.settings("palette=print", "style=print") must beEqualTo(Look.print)
      Look.settings("title.size=18", "grid.dash=dotted") must beEqualTo(
        Look() + Text(TextRole.Title, size = 18) + Lines.grid(dash = Dash.Dotted)
      )
      Look.settings() must beEqualTo(Look.none)
      Look.settings("palette=neon") must throwWith[IllegalArgumentException](
        "expected one of brand, reference, print, grayscale, clear"
      )
      Look.settings("style=poster") must throwWith[IllegalArgumentException](
        "expected one of default, slides, print, clear"
      )
      Settings.parse("palette=print") must beEqualTo(Palette.print)
    }
    "add looks and tweaks in the order given, the later one winning" in {
      val look = Look.print ++ (Look() + Style.slides)
      Plot.barcode(d)(using look).style must beEqualTo(Style.slides)
      Plot.barcode(d)(using look).palette must beEqualTo(Palette.print)
    }
  }

object PlotLookSpec:
  /** A shared look, as a project would keep it in one place. */
  object House:
    given look: Look = Look.print + Text(TextRole.Legend, size = 9)
