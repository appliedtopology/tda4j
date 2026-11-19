package org.appliedtopology.tda4j

import scala.util.Random

/** One run of the harmonic chain barcode, for the A/B of `Orthogonalization` (#305): one strategy per JVM. Prints the
  * seconds the run took, the number of bars, and the heap retained by the finished run (after full collections, with
  * the run still reachable) beside what the JVM held before it, which is what the basis costs; run with a fixed heap
  * and the serial collector so that `-Xmx` is also a test of what completes:
  * {{{
  * java -Xmx2g -XX:+UseSerialGC -cp $CP org.appliedtopology.tda4j.HarmonicBenchmarkDriver \
  *   points=100 radius=0.35 degree=1 how=gram|solve [coefficients=reals|rationals] [seed=1] [trial=1] [dump=bars.txt]
  * }}}
  * The points are uniform in the unit square; the complex is the Vietoris-Rips complex up to `radius`.
  */
object HarmonicBenchmarkDriver:
  private def used(): Long =
    for _ <- 0 until 4 do System.gc()
    val r = Runtime.getRuntime
    r.totalMemory - r.freeMemory

  def main(args: Array[String]): Unit =
    val options = args.map(_.split("=", 2)).collect { case Array(k, v) => k -> v }.toMap
    val n = options.getOrElse("points", "100").toInt
    val radius = options.getOrElse("radius", "0.35").toDouble
    val degree = options.getOrElse("degree", "1").toInt
    val seed = options.getOrElse("seed", "1").toLong
    val how = options.getOrElse("how", "gram") match
      case "gram"  => HarmonicChainBarcode.Orthogonalization.GramSchmidt
      case "solve" => HarmonicChainBarcode.Orthogonalization.LaplacianSolve
      case other   => throw IllegalArgumentException(s"how must be gram or solve, not $other")
    val coefficients = options.getOrElse("coefficients", "reals") match
      case "reals"     => Coefficients(0)
      case "rationals" => Coefficients.Rationals
      case other       => throw IllegalArgumentException(s"coefficients must be reals or rationals, not $other")
    import coefficients.given
    val rng = Random(seed)
    val points = Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))
    val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = degree, maxFiltrationValue = radius)
    val order = HarmonicChainBarcode.trackedOrder(HarmonicChainBarcode.insertionOrder(rips, degree), degree)
    val before = used()
    val start = System.nanoTime()
    val run = new HarmonicChainBarcode.Run[Simplex[Int], coefficients.C](
      degree,
      order,
      includeZeroLength = false,
      orthogonalization = how
    )
    val bars = run.execute()
    options.get("dump").foreach { path =>
      val lines = bars.map(b => s"${b.dim} ${b.lower} ${b.upper}").sorted
      java.nio.file.Files.write(java.nio.file.Paths.get(path), lines.mkString("\n").getBytes("UTF-8"))
    }
    val seconds = (System.nanoTime() - start) / 1e9
    val retained = used() - before
    println(
      f"how=$how points=$n radius=$radius%.3f degree=$degree cells=${order.size} bars=${bars.size} " +
        f"seconds=$seconds%.2f retainedMB=${retained / 1048576.0}%.1f ${run.order.size}"
    )
