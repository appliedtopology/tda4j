package org.appliedtopology.tda4j

/** The stream cohomology engine on an alpha complex, on flat arrays or on the cells (`packSimplices`), one setting per
  * JVM for an A/B. The complex is built and sorted before the clock starts; each trial reduces it in full.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.StreamCohomologyBenchmark points.csv degree packed|cells [trials] [cycles|cocycles] [backend]
  * }}}
  */
object StreamCohomologyBenchmark:
  given Epsilon = Epsilon(1e-5)

  def main(args: Array[String]): Unit =
    val pts = PaperBenchmarkDriver.readMatrix(args(0))
    val degree = args(1).toInt
    val packed = args(2) == "packed"
    val trials = args.lift(3).fold(3)(_.toInt)
    val cycles = !args.lift(4).contains("cocycles")
    val backend = args.lift(5).fold(AlphaBackend.Default)(b => AlphaBackend.valueOf(b))
    val start = System.nanoTime()
    val stream = Truncated(AlphaShapes(pts, backend), degree)
    val cells = stream.iterator.size
    println(
      f"${pts.length} points in R^${pts.head.length}, degree $degree, $backend: $cells cells in " +
        f"${(System.nanoTime() - start) / 1e9}%.1f s; ${if packed then "packed" else "cells"}, " +
        s"${if cycles then "cycles" else "cocycles"}"
    )
    val ff = FiniteField(17)
    import ff.given
    val engine = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double](packSimplices = packed)
    val times = (1 to trials).map { _ =>
      val t0 = System.nanoTime()
      val bars = if cycles then engine.persistentHomology(stream) else engine.persistentCohomology(stream)
      val seconds = (System.nanoTime() - t0) / 1e9
      println(f"  $seconds%.3f s, ${bars.size} bars")
      seconds
    }
    println(f"  median ${times.sorted.apply(times.size / 2)}%.3f s, min ${times.min}%.3f s")
