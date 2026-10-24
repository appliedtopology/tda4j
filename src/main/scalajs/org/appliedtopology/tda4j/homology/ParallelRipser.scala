package org.appliedtopology.tda4j

/** The JVM build runs the block-parallel Ripser engine here; a JavaScript program has one thread, so `run` refuses
  * (`Persistence.Engine.ParallelRipser`) and `Engine.Auto`, which only asks with several threads, never gets here.
  */
// Restricted: see the JVM twin in `src/main/scalajvm`.
private[tda4j] object ParallelRipser:
  // True so that asking for the engine by name reaches `run` and its message, not a size or prime it never looked at.
  def supports(points: Int, maxDimension: Int, characteristic: Int, cycles: Boolean): Boolean = true

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
