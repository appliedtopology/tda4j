package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap

import com.dreizak.miniball.model.PointSet
import com.dreizak.miniball.highdim.Miniball

/** Adapts a raw coordinate array to Miniball's `PointSet` interface. Declared locally in `streams` rather than anywhere
  * in `alpha`: `streams` is the more foundational package (`alpha` already depends on `streams`, not the other way
  * around -- see CLAUDE.md's "Package layout"), so a shared adapter would have to live here regardless. See
  * `CechFiltration`'s own doc for why Miniball, not the DAQP/alpha-shape machinery, is the right primitive for Cech
  * specifically.
  */
private class MiniballPointSet(points: Array[Array[Double]]) extends PointSet:
  override def size: Int = points.length
  override def dimension: Int = points(0).length
  override def coord(i: Int, j: Int): Double = points(i)(j)

/** The Cech radius of a simplex: the true minimum-enclosing-ball radius of its vertices' coordinates, computed once per
  * simplex and cached forever -- a deliberate departure from `RipserCohomologyContext`'s "don't cache filtration values
  * by default" doctrine, since (unlike VR's diameter, which `insertionDiameter` recomputes incrementally in O(d)) there
  * is no incremental shortcut for a minimum-enclosing-ball radius: every filter check, sort, and `filtrationOrdering`
  * comparison would otherwise re-run a full Miniball solve.
  *
  * Caching is also a correctness safeguard, not just a speed one: Miniball is a randomized-incremental algorithm, so
  * two separate calls on the identical input can in principle round differently and return bit-different radii.
  * `filtrationOrdering` needs one consistent answer per simplex to stay a total order; caching guarantees that without
  * needing to trust a third-party library's determinism.
  *
  * Cech's own value over Vietoris-Rips is exactly this quantity: unlike VR's purely combinatorial max-pairwise-
  * distance, the Cech radius needs the vertices' real coordinates and a minimum-enclosing-ball computation (Welzl's
  * algorithm, via Miniball) -- not `alpha.AlphaComplexDQP`'s dual active-set QP, which answers a different question
  * (restricted-Delaunay membership, dependent on the whole point cloud, not just a simplex's own vertices).
  */
object CechFiltration:
  private def cechRadius(vertexCoords: Array[Array[Double]]): Double =
    if vertexCoords.length <= 1 then 0.0 // matches MaximumDistanceFiltrationValue's own dim<=0 convention
    else math.sqrt(Miniball(MiniballPointSet(vertexCoords)).squaredRadius())

  /** A fresh `PartialFunction` with its own private cache -- one call to `CechFiltration(...)` per stream instance, not
    * a shared/global cache, matching every other per-stream filtration value in this codebase.
    *
    * '''Monotonicity is explicitly enforced here, not merely trusted from the math''': the Cech radius is
    * mathematically non-decreasing under vertex insertion, but Miniball's raw floating-point output can violate this by
    * a few ULPs on near-degenerate inputs -- and `CellularHomologyContext`'s reduction requires it to hold exactly (see
    * CLAUDE.md's ordering-contract rule 3). Fixed by clamping every computed radius to at least the max of its own
    * facets' ALREADY-CACHED radii (a plain lookup, never a fresh Miniball call): every facet of any simplex this method
    * is asked about is guaranteed already cached, because `filtrationValue` is only ever queried on simplices the
    * coface loop is about to accept or has already generated, and (per `CechCofaceSimplexStream`'s own downward-closure
    * argument) an accepted simplex's facets are always visited, and hence cached, one dimension earlier.
    */
  def apply(euclideanMetricSpace: EuclideanMetricSpace): PartialFunction[Simplex[Int], Double] =
    // TrieMap, not mutable.HashMap: RipserCofaceSimplexStream's parallelFiltrationValue pre-warm step calls
    // this PartialFunction's apply concurrently from multiple threads when enabled -- see
    // .claude/WORKLOG-parallelization-survey.md item 2. TrieMap's getOrElseUpdate is a genuine drop-in (same
    // signature) backed by a lock-free Ctrie, safe for concurrent reads and writes; computeRadius's own
    // facet-floor lookups (cache.getOrElse, a plain read) are likewise safe concurrently, since the parallel
    // phase never writes a NEW entry mid-computation -- only already-complete lower-dimension entries are
    // ever read while dimension d's own candidates are being computed.
    val cache = TrieMap.empty[Simplex[Int], Double]
    def computeRadius(spx: Simplex[Int]): Double =
      val raw = cechRadius(spx.underlying.toArray.map(euclideanMetricSpace.pts))
      val facetFloor = spx.underlying.iterator
        .map(v => cache.getOrElse((spx.underlying - v).asSimplex, 0.0))
        .maxOption
        .getOrElse(0.0)
      math.max(raw, facetFloor)
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean =
        spx.forall(v => euclideanMetricSpace.contains(v))
      def apply(spx: Simplex[Int]): Double =
        cache.getOrElseUpdate(spx, if spx.dim <= 0 then 0.0 else computeRadius(spx))

