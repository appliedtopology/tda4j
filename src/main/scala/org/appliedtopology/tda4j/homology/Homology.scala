package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}

import org.appliedtopology.tda4j.barcode.PersistenceBar

import collection.{immutable, mutable}
import scala.annotation.tailrec

import math.Fractional.Implicits.infixFractionalOps
import math.Ordering.Implicits.sortedSetOrdering

//class ReducedSimplicialHomologyContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]()
//  extends CellularHomologyContext[Simplex[VertexT], CoefficientT, FiltrationT]() {}

class SimplicialHomologyContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]()
    extends CellularHomologyContext[Simplex[VertexT], CoefficientT, FiltrationT] {}

/** Naive persistent homology via the standard single-pivot-table reduction algorithm: process cells in filtration
  * order, reduce each cell's boundary against the pivots recorded so far, and every cell either opens a class (reduced
  * boundary is zero) or closes one (reduced boundary is nonzero, and its leading cell -- the pivot -- is necessarily a
  * previously-opened, still-unpaired cell).
  *
  * No clearing, no chunking, no cohomology/twist optimization: this is the reference-grade baseline the other two
  * algorithms in this file (`PersistenceInChunksContext`, `SimplicialHomologyByDimensionContext`) can be
  * cross-validated against.
  *
  * Correctness note for future maintainers: the `RingModule`/`Ordering[CellT]` instances used for chain arithmetic MUST
  * be summoned inside `HomologyState`, not at `CellularHomologyContext` class scope. A `given Ordering[CellT]` derived
  * from a per-stream `filtrationOrdering` only exists once a stream is available (i.e. inside `HomologyState`);
  * summoning `Chain[CellT, CoefficientT] is RingModule` any earlier silently falls back to the generic,
  * filtration-blind `OrderedCell`-derived ordering and bakes it into that RingModule instance's closures permanently
  * (Scala resolves a given's own implicit parameters once, at the point the given is constructed, not at each later
  * call to its methods). That was a real, confirmed bug here: see WORKLOG-naive-homology.md.
  */
class CellularHomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

  import barcode.*

  case class HomologyState(
    boundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]], // pivot cell -> reduced boundary column
    generators: mutable.Map[CellT, Chain[CellT, CoefficientT]], // pivot cell -> V-column of its producing cell
    positives: mutable.Map[
      CellT,
      (FiltrationT, Chain[CellT, CoefficientT])
    ], // open cell -> (birth, representative cycle)
    stream: CellStream[CellT, FiltrationT],
    var current: FiltrationT,
    barcode: mutable.ArrayDeque[(Int, FiltrationT, FiltrationT, Chain[CellT, CoefficientT])]
  ):
    given Ordering[CellT] = stream.filtrationOrdering
    import Ordering.Implicits.infixOrderingOps
    given filtration: Filtration[CellT, FiltrationT] = stream

    // Summoned here, not at CellularHomologyContext scope -- see class doc above.
    val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
    import chainRM.*

    // NOT stream.iterator directly: for any StratifiedCellStream (every stream in this codebase besides a
    // hand-built .iterator override), that default is DIMENSION-MAJOR -- Iterator.from(0).takeWhile(...).flatMap
    // (iterateDimension), all of dimension d before any of dimension d+1. That's correct only WITHIN each
    // dimension's own bucket, which every stream here is careful to sort by filtrationOrdering.reverse -- but the
    // single shared pivot table this algorithm uses (boundaries/positives, populated across ALL dimensions
    // together, unlike RipserCohomologyContext's per-dimension-reset basis) requires cells consumed in TRUE
    // combined filtration order: a dimension-(d-1) cell can legitimately become a dimension-d cell's pivot, and
    // Algorithm 1's correctness proof (a cell is ever used as a pivot iff it opened a class of its own) depends
    // on processing ALL dimensions together in one oldest-to-youngest sequence, not per-dimension blocks.
    // Dimension-major order is NOT generally consistent with that: a higher-dimensional cell can have a SMALLER
    // (older) filtration value than an unrelated lower-dimensional one (there is no general monotonicity of
    // filtration value with dimension across UNRELATED simplices, only along a single face/coface chain), so
    // dimension-major order can process a "negative" (already paired downward) low-dimensional cell before a
    // higher-dimensional cell that legitimately needed it as an open pivot -- confirmed by direct reproduction:
    // a 15-point, ambientDim=3 cloud built to dimension 4 has real filtration-value inversions between dimension-3
    // and dimension-4 cells, and crashed identically (same pivot, same message) on two independently-implemented
    // streams (EnumeratingCofaceSimplexStream and IncrementalVietorisRipsSimplexStream/NewVR) that differ in every
    // other respect, which rules out a stream-specific bug and confirms this is a CellularHomologyContext defect.
    // Fixed by re-sorting the fully-materialized cell sequence -- NOT via `stream.filtrationOrdering.reverse`
    // wholesale (a first attempt at this fix, reverted): filtrationOrdering's tie-break is `(dimension, then
    // colex)` UNREVERSED even though its primary key (filtration value) IS reversed -- see
    // EnumeratingCofaceSimplexStream.filtrationOrdering's own doc, "ascending fv comparison ... then dimension ...
    // then colex", where only the fv comparison is written negated. `.reverse` on the WHOLE ordering therefore
    // flips the dimension tie-break too: under `.reverse` a tie sorts LARGER dimension first, so a triangle
    // processes before its own longest edge whenever they tie (which happens on every VR triangle, by
    // definition). That is exactly backwards from Algorithm 1's actual precondition (every cell's faces must
    // appear before it), and never surfaced from any single-dimension `.sorted(using filtrationOrdering.reverse)`
    // call site elsewhere in this codebase because within one dimension `x.size == y.size` makes that tie-break a
    // no-op -- this is the first place the comparator spans dimensions. Built explicitly instead: ascending
    // filtration value (oldest first, the correct direction, unreversed), THEN ascending dimension (faces before
    // cofaces on a tie), falling back to `stream.filtrationOrdering.reverse` only to inherit the established
    // within-dimension tie-break (colex) bit-for-bit -- safe to reuse there because two cells of the SAME
    // dimension never hit the dimension key above, so `.reverse`'s flipped dimension-ordering is never consulted.
    val processingOrder: Ordering[CellT] =
      Ordering
        .by[CellT, FiltrationT](c => stream.filtrationValue.applyOrElse(c, (_: CellT) => stream.smallest))
        .orElse(Ordering.by[CellT, Int](_.dim))
        .orElse(stream.filtrationOrdering.reverse)
    val CellIterator: collection.BufferedIterator[CellT] =
      stream.iterator.toVector.sorted(using processingOrder).iterator.buffered

    private def cellFiltrationValue(cell: CellT, fallback: FiltrationT): FiltrationT =
      stream.filtrationValue.applyOrElse(cell, (_: CellT) => fallback)

    // The persistence matching partitions every cell into POSITIVE (its own reduced boundary is zero -- a
    // creator, recorded in `positives` until matched) or NEGATIVE (its own reduced boundary is nonzero -- a
    // destroyer, matched immediately with the positive pivot it kills, recorded via `boundaries`/`generators`
    // keyed by THAT pivot, never by itself). A cell is never both, and `boundaries` only ever holds POSITIVE
    // (matched) cells as keys -- so when a LATER, higher-dimension column's reduction cascades down and its
    // current leading term is a cell that is itself NEGATIVE, `boundaries.get` correctly finds nothing, but
    // that does NOT mean reduction is finished: a negative cell was already matched (as a destroyer), so it
    // is not an available pivot -- but it also isn't an inexpressible dead end, since it has its own V-column
    // (a chain in ITS OWN dimension whose leading term is the cell itself, by the same construction as any
    // positive cell's `generators` entry) recorded right below at the moment it went negative. Recording that
    // here, keyed by the negative cell itself, and threading it through `Chain.reduceBy`'s existing `fallback`
    // hook (the same mechanism `RipserCohomologyContext` already uses for its own apparent-pairs on-the-fly
    // substitution -- see that class's `zeroApparentFacet`) lets reduction substitute and continue past a
    // negative cell exactly as it already does past a positive one, instead of wrongly treating "not a
    // positive pivot" as "reduction is done." See WORKLOG-reference-engine-fix.md for the derivation.
    val negativeVCols: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty

    def advanceOne(): Unit =
      if CellIterator.hasNext then
        val sigma: CellT = CellIterator.next()
        val dsigma: Chain[CellT, CoefficientT] = Chain.from(sigma.boundary[CoefficientT])
        // Chain.reduceBy (the SortedMap-based object-level primitive shared with
        // PersistenceInChunksContext), not a hand-rolled reduction over raw Chain arithmetic: `-`/`⊠`
        // on Chain objects only collapse the *head* of their internal priority queue lazily, so a
        // hand-rolled fold accumulating many raw subtractions builds up an ever-growing backlog of
        // uncollapsed duplicate entries -- fine for the tiny hand-built fixtures this was first tested
        // against, but quadratic-to-worse blowup on a real (even small) Vietoris-Rips stream, confirmed
        // directly (a first attempt at this method hung/burned CPU for minutes on an 8-12 point VR
        // complex that should take milliseconds). Chain.reduceBy works through a SortedMap internally,
        // which collapses duplicates by construction on every insertion. `fallback = negativeVCols.get`
        // lets reduction substitute past an already-matched NEGATIVE cell too -- see the field's own doc.
        val (reduced, log) = Chain.reduceBy(dsigma, boundaries, Chain.empty, fallback = negativeVCols.get)
        // V-column: sigma minus, for every pivot the reduction subtracted off, that pivot's own
        // producing cell's V-column. If ∂sigma reduced to zero this chain is itself the new cycle
        // representative; either way it becomes the generator future cells reduce through if sigma
        // itself goes on to become a pivot. Collapsed explicitly before use for the same reason as
        // above: this fold also accumulates through raw Chain subtraction.
        val vcol: Chain[CellT, CoefficientT] = log.items.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
          // `log` now names two structurally different kinds of elimination (since reduceLoop's fallback
          // was wired up above), and they need different treatment here:
          //  - eliminated via `boundaries` (a POSITIVE, matched pivot): `boundaries(pivot) = d(generators
          //    (pivot))` by construction (the killer's own V-column's boundary), so substituting it into
          //    dsigma's reduction corresponds to a REAL change in sigma's own identity -- vcol must apply
          //    the matching correction, or `d(vcol) = reduced` (the invariant every later generators/
          //    negativeVCols lookup depends on) breaks.
          //  - eliminated via `negativeVCols` (a NEGATIVE cell's own fallback substitution): this is a
          //    PURE basis change WITHIN dsigma's own dimension (re-expressing one raw cell via earlier
          //    same-dimension cells -- see negativeVCols' own doc) with no dimension shift and no relation
          //    to sigma's identity at all. The underlying value of dsigma's reduction is unchanged by it,
          //    only its expression is, so d(vcol) = reduced already holds without any vcol correction here
          //    -- applying one anyway would be wrong, not merely redundant.
          // `generators`/`negativeVCols` are disjoint keys (the persistence matching: a cell is never both
          // positive-matched and negative), so checking negativeVCols first is unambiguous.
          if negativeVCols.contains(pivot) then acc
          else
            acc - coeff ⊠ generators.getOrElse(
              pivot,
              throw new IllegalStateException(s"pivot $pivot has a boundaries entry but no generators entry")
            )
        }
        // Collapse BEFORE either write below, not after: `generators`/`positives` entries are read
        // back into future cells' own vcol folds (line ~86 above), so an uncollapsed value stored here
        // would make every subsequent generation's fold re-accumulate this cell's own backlog on top
        // of its own -- silently reintroducing the superlinear blowup this collapse call exists to
        // prevent (see WORKLOG-naive-homology.md). Moving this call after the writes below is a
        // regression, not a refactor.
        vcol.collapseAll()
        if reduced.isZero() then
          // sigma's boundary was fully cancelled by already-recorded pivots: sigma is a new,
          // as-yet-unpaired positive cell -- a class is born here, represented by vcol. A cell with
          // no known filtration value is assumed to have happened as early as possible (smallest),
          // matching the essential-bar convention in diagramWithGeneratorsAt below.
          val birthFv = cellFiltrationValue(sigma, filtration.smallest)
          positives(sigma) = (birthFv, vcol)
          current = birthFv
        else
          // reduced is nonzero, so its leading cell is a pivot: by construction (reduction only
          // stops at a cell no earlier column has claimed as a pivot) that pivot must be a
          // currently-open positive cell -- the one sigma's arrival pairs off and kills.
          val pivot = reduced.leadingCell.get
          boundaries(pivot) = reduced
          generators(pivot) = vcol
          // sigma itself is NEGATIVE (matched, as the destroyer of pivot's class) -- record its own
          // V-column (leading term = sigma, see this map's own doc above) so a later, higher-dimension
          // reduction that cascades onto sigma can substitute via Chain.reduceBy's fallback instead of
          // wrongly treating it as an unrecorded, still-open pivot.
          negativeVCols(sigma) = vcol
          val (pivotFv, representative) = positives
            .remove(pivot)
            .getOrElse(
              throw new IllegalStateException(
                s"reduction pivot $pivot was not a recorded open class -- reduction invariant violated"
              )
            )
          // A death cell with no known filtration value is assumed to have happened as late as
          // possible (largest) -- the opposite fallback direction from a birth, so an unknown death
          // is never silently placed before its own (already-recorded) birth.
          val deathFv = cellFiltrationValue(sigma, filtration.largest)
          barcode.append((pivot.dim, pivotFv, deathFv, representative))
          current = deathFv

    def advanceTo(f: FiltrationT): Unit =
      // Closed-birth/open-death convention: a cell exactly at f has already occurred by the time we
      // query at f, so it must be consumed (hence f >= headFv, not the previous strict f > headFv). A
      // cell with no known filtration value is assumed to have happened already (smallest), so it
      // always gets consumed eagerly.
      while CellIterator.hasNext && f >= cellFiltrationValue(CellIterator.head, filtration.smallest) do advanceOne()

    def advanceAll(): Unit =
      while CellIterator.hasNext do advanceOne()

    /** Full diagram at f, each bar annotated with its representative cycle (the class's generator at birth for a
      * finished bar; the still-open generator for an essential class).
      *
      * Query values across successive calls must be non-decreasing: this mutates state by advancing the underlying
      * stream, never rewinding it, so `diagramAt(3.0)` followed by `diagramAt(1.0)` does not recompute the state as of
      * 1.0 -- it reports whatever was still open at 3.0 as if newly queried at 1.0. Pre-existing behavior, inherited
      * unchanged from the algorithm this replaces.
      */
    def diagramWithGeneratorsAt(f: FiltrationT): List[(Int, FiltrationT, FiltrationT, Chain[CellT, CoefficientT])] =
      advanceTo(f)
      val finished: List[(Int, FiltrationT, FiltrationT, Chain[CellT, CoefficientT])] =
        barcode.toList.collect { case (dim, lower, upper, rep) if lower <= f => (dim, lower, upper.min(f), rep) }
      // A class still open at query time f is only truly essential (dies at +infinity) once the
      // whole stream is exhausted; mid-stream it merely hasn't died *yet*, so its death is capped at
      // the query value f rather than reported as infinite.
      val essentialUpper: FiltrationT = if CellIterator.hasNext then f else filtration.largest
      val essential: List[(Int, FiltrationT, FiltrationT, Chain[CellT, CoefficientT])] =
        positives.toList.collect {
          case (sigma, (birth, rep)) if birth <= f => (sigma.dim, birth, essentialUpper, rep)
        }
      finished ++ essential

    def diagramAt(f: FiltrationT): List[(Int, FiltrationT, FiltrationT)] =
      diagramWithGeneratorsAt(f).map { case (dim, lower, upper, _) => (dim, lower, upper) }

    def barcodeAt(f: FiltrationT): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
      diagramWithGeneratorsAt(f).map { (dim, l, u, rep) =>
        val lower: BarcodeEndpoint[FiltrationT] = l match
          case i if i == filtration.smallest => NegativeInfinity()
          case f: FiltrationT                => ClosedEndpoint(f)
        val upper: BarcodeEndpoint[FiltrationT] = u match
          case i if i == filtration.largest => PositiveInfinity()
          case f: FiltrationT               => OpenEndpoint(f)
        new PersistenceBar(dim, lower, upper, Some(rep))
      }

  def persistentHomology(stream: => CellStream[CellT, FiltrationT]): HomologyState =
    HomologyState(
      mutable.Map.empty, // boundaries: pivot -> reduced column
      mutable.Map.empty, // generators: pivot -> producing cell's V-column
      mutable.Map.empty, // positives: open cell -> (birth value, representative cycle)
      stream,
      stream.smallest: FiltrationT,
      mutable.ArrayDeque.empty
    )

