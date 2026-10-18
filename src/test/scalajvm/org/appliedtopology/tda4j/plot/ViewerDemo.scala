package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** Shows a few plots in the live viewer and keeps the JVM alive for `args(0)` seconds (default 60), for looking at the
  * viewer page: `sbt "plot/Test/runMain org.appliedtopology.tda4j.plot.ViewerDemo"`.
  */
object ViewerDemo:
  def main(args: Array[String]): Unit =
    val circle = Gallery.noisyCircle(40, 0.08, 1)
    val d = Persistence(circle, maxDimension = 1)
    Plot.barcode(d).view()
    Plot.complex(circle, 0.45).view()
    Plot.diagram(d).view()
    Thread.sleep(1000L * args.headOption.flatMap(_.toIntOption).getOrElse(60))
