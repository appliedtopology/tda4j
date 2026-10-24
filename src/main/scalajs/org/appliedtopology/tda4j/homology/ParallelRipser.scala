package org.appliedtopology.tda4j

/** The JVM build runs the block-parallel Ripser engine here; a JavaScript program has one thread, so `supports` is
  * never true and `run` refuses (`Persistence.Engine.ParallelRipser`).
  */
// Restricted: see the JVM twin in `src/main/scalajvm`.
private[tda4j] object ParallelRipser:
  def supports(points: Int, maxDimension: Int, characteristic: Int, cycles: Boolean): Boolean = false

  def run(
    metricSpace: FiniteMetricSpace[Int],
    maxDimension: Int,
    threshold: Double,
    characteristic: Int,
    withPairs: Boolean
  ): ParallelRipserRun =
    throw new UnsupportedOperationException(
      "Persistence: engine = ParallelRipser needs threads, which JavaScript does not have; use Engine.Ripser"
    )