/** `maxDim` means "top homological degree reported," not "top simplex dimension built" -- fixed at the source, the same
  * fix and for the same reason as `RipserCohomologyContext`'s own `maxDimension` (see
  * `.claude/WORKLOG-maxdim-semantics-fix.md`). This is homology, not cohomology, so the mirror-image fact holds:
  * correctly determining whether a class BORN at dimension `maxDim` is essential or killed requires considering real
  * `(maxDim + 1)`-dimensional cells' own boundaries (a `(maxDim+1)`-simplex's boundary reduces to a dimension-`maxDim`
  * pivot exactly when it kills that class) -- without them, every dimension-`maxDim` class was unconditionally
  * essential, since no cell of the stream was ever considered that could possibly pair against it. Fixed by internally
  * walking `0.to(maxDim + 1)` (both in `allCells`'s construction and both loops in `advanceAll`) instead of
  * `0.to(maxDim)`, so `(maxDim + 1)`-cells DO get locally/globally reduced and CAN correctly kill a `maxDim`-born class
  * -- and filtering `diagramAt`'s essential-bar output back down to `sigma.dim <= maxDim` (finite bars need no
  * equivalent filter: `recordPair`'s `barDim = pivot.dim`, and a pivot is always one dimension below its killer, so
  * `barDim <= maxDim` automatically whenever the killer's own dimension is `<= maxDim + 1`). `(maxDim+1)`-cells that
  * themselves end up looking essential (nothing of dimension `maxDim + 2` was ever considered to check) are
  * deliberately left in `essentialSimplices` internally (later pairing logic in `recordPair` needs an accurate view
  * across all live dimensions) and only excluded at this final reporting boundary, never a filter applied earlier.
  */