/** The Cech complex, built the same way `RipserCofaceSimplexStream` builds Vietoris-Rips: dimension by dimension,
  * extending only cofaces of the PREVIOUS dimension's already-accepted simplices (never the full `binomial(n, d+1)`
  * power set) -- valid for Cech for a real, checked reason, not by analogy to VR: Cech is downward-closed (if a point
  * witnesses a simplex's balls having a common intersection, that same point trivially witnesses every subset's balls
  * having one too), so a valid Cech `(d+1)`-simplex's canonical generating facet (obtained by removing its own minimum
  * vertex, `RipserCofaceSimplexStream`'s own convention) is GUARANTEED to already be sitting in the accepted
  * `d`-dimensional cache -- the enumeration cannot silently skip a real Cech simplex. `CechStreamSpec`'s
  * enumeration-completeness check pins this empirically (brute-force `combinations(d+1).filter(cechValid)` count vs.
  * this class's own count), not just by the proof above.
  *
  * Deliberately NOT built on `IncrementalVietorisRipsSimplexStream` ("New-VR")'s Table-Lookup optimization (Algorithm
  * 2): that optimization's entire speed advantage comes from VR being a FLAG complex (simplex membership fully
  * determined by which vertex PAIRS are edges) -- Cech is not a flag complex (three balls can pairwise-overlap in three
  * different places with no common triple intersection), so pruning candidate vertices via graph structure the way
  * Table-Lookup does isn't valid here without its own from-scratch proof, for uncertain benefit.
  * `RipserCofaceSimplexStream`'s plainer "try every remaining vertex against every accepted lower-dimensional simplex,
  * filtered by the real criterion" shape needs no such proof: it stays correct for ANY downward-closed criterion, VR's
  * or Cech's.
  *
  * `maxFiltrationValue` is in CECH RADIUS units directly (not a VR diameter to be internally doubled) --
  * `RipserCofaceSimplexStream`'s inherited coface loop gates every candidate through `filtrationValue` directly (no
  * separate VR-diameter-based edge-graph precomputation is used once `iterateDimension` is overridden, so there is no
  * VR-units quantity anywhere in this class to convert from).
  */
class CechCofaceSimplexStream(
  val euclideanMetricSpace: EuclideanMetricSpace,
  keepCriterion: PartialFunction[Simplex[Int], Boolean] = { case _ => true },
  maxFiltrationValue: Option[Double] = None,
  // Threaded straight through to RipserCofaceSimplexStream's own parameter of the same name -- see its doc
  // there. Cech's own Miniball-based radius computation is exactly the case this exists for: unlike a
  // cheap arithmetic filtration value, a real per-candidate Miniball solve is worth parallelizing once a
  // complex is large enough. See .claude/WORKLOG-parallelization-survey.md item 2.
  parallelFiltrationValue: Boolean = false
) extends RipserCofaceSimplexStream(
      euclideanMetricSpace,
      keepCriterion,
      maxFiltrationValue,
      Some(CechFiltration(euclideanMetricSpace)),
      parallelFiltrationValue
    )
