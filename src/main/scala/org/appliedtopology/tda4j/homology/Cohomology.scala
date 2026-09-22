package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.appliedtopology.tda4j.barcode.PersistenceBar

import scala.collection.mutable

/** Persistent cohomology (Bauer's algorithm, arXiv:1908.02518) generic over `CellT: OrderedCell` -- the cohomology
  * counterpart to `CellularHomologyContext`, filling in what CLAUDE.md's own architecture notes call a real,
  * previously-unfilled asymmetry: cohomology in this codebase used to mean `RipserCohomologyContext`/
  * `PackedRipserCohomologyContext` only, both hardcoded to `Simplex[Int]` via `SimplexIndexing`'s combinatorial number
  * system. This class instead works for any `CellT: OrderedCell` this library has -- `Simplex`, `Cube`,
  * `FiniteSimplicialSet` generators alike -- including complexes that already use `Simplex[Int]` but aren't flag
  * complexes (Cech, Alpha), which the VR-specialized engines can't serve either way. See
  * `.claude/DESIGN-generic-cohomology.md` for the full design derivation (including an `advisor()` review and a later
  * correction dropping apparent pairs from the design entirely); this doc summarizes the load-bearing points, not the
  * exploration.
  *
  * '''The key idea''': the coboundary matrix persistent cohomology reduces is the transpose of the ordinary boundary
  * matrix, same coefficients -- if `tau.boundary` contains `(sigma, c)`, `sigma`'s coboundary contains `(tau, c)`.
  * Every stream this class targets (Cube, `FiniteSimplicialSet`, Cech, Alpha, and even ordinary `Simplex[Int]` VR
  * complexes at a size where the reference/oracle engines matter more than raw speed) already gets fully materialized
  * before persistence runs, unlike Vietoris-Rips at the scale `RipserCohomologyContext` targets -- so unlike that
  * class's elaborate `SimplexIndexing`/`insertionDiameter`/`sparseCofacets` apparatus (built specifically to avoid ever
  * materializing a combinatorially-exploding full flag complex), this class builds the coboundary relation directly, by
  * inverting each materialized cell's own already-generic `boundary[CoefficientT]` call -- no cell-type-specific
  * coboundary formula needed anywhere, and no dual `Cocell`/`OrderedCocell` typeclass either (removed from
  * `Chain.scala`, on the same understanding: coboundary is extrinsic to a cell, not intrinsic the way `boundary` is,
  * since it depends on which higher-dimensional cells actually exist in the ambient complex).
  *
  * '''No `maxDim` parameter''', unlike every other engine in this codebase's history -- deliberately, not by oversight:
  * this class simply computes cohomology up to whatever top dimension the materialized stream actually contains, which
  * deletes the whole "does `maxDim` mean top *built* or top *reported* degree" footgun class
  * (`CellularPersistenceInChunksContext`, `RipserCohomologyContext`, and `PackedRipserCohomologyContext` each had to
  * fix this exact bug once -- see `.claude/WORKLOG-maxdim-semantics-fix.md`) rather than reimplementing it a fourth
  * time. A caller wanting only `H_0..H_k` wraps the *input* stream first --
  * `LimitedCofaceSimplexStream(stream, k + 1)`, the mechanism `RipserCohomologySpec`'s own oracle and the MATLAB
  * facade's `engine=naive` path already use for exactly this -- so real `(k+1)`-dimensional cells exist to correctly
  * resolve whether a `k`-born class is finite or essential, and drops any `dim == k + 1` bars from the returned list
  * itself afterward.
  *
  * '''No apparent pairs''', also deliberately: Definition 3.2/Proposition 3.9's whole point is avoiding coboundary
  * *enumeration* for cells that turn out to be trivially paired -- and this class has no enumeration to avoid, because
  * it must materialize the coboundary relation for every cell up front just to have "coboundary" exist at all. What
  * would be left after porting the mutual-pair check (skip one `basis` write, skip one call into an already-cheap
  * `Chain.reduceBy` miss) is noise, plausibly a net loss once the pair- detection scan itself is counted, and not worth
  * the extra machinery. See the design doc's "What does NOT carry over" section for the full argument.
  *
  * '''Representatives''': every bar carries a V-column (tracked exactly the way
  * `RipserCohomologyContext.persistentCohomology` already does), satisfying this codebase's standing "every engine
  * needs generic `Field` + real representatives" principle automatically -- this is also this class's actual point, not
  * an afterthought: over a field the cohomology barcode is identical to the homology barcode (the reason Ripser
  * computes cohomology at all -- same answer, cheaper algorithm), so a bars-only version of this class would be
  * entirely redundant with `CellularHomologyContext`, which already covers every cell type this class does. Only an
  * ''essential'' bar's V-column is a genuine cocycle (`d(vcol) = 0`) by construction -- Algorithm 1's invariant is
  * `d(V_j) = R_j` throughout, and `R_j` is zero exactly when the bar is essential; a finite bar's V-column has
  * coboundary equal to its own nonzero reduced pivot chain instead (still a valid representative -- it witnesses the
  * class on the sub-level set strictly before the bar's death, since every term of that nonzero coboundary is born at
  * or after the death value -- just not a cocycle over the whole complex). `coboundaryOfChain` exists specifically so a
  * caller (in practice, a test) can verify this directly for essential bars (`coboundaryOfChain(rep, ...).isZero()`) --
  * something no engine in this codebase could check for Cube/`FiniteSimplicialSet`/Cech/Alpha before this class
  * existed, since none of them ever had a cocycle representative to check in the first place.
  */
class CellularCohomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:
  import barcode.*

  /** Full persistent cohomology of `stream`, one dimension-band coboundary block at a time (built by inverting
    * `boundary`, then discarded once that dimension's cells are all processed -- the block is a fresh local `val` per
    * loop iteration, so nothing needs an explicit "discard" beyond simply not keeping a reference around; peak memory
    * is bounded by the single largest block, not the whole coboundary matrix).
    *
    * `cohomologyOrdering` is built explicitly here (ascending filtration value, then the stream's own
    * `filtrationOrdering` unreversed as tie-break), never via `stream.filtrationOrdering.reverse` -- `.reverse` on that
    * whole ordering would flip its dimension and within-dimension tie-break too, not just the filtration-value key (the
    * same hazard `CellularHomologyContext.processingOrder`'s own doc documents and works around). Safe here
    * specifically because this algorithm never compares cells of different dimensions under `cohomologyOrdering` --
    * every sort and every `Chain.reduceBy` call below operates within one dimension band at a time, by construction of
    * the per-dimension loop -- unlike `CellularHomologyContext`, which genuinely needs a single cross-dimension pivot
    * table and therefore needs the more careful construction it uses.
    *
    * `cohomologyOrdering` is summoned as a `given` right here, before anything that constructs a `Chain` --
    * `Chain.scala`'s own ambient `given [CellT: OrderedCell] => Ordering[CellT] = oCell.ordering` (filtration-blind)
    * would otherwise silently win at every `Chain.from`/`Chain.apply`/`RingModule` summon site below, exactly the
    * `chainRM`-summoned-too-early bug class `CellularHomologyContext` shipped once (see that class's own doc).
    */
  def persistentCohomology(
    stream: => CellStream[CellT, FiltrationT]
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    val theStream = stream
    val cellsByDim: Map[Int, Vector[CellT]] = theStream.iterator.toVector.groupBy(_.dim)

    if cellsByDim.isEmpty then List.empty
    else
      val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
      def cellFv(c: CellT): FiltrationT = fv.applyOrElse(c, (_: CellT) => theStream.smallest)

      given cohomologyOrdering: Ordering[CellT] =
        Ordering.by[CellT, FiltrationT](cellFv).orElse(theStream.filtrationOrdering)

      val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
      import chainRM.*

      val bars = mutable.ArrayDeque.empty[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]
      // Accumulated across every dimension, not per-dimension-rotated -- unlike
      // `PackedRipserCohomologyContext.activeCleared`, whose rotation exists only to work around a bare `Long`
      // combinatorial index colliding across differently-sized simplices. `CellT` values carry their own
      // dimension intrinsically (a `Simplex`/`Cube`/`FiniteSimplicialSet` generator of one dimension can never
      // structurally equal one of another), so a single flat set is safe -- the same reasoning
      // `RipserCohomologyContext.cleared` already relies on.
      val cleared: mutable.Set[CellT] = mutable.Set.empty
      val topDim = cellsByDim.keys.max
      // Belt-and-braces, not a genuine defensive necessity -- structurally impossible for every stream this
      // class targets (a `d`-cell forces its own faces, hence lower dimensions, to exist), unlike
      // `coboundaryOfChain`'s own `require`s, which sit on a real public boundary a caller can actually violate.
      // Kept anyway: a violation here would otherwise silently leave a whole dimension's coboundary block
      // empty rather than fail loudly, since `cellsByDim.getOrElse(d + 1, Vector.empty)` below treats a
      // missing dimension the same as a genuinely empty one.
      require(
        cellsByDim.keySet == (0 to topDim).toSet,
        s"CellularCohomologyContext requires a dimension-contiguous cell set (0..$topDim, no gaps), got " +
          s"dimensions ${cellsByDim.keySet.toSeq.sorted.mkString(", ")}"
      )

      // Runs through `topDim` itself, not `topDim - 1`: at `d == topDim`, `cellsByDim.getOrElse(d + 1, ...)` is
      // empty by construction (there is no higher dimension in the materialized stream), so every top-dimension
      // cell's coboundary is trivially empty and it opens an essential class unless already claimed as some
      // `topDim - 1` cell's pivot -- exactly `RipserCohomologyContext`'s own behavior at its requested top
      // dimension, reached here for free rather than via a separate post-loop special case.
      for d <- 0 to topDim do
        val coboundaryMap: mutable.Map[CellT, mutable.ArrayBuffer[(CellT, CoefficientT)]] = mutable.Map.empty
        cellsByDim.getOrElse(d + 1, Vector.empty).foreach { cof =>
          cof.boundary[CoefficientT].foreach { case (face, coeff) =>
            coboundaryMap.getOrElseUpdate(face, mutable.ArrayBuffer.empty) += ((cof, coeff))
          }
        }

        // Youngest first: Algorithm 1 processes columns in increasing [matrix] order, which under the
        // reversed coboundary matrix means decreasing real filtration order -- the same fact
        // `RipserCohomologyContext.persistentCohomology`'s own comment documents. `.reverse` here is safe: it's
        // applied only to already-single-dimension data (`cellsByDim(d)`), never spanning dimensions.
        val cellsAtD = cellsByDim.getOrElse(d, Vector.empty).sorted(using cohomologyOrdering.reverse)

        // Reset per dimension, mirroring both existing cohomology engines: dimension d's coboundary matrix
        // delta: C^d -> C^{d+1} is reduced independently of every other dimension's.
        val basis: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
        val generators: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty

        for sigma <- cellsAtD if !cleared.contains(sigma) do
          val sigmaFv = cellFv(sigma)
          val z = Chain.from(coboundaryMap.getOrElse(sigma, mutable.ArrayBuffer.empty).toSeq)
          // No `fallback` argument: its default (`_ => None`) is exactly right here, since there is no
          // apparent-pairs substitution to wire in -- see this class's own doc.
          val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty)
          val vcol: Chain[CellT, CoefficientT] = log.items.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
            acc - coeff ⊠ generators.getOrElse(
              pivot,
              throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
            )
          }
          vcol.collapseAll()
          if reduced.isZero() then
            bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), PositiveInfinity(), Some(vcol)))
          else
            val pivot = reduced.leadingCell.get
            basis(pivot) = reduced
            generators(pivot) = vcol
            cleared += pivot
            bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(cellFv(pivot)), Some(vcol)))
        // `coboundaryMap` goes out of scope here, at the end of this dimension's own iteration -- nothing
        // keeps it alive into the next one.

      bars.toList

  /** The coboundary of `chain` (a chain of dimension-`d` cells), computed against `cofacets` (the candidate
    * dimension-`(d+1)` cells to check) by the same boundary-inversion this class's own `persistentCohomology` uses
    * internally -- exposed publicly purely for verification, not as a hot-path method:
    * `coboundaryOfChain(representative, cellsAtDPlusOne).isZero()` is what makes a returned representative *checkable*
    * as a genuine cocycle, rather than merely present. `persistentCohomology`'s own per-dimension coboundary block is
    * discarded once that dimension's cells are processed (see its own doc), so this method rebuilds whatever it needs
    * from the supplied `cofacets` on demand rather than assuming any of that state is still around.
    *
    * Uses the ambient, filtration-blind `Ordering[CellT]` (`Chain.scala`'s `given` derived from `OrderedCell` itself),
    * not `cohomologyOrdering` -- deliberately: this method only needs *some* total order under which structurally-equal
    * cells collapse correctly for `isZero()`'s own purposes, not a filtration-consistent one (unlike
    * `persistentCohomology`'s internal pivot selection, where the specific ordering is load-bearing).
    *
    * `chain` must be homogeneous (every cell the same dimension `d`) and every cell `cofacets` yields must be dimension
    * `d + 1` -- both checked with `require`, not merely documented: this method has no way to detect a caller mixing
    * dimensions or passing the wrong band on its own (`Chain.from` over mismatched dimensions still type-checks and
    * silently computes a partial, meaningless sum), and every caller in this codebase already satisfies both
    * (`persistentCohomology`'s own internal use, and every test, always passes a single bar's own-dimension
    * representative alongside `cellsByDim(bar.dim + 1)`).
    */
  def coboundaryOfChain(
    chain: Chain[CellT, CoefficientT],
    cofacets: IterableOnce[CellT]
  ): Chain[CellT, CoefficientT] =
    val fr = summon[CoefficientT is Field]
    val chainMap: Map[CellT, CoefficientT] = chain.items.toMap
    require(
      chainMap.keys.map(_.dim).toSet.sizeIs <= 1,
      s"coboundaryOfChain requires a homogeneous chain (every cell the same dimension), got dimensions " +
        s"${chainMap.keys.map(_.dim).toSet.toSeq.sorted.mkString(", ")}"
    )
    val chainDim: Option[Int] = chainMap.keys.headOption.map(_.dim)
    val terms = mutable.ArrayBuffer.empty[(CellT, CoefficientT)]
    cofacets.iterator.foreach { cof =>
      require(
        chainDim.forall(_ + 1 == cof.dim),
        s"coboundaryOfChain requires every cofacet to be exactly one dimension above the chain " +
          s"(expected dimension ${chainDim.map(_ + 1)}, got a cofacet of dimension ${cof.dim})"
      )
      cof.boundary[CoefficientT].foreach { case (face, coeff) =>
        chainMap.get(face).foreach { chainCoeff =>
          terms += ((cof, fr.times(chainCoeff, coeff)))
        }
      }
    }
    Chain.from(terms.toSeq)