class CellularPersistenceInChunksContext[CellT: OrderedCell, CoefficientT: Field](maxDim: Int = 5):
  val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
  import chainRM.*

  // The real internal ceiling: one dimension higher than what's reported, so a class born AT maxDim can still be
  // correctly killed by a genuine (maxDim + 1)-cell rather than looking essential purely because nothing above
  // maxDim was ever considered. See the class doc above.
  private val internalMaxDim: Int = maxDim + 1

  case class HomologyState(
    boundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    stream: StratifiedCellStream[CellT, Double],
    barcode: mutable.Map[Int, immutable.Queue[(Double, Double, Chain[CellT, CoefficientT])]]
  ):

    given Ordering[CellT] = stream.filtrationOrdering

    // top-down state:
    //   cleared ------------- simplices paired as pivots (positive side); their column is implicitly zero
    //   paired -------------- simplices paired as σ (negative side); already recorded a bar
    //   essentialSimplices -- so-far unpaired classes
    val cleared: mutable.Set[CellT] = mutable.Set.empty
    val paired: mutable.Set[CellT] = mutable.Set.empty
    val essentialSimplices: mutable.Set[CellT] = mutable.Set.empty

    // start from max dimension instead for clearing's sake
    /*
    val maxDim: Int =
      var d = 0
      while stream.iterateDimension.isDefinedAt(d + 1) do d += 1
      d
     */

    // build index map to support chunk boundary calculation.
    // Note: stream.iterator would walk the stream's own full natural bound (now that
    // StratifiedCellStream's default .iterator is fixed, see its doc), which may be looser than
    // internalMaxDim this context was asked for -- walk dimensions explicitly instead so internalMaxDim is
    // enforced regardless of what the stream itself would otherwise produce. Walks to internalMaxDim
    // (maxDim + 1), not maxDim -- see the class doc above for why the extra dimension is needed.
    val allCells: Vector[CellT] =
      0.to(internalMaxDim)
        .iterator
        .flatMap { d =>
          stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)
        }
        .toVector
    val cellIndex: Map[CellT, Int] = allCells.zipWithIndex.toMap
    val chunkSize: Int = math.max(1, math.sqrt(allCells.size.toDouble).floor.toInt)

    // killer column index for each local pivot
    val killer: mutable.Map[CellT, CellT] = mutable.Map.empty
    // R supplies R_k for unpaired column k, to be used in marking active entries
    val R: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
    // active entries
    val activeRows: mutable.Map[CellT, Boolean] = mutable.Map.empty

    def diagramAt(f: Double): List[(Int, Double, Double)] =
      advanceAll()

      val pairs: List[(Int, Double, Double)] =
        barcode.toList.flatMap { case (dim, bars) =>
          bars.toList.collect {
            case (lower, upper, _) if lower <= f => (dim, lower, upper min f)
          }
        }

      // Filtered to sigma.dim <= maxDim: a dimension-(maxDim + 1) cell can end up in essentialSimplices too
      // (nothing of dimension maxDim + 2 was ever considered to possibly kill IT), but that's scaffolding for
      // correctly resolving maxDim, not information the caller asked for -- see the class doc above. Finite
      // bars need no equivalent filter (recordPair's barDim = pivot.dim is always <= maxDim already).
      val essentialBars: List[(Int, Double, Double)] =
        essentialSimplices.toList.filter(_.dim <= maxDim).map { sigma =>
          val lower =
            stream.filtrationValue.applyOrElse(sigma, (_: CellT) => Double.NegativeInfinity)
          (sigma.dim, lower, Double.PositiveInfinity)
        }

      pairs ++ essentialBars

    def recordPair(sigma: CellT, dsigmaReduced: Chain[CellT, CoefficientT]): Unit =
      val pivot = dsigmaReduced.leadingCell.get
      boundaries(pivot) = dsigmaReduced
      cleared += pivot
      paired += sigma
      killer(pivot) = sigma
      // if the pivot was previously marked essential (e.g. by an earlier local pass),
      // promote it to a paired class now
      essentialSimplices -= pivot
      essentialSimplices -= sigma
      val lower =
        stream.filtrationValue.applyOrElse(pivot, (_: CellT) => Double.NegativeInfinity)
      val upper =
        stream.filtrationValue.applyOrElse(sigma, (_: CellT) => Double.PositiveInfinity)
      val barDim = pivot.dim
      barcode(barDim) = barcode
        .getOrElse(barDim, immutable.Queue.empty)
        .appended((lower, upper, dsigmaReduced))

    def processCell(sigma: CellT, stop: CellT => Boolean): Unit =
      if cleared.contains(sigma) || paired.contains(sigma) then ()
      else
        // rebuild the boundary chain under the local filtration ordering — the chain
        // returned by sigma.boundary is ordered by Simplex.scala's default (lex) ordering,
        // not stream.filtrationOrdering, which gives wrong pivots in top-down.
        val dsigma: Chain[CellT, CoefficientT] =
          Chain.from(sigma.boundary[CoefficientT])
        val (dsigmaReduced, _) =
          Chain.reduceByUntil(dsigma, boundaries, Chain.empty, stop)
        if dsigmaReduced.isZero() then
          R -= sigma
          essentialSimplices += sigma
        else
          R(sigma) = dsigmaReduced
          val pivot = dsigmaReduced.leadingCell.get
          // only record when the pivot is local (i.e. stop didn't fire)
          if !stop(pivot) then recordPair(sigma, dsigmaReduced)

    def markActiveEntries(): Unit =
      activeRows.clear()
      val activeColumns: mutable.Map[CellT, Boolean] = mutable.Map.empty

      def markColumn(k: CellT): Boolean =
        activeColumns.get(k) match
          case Some(b) => b
          case None    =>
            activeColumns(k) = false
            var isActive = false
            val Rk = R.getOrElse(k, Chain.empty)
            // Every row in Rk needs to be individually classified into activeRows (compress()/globalReduce
            // later look each one up independently) -- this must be a full scan, not stop at the first
            // active row. A `takeWhile(_ => !isActive)` short-circuit used to sit here: once any one row
            // set isActive, every later row in the same chain was silently left unclassified, defaulting
            // to "inactive" wherever activeRows is read. Changed to a full scan on general principle while
            // root-causing a real, confirmed PersistenceInChunksContext bug (see eliminationFallback's own
            // doc, and WORKLOG-benchmark-and-chunks-bug.md section 5, for the actual mechanism that was
            // found and fixed) -- this specific change wasn't shown to be load-bearing for that bug by
            // itself, but marking MORE rows active is the conservative direction to err in: an inactive row
            // gets deleted outright by eliminationFallback (Chain(l) self-cancel), while an active row
            // substitutes its killer's full column, so under-marking risks silently dropping real content
            // and over-marking only costs an extra (still-correct) substitution.
            Rk.items.iterator.foreach { case (i, _) =>
              if !cleared.contains(i) && !paired.contains(i) then
                activeRows(i) = true
                isActive = true
              // i is unpaired (global)
              else if cleared.contains(i) then
                killer.get(i).foreach { j =>
                  if j != k && markColumn(j) then
                    activeRows(i) = true
                    isActive = true
                }
            // else i is paired (negative side of local pair)
            }
            activeColumns(k) = isActive
            isActive

      for k <- R.keys do markColumn(k)

    // Shared by compress (Algorithm 4) and globalReduce (Algorithm 5): a cleared row substitutes its
    // killer's own chain if active, else self-cancels ("compression" -- the row is provably irrelevant
    // downstream); a paired row self-cancels if inactive, or is left alone (no killer exists for a paired
    // cell, so there is nothing to substitute) if active -- an active paired row is a legitimate potential
    // final pivot for Rk.
    //
    // globalReduce's own reduction (against `boundaries`, which only ever holds *cleared* pivots) can
    // expose a *paired* cell as a new term partway through -- e.g. substituting a cleared cell's boundary
    // can introduce one of ITS OWN non-pivot terms, and that term can be a cell already used as someone
    // else's killer. Passing only `boundaries` there (no fallback) left such a term unhandled: it could
    // survive as Rk's final leading term even though it was already `paired`, and recordPair then added it
    // to `cleared` while still in `paired`, corrupting the pivot/pairing invariant. compress and
    // globalReduce must therefore share this exact elimination rule, not just the same `boundaries` map --
    // found via EngineComparisonBenchmarkSpec's filtrationOrdering regression tests, which (once the
    // ordering bug itself was fixed) exposed this as a second, unrelated PersistenceInChunksContext bug
    // reproducing even on the well-established EnumeratingCofaceSimplexStream (see CLAUDE.md): it silently
    // dropped essential classes at a bounded maxDim whenever this cross-step gap was hit.
    def eliminationFallback(l: CellT): Option[Chain[CellT, CoefficientT]] =
      if cleared.contains(l) then
        if activeRows.getOrElse(l, false) then killer.get(l).map(j => R.getOrElse(j, Chain.empty))
        else Some(Chain(l))
      else if paired.contains(l) then
        if activeRows.getOrElse(l, false) then None
        else Some(Chain(l))
      else None

    // Algorithm 4: global column compression from clear-and-compress paper.
    // The paper writes this over Z/2; over a general field we have to scale the
    // killer column by the right ratio.
    //
    // Implemented via Chain.reduceByUntil's own fixpoint loop (the same primitive processCell/globalReduce
    // use), NOT a hand-rolled single pass over Rk.items.toSeq -- an earlier version took that static
    // snapshot once and mutated Rk inside the loop body, so any term newly introduced by a substitution
    // was silently never itself eliminated (see eliminationFallback's doc above for the concrete failure
    // this caused).
    def compress(k: CellT): Unit =
      val Rk: Chain[CellT, CoefficientT] = R.getOrElse(k, Chain.empty)
      val (reduced, _) = Chain.reduceByUntil(
        Rk,
        mutable.Map.empty,
        Chain.empty,
        stop = (_: CellT) => false,
        fallback = eliminationFallback
      )
      R(k) = reduced

    // Algorithm 5 (lines 9-16): reduce the (now-compressed) global column k and record any pair found.
    def globalReduce(sigma: CellT): Unit =
      if cleared.contains(sigma) || paired.contains(sigma) then return
      // If R(sigma) was never stored, sigma was locally essential and has nothing to reduce.
      R.get(sigma) match
        case None         => () // stays in essentialSimplices unless a higher-dim sigma globally pairs with it
        case Some(rSigma) =>
          val noStop: CellT => Boolean = _ => false
          val (dsigmaReduced, _) =
            Chain.reduceByUntil(rSigma, boundaries, Chain.empty, noStop, fallback = eliminationFallback)
          R(sigma) = dsigmaReduced
          if dsigmaReduced.isZero() then
            R.remove(sigma)
            essentialSimplices += sigma
          else recordPair(sigma, dsigmaReduced)

    def advanceAll(): Unit =
      val n: Int = allCells.size
      val m: Int = (n + chunkSize - 1) / chunkSize

      val chunks: IndexedSeq[IndexedSeq[CellT]] =
        allCells.grouped(chunkSize).toIndexedSeq

      // Algorithm 2: local_reduction from clear-and-compress paper. Walks to internalMaxDim (maxDim + 1), not
      // maxDim -- see the class doc above for why the extra dimension is needed.
      for delta <- internalMaxDim.to(0, -1) do
        for r <- 1.to(2) do
          for b <- (r - 1).until(m) do // parallelizable!
            val floorIdx: Int = math.max(0, (b - r + 1) * chunkSize)
            val stop: CellT => Boolean =
              sigma => cellIndex.getOrElse(sigma, -1) < floorIdx
            for sigma <- chunks(b) if sigma.dim == delta do processCell(sigma, stop)

      // Algorithm 3: mark_active_entries from clear-and-compress paper
      markActiveEntries()

      // Algorithm 5 (Persistence in chunks): per dim top-down, compress unpaired
      // global columns then reduce them. Clearing keeps positives' columns at zero.
      // Walks to internalMaxDim (maxDim + 1), not maxDim -- see the class doc above for why.
      for delta <- internalMaxDim.to(0, -1) do
        val cellsAtDim =
          stream.iterateDimension.applyOrElse(delta, (_: Int) => Iterator.empty).toVector
        // step 2: compress unpaired global columns
        for sigma <- cellsAtDim do
          if !cleared.contains(sigma) && !paired.contains(sigma) && R.contains(sigma) then compress(sigma)
        // step 3: reduce the compressed global columns and record pairs
        for sigma <- cellsAtDim do globalReduce(sigma)

  def persistentHomology(stream: => StratifiedCellStream[CellT, Double]): HomologyState =
    HomologyState(
      mutable.Map.empty,
      stream,
      mutable.Map.empty
    )

/** Thin `Simplex`-specific wrapper around `CellularPersistenceInChunksContext`, exactly mirroring
  * `SimplicialHomologyContext`'s relationship to `CellularHomologyContext` above -- every existing call site
  * (`PersistenceInChunksContext[Int, Double](...)` etc.) keeps working unchanged, since the generic engine itself has
  * no `Simplex`-specific behavior anywhere in its body: everything goes through the generic `OrderedCell` interface
  * (`.dim`, `.boundary[CoefficientT]`), so genericizing was a pure type-annotation change, not a behavior change.
  * `Simplex[VertexT] is OrderedCell` resolves automatically here from `Ordering[VertexT]` alone
  * (`default_Simplex_is_OrderedCell`, `SimplexOrderedCell.scala`), same as `SimplicialHomologyContext` already relies
  * on. See `.claude/WORKLOG-simplicial-set-filtration.md`.
  */
class PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5)
    extends CellularPersistenceInChunksContext[Simplex[VertexT], CoefficientT](maxDim) {}

