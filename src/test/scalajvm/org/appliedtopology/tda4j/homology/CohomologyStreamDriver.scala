package org.appliedtopology.tda4j

import Persistence.Engine

/** Test-scope driver for `Engine.Cohomology` on the Vietoris-Rips stream, with the stream's layers built by the coface
  * loop and a sort (`coface`, the generator switched off) or by the layered in-order generator (`generator`, the stream
  * as it is), one variant per JVM:
  *
  * {{{
  * java -cp <test classpath> org.appliedtopology.tda4j.CohomologyStreamDriver coface|generator layers|cohomology <workload> [Cycles|Cocycles] [trials] [seed]
  * }}}
  *
  * `workload` is `sphere:<n>` (n random points on S^2), `cloud:<n>:<d>` (n uniform points in [0,1]^d) or
  * `grid:<side>:<d>:<radius>` (the integer lattice `side^d`, many equal distances, with that threshold), degrees 0..2
  * as in `GenericEngineProfileDriver`. `layers` times only the building of every dimension up to 3 as flat arrays;
  * `cohomology` times the whole `Persistence(stream, engine = Cohomology)` call, the stream built before the clock (a
  * stream is lazy: its layers are built inside the call). Prints the median wall time of the trials and the number of
  * cells or bars; the cell counts of the two variants must agree.
  */
object CohomologyStreamDriver:
  def main(args: Array[String]): Unit =
    val variant = args(0)
    val task = args(1)
    val workload = args(2).split(':').toList
    val representatives = Representatives.valueOf(args.lift(3).getOrElse("Cycles"))
    val trials = args.lift(4).fold(3)(_.toInt)
    val rng = scala.util.Random(args.lift(5).fold(17L)(_.toLong))
    require(variant == "coface" || variant == "generator", s"variant is coface or generator, not $variant")

    val points = workload match
      case "sphere" :: n :: Nil =>
        Array.fill(n.toInt) {
          val v = Array.fill(3)(rng.nextGaussian())
          val norm = math.sqrt(v.map(x => x * x).sum)
          v.map(_ / norm)
        }
      case "cloud" :: n :: d :: Nil        => Array.fill(n.toInt)(Array.fill(d.toInt)(rng.nextDouble()))
      case "grid" :: side :: d :: _ :: Nil =>
        val cells = Seq
          .fill(d.toInt)(0 until side.toInt)
          .foldLeft(Seq(Seq.empty[Double]))((rows, axis) => for row <- rows; x <- axis yield row :+ x.toDouble)
        cells.map(_.toArray).toArray
      case other => throw new IllegalArgumentException(s"unknown workload $other")
    val threshold = workload match
      case "grid" :: _ :: _ :: radius :: Nil => Some(radius.toDouble)
      case _                                 => None
    val metric = EuclideanMetricSpace(points)

    def stream(): LevelwiseSimplexStream[Int, Double] =
      val base =
        if variant == "generator" then new RipserCofaceSimplexStream(metric, maxFiltrationValue = threshold)
        else
          new RipserCofaceSimplexStream(metric, maxFiltrationValue = threshold):
            override protected def flatGenerator = None
      Truncated.ofCofaces(base, 2)

    var count = 0
    val times = (1 to trials).map { _ =>
      val s = stream()
      val t0 = System.nanoTime()
      task match
        case "layers" =>
          count = s match
            case flat: FlatLayerStream => flat.flatLayers.get.map(_.size).sum
            case _                     => throw new IllegalStateException("the stream has no flat layers")
        case "cohomology" =>
          count =
            Persistence(s, maxDimension = 2, engine = Engine.Cohomology, representatives = representatives).bars.size
        case other => throw new IllegalArgumentException(s"unknown task $other")
      (System.nanoTime() - t0) / 1e6
    }
    println(
      f"$variant $task ${args(2)}: median ${times.sorted.apply(trials / 2)}%.0f ms of ${times
          .map(t => f"$t%.0f")
          .mkString(", ")}, $count ${if task == "layers" then "cells" else "bars"}"
    )
