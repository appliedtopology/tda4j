package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

import scala.collection.mutable

/** `Plot.viewWith`: a program that hosts the library takes the plots that `view()` would have sent to the live viewer.
  * Nothing here may reach the viewer itself (it would start a server, or open a browser from Node), so every `view()`
  * is inside a `viewWith`; the way back to the viewer is checked where the viewer is (`PlotSpec`).
  */
class PlotViewWithSpec extends Specification:

  private val circle = Array.tabulate(16)(i => Array(math.cos(2 * math.Pi * i / 16), math.sin(2 * math.Pi * i / 16)))
  private lazy val d = Persistence(circle, maxDimension = 1)

  "Plot.viewWith" should {
    "hand each plot viewed in its body to the target, in order, and give the body's value back" in {
      val seen = mutable.ArrayBuffer.empty[Plot]
      val barcode = Plot.barcode(d)
      val diagram = Plot.diagram(d)
      val scene = Scene(title = "balls")
      val result = Plot.viewWith(seen += _) {
        barcode.view()
        scene.view()
        diagram.view()
        "done"
      }
      result must beEqualTo("done")
      // The plots themselves, not copies or their pages: a host chooses how to show one (`html`, `fragment`, `svg`).
      seen.toList.map(System.identityHashCode) must beEqualTo(List(barcode, scene, diagram).map(System.identityHashCode))
    }

    "leave `view()` returning the plot it was called on, as it does with the viewer" in {
      val fig = Plot.barcode(d)
      val returned = Plot.viewWith(_ => ())(fig.view())
      (returned eq fig) must beTrue
    }

    "send a plot to the innermost target only, and to the outer one again after the inner body" in {
      val outer = mutable.ArrayBuffer.empty[String]
      val inner = mutable.ArrayBuffer.empty[String]
      def titled(t: String) = Plot.barcode(d).copy(title = t)
      Plot.viewWith(p => outer += p.title) {
        titled("before").view()
        Plot.viewWith(p => inner += p.title)(titled("inside").view())
        titled("after").view()
      }
      inner.toList must beEqualTo(List("inside"))
      outer.toList must beEqualTo(List("before", "after"))
    }

    "restore the outer target when the body throws" in {
      val outer = mutable.ArrayBuffer.empty[String]
      val inner = mutable.ArrayBuffer.empty[String]
      Plot.viewWith(p => outer += p.title) {
        try Plot.viewWith(p => inner += p.title)(throw IllegalStateException("the body failed"))
        catch case _: IllegalStateException => ()
        Plot.barcode(d).copy(title = "after the failure").view()
      }
      inner.toList must beEqualTo(Nil)
      outer.toList must beEqualTo(List("after the failure"))
    }
  }
