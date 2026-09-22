package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import scala.collection.concurrent.TrieMap

import com.dreizak.miniball.model.PointSet
import com.dreizak.miniball.highdim.Miniball

/** Adapts a raw coordinate array to Miniball's `PointSet` interface -- structurally identical to `alpha.ScalaPointSet`,
  * but declared separately here rather than imported from `alpha`: `streams` is the more foundational package (`alpha`
  * already depends on `streams`, not the other way around -- see CLAUDE.md's "Package layout"), so reusing
  * `alpha.ScalaPointSet` here would introduce a backwards cross-package dependency for a 3-line adapter. See
  * `CechFiltration`'s own doc for why Miniball, not the DAQP/alpha-shape machinery, is the right primitive for Cech
  * specifically.
  */
private class MiniballPointSet(points: Array[Array[Double]]) extends PointSet:
  override def size: Int = points.length
  override def dimension: Int = points(0).length
  override def coord(i: Int, j: Int): Double = points(i)(j)

/** The Cech radius of a simplex: the true minimum-enclosing-ball radius of its vertices' coordinates, computed once per
  * simplex and cached forever (a deliberate, DOCUMENTED departure from `RipserCohomologyContext`'s "don't cache
  * filtration values by default" memory-frugality doctrine, not an oversight -- that doctrine exists because
  * `insertionDiameter` gives the VR diameter functional a cheap O(d) incremental recompute that makes NOT caching
  * viable; no equivalent incremental shortcut exists for the minimum-enclosing-ball radius here, so without a cache
  * this value would be recomputed via a full Miniball solve on every `keptByThresholdAndCriterion` filter check, every
  * `sortedByFiltration` sort, and every later `filtrationOrdering` comparison inside the homology reduction itself --
  * easily 3+ full solves per simplex).
  *
  * This caching is also a real CORRECTNESS safeguard, not just a speed one: Miniball is a randomized-incremental
  * algorithm, and while the minimum enclosing ball of a fixed point set is mathematically unique, a randomized
  * algorithm can in principle accumulate floating-point rounding differently across separate calls on the identical
  * input, returning bit-different radii. `filtrationOrdering`'s comparator needs one CONSISTENT answer per simplex to
  * stay a total order -- an inconsistency here is exactly the failure shape (a coface sorting inconsistently relative
  * to its own facet) that has produced three separate "reduction pivot ... was not a recorded open class" crashes
  * elsewhere in this codebase (see CLAUDE.md's "Bug found while cross-validating" section). Caching sidesteps the
  * question of whether Miniball is actually deterministic across calls, rather than resting correctness on trusting a
  * third-party library's internals.
  *
  * Cech's own value proposition over Vietoris-Rips is exactly this quantity: unlike VR's max-pairwise-distance (purely
  * combinatorial, needs no ambient geometry), the Cech radius genuinely needs the vertices' real coordinates and the
  * classical minimum-enclosing-ball computation -- Welzl's algorithm, which Miniball implements. This does NOT need
  * `alpha.AlphaComplexDQP`'s dual active-set QP machinery: that solver answers a strictly harder question (does this
  * simplex survive as a face of the RESTRICTED Delaunay/Voronoi complex, i.e. is there a witness point not closer to
  * any OTHER point in the cloud) that Cech has no analogue of -- Cech membership depends only on a simplex's OWN
  * vertices, never on what else is in the point cloud.
  */
object CechFiltration:
  private def cechRadius(vertexCoords: Array[Array[Double]]): Double =
    if vertexCoords.length <= 1 then 0.0 // matches MaximumDistanceFiltrationValue's own dim<=0 convention
    else math.sqrt(Miniball(MiniballPointSet(vertexCoords)).squaredRadius())

  /** A fresh `PartialFunction` with its own private cache -- one call to `CechFiltration(...)` per stream instance, not
    * a shared/global cache, matching every other per-stream filtration value in this codebase.
    *
    * '''Monotonicity is explicitly ENFORCED here, not merely trusted from the math''': Cech radius is mathematically
    * monotone non-decreasing under vertex insertion (adding a ball-intersection constraint can never shrink the minimum
    * enclosing ball), but Miniball's raw floating-point output can violate this by an ULP or two on near-degenerate
    * inputs -- confirmed directly (not hypothesized) while validating this class: a concrete random point cloud
    * produced a facet radius of 0.3887884477377332 and its own coface's radius as 0.3887884477377331, one ULP SMALLER.
    * `CellularHomologyContext`'s reduction requires this monotonicity to hold EXACTLY (its ascending-filtration
    * processing order is the same invariant three prior "reduction pivot ... was not a recorded open class" crashes
    * elsewhere in this codebase trace back to, see CLAUDE.md's "Bug found while cross-validating" section) -- confirmed
    * to reproduce that exact crash here before this fix. Fixed by clamping every computed radius to be at least the max
    * of its own facets' ALREADY-CACHED radii (a plain lookup, never a fresh Miniball call): every facet of any simplex
    * this method is ever asked about is guaranteed to already be cached by the time this runs, because
    * `filtrationValue` is only ever queried on simplices `RipserCofaceSimplexStream`'s coface loop is about to accept
    * or has already generated, and (per `CechCofaceSimplexStream`'s own downward-closure argument) an accepted
    * simplex's facets are always visited, and hence cached, at the dimension immediately below.
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