class SimplicialHomologyByDimensionContext[VertexT: Ordering, CoefficientT: Field]:
  case class HomologyState(
    cycles: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
    cyclesBornBy: mutable.Map[Simplex[VertexT], Simplex[VertexT]],
    boundaries: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
    boundariesBornBy: mutable.Map[Simplex[VertexT], Simplex[VertexT]],
    coboundaries: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
    stream: StratifiedCellStream[Simplex[VertexT], Double],
    var current: Double,
    var currentDim: Int,
    var currentIterator: collection.BufferedIterator[Simplex[VertexT]],
    barcode: mutable.Map[Int, immutable.Queue[(Double, Double, Chain[Simplex[VertexT], CoefficientT])]]
  ):
    // Must be summoned before chainRM below, and must shadow the generic lexicographic
    // Simplex is OrderedCell ordering -- see CellularHomologyContext's class doc for why a stale,
    // filtration-blind ordering baked into chain arithmetic is a real, previously-confirmed bug class.
    given Ordering[Simplex[VertexT]] = stream.filtrationOrdering
    val chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]
    import chainRM.*

    // first off, all vertices are immediately cycles
    cycles.addAll(stream.iterateDimension(0).map(cell => cell -> Chain(cell)))
    cyclesBornBy.addAll(cycles.map((cell, chain) => cell -> cell))

    // Secondly, dimension 0 and the births of dimension 1 can be read off directly: one pass over the
    // stream's own dimension-1 cells, in filtration order (already guaranteed by the stream contract, so
    // no separate sort is needed), each reduced through the exact same Chain.reduceBy primitive advanceOne
    // uses for every other dimension. This is Kruskal's algorithm (a tree edge merges two components and
    // kills the younger one's class; a non-tree edge births a new 1-cycle), but deliberately NOT routed
    // through the Kruskal class: that class (a) reconstructs a complete graph from a pairwise distance
    // function (wrong whenever the complex's 1-skeleton isn't complete -- alpha complexes, thresholded VR),
    // and (b) computes its entire union-find result eagerly at construction time, which discards which
    // vertex was the open root AT THE TIME each specific edge was processed -- exactly what the elder rule
    // needs. An earlier version of this method used Kruskal.mstIterator/.cyclesIterator directly and took
    // dEdge.leadingCell.get (the edge's own two endpoints) as the dying vertex; that crashed with
    // NoSuchElementException as soon as a second tree edge touched a vertex already killed by an earlier
    // one in the same pass, because the true dying vertex after a cascade is the reduced pivot, not
    // necessarily either of the edge's own two endpoints. Routing every edge through Chain.reduceBy against
    // `boundaries` (exactly like advanceOne below) makes this provably the same computation as the general
    // algorithm applied one dimension early, not a hand-rolled shortcut that could silently diverge from
    // it -- see SimplicialHomologyByDimensionSpec for the cross-validation this now passes.
    stream.iterateDimension.applyOrElse(1, (_: Int) => Iterator.empty).foreach { edge =>
      val fr = summon[CoefficientT is Field]
      val dEdge: Chain[Simplex[VertexT], CoefficientT] = Chain.from(edge.boundary)
      val (reduced, reductionLog) = Chain.reduceBy(dEdge, boundaries, Chain.empty)
      val coboundary: Chain[Simplex[VertexT], CoefficientT] =
        reductionLog.items.foldRight(fr.negate(fr.one) ⊠ Chain(edge)) { (item, acc) =>
          val (spx, coeff) = item
          if coboundaries.contains(spx) then acc + coeff ⊠ coboundaries(spx)
          else acc
        }
      if reduced.isZero() then
        // this edge closes a loop: a new 1-dimensional cycle is born
        cycles(coboundary.leadingCell.get) = coboundary
        cyclesBornBy(coboundary.leadingCell.get) = edge
      else
        // this edge connects two previously-separate components: the younger one's H0 class dies
        val dyingVertex = reduced.leadingCell.get
        boundaries(dyingVertex) = reduced
        coboundaries(dyingVertex) = coboundary
        barcode(0) = barcode
          .getOrElse(0, immutable.Queue.empty)
          .appended((stream.filtrationValue(dyingVertex), stream.filtrationValue(edge), cycles(dyingVertex)))
        cycles.remove(dyingVertex)
    }

    // Setup is done, we should be ready to start dimension 2. `current` must stay at "nothing real
    // processed yet" (stream.smallest, i.e. -Infinity), not +Infinity: advanceTo's `f > current` guard
    // below needs this to be less than any real query value `f` (including its own +Infinity default),
    // or the very first advanceTo call would never enter its loop at all -- a real, previously-confirmed
    // bug found via SimplicialHomologyByDimensionSpec's cross-validation (dimension-2+ cells were never
    // processed, so no bar above dimension 1 was ever recorded).
    currentDim = 1
    current = stream.smallest

    def advanceOne(): Unit =
      if currentIterator.hasNext then
        val fr = summon[CoefficientT is Field]
        val sigma = currentIterator.next()
        val dsigma: Chain[Simplex[VertexT], CoefficientT] = Chain.from(sigma.boundary)
        val (dsigmaReduced, reduction) = Chain.reduceBy(dsigma, boundaries, Chain.empty)
        val coboundary = reduction.items.foldRight(fr.negate(fr.one) ⊠ Chain(sigma)) { (next, acc) =>
          val (spx, coeff) = next
          if coboundaries.contains(spx) then acc + coeff ⊠ coboundaries(spx)
          else acc
        }
        if dsigmaReduced.isZero() then
          // adding a boundary to a boundary creates a new cycle as sigma + whatever whose boundary eliminated dsigma
          cycles(coboundary.leadingCell.get) = coboundary
          cyclesBornBy(coboundary.leadingCell.get) = sigma
        else
          // we have a new boundary witnessed
          boundaries(dsigmaReduced.leadingCell.get) = dsigmaReduced
          boundariesBornBy(dsigmaReduced.leadingCell.get) = sigma
          coboundaries(dsigmaReduced.leadingCell.get) = coboundary

          val (_, cycleBasis) = Chain.reduceBy(dsigmaReduced, cycles, Chain.empty)
          val representativeCycle: Chain[Simplex[VertexT], CoefficientT] = cycleBasis.leadingCell match
            case None       => Chain()
            case Some(cell) => cycles(cell)
          cycleBasis.leadingCell match
            case None       => ()
            case Some(cell) => cycles.remove(cell)

          val lower: Double = cycleBasis.leadingCell match
            case None      => Double.NegativeInfinity
            case Some(spx) =>
              stream.filtrationValue.orElse(_ => Double.NegativeInfinity).compose(cyclesBornBy)(spx)
          val upper: Double =
            stream.filtrationValue.orElse(_ => Double.PositiveInfinity)(sigma)
          // A bar's dimension is the dimension of the CLASS being closed (the killed cycle, one
          // dimension below sigma), not sigma's own dimension -- matching CellularHomologyContext's
          // barcode.append((pivot.dim, ...)). Using currentDim (= sigma.dim) here was a real,
          // previously-confirmed bug: every finite bar above dimension 0 was recorded one dimension too
          // high (e.g. a killed 1-cycle filed under dimension 2), caught by
          // SimplicialHomologyByDimensionSpec's cross-validation against the hand-verified fixtures.
          val barDim: Int = cycleBasis.leadingCell.map(_.dim).getOrElse(currentDim - 1)

          barcode(barDim) = barcode
            .getOrElse(barDim, immutable.Queue.empty)
            .appended((lower, upper, representativeCycle))
        current = stream.filtrationValue.lift(sigma).getOrElse(stream.smallest)
      else
        currentDim += 1
        currentIterator = stream.iterateDimension
          .applyOrElse(currentDim, _ => Iterator.empty)
          .buffered
        current = Double.NegativeInfinity

    // Deliberately does NOT gate on currentIterator.hasNext: currentIterator starts out empty (the
    // Iterator.empty.buffered passed by persistentHomology below), and becoming empty mid-walk is the
    // NORMAL signal advanceOne uses to move to the next dimension, not a "nothing left at all" signal --
    // gating on it here made the very first call (and any call that lands on an empty dimension) a no-op,
    // silently skipping every cell from that point on. `currentDim <= dim` alone is the correct bound: once
    // currentDim exceeds the caller's target, further advanceOne calls would only ever hit empty iterators
    // (iterateDimension.applyOrElse degrades to Iterator.empty past the stream's real max), so the loop
    // still terminates even though current gets reset to -Infinity on every dimension change.
    def advanceTo(dim: Int, f: Double = Double.PositiveInfinity): Unit =
      while currentDim <= dim && f > current
      do advanceOne()

  def persistentHomology(stream: => StratifiedCellStream[Simplex[VertexT], Double]): HomologyState =
    HomologyState(
      mutable.Map.empty,
      mutable.Map.empty,
      mutable.Map.empty,
      mutable.Map.empty,
      mutable.Map.empty,
      stream,
      stream.smallest,
      0,
      Iterator.empty.buffered,
      mutable.Map.empty
    )

