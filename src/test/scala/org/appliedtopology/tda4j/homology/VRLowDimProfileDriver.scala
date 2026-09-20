package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import scala.util.Random

/** Test-scope driver, kept alongside `CubicalProfileDriver`/`SingleEngineProfileDriver` -- a single-process JVM target
  * for profiling `SimplicialHomologyContext` (the naive engine) on a large, SPARSE, low-`maxDim` Vietoris-Rips complex.
  * Invoked directly:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.homology.VRLowDimProfileDriver <n> <maxDim> [seed] [thresholdScale] [forceUncached]
  * }}}
  *
  * Built to answer the question item #2 of `.claude/WORKLOG-autonomous-session-2026-09-19.md` needed answered BEFORE
  * writing any raw-`UnionFind` code: `WORKLOG-mst-and-perf.md`'s own deferred-decision section named "large point
  * cloud, low maxDim (0 or 1) -- where dimension-0/1 IS most or all of the complex" as the one scenario where a
  * raw-UnionFind fast path could plausibly pay off, and explicitly said this needs its own targeted benchmark rather
  * than being assumed. `maxDim` defaults to 1 for exactly that reason -- `maxDim >= 2` is the case that same worklog
  * already settled as NOT worth it (dimension-0/1 is a small fraction of the complex once triangles exist).
  *
  * The measurement this drove found the answer wasn't raw UnionFind at all: deep-stack profiling attributed ~57% of
  * samples to `filtrationValue`/`filtrationOrdering`, and `EnumeratingCofaceSimplexStream.filtrationValue` (its default
  * `MaximumDistanceFiltrationValue` fallback) turned out to be exactly as uncached as `CubicalGridStream` was before
  * task #1's fix -- same bug class, same fix. That fix now lives on the stream itself (`SimplexStream.scala`), on by
  * default. `forceUncached` (default `false`) lets this driver still reproduce the PRE-fix baseline on demand, by
  * supplying an explicit, deliberately-uncached `filtrationValueOverride` -- kept for exactly this kind of before/after
  * re-measurement, not because the default stream is still uncached.
  *
  * Phase-separated exactly like `CubicalProfileDriver`, for the same reason (a FRESH stream instance per phase, so no
  * phase's cost is accidentally inflated or hidden by another phase's side effects/caching):
  *   1. `stream.iterator.size` -- the stream's own coface enumeration + per-dimension sort.
  *   2. `persistentHomology(stream)` -- `HomologyState`'s construction (its own global `processingOrder` sort).
  *   3. `.diagramAt(...)` -- the actual `advanceAll`/reduction loop.
  *
  * The point cloud is sparse by construction (threshold scaled as `thresholdScale / sqrt(n)`, the same "roughly
  * constant expected neighbor count as n grows" convention `SparseRipsBenchmarkSpec` already uses) -- a fixed threshold
  * would make the complex denser as `n` grows, which is not the "large point cloud, low maxDim" scenario this driver
  * exists to characterize.
  */
object VRLowDimProfileDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val maxDim = if args.length > 1 then args(1).toInt else 1
    val seed = if args.length > 2 then args(2).toInt else 42
    val thresholdScale = if args.length > 3 then args(3).toDouble else 2.5
    val forceUncached = if args.length > 4 then args(4).toBoolean else false

    given Double is Field = Field.DoubleApproximated(1e-9)
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val rng = new Random(seed.toLong)
    val points = Array.fill(n)(Array.fill(2)(rng.nextDouble()))
    val metricSpace = EuclideanMetricSpace(points)
    val threshold = thresholdScale / math.sqrt(n.toDouble)

    def freshStream() =
      // A fresh, UNCACHED PartialFunction every call (unlike the stream's own default, memoized fallback) --
      // deliberately reproduces the pre-fix baseline for comparison, not a realistic usage pattern.
      val fvOverride =
        if forceUncached then Some(FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace))
        else None
      LimitedCofaceSimplexStream(
        EnumeratingCofaceSimplexStream(
          metricSpace,
          maxFiltrationValue = Some(threshold),
          filtrationValueOverride = fvOverride
        ),
        maxDim
      )

    val t0 = System.nanoTime()
    val phase1Count = freshStream().iterator.size
    val t1 = System.nanoTime()

    val state = persistentHomology(freshStream())
    val t2 = System.nanoTime()

    val barcode = state.diagramAt(Double.PositiveInfinity)
    val t3 = System.nanoTime()

    val phase1Ms = (t1 - t0) / 1e6
    val phase2Ms = (t2 - t1) / 1e6
    val phase3Ms = (t3 - t2) / 1e6
    val totalMs = (t3 - t0) / 1e6

    def usPer(ms: Double): Double = ms * 1000.0 / phase1Count

    println(
      s"n=$n maxDim=$maxDim threshold=$threshold cells=$phase1Count bars=${barcode.size}\n" +
        f"  phase1(coface enum + sort)     ms=$phase1Ms%10.1f  us/cell=${usPer(phase1Ms)}%8.3f\n" +
        f"  phase2(HomologyState build)    ms=$phase2Ms%10.1f  us/cell=${usPer(phase2Ms)}%8.3f\n" +
        f"  phase3(advanceAll reduction)   ms=$phase3Ms%10.1f  us/cell=${usPer(phase3Ms)}%8.3f\n" +
        f"  total                          ms=$totalMs%10.1f  us/cell=${usPer(totalMs)}%8.3f"
    )
