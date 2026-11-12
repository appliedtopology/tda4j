package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** Which axes zooming a [[Panel.Flat.zoomable]] panel changes: both (the usual), only `x` (a barcode zoomed along the
  * filtration), or only `y`.
  */
enum ZoomAxes:
  case Both, X, Y

  /** What the page is told: `b`, `x` or `y`. */
  def code: String = this match
    case Both => "b"
    case X    => "x"
    case Y    => "y"

/** The range of a picture a viewer has zoomed to: `x0` to `x1` along the horizontal axis and `y0` to `y1` along the
  * vertical one, in data units. [[PageState.zoom]] puts it in a still picture. On a figure drawn with equal aspect,
  * give a window with the aspect of the picture (the page's own zoom keeps it).
  */
final case class Window(x0: Double, x1: Double, y0: Double, y1: Double):
  require(
    Seq(x0, x1, y0, y1).forall(_.isFinite) && x0 < x1 && y0 < y1,
    s"Window: a window needs x0 < x1 and y0 < y1, all numbers; got x $x0 to $x1, y $y0 to $y1"
  )