/** Persistent cohomology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to `Simplex[Int]`
  * Vietoris-Rips/clique complexes via the combinatorial number system (`SimplexIndexing`) -- a deliberate narrowing
  * from `CellularHomologyContext`'s generic `CellT: OrderedCell`, agreed with the project lead (see "Phase 2 plan" in
  * WORKLOG-naive-homology.md). One-shot: computes the full barcode in a single pass, no incremental querying (also
  * agreed scope, same source).
  *
  * Includes clearing (see the `cleared` set in `persistentCohomology`): unlike the standard framing of clearing as a
  * pure performance optimization on top of an already-correct baseline, a first draft of this engine without it was
  * confirmed WRONG by hand-deriving H^1 of a plain 3-cycle graph (spurious essential classes from dimension-d simplices
  * that were already claimed as pivots one dimension down, violating Proposition 3.1's "not a pivot anywhere" clause)
  * -- see WORKLOG-cohomology.md for the full derivation. Do not "simplify" this against intuition without rereading
  * that derivation; the reversed-order reasoning is genuinely non-obvious and has already produced two
  * plausible-but-wrong drafts during development.
  *
  * Also includes a PARTIAL apparent-pairs optimization (see `zeroApparentCofacet` and its use in
  * `persistentCohomology`) -- partial, and that qualifier matters: what's implemented is Definition 3.2 apparent-pair
  * identification wired into the reduction loop to SKIP `Chain.reduceBy`'s reduction pass for an apparent sigma (it's
  * guaranteed to be a no-op by Proposition 3.9/Lemma 3.3), NOT Ripser's further, larger optimization of never building
  * an apparent sigma's coboundary at all unless some other column's reduction actually needs it. `coboundaryOf(sigma)`
  * is still called in full on the shortcut path -- `basis(tau)` needs the COMPLETE reduced column (all of sigma's
  * cofacets, not just tau), not a truncated single-term stand-in (an earlier draft got this wrong; see
  * WORKLOG-cohomology.md's "Apparent pairs: resolved" section for the 12-point counterexample that caught it). Despite
  * still paying for the enumeration, this is a measured 1.35x-1.8x wall-clock win on n=12-20 point random VR complexes
  * at maxDimension=2 (growing with n) -- `Chain.reduceBy`'s recursive per-pivot `SortedMap` fold, not the coboundary
  * enumeration, turns out to dominate this loop's cost, so skipping just the reduction pass is still worthwhile. Unlike
  * Ripser's own C++ implementation, this shortcut does NOT need Ripser's `assemble_columns_to_reduce` exclusion step or
  * its `compute_pairs` on-the-fly substitution fallback (`get_zero_apparent_facet` at `compute_pairs` time in Ripser's
  * source) -- this engine never removes a simplex from the set it iterates over at a given dimension (only `cleared`
  * skips a *later* dimension's redundant reprocessing, which was already correct before this shortcut existed), so the
  * true (mutual) Definition 3.2 apparent-pair check alone is sufficient for THIS optimization to be safe. Getting the
  * larger, lazy optimization would require also changing how `persistentCohomology` enumerates each dimension's
  * simplices in the first place (currently an eager `(0 until binomial(n, d+1))` over the WHOLE complex, unlike
  * Ripser's own incrementally-assembled `columns_to_reduce`) -- a separate, larger, not-yet-attempted project. See
  * WORKLOG-cohomology.md's dated "Apparent pairs: resolved" section (after the earlier "negative result" section, which
  * stays as historical record) for the full derivation, grounded directly in Ripser's own `ripser.cpp` source and
  * arXiv:1908.02518's Definition 3.2/3.11 and Proposition 3.9/Lemma 3.3 -- re-derived from those primary sources this
  * session, not reconstructed from memory.
  *
  * `useApparentPairs` (default `true`) exists so `ApparentPairsBenchmarkSpec` can isolate this optimization's own
  * effect: with it `false`, `persistentCohomology` always falls through to the ordinary `Chain.reduceBy` path, byte for
  * byte the same output as when it's `true` -- see `zeroApparentCofacet`'s doc for why the shortcut is provably a
  * no-op, not just an empirically-checked one. Not intended as a user-facing tuning knob outside benchmarking; there is
  * no known case where `false` is the right choice for real use.
  */
/** `maxFiltrationValue` now defaults to `metricSpace.minimumEnclosingRadius`, not `Double.PositiveInfinity` -- a change
  * from the "explicit, approved architectural choice" of a second session (see WORKLOG-lazy-enumeration.md's "Session
  * 2" section for that original derivation, and WORKLOG-mst-and-perf.md for this one). This is NOT a reversal of that
  * session's reasoning about sparse-Rips truncation being a real, bar-dropping semantic choice that shouldn't silently
  * default on (that reasoning still holds for any FINITE threshold below the enclosing radius) -- it's a different,
  * narrower optimization layered underneath it: beyond `minimumEnclosingRadius`, every vertex is within range of every
  * other, so the complex is a cone from that point on and provably contributes no further homology (Ripser paper, p.
  * 412). Cutting there computes the exact same barcode over fewer simplices -- confirmed empirically against
  * `RipserCohomologySpec`'s existing "thresholded barcode = untruncated barcode restricted to [0, t]" test machinery,
  * not just asserted -- unlike a genuine finite `maxFiltrationValue` below the enclosing radius, which DOES drop real
  * bars and remains an explicit, deliberate truncation the caller opts into. Still matches `RipserStreamBase`'s
  * sparse-Rips convention, still NOT `AlphaShapeDQP`'s always-untruncated one (alpha shapes and this Ripser
  * reproduction remain different sections of the library with minimal interaction). Pass `Double.PositiveInfinity`
  * explicitly for the old always-unbounded behavior. `memoizeFiltrationValue` (default `false`) is unaffected by this
  * change -- see the original derivation below for why it stays opt-in: Ripser's own historical design goal was MEMORY
  * frugality (the classic bottleneck for persistent homology implementations before Ripser), not raw speed -- the
  * speedups were a side effect of that, not the primary goal. A global cache of every filtration value ever touched
  * runs directly against that goal on large complexes, so it's opt-in here, not the default. The actual replacement for
  * it is `insertionDiameter`'s incremental diameter formula (below), which ELIMINATES the O(d^2)
  * `MaximumDistanceFiltrationValue` recomputation for cofacet enumeration entirely, rather than paying for it once and
  * caching the answer -- strictly better than a cache on every axis that matters here (no growing memory footprint, no
  * hashing, no first-computation cost to amortize).
  */
/** `maxDimension` means "top HOMOLOGICAL DEGREE reported," not "top simplex dimension built" -- fixed at the source
  * (previously only worked around at the MATLAB facade layer, `matlab.Tda4j`, which built `requestedMaxDimension + 1`
  * internally and filtered the extra dimension back out; see `.claude/WORKLOG-maxdim-semantics-fix.md` for the full
  * derivation, including how this was discovered via a same-hardware benchmark against real `ripser.cpp`). Before this
  * fix, `coboundaryOf`/`zeroPivotCofacet` refused to look past `sigma.dim + 1 > maxDimension`, i.e. `sigma.dim ==
  * maxDimension` always got a trivially-empty coboundary and therefore always came out essential -- a well-known
  * truncation artifact (H_k needs (k+1)-chains to resolve correctly), not real information about H_maxDimension. Fixed
  * by relaxing that guard to `sigma.dim > maxDimension` (see `coboundaryOf`'s own doc): a real `(maxDimension +
  * 1)`-simplex is now enumerated on the fly, transiently, whenever needed to resolve a dimension-`maxDimension` pairing
  * -- never materialized into its own `currentLevel`/reduced as its own column, so `totalSimplexCount` and the main
  * loop's own bounds (`for d <- 0 to maxDimension`) are UNCHANGED by this fix; only the two guards moved. Any external
  * caller previously passing `maxDimension + 1` and filtering out `dim == maxDimension + 1` bars itself should now pass
  * the real requested degree directly and drop that workaround entirely -- double-shifting by continuing the old
  * pattern on top of this fix reintroduces the exact artifact one dimension further out.
  */
