package org.appliedtopology.tda4j

/** The JVM build runs the block-parallel Ripser engine here; a JavaScript program has one thread, so it refuses
  * (`Persistence.Engine.ParallelRipserBarsOnlyNoRepresentatives`).
  */
// Restricted: see the JVM twin in `src/main/scalajvm`.
private[tda4j] object ParallelRipser:
  def bars(
    metricSpace: FiniteMetricSpace[Int],
    maxDimension: Int,
    threshold: Double,
    characteristic: Int
  ): List[(Int, Double, Double)] =
    throw new UnsupportedOperationException(
      "Persistence: engine = ParallelRipserBarsOnlyNoRepresentatives needs threads, which JavaScript does not have; " +
        "use Engine.Ripser"
    )
