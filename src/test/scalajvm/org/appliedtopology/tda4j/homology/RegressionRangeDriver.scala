package org.appliedtopology.tda4j

import scala.util.Random

/** Test-scope driver for #51: the time of one `BarcodeRegressionSpec` case (chunks engine, degrees 0..3,
  * `diagramAt(5.0)`) on `n` uniform points in `ambient` dimensions: `RegressionRangeDriver <case> <n> <ambient> [seed]`
  * with `case` one of `dqp`, `helix`, `vr`.
  */
object RegressionRangeDriver:
  def main(args: Array[String]): Unit =
    val kind = args(0)
    val n = args(1).toInt
    val ambient = args(2).toInt
    val rng = new Random(if args.length > 3 then args(3).toLong else 7L)
    val pts = Array.fill(n)(Array.fill(ambient)(rng.nextDouble()))
    given Double is Field = Field.DoubleApproximated(1e-25)
    val t0 = System.nanoTime()
    val stream: LevelwiseSimplexStream[Int, Double] = kind match
      case "dqp"   => AlphaShapes(pts.toIndexedSeq, AlphaBackend.DQP)
      case "helix" => AlphaShapes(pts.toIndexedSeq, AlphaBackend.Helix)
      case "vr"    => LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(pts)), 4)
    val homology = PersistenceInChunksEngine[Int, Double](3).persistentHomology(stream)
    val dgm = homology.diagramAt(5.0)
    val ms = (System.nanoTime() - t0) / 1e6
    println(f"$kind n=$n ambient=$ambient bars=${dgm.size} ms=$ms%.0f")