class RipserCohomologyContext[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // NaN is a sentinel for "not explicitly set," resolved to metricSpace.minimumEnclosingRadius just below --
  // NOT a literal default of metricSpace.minimumEnclosingRadius, because Scala 3 only allows a default value
  // to reference an EARLIER parameter LIST, not an earlier parameter within the same list, and splitting this
  // into a second, curried parameter list would require every existing call site (including plain
  // `RipserCohomologyContext(metricSpace, maxDim)` ones) to add an explicit trailing `()` -- confirmed: Scala
  // does not let a call site omit a later parameter list just because every parameter in it has a default.
  // See the class doc above this class for why the resolved default itself changed from
  // Double.PositiveInfinity to metricSpace.minimumEnclosingRadius.
  maxFiltrationValue: Double = Double.NaN,
  memoizeFiltrationValue: Boolean = false
):
  import barcode.*

  private val resolvedMaxFiltrationValue: Double =
    if maxFiltrationValue.isNaN then metricSpace.minimumEnclosingRadius else maxFiltrationValue

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  private val rawFiltrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  /** Optionally-memoized wrapper around `rawFiltrationValue`: `MaximumDistanceFiltrationValue.apply` recomputes an
    * O(d^2) max over pairwise vertex distances from scratch on every call, with no caching of its own (confirmed by
    * reading `FiniteMetricSpace.scala` directly, not assumed). Gated behind `memoizeFiltrationValue` (default `false`
    * -- see the class doc above for why): the enumeration/assembly path (`insertionDiameter`, `sparseCofacets`,
    * `persistentCohomology`'s own per-dimension loop) never goes through this at all, carrying diameters incrementally
    * instead, so this field's remaining callers are `cohomologyOrdering` (consulted on every `SortedMap`/
    * `PriorityQueue` comparison inside `Chain.reduceBy`'s reduction machinery -- the majority of remaining calls) plus
    * the handful of once-per-simplex lookups in `coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet`/bar-endpoint
    * reporting. See WORKLOG-lazy-enumeration.md for the measured cost of leaving this `false`.
    */
  val filtrationValue: PartialFunction[Simplex[Int], Double] =
    if memoizeFiltrationValue then
      new PartialFunction[Simplex[Int], Double]:
        private val cache: mutable.HashMap[Simplex[Int], Double] = mutable.HashMap.empty
        def isDefinedAt(spx: Simplex[Int]): Boolean = rawFiltrationValue.isDefinedAt(spx)
        def apply(spx: Simplex[Int]): Double = cache.getOrElseUpdate(spx, rawFiltrationValue(spx))
    else rawFiltrationValue

  private val fr = summon[CoefficientT is Field]

  /** Shared comparator logic for "ascending by filtration value, ties broken so a LARGER combinatorial index sorts as
    * OLDER (smaller)" -- factored out so a diameter-CARRYING ordering (`diameterSimplexOrdering`, below) can reuse the
    * exact same tie-break instead of being a second, independently-written comparator that might silently disagree on a
    * tie (the trap this codebase's own history -- `NOTES-for-guides.md` item 3 -- flags explicitly). Takes raw
    * `(filtrationValue, combinatorialIndex)` pairs rather than `Simplex[Int]` so callers who already have the
    * filtration value in hand (i.e. don't need to call `filtrationValue` at all) can avoid it.
    */
  private def compareFvThenIndex(xFv: Double, xIdx: Long, yFv: Double, yIdx: Long): Int =
    val fc = java.lang.Double.compare(xFv, yFv)
    if fc != 0 then fc else java.lang.Long.compare(yIdx, xIdx)

  /** Ascending by filtration value; ties broken so a LARGER combinatorial index sorts as OLDER (smaller) -- the
    * lexicographically-refined tie-break Definition 3.2/Proposition 3.9 rely on. `Chain`'s `leadingCell` is the MINIMUM
    * under whatever `Ordering` is supplied (`Chain.from` builds its `PriorityQueue` with `ord.reverse`), and persistent
    * cohomology's pivot is the OLDEST cofacet in the reduced coboundary chain -- so this ascending ordering, not a
    * reversed one, is what the coboundary-side `Chain`/`RingModule` machinery needs in scope. This is the opposite
    * convention from `CellularHomologyContext`'s `stream.filtrationOrdering`, which is deliberately reversed so ITS
    * `leadingCell` means youngest -- see WORKLOG-cohomology.md for the full "transpose + reverse filtration order"
    * derivation from the paper. Safe to declare at class scope (unlike the `chainRM` hazard documented on
    * `CellularHomologyContext`): this ordering is self-contained, built directly from `filtrationValue`/`si` rather
    * than by summoning some other ambient `Ordering`, so there is no stream-not-yet-available timing issue to worry
    * about here.
    */
  given cohomologyOrdering: Ordering[Simplex[Int]]:
    def compare(x: Simplex[Int], y: Simplex[Int]): Int =
      compareFvThenIndex(filtrationValue(x), si(x), filtrationValue(y), si(y))

  /** A simplex paired with its ALREADY-KNOWN filtration value, carried through enumeration/assembly so it never needs
    * to be recomputed (the `insertionDiameter` incremental formula below, not `filtrationValue`/`MaximumDistance
    * FiltrationValue`'s O(d^2) recompute, is how a cofacet's `diameter` field gets produced in the first place). This
    * is this codebase's analogue of Ripser's own `diameter_index_t` -- deliberately NOT the same compact
    * `(Double, Int)` representation Ripser actually uses (Ripser stores a combinatorial index, not a materialized
    * `Simplex[Int]`/`SortedSet[Int]`): carrying the full `Simplex[Int]` is a simplicity/speed choice made AGAINST the
    * project's stated memory goal, not an oversight -- flagged here as a live option for a future session, not
    * something to silently "fix" by trying to swap in a raw-index representation without re-deriving what else that
    * would touch (every downstream consumer currently expects a `Simplex[Int]`).
    *
    * WARNING: do not use `DiameterSimplex` as a `Set`/`Map` key anywhere -- its case-class equality includes the
    * `Double` diameter, so two carriers for the textually-same simplex could compare unequal on floating-point noise.
    * `cleared`/`basis`/`generators` are and must stay keyed by `.simplex` directly, never by a `DiameterSimplex`.
    */
  private final case class DiameterSimplex(diameter: Double, simplex: Simplex[Int])

  private val diameterSimplexOrdering: Ordering[DiameterSimplex] =
    (x: DiameterSimplex, y: DiameterSimplex) => compareFvThenIndex(x.diameter, si(x.simplex), y.diameter, si(y.simplex))

  /** Ripser's actual cofacet-diameter recurrence (`simplex_coboundary_enumerator` in `ripser.cpp`): a cofacet formed by
    * inserting vertex `v` into `sigma` has diameter `max(sigma's own diameter, max over sigma's vertices of the
    * distance to v)` -- O(d) given `sigma`'s already-known diameter, vs. `MaximumDistanceFiltrationValue.apply`'s
    * O(d^2) full pairwise recompute from scratch, which is ignorant of any already-known partial answer. This is what
    * makes carrying `DiameterSimplex` through enumeration strictly better than caching: the expensive computation is
    * eliminated, not paid for once and reused. Valid for inserting ANY vertex, not just ones satisfying the "above
    * sigma's own max" canonical-cofacet convention `sparseCofacets` uses below -- so this is also used inside
    * `coboundaryOf`/`zeroPivotCofacet`, which need to consider cofacets from inserting vertices in general.
    */
  /** Takes `sigma`'s vertex set as an already-materialized `Array[Int]`, not a `Simplex[Int]`/`SortedSet[Int]`, as of
    * a later follow-up session -- see `PackedRipserCohomologyContext.insertionDiameter` (`PackedRipserCohomology.scala`)
    * for the full rationale: the earlier closure-allocation fix that replaced `.map(...).max` with a `while` loop (see
    * git history) still called `sigma.underlying.iterator`, and `TreeSet.iterator()` itself allocates a `KeysIterator`
    * wrapping a `TreeIterator` (with its own `Tree[]` DFS-stack array) on every single call -- re-profiling
    * `PackedRipserCohomologyContext` found this was actually that class's single LARGEST allocation source (23.8% of
    * total weight), bigger than `Chain.reduceLoop`'s own accumulator churn. `sigma` is fixed across every candidate
    * vertex considered within one enumeration call, so each caller here now hoists `sigma.underlying.toArray` ONCE
    * and passes the same array to every `insertionDiameter` call in that enumeration, rather than each call
    * re-iterating `sigma.underlying` itself. The two engines are no longer byte-for-byte identical at their call
    * sites (this one already holds `sigma: Simplex[Int]` directly, so it hoists straight from it; the packed engine
    * decodes from a combinatorial index first) but `insertionDiameter`'s own array-indexing body stays identical.
    */
  private def insertionDiameter(vertices: Array[Int], sigmaFv: Double, v: Int): Double =
    var maxD = sigmaFv
    var i = 0
    while i < vertices.length do
      val d = metricSpace.distance(vertices(i), v)
      if d > maxD then maxD = d
      i += 1
    maxD

  /** The canonical cofacets of `sigma` -- one per higher simplex that has `sigma` as ITS canonical facet (the facet
    * obtained by removing its own maximum vertex) -- generated by inserting a vertex strictly greater than `sigma`'s
    * own maximum, the same convention `SimplexIndexing.topCofacetIterator`/`cofacetIterator(..., allCofacets = false)`
    * already uses and `SimplexIndexingSpec` already verifies against the paper's worked examples. This generates each
    * dimension-(d+1) simplex from EXACTLY one dimension-d source, so no deduplication is needed when assembling a whole
    * dimension's worth of candidates from the previous dimension's simplices (`persistentCohomology`'s
    * `currentLevel.iterator.flatMap(sparseCofacets)`) -- see WORKLOG-lazy-enumeration.md's uniqueness argument.
    *
    * Deliberately built by direct `SortedSet` insertion (`sigma.simplex.underlying + v`), NOT by routing through
    * `SimplexIndexing.cofacetIterator` + `si(idx, ...)` decode: the combinatorial-index round-trip costs O(d log n) per
    * candidate for information (the inserted vertex) this method already has directly from the loop variable, where a
    * direct `SortedSet` insertion is O(d). `maxFiltrationValue` is enforced here -- this is the one place in the engine
    * that actually EXCLUDES a simplex from existing in the complex at all, as opposed to `coboundaryOf`'s
    * within-an-existing-simplex's-coboundary filtering.
    */
  private def sparseCofacets(sigma: DiameterSimplex): Iterator[DiameterSimplex] =
    if sigma.simplex.dim + 1 > maxDimension then Iterator.empty
    else
      val vertices = sigma.simplex.underlying.toArray
      val maxVertex = vertices(vertices.length - 1)
      (maxVertex + 1 until metricSpace.size).iterator
        .map { v =>
          DiameterSimplex(insertionDiameter(vertices, sigma.diameter, v), (sigma.simplex.underlying + v).asSimplex)
        }
        .filter(_.diameter <= resolvedMaxFiltrationValue)

  /** Coboundary of sigma, implicitly restricted to the maxFiltrationValue-thresholded complex. `maxDimension` means
    * "top homological degree reported," not "top simplex dimension built" (see
    * `.claude/WORKLOG-maxdim-semantics-fix.md` for the full derivation of this distinction and why it matters):
    * correctly resolving whether a dimension- `maxDimension` class is finite or essential needs a REAL coboundary
    * against genuine `(maxDimension + 1)`-simplices, so this method only refuses to look past `maxDimension + 1`
    * (`sigma.dim > maxDimension`), not `maxDimension` itself. Those `(maxDimension + 1)`-simplices are enumerated here
    * on the fly, via `si.cofacetIterator`, and never separately materialized into `currentLevel`/`simplicesAtD` --
    * `persistentCohomology`'s own loop never runs a `d == maxDimension + 1` iteration, so nothing above `maxDimension`
    * is ever independently reduced as its own column; it exists only transiently, as a pairing target for the
    * dimension-`maxDimension` column that needs it. A candidate cofacet past `maxFiltrationValue` is filtered out via
    * `insertionDiameter`'s O(d) incremental formula (one `filtrationValue(sigma)` call for `sigma` itself, not one per
    * candidate) rather than `filtrationValue(tau)`'s O(d^2) full recompute per candidate -- this is the one place
    * `coboundaryOf` genuinely needs a diameter it doesn't already have (it considers ALL of sigma's cofacets, not just
    * tied ones, unlike `zeroPivotCofacet` below). Sign convention dual to `Simplex.scala`'s boundary: `(-1)^`(number of
    * sigma's vertices smaller than the inserted vertex).
    */
  /** Built directly on `SimplexIndexing.CofacetCursor`, not `si.cofacetIterator` + `si(idx, ...)` decode, as of the
    * cursor-redesign session (`.claude/WORKLOG-ripser-profiling.md`): the old vertex-less `cofacetIterator` forced
    * every candidate to be fully decoded back into a `Simplex[Int]` (`si(cofacetIdx, sigma.size + 1)`) and then
    * linearly scanned (`tau.underlying.find(...)`) just to recover the ONE vertex `CofacetCursor` already hands over
    * directly as `cur.vertex` -- on top of `cofacetIteratorWithVertex`'s own `(Int, Long)` tuple allocation per
    * candidate, together the single largest remaining allocation source measured in this engine (~49.8% for the
    * tuple boxing alone, before even counting the redundant decode this rewrite also removes). `tau` is now built by
    * inserting `cur.vertex` directly into `sigma`'s own vertex set (`sigma.underlying + v`, O(log d)) -- the same
    * incremental-insertion `sparseCofacets` above already uses, rather than a full O(d log d) combinatorial decode.
    */
  def coboundaryOf(sigma: Simplex[Int]): Chain[Simplex[Int], CoefficientT] =
    if sigma.dim > maxDimension then Chain.empty
    else
      val sigmaFv = filtrationValue(sigma)
      val vertices = sigma.underlying.toArray
      val cur = si.cofacetCursor(si(sigma), sigma.size, allCofacets = true)
      val buffer = mutable.ArrayBuffer.empty[(Simplex[Int], CoefficientT)]
      while cur.hasNext do
        val v = cur.vertex
        if insertionDiameter(vertices, sigmaFv, v) <= resolvedMaxFiltrationValue then
          val tau = (sigma.underlying + v).asSimplex
          // `vertices` is sorted ascending, so this is a plain scan, not `sigma.underlying.count(_ < v)` -- see
          // `insertionDiameter`'s doc above for why a `SortedSet` operation here allocates an iterator on every
          // single candidate.
          var position = 0
          while position < vertices.length && vertices(position) < v do position += 1
          val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
          buffer += ((tau, sign))
        cur.advance()
      Chain.from(buffer.toSeq)

  /** Coboundary of a whole chain, linearly extending `coboundaryOf`. Unlike `Chain.scala`'s `.boundary` extension
    * (intrinsic to a cell), a coboundary is extrinsic -- it depends on which higher-dimensional simplices exist in this
    * (possibly truncated) complex -- so it lives here rather than as a general-purpose `Chain` extension. Used by tests
    * to check that a representative essential cocycle genuinely has zero coboundary -- genuinely meaningful at every
    * dimension up to and including `maxDimension` (not vacuously true at the top dimension anymore, since
    * `coboundaryOf` now computes a real coboundary there too; see its own doc).
    */
  def coboundaryOfChain(c: Chain[Simplex[Int], CoefficientT]): Chain[Simplex[Int], CoefficientT] =
    Chain.from(c.items.flatMap { case (cell, coeff) =>
      coboundaryOf(cell).items.map { case (tau, sign) => (tau, fr.times(coeff, sign)) }
    })

  /** `sigma`'s cofacet tied at `sigma`'s own filtration value with the LARGEST combinatorial index, i.e. the "oldest
    * cofacet" in Definition 3.2/3.11's sense (`cohomologyOrdering` sorts a larger index as older). Built directly
    * against `si.cofacetIterator`'s full (unrestricted) enumeration, NOT `RipserStreamBase`'s
    * `zeroPivotCofacet`/`Cofacets.scala`'s `apparentVertex` -- see CLAUDE.md: those are restricted to cofacets formed
    * by inserting a vertex strictly greater than sigma's own maximum, which silently misses a real tied cofacet
    * whenever sigma already contains `vertexCount - 1` (a false negative, not merely an inefficiency). Guarded at
    * `sigma.dim > maxDimension`, the same boundary `coboundaryOf` uses and for the same reason (see its doc): a real
    * tied cofacet at `sigma.dim == maxDimension` must be found too, or the apparent-pairs shortcut would silently miss
    * genuine pairs at the requested top dimension. `SimplexIndexing`'s raw iterators have no notion of any dimension
    * cap on their own, hence the explicit guard. Tau's diameter is computed via `insertionDiameter`'s O(d) incremental
    * formula, not `filtrationValue(tau)`'s O(d^2) recompute. No SEPARATE `maxFiltrationValue` guard is needed here
    * (unlike `coboundaryOf`, which considers every cofacet, not just tied ones): this method only ever selects a tau
    * tied at `sigma`'s own value, and `sigma` is only ever called with here if it's already within the threshold
    * (guaranteed by construction -- see `sparseCofacets`), so any tied tau is automatically within threshold too.
    */
  /** A hand-rolled `while` loop, not `.filter(...).maxByOption((tau, _) => si(tau))`, as of a later follow-up
    * session (`.claude/WORKLOG-ripser-profiling.md`): two separate costs stacked here. First, `maxByOption` boxes
    * every `Long` key comparison, same as `PackedRipserCohomologyContext.zeroPivotCofacet`'s identical fix. Second,
    * and specific to this engine, `si(tau)` RE-ENCODES a simplex that was just DECODED from `idx` one line above --
    * `idx` already IS `tau`'s own combinatorial index by construction (`si.cofacetIterator` yields it,
    * `tau = si(idx, ...)` decodes it), so `si(tau)` is a fully redundant O(d log d) round trip through the same
    * `searchRow`/`binomialEntry` machinery for a value already in hand. Reusing `idx` directly is not an
    * assumption: `SimplexIndexingSpec`'s round-trip property test (`si(si(idx, size), size) == idx` for random
    * valid indices) confirms decode-then-encode is the identity, independent of this fix.
    */
  /** Built on `CofacetCursor` directly, as of the cursor-redesign session -- see `coboundaryOf`'s identical rewrite
    * above for the shared rationale. Unlike `coboundaryOf`, which needs every surviving candidate's `Simplex[Int]`,
    * this method only ever needs the SINGLE winning one -- so `tau` is never built at all until the loop finishes,
    * only `bestVertex`/`bestIdx` (primitives) are tracked per candidate, and `sigma.underlying + bestVertex` runs
    * exactly once, for the winner, not once per candidate considered.
    */
  private def zeroPivotCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    if sigma.dim > maxDimension then None
    else
      val d = filtrationValue(sigma)
      val vertices = sigma.underlying.toArray
      val cur = si.cofacetCursor(si(sigma), sigma.size, allCofacets = true)
      var bestVertex: Int = -1
      var bestIdx: Long = -1L
      var found = false
      while cur.hasNext do
        val tauFv = insertionDiameter(vertices, d, cur.vertex)
        if tauFv == d && (!found || cur.index > bestIdx) then
          bestVertex = cur.vertex
          bestIdx = cur.index
          found = true
        cur.advance()
      if found then Some((sigma.underlying + bestVertex).asSimplex) else None

  /** `tau`'s facet tied at `tau`'s own filtration value with the SMALLEST combinatorial index, i.e. the "youngest
    * facet" in Definition 3.2/3.11's sense. No `maxDimension` guard needed: a facet is always one dimension lower than
    * `tau`, which is already within the truncated complex by construction (it only exists as some `sigma`'s candidate
    * cofacet, already dimension-checked by `zeroPivotCofacet` above). Likewise no `maxFiltrationValue` guard: by
    * Vietoris-Rips monotonicity a facet's diameter can only be <= its coface's, so if `tau` is within threshold every
    * one of its facets automatically is too. Candidate facets' diameters are NOT computed incrementally here, unlike
    * the cofacet direction (`insertionDiameter`) -- removing a vertex doesn't admit the same cheap O(d) recurrence
    * (whether the diameter changes at all depends on whether the removed vertex realized `tau`'s own maximum pairwise
    * distance, which isn't tracked) -- so this remains a `filtrationValue(sigma)` call per candidate, same as before
    * this session's enumeration work. Left as a scope boundary, not an oversight: see WORKLOG-lazy-enumeration.md's
    * "Session 2" section.
    */
  /** Hand-rolled for the same two reasons as `zeroPivotCofacet` above: `.minByOption(sigma => si(sigma))` boxes
    * every `Long` comparison, AND `si(sigma)` re-encodes a simplex just decoded from `idx`, which already IS
    * `sigma`'s own index. The ONE genuinely necessary encode call, `si(tau)` (seeding `facetIterator` with `tau`'s
    * own index, since a facet iterator needs to know what it's removing a vertex FROM), stays -- it happens once
    * per `zeroPivotFacet` call, not once per candidate, so it was never part of either cost.
    */
  /** Built on `FacetCursor` directly, as of the cursor-redesign session: `FacetCursor.vertex` is the vertex REMOVED
    * to reach `.index` (verified against `decodeToArray` independently by `SimplexIndexingSpec`'s `FacetCursor`
    * property before this was relied on here), so each candidate's vertex set is built by removing ONE vertex from
    * `tau`'s own already-materialized `underlying` (`tau.underlying - v`, O(log d)) rather than a full combinatorial
    * decode (`si(idx, tau.size - 1)`, O(d log vertexCount) search plus `size - 1` incremental tree insertions).
    * `filtrationValue(sigma)` itself still needs the full candidate vertex set on every candidate, unlike the cofacet
    * direction's `insertionDiameter` -- no incremental shortcut exists for removing a vertex's diameter contribution
    * (same scope boundary this class's other docs already note), so `sigma` can't be deferred to just the winner
    * the way `zeroPivotCofacet` defers `tau`.
    */
  private def zeroPivotFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    val d = filtrationValue(tau)
    val cur = si.facetCursor(si(tau), tau.size)
    var best: Simplex[Int] = ∆()
    var bestIdx: Long = Long.MaxValue
    var found = false
    while cur.hasNext do
      val sigma = (tau.underlying - cur.vertex).asSimplex
      if filtrationValue(sigma) == d && (!found || cur.index < bestIdx) then
        best = sigma
        bestIdx = cur.index
        found = true
      cur.advance()
    if found then Some(best) else None

  /** `Some(tau)` iff `(sigma, tau)` is a genuine (mutual) Definition 3.2 apparent pair: tau is sigma's oldest tied
    * cofacet, AND sigma is, symmetrically, tau's youngest tied facet. Verified against the hand-derived
    * `threePointLine` fixture in `RipserCohomologySpec` (`{0,2}` paired with the triangle `{0,1,2}`, both born at 3.0).
    * Deliberately just the mutual check, not Ripser's own broader "emergent pair" condition (`ripser.cpp`'s
    * `init_coboundary_and_get_pivot`, which also has to guard against a *different*, non-apparent simplex racing for
    * the same tau) -- that broader condition exists in Ripser only because Ripser additionally removes tau from the
    * pool of simplices it ever separately reduces (`assemble_columns_to_reduce`'s `is_in_zero_apparent_pair`
    * exclusion), which forces it to also handle tau turning up as some *other* column's intermediate pivot via a
    * substitution fallback (`compute_pairs`'s own `get_zero_apparent_facet` call). This engine does neither:
    * `persistentCohomology`'s loop below still visits every non-cleared simplex, so the mutual pair's `sigma` is
    * *always* the first (youngest, smallest-index) simplex whose raw, unreduced coboundary can possibly have `tau` as
    * its leading term under `cohomologyOrdering` -- any tied-diameter cofacet of any simplex necessarily is that
    * simplex's chain minimum, by Vietoris-Rips monotonicity (a cofacet's filtration value is never smaller than its
    * facet's, so a tied one is always the smallest term present) -- so `tau` can never already be claimed in `basis` by
    * anything else by the time `sigma`'s turn comes up. See WORKLOG-cohomology.md's dated "Apparent pairs: resolved"
    * section for the full derivation, including why this makes the shortcut in `persistentCohomology` provably
    * behavior-preserving rather than merely empirically-checked.
    */
  private def zeroApparentCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    for
      tau <- zeroPivotCofacet(sigma)
      partner <- zeroPivotFacet(tau)
      if partner == sigma
    yield tau

  /** Mirror of `zeroApparentCofacet`, entered from the other side: `Some(sigma)` iff `tau` has a facet `sigma` such
    * that `(sigma, tau)` is a genuine (mutual) Definition 3.2 apparent pair. This is the lookup Ripser's
    * `compute_pairs` performs (`get_zero_apparent_facet`) when some OTHER column's reduction reaches `tau` as an
    * unresolved pivot -- confirmed against `ripser.cpp` directly (source fetched this session, not recalled): the
    * substitution is a fresh recomputation every time, with NO cache anywhere in Ripser's own implementation. Mirrored
    * here for the same reason, not out of caution: `zeroApparentCofacet`'s soundness proof (see its own doc) only
    * establishes that `sigma` is the first simplex whose RAW, unreduced coboundary can reach `tau` -- it says nothing
    * about whether some other column's own mid-cascade, already-partially-reduced working chain could reach `tau` as an
    * intermediate pivot before `sigma`'s own turn in the outer sweep. A map recording "who claimed this pair first"
    * would need to answer that question to be trustworthy; a pure recomputation from Definition 3.2 doesn't, because
    * `sigma` is the mutual apparent partner of `tau` as a fact about filtration values and combinatorial indices alone,
    * independent of when or how `tau` was reached. See WORKLOG-lazy-enumeration.md's "on-the-fly substitution" section
    * for the full reasoning (advisor-caught: an earlier draft of this session's plan proposed a recorded `tau -> sigma`
    * map instead, which this exact argument ruled out before it was implemented).
    */
  private def zeroApparentFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    for
      sigma <- zeroPivotFacet(tau)
      partner <- zeroPivotCofacet(sigma)
      if partner == tau
    yield sigma

  private var _substitutionCount: Int = 0

  /** How many times the on-the-fly substitution above actually fired during the most recent `persistentCohomology()`
    * call -- i.e. how many times some OTHER column's reduction reached an apparent pair's tau as an unresolved pivot
    * and had to recompute that pair's coboundary on the fly. Exposed purely for testing: WORKLOG-lazy-enumeration.md's
    * whole point is that this should be RARE (most apparent pairs are never looked up by anyone else's reduction) -- a
    * test that only checks the final barcode is unchanged cannot distinguish "the fallback fired and computed
    * correctly" from "the fallback never fired at all," so a discriminating test needs this counter, not just the bars.
    */
  def substitutionCount: Int = _substitutionCount

  private var _totalSimplexCount: Int = 0

  /** Total number of simplices actually assembled across all dimensions during the most recent `persistentCohomology()`
    * call -- exposed for testing. NOT `Σ binomial(n, d+1)`: that formula assumes every combinatorially-possible subset
    * exists, which is only true at `maxFiltrationValue = +Infinity`. For a genuinely thresholded complex most subsets
    * never get generated at all (see `sparseCofacets`), so the `finite*2 + essential == totalSimplices` structural
    * invariant `RipserCohomologySpec` checks needs THIS count, not the binomial formula, once a finite threshold is in
    * play.
    */
  def totalSimplexCount: Int = _totalSimplexCount

  def persistentCohomology(): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    // Summoned here, not any earlier -- see class doc above and CellularHomologyContext's class doc for
    // why a Chain[...] is RingModule instance's summon-time Ordering[CellT] scoping matters.
    val chainRM = summon[Chain[Simplex[Int], CoefficientT] is RingModule]
    import chainRM.*

    _substitutionCount = 0
    _totalSimplexCount = 0
    val bars = mutable.ArrayDeque.empty[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]]

    // Clearing: a d-simplex that was already claimed as the PIVOT of some (d-1)-simplex's reduction
    // (i.e. it's the death side of a bar already recorded one dimension down) MUST NOT be independently
    // considered when reducing dimension d's own coboundary matrix -- carried across dimensions, not
    // reset. This is NOT an optional speedup here (see WORKLOG-cohomology.md's "clearing is required for
    // correctness" finding, confirmed by hand-deriving H^1 of a 3-cycle graph and catching this exact
    // engine reporting 2-3 spurious essential classes instead of the correct 0-1): Proposition 3.1 defines
    // essential indices as {i | R_i = 0 AND i is not a pivot anywhere}, and skipping that second condition
    // is precisely the bug this set exists to prevent. A cleared simplex contributes no bar at all (its
    // bar was already recorded when it was claimed as a pivot).
    val cleared: mutable.Set[Simplex[Int]] = mutable.Set.empty

    // On-the-fly apparent-pair substitution (Ripser's `compute_pairs`, confirmed no-cache against
    // ripser.cpp -- see zeroApparentFacet's doc and WORKLOG-lazy-enumeration.md). Consulted by
    // `Chain.reduceBy` below ONLY when a working chain's leading pivot has no `basis` entry -- which,
    // now that apparent pairs never write one (see the `Some(tau)` branch below), is exactly the case
    // for an apparent pair's tau whenever some OTHER column's reduction happens to reach it. This is
    // what lets `persistentCohomology` skip `coboundaryOf(sigma)` ENTIRELY for every apparent pair
    // nobody else's reduction ever touches, not merely skip the reduction pass the way last session's
    // version did (that version called `coboundaryOf(sigma)` unconditionally to eagerly populate
    // `basis(tau)`, "just in case").
    val basisFallback: Simplex[Int] => Option[Chain[Simplex[Int], CoefficientT]] =
      if useApparentPairs then
        (tau: Simplex[Int]) =>
          zeroApparentFacet(tau).map { sigma =>
            _substitutionCount += 1
            coboundaryOf(sigma)
          }
      else (_: Simplex[Int]) => None

    // Dimension-0 candidates: every vertex, diameter 0.0 by convention (matches
    // MaximumDistanceFiltrationValue's own `spx.dim <= 0 then 0.0`). This, not `(0 until binomial(n,
    // d+1))`-style direct combinatorial indexing, is the seed of the incrementally-assembled candidate
    // list every higher dimension is built from -- see WORKLOG-lazy-enumeration.md's "Session 2" section.
    var currentLevel: Seq[DiameterSimplex] =
      (0 until metricSpace.size).map(v => DiameterSimplex(0.0, Simplex(v)))

    for d <- 0 to maxDimension do
      // Youngest first: Algorithm 1 processes columns in increasing [matrix] order, which under the
      // reversed-order coboundary matrix means decreasing real filtration order. Structural, not a
      // performance tweak -- see WORKLOG-cohomology.md. Sorted via `diameterSimplexOrdering` (sharing
      // `compareFvThenIndex` with `cohomologyOrdering`, so this is provably the same tie-break, not a
      // second independently-written comparator) over the CARRIED diameters, not recomputed ones.
      val simplicesAtD: Seq[DiameterSimplex] = currentLevel.sorted(using diameterSimplexOrdering.reverse)
      _totalSimplexCount += simplicesAtD.size

      // Pivot (dimension d+1 simplex) -> reduced coboundary column, reset per dimension: dimension d's
      // coboundary matrix delta: C^d -> C^{d+1} is reduced independently of every other dimension's.
      val basis: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty
      // Pivot -> the V-column (a dimension-d chain) of whichever d-simplex claimed that pivot.
      val generators: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty

      for ds <- simplicesAtD if !cleared.contains(ds.simplex) do
        val sigma = ds.simplex
        // sigma's own diameter is already known (this dimension's assembly just computed it) -- using it
        // directly for bar-endpoint reporting below avoids yet another filtrationValue(sigma) recompute.
        val sigmaFv = ds.diameter
        (if useApparentPairs then zeroApparentCofacet(sigma) else None) match
          case Some(tau) =>
            // Apparent pair shortcut (Definition 3.2/Proposition 3.9): sigma's raw, UNREDUCED coboundary
            // already has tau as its leading term under cohomologyOrdering, and no earlier-processed
            // simplex can have already claimed tau in `basis` -- see `zeroApparentCofacet`'s doc and
            // WORKLOG-cohomology.md's "Apparent pairs: resolved" section for why. So `Chain.reduceBy`
            // is GUARANTEED to be a no-op here (log = Nil, reduced = z unchanged) and can be skipped.
            //
            // UNLIKE last session's version, `coboundaryOf(sigma)` is NOT computed here at all, and
            // `basis(tau)` is deliberately NEVER written. If some OTHER column's reduction later reaches
            // tau as an unresolved pivot, `basisFallback` above recomputes `coboundaryOf(sigma)` fresh
            // at that point instead -- Ripser's own `compute_pairs` substitution, confirmed no-cache
            // against ripser.cpp (see zeroApparentFacet's doc and WORKLOG-lazy-enumeration.md). This is
            // what turns the apparent-pairs shortcut from "skip the reduction pass, still pay for the
            // full coboundary enumeration" (last session, a measured 1.35x-1.8x win) into "skip the
            // coboundary enumeration too, for every apparent pair nobody else's reduction ever reaches."
            //
            // generators(tau) MUST still be written here even though basis(tau) is not: it's read back
            // by ANY later column whose reduction log has a `tau` entry, whether tau was reached via
            // ordinary `basis` or via `basisFallback` -- omitting it reintroduces the "pivot has a basis
            // entry but no generators entry" throw below.
            val vcol = Chain[Simplex[Int], CoefficientT](sigma)
            generators(tau) = vcol
            cleared += tau
            bars.append(
              PersistenceBar(d, ClosedEndpoint(sigmaFv), OpenEndpoint(filtrationValue(tau)), Some(vcol))
            )
          case None =>
            val z = coboundaryOf(sigma)
            // Chain.reduceBy (SortedMap-based), not hand-rolled reduction over raw Chain arithmetic -- see
            // WORKLOG-naive-homology.md's "critical performance bug" for why that silently reintroduces
            // superlinear blowup on real VR streams. `basisFallback` (see above) supplies the on-the-fly
            // apparent-pair substitution when this reduction's own working chain hits an unclaimed pivot.
            val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty, basisFallback)
            // V-column: by construction (Algorithm 1's V_j, updated in lockstep with R_j = delta(V_j)
            // throughout), this is itself a genuine cocycle whenever reduced is zero -- no separate
            // cocycle-reconstruction step needed, unlike the naive engine's homology case. Collapsed
            // explicitly, and BEFORE either write below, for the same reason as CellularHomologyContext:
            // generators entries get read back into later cells' own vcol folds.
            val vcol: Chain[Simplex[Int], CoefficientT] =
              log.items.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
                acc - coeff ⊠ generators.getOrElse(
                  pivot,
                  throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
                )
              }
            vcol.collapseAll()
            if reduced.isZero() then
              // sigma's coboundary fully cancelled, AND (since we didn't skip it above) sigma was never
              // claimed as anyone's pivot: a genuine essential class born at sigma (dimension d). Emitted
              // unconditionally, including alongside a zero-length finite bar elsewhere in the list -- see
              // WORKLOG-cohomology.md on why zero-length bars must not be silently dropped at this stage.
              bars.append(PersistenceBar(d, ClosedEndpoint(sigmaFv), PositiveInfinity(), Some(vcol)))
            else
              // reduced is nonzero: its leading cell (the OLDEST cofacet remaining, per cohomologyOrdering)
              // is sigma's death partner. Birth = sigma (dimension d, the column); death = that pivot
              // (dimension d+1, the row) -- see WORKLOG-cohomology.md's birth/death/dimension derivation.
              val pivot = reduced.leadingCell.get
              basis(pivot) = reduced
              generators(pivot) = vcol
              cleared += pivot
              bars.append(
                PersistenceBar(
                  d,
                  ClosedEndpoint(sigmaFv),
                  OpenEndpoint(filtrationValue(pivot)),
                  Some(vcol)
                )
              )

      // Assemble the NEXT dimension's candidates from every dimension-d simplex, cleared ones included --
      // see WORKLOG-lazy-enumeration.md's "Session 2" section, confirmed from ripser.cpp's own
      // `assemble_columns_to_reduce`: `next_simplices.push_back(...)` runs unconditionally, BEFORE the
      // `is_in_zero_apparent_pair`/already-a-pivot exclusion checks that shrink `columns_to_reduce`.
      // Clearing controls which simplices get independently REDUCED at a dimension, never which simplices
      // are a valid source for generating the next dimension's cofacets -- a cleared simplex's own higher
      // cofacets still genuinely exist in the complex. Getting this backwards would silently omit real
      // simplices from every dimension above the first one with a cleared/apparent-paired member.
      if d < maxDimension then currentLevel = simplicesAtD.iterator.flatMap(sparseCofacets).toSeq

    bars.toList
