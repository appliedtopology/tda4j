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

/** Thin wrapper mirroring `SimplicialHomologyContext`'s own relationship to `CellularHomologyContext` -- `Cube` needed
  * nothing new from the naive engine (it is already generic over `CellT: OrderedCell`), so this exists purely for the
  * same ergonomic reason `SimplicialHomologyContext` does: a concrete, easily-discoverable name instead of writing out
  * `CellularHomologyContext[Cube, CoefficientT, FiltrationT]` at every call site.
  */
class CubicalHomologyContext[CoefficientT: Field, FiltrationT: Ordering]()
    extends CellularHomologyContext[Cube, CoefficientT, FiltrationT] {}

/** Naive persistent homology via the standard single-pivot-table reduction algorithm: process cells in filtration
  * order, reduce each cell's boundary against the pivots recorded so far, and every cell either opens a class (reduced
  * boundary is zero) or closes one (reduced boundary is nonzero, and its leading cell -- the pivot -- is necessarily a
  * previously-opened, still-unpaired cell).
  *
  * No clearing, no chunking, no cohomology/twist optimization: this is the reference-grade baseline the other algorithm
  * in this file (`PersistenceInChunksContext`) can be cross-validated against.
  *
  * Correctness note for future maintainers: the `RingModule`/`Ordering[CellT]` instances used for chain arithmetic MUST
  * be summoned inside `HomologyState`, not at `CellularHomologyContext` class scope. A `given Ordering[CellT]` derived
  * from a per-stream `filtrationOrdering` only exists once a stream is available (i.e. inside `HomologyState`);
  * summoning `Chain[CellT, CoefficientT] is RingModule` any earlier silently falls back to the generic,
  * filtration-blind `OrderedCell`-derived ordering and bakes it into that RingModule instance's closures permanently
  * (Scala resolves a given's own implicit parameters once, at the point the given is constructed, not at each later
  * call to its methods). A real, confirmed bug -- see `.claude/WORKLOG-naive-homology.md`.
  */
class CellularHomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

  import barcode.*

  class HomologyState(
    boundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]], // pivot cell -> reduced boundary column
    generators: mutable.Map[CellT, Chain[CellT, CoefficientT]], // pivot cell -> V-column of its producing cell
    val positives: mutable.Map[
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

    // NOT stream.iterator directly: for any StratifiedCellStream, that default is DIMENSION-MAJOR (all of
    // dimension d before any of dimension d+1). That's correct only WITHIN each dimension's own bucket -- but
    // the single shared pivot table this algorithm uses (boundaries/positives, populated across ALL dimensions
    // together) requires cells consumed in TRUE combined filtration order: a dimension-(d-1) cell can
    // legitimately become a dimension-d cell's pivot, and Algorithm 1's correctness proof depends on processing
    // all dimensions together in one oldest-to-youngest sequence. Dimension-major order is not generally
    // consistent with that, since filtration value has no general monotonicity with dimension across unrelated
    // simplices -- confirmed by direct reproduction on two independently-implemented streams, ruling out a
    // stream-specific bug.
    //
    // Built explicitly, NOT via `stream.filtrationOrdering.reverse` wholesale (a first attempt, reverted):
    // `filtrationOrdering`'s tie-break is `(dimension, then colex)` UNREVERSED even though its primary key
    // (filtration value) IS reversed, so `.reverse` on the WHOLE ordering also flips the dimension tie-break --
    // under `.reverse` a tie sorts LARGER dimension first, so a triangle processes before its own longest edge
    // whenever they tie (every VR triangle, by definition), exactly backwards from Algorithm 1's precondition
    // that every cell's faces appear before it. This never surfaced from any single-dimension
    // `.sorted(using filtrationOrdering.reverse)` call site elsewhere, since within one dimension the dimension
    // tie-break is a no-op. Built instead as: ascending filtration value (oldest first, unreversed), THEN
    // ascending dimension (faces before cofaces on a tie), falling back to `stream.filtrationOrdering.reverse`
    // only to inherit the established within-dimension tie-break (colex) bit-for-bit -- safe there because two
    // cells of the SAME dimension never reach the dimension key above.
    val processingOrder: Ordering[CellT] =
      Ordering
        .by[CellT, FiltrationT](c => stream.filtrationValue.applyOrElse(c, (_: CellT) => stream.smallest))
        .orElse(Ordering.by[CellT, Int](_.dim))
        .orElse(stream.filtrationOrdering.reverse)
    val cellIterator: collection.BufferedIterator[CellT] =
      stream.iterator.toVector.sorted(using processingOrder).iterator.buffered

    private def cellFiltrationValue(cell: CellT, fallback: FiltrationT): FiltrationT =
      stream.filtrationValue.applyOrElse(cell, (_: CellT) => fallback)

    // The persistence matching partitions every cell into POSITIVE (its own reduced boundary is zero -- a
    // creator, recorded in `positives` until matched) or NEGATIVE (its own reduced boundary is nonzero -- a
    // destroyer, matched immediately with the positive pivot it kills, recorded via `boundaries`/`generators`
    // keyed by THAT pivot, never by itself). `boundaries` only ever holds POSITIVE (matched) cells as keys -- so
    // when a LATER column's reduction cascades down onto a NEGATIVE cell, `boundaries.get` correctly finds
    // nothing, but that does NOT mean reduction is finished: the negative cell isn't an available pivot, but it
    // isn't an inexpressible dead end either, since it has its own V-column (recorded right below, at the
    // moment it went negative). Recording that here, keyed by the negative cell itself, and threading it
    // through `Chain.reduceBy`'s existing `fallback` hook (the same mechanism `RipserCohomologyContext` uses
    // for its own apparent-pairs substitution) lets reduction substitute and continue past a negative cell
    // exactly as it already does past a positive one. See `.claude/WORKLOG-reference-engine-fix.md`.
    val negativeVCols: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty

    def advanceOne(): Unit =
      if cellIterator.hasNext then
        val sigma: CellT = cellIterator.next()
        val dsigma: Chain[CellT, CoefficientT] = Chain.from(sigma.boundary[CoefficientT])
        // Chain.reduceBy (the SortedMap-based object-level primitive shared with
        // PersistenceInChunksContext), not a hand-rolled reduction over raw Chain arithmetic: `-`/`⊠`
        // on Chain objects only collapse the *head* of their internal priority queue lazily, so a
        // hand-rolled fold accumulating many raw subtractions builds up an ever-growing backlog of
        // uncollapsed duplicate entries -- fine for tiny hand-built fixtures, but quadratic-to-worse
        // blowup on a real Vietoris-Rips stream. Chain.reduceBy works through a SortedMap internally,
        // which collapses duplicates by construction on every insertion. `fallback = negativeVCols.get`
        // lets reduction substitute past an already-matched NEGATIVE cell too -- see the field's own doc.
        val (reduced, log) = Chain.reduceBy(dsigma, boundaries, Chain.empty, fallback = negativeVCols.get)
        // V-column: sigma minus, for every pivot the reduction subtracted off, that pivot's own
        // producing cell's V-column. If ∂sigma reduced to zero this chain is itself the new cycle
        // representative; either way it becomes the generator future cells reduce through if sigma
        // itself goes on to become a pivot. Collapsed explicitly before use for the same reason as
        // above: this fold also accumulates through raw Chain subtraction.
        val vcol: Chain[CellT, CoefficientT] = log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
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
      while cellIterator.hasNext && f >= cellFiltrationValue(cellIterator.head, filtration.smallest) do advanceOne()

    def advanceAll(): Unit =
      while cellIterator.hasNext do advanceOne()

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
      val essentialUpper: FiltrationT = if cellIterator.hasNext then f else filtration.largest
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
  import barcode.*

  // The real internal ceiling: one dimension higher than what's reported, so a class born AT maxDim can still be
  // correctly killed by a genuine (maxDim + 1)-cell rather than looking essential purely because nothing above
  // maxDim was ever considered. See the class doc above.
  private val internalMaxDim: Int = maxDim + 1

  class HomologyState(
    boundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    stream: StratifiedCellStream[CellT, Double],
    barcode: mutable.Map[Int, immutable.Queue[(Double, Double, Chain[CellT, CoefficientT])]],
    // Representative cycle for a still-open (essential) class, keyed by the cell itself. Populated eagerly by
    // unionFindDim01 for dimension 0 (root vertices, trivially `Chain(root)`) and dimension 1 (cycle-forming
    // edges, via the spanning-forest-path construction in unionFindDim01's own doc) -- both cheap, bounded by
    // the union-find pass itself. Dimension >= 2 essential cells are NOT populated here eagerly; barcodeAt
    // fills this map lazily, on first request, via vcolOf (see its own doc) -- essential classes are typically
    // few (bounded by the Betti number at that dimension), so computing their representatives on demand,
    // reusing this class's own already-complete `boundaries`/`killer` state, costs only what each one's own
    // reduction actually touches, not a second full pass over every cell.
    essentialRepresentatives: mutable.Map[CellT, Chain[CellT, CoefficientT]]
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
    // Reverse of `killer` (killer cell -> the pivot it kills), maintained alongside it everywhere `killer` is
    // set -- needed by vcolOf (see its own doc) to recover a PAIRED cell's own pivot without a full scan of
    // `killer`, and without relying on `R` (never populated by unionFindDim01 for dimension 0/1 tree edges).
    val killerOf: mutable.Map[CellT, CellT] = mutable.Map.empty
    // R supplies R_k for unpaired column k, to be used in marking active entries
    val R: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
    // active entries
    val activeRows: mutable.Map[CellT, Boolean] = mutable.Map.empty

    // Guards unionFindDim01 against re-running: diagramAt calls advanceAll unconditionally on every query,
    // and every OTHER mutation in this class is behind an idempotency check (processCell no-ops on an
    // already-cleared/paired cell; compress/globalReduce only run on cells not yet resolved) specifically so
    // repeated advanceAll calls on the same state are safe. unionFindDim01 has no such per-cell check of its
    // own (it always re-derives the same pairs from scratch), so without this flag a second diagramAt call
    // would append a duplicate bar to barcode(0) for every dimension-0/1 pair, every time.
    private var dim01Resolved: Boolean = false

    /** Raw, `Chain`-free elder-rule union-find for dimensions 0 and 1, replacing the general `Chain.reduceByUntil`
      * machinery for these two dimensions specifically -- see `.claude/DESIGN-unionfind-in-chunks.md` for the full
      * derivation. Two facts make this a safe, self-contained substitution rather than an approximation:
      *
      *   - `Chain.reduceByUntil`'s own reduction (`reduceLoop`, `Chain.scala`) is a canonical fixpoint over a FIXED
      *     total order (`Ordering[CellT]` above) -- given that order, the reduced boundary matrix in any two dimensions
      *     is uniquely determined regardless of what order individual columns are reduced in. Elder-rule union-find,
      *     run strictly in the stream's own filtration order, computes exactly that same canonical answer for
      *     dimensions 0/1 by a cheaper algorithm, not a different one.
      *   - Nothing above dimension 1 ever needs what this method deliberately does NOT populate: `boundaries` at a
      *     vertex key (only edges ever appear in a HIGHER cell's own boundary -- 2-cells reference only edges, never
      *     vertices directly) or an `R` entry for a cycle-forming ("survivor") edge (`markColumn` only chases `killer`
      *     for cells in `cleared`, never for a cell that is merely `paired` or merely in `essentialSimplices`). Both
      *     were confirmed by tracing every read site in `markActiveEntries`/
      *     `eliminationFallback`/`compress`/`globalReduce`, not assumed.
      *
      * `Ordering[CellT]` (`stream.filtrationOrdering`) already encodes "smaller = younger" -- the same convention
      * `Chain`'s own pivot selection (`leadingCell`, `Chain.from`'s reversed `PriorityQueue` ordering) uses to pick the
      * youngest term as a boundary's pivot. "Elder rule" here is therefore just "union by this ordering": of two roots
      * being merged, the smaller (younger) one always becomes the child, and is the vertex recorded as dying.
      *
      * Generic over `CellT` (works for `Simplex`, `Cube`, `FiniteSimplicialSet` generators alike) -- a dimension-1
      * cell's boundary always has exactly two terms for every concrete `OrderedCell` in this codebase (two distinct
      * vertices for `Simplex`/`Cube`; for `FiniteSimplicialSet`, always two terms too, since a dimension-0 element can
      * never be degenerate -- there is no dimension below 0 to degenerate from -- though the two terms can reference
      * the SAME vertex, e.g. a self-loop edge like `SimplicialSetFixtures.minimalSphere(1)`'s). Comparing ROOTS after
      * `find`, not raw endpoints, handles that case uniformly: a same-vertex self-loop resolves to a single root
      * immediately, correctly read as cycle-forming, with no special case needed.
      */
    def unionFindDim01(): Unit =
      if dim01Resolved then ()
      else
        dim01Resolved = true
        val vertices: Vector[CellT] =
          stream.iterateDimension.applyOrElse(0, (_: Int) => Iterator.empty).toVector
        val vertexIndex: Map[CellT, Int] = vertices.zipWithIndex.toMap
        val parent: Array[Int] = Array.range(0, vertices.size)

        def find(i: Int): Int =
          var root = i
          while parent(root) != root do root = parent(root)
          var cur = i
          while parent(cur) != root do
            val next = parent(cur)
            parent(cur) = root
            cur = next
          root

        val ord = summon[Ordering[CellT]]
        // Tree edges (r0 != r1) are collected here, not resolved into a representative inline -- building the
        // dim-1 essential (cycle-forming edge) representatives below needs the FULL spanning forest, not a
        // partial one, and needs it via an explicit, uncompressed parent-edge structure distinct from `parent`
        // above (which IS heavily path-compressed by the time this loop finishes, so it can no longer answer
        // "which edge actually connects v to its tree-parent" for an arbitrary non-root v).
        val treeEdges: mutable.ArrayBuffer[CellT] = mutable.ArrayBuffer.empty
        val cycleEdges: mutable.ArrayBuffer[CellT] = mutable.ArrayBuffer.empty
        stream.iterateDimension.applyOrElse(1, (_: Int) => Iterator.empty).foreach { edge =>
          val endpoints = edge.boundary[CoefficientT]
          val r0 = find(vertexIndex(endpoints(0)._1))
          val r1 = find(vertexIndex(endpoints(1)._1))
          if r0 == r1 then
            essentialSimplices += edge
            cycleEdges += edge
          else
            val (youngRoot, oldRoot) =
              if ord.lt(vertices(r0), vertices(r1)) then (r0, r1) else (r1, r0)
            parent(youngRoot) = oldRoot
            val dyingVertex = vertices(youngRoot)
            cleared += dyingVertex
            paired += edge
            killer(dyingVertex) = edge
            killerOf(edge) = dyingVertex
            treeEdges += edge
            val lower =
              stream.filtrationValue.applyOrElse(dyingVertex, (_: CellT) => Double.NegativeInfinity)
            val upper =
              stream.filtrationValue.applyOrElse(edge, (_: CellT) => Double.PositiveInfinity)
            // Chain(dyingVertex), not Chain.empty: a dimension-0 bar's representative is trivially the dying
            // vertex itself (every 0-chain is automatically a cycle, no dimension -1 to map to) -- exactly what
            // CellularHomologyContext's own `cycles` map holds at dimension 0
            // (`cycles.addAll(stream.iterateDimension(0).map(cell => cell -> Chain(cell)))`). Matching that
            // exactly, not just storing SOME valid cycle, is what makes this trustworthy enough to expose via
            // barcodeAt -- see .claude/CLAUDE.md's coefficients-and-representatives design principle and
            // barcodeAt's own doc below for the current scope boundary (dimension 0 only, for now).
            barcode(0) = barcode.getOrElse(0, immutable.Queue.empty).appended((lower, upper, Chain(dyingVertex)))
        }
        vertices.indices.foreach { i =>
          if find(i) == i then
            essentialSimplices += vertices(i)
            essentialRepresentatives(vertices(i)) = Chain(vertices(i))
        }

        // Dimension-1 essential (cycle-forming) edge representatives: a real spanning-forest-path
        // construction, NOT a trivial one -- a lone cycle-forming edge is not itself a cycle (its own
        // boundary is generally nonzero), unlike the dimension-0 case above. Built from `treeEdges` alone
        // (never touching `parent`'s path-compressed pointers, which have already lost the actual historical
        // tree shape by this point): one plain BFS per component over an adjacency list assembled from tree
        // edges only, computing `pathFromRoot(v)` incrementally so that `boundary(pathFromRoot(v)) ==
        // Chain(v) - Chain(root)` always holds -- verified by `PersistenceInChunksSpec`/`HomologySpec`'s own dd=0
        // checks over a signed field, not just asserted here. For a tree edge `e` with `e.boundary` terms
        // `(u, cu)` (already resolved, i.e. `u`'s own path is known) and `(v, cv)` (the newly-reached vertex):
        // `boundary(Chain(e)) = cu*u + cv*v`, and since `boundary(Chain(u)) = 0` trivially (dimension 0), the
        // correction term `(1/cv) * Chain(e)` alone has `boundary = (cu/cv)*u + v`, which equals `v - u`
        // exactly because every dimension-1 boundary in this codebase (Simplex, Cube, FiniteSimplicialSet
        // alike) has its two coefficients as additive inverses (`cu = -cv`) -- so `pathFromRoot(v) =
        // pathFromRoot(u) + (1/cv) ⊠ Chain(e)`, no `Chain(u)` term needed at all.
        val adjacency: mutable.Map[CellT, mutable.ArrayBuffer[(CellT, CellT)]] = mutable.Map.empty
        treeEdges.foreach { edge =>
          val endpoints = edge.boundary[CoefficientT]
          val v0 = endpoints(0)._1
          val v1 = endpoints(1)._1
          adjacency.getOrElseUpdate(v0, mutable.ArrayBuffer.empty) += ((v1, edge))
          adjacency.getOrElseUpdate(v1, mutable.ArrayBuffer.empty) += ((v0, edge))
        }
        val fld = summon[CoefficientT is Field]
        val pathFromRoot: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
        vertices.indices.foreach { i =>
          if find(i) == i then
            val root = vertices(i)
            pathFromRoot(root) = Chain.empty
            val stack = mutable.Stack(root)
            while stack.nonEmpty do
              val u = stack.pop()
              adjacency.getOrElse(u, mutable.ArrayBuffer.empty).foreach { case (v, edge) =>
                if !pathFromRoot.contains(v) then
                  val cv = edge.boundary[CoefficientT].find(_._1 == v).get._2
                  pathFromRoot(v) = pathFromRoot(u) + (fld.invert(cv) ⊠ Chain(edge))
                  stack.push(v)
              }
        }
        cycleEdges.foreach { edge =>
          val endpoints = edge.boundary[CoefficientT]
          val (v0, c0) = endpoints(0)
          val (v1, c1) = endpoints(1)
          essentialRepresentatives(edge) = Chain(edge) - (c0 ⊠ pathFromRoot(v0)) - (c1 ⊠ pathFromRoot(v1))
        }

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

    // Memoized, on-demand V-column for a cell already resolved by advanceAll -- the mechanism that closes the
    // representative gap for essential bars at dimension >= 2 (dimension 0/1 are handled entirely by
    // unionFindDim01 above, cheaply, and never call this). Modeled directly on
    // `CellularHomologyContext.advanceOne`'s own audited V-column formula and on
    // `PackedRipserCohomologyContext.persistentCohomology`'s inline `generators` tracking (the project lead's
    // own pointer to how Ripser reconciles clearing/optimizations with real representatives, without a second
    // pass) -- but evaluated lazily, per cell, AFTER advanceAll has already finished and `boundaries`/`killer`
    // are complete and immutable, rather than threaded through processCell/compress/globalReduce's own
    // multi-round local/global state. That's a deliberate, checked choice, not a simplification of convenience:
    // `boundaries` only ever grows (a pivot is recorded exactly once, at recordPair time, and no code path ever
    // overwrites an existing entry -- confirmed by reading every call site), so a reduction that finds zero
    // against a PARTIAL boundaries table (e.g. processCell's stop-bounded local pass) is already the correct
    // answer against the COMPLETE one too: reduceLoop only ever narrows an accumulator, never un-eliminates a
    // term, so an already-empty result can't change when more substitutions become available. That monotonicity
    // is exactly what makes calling this post-hoc, over the finished `boundaries`, safe -- it reproduces the
    // SAME elimination sequence advanceAll's own local/global passes would have found, without needing to track
    // where in that multi-round process any given cell was actually resolved.
    //
    // A PAIRED cell x is the one place that monotonicity argument doesn't apply as-is: x's own reduction
    // stopped the moment it found its pivot (`killerOf(x)`, frozen at recordPair/unionFindDim01 time -- once
    // set, never changed). Re-reducing x's boundary against the NOW-complete `boundaries` with no stop at all
    // would let reduction run PAST that pivot (now itself available as a substitution target, since whatever
    // killed x's pivot was necessarily recorded too), producing a different elimination log than the one that
    // actually determined x's own bar -- and, worse, risking a recursive call back into a cell whose vcol
    // depends on x's own. Guarded by reducing with `stop = (c => c == pivotOf(x))`, reproducing exactly where
    // the original reduction halted. `pivotOf` reads `killerOf` (the reverse of `killer`), not `R` -- `R` is
    // never populated for dimension 0/1 tree edges (unionFindDim01 is deliberately Chain-free), so it can't
    // serve this lookup uniformly across both the union-find fast path and the general processCell/compress/
    // globalReduce path.
    //
    // A second, necessary fallback: `boundaries` alone is NOT a sufficient `basis` for this reduction. It only
    // ever holds entries for CLEARED (pivot) cells -- a PAIRED cell (a killer, of ANY dimension, tree edges
    // included) never gets one, by construction. But a cell's raw boundary can perfectly well have a PAIRED
    // cell as a term that still needs eliminating before reduction can reach zero (e.g. a dimension-3 pivot's
    // own 4-triangle boundary can reduce to a 1-term, uneliminated residual instead of zero, whenever its
    // youngest face is a PAIRED triangle with no `boundaries` entry to substitute through). This is exactly the
    // gap `CellularHomologyContext.advanceOne`'s own `negativeVCols` fallback closes for the naive engine --
    // ported here the same way, just computed lazily via `vcolOf` itself instead of a map populated during a
    // single sequential sweep: `fallback` below recurses into `vcolOf(pairedCell)`, whose own result always has
    // `pairedCell` as its leading term (by the same induction the naive engine's own `negativeVCols` relies on),
    // satisfying `Chain.reduceByUntil`'s fallback contract.
    //
    // Termination: `boundaries(pivot)`'s own content, and any PAIRED cell reached via the fallback, can only
    // reference cells that were ALREADY resolved (recorded in `boundaries`/`killer`) at the moment `killer(x)`
    // itself got set -- so the "which cells does resolving x eliminate" relation is acyclic by the very order
    // recordPair/unionFindDim01 calls actually happened in, regardless of filtration order. Trusted but not
    // ONLY trusted: `vcolInProgress` below detects a real cycle at runtime and fails loudly instead of
    // overflowing the stack, in case this reasoning is wrong for some case not yet found.
    private val vcolCache: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty
    private val vcolInProgress: mutable.Set[CellT] = mutable.Set.empty

    private def pivotOf(x: CellT): CellT =
      killerOf.getOrElse(x, throw new IllegalStateException(s"vcolOf: paired cell $x has no recorded pivot"))

    def vcolOf(sigma: CellT): Chain[CellT, CoefficientT] =
      vcolCache.get(sigma) match
        case Some(c) => c
        case None    =>
          if vcolInProgress.contains(sigma) then
            throw new IllegalStateException(
              s"vcolOf: cycle detected reconstructing $sigma's representative -- the acyclic-elimination " +
                "invariant this relies on was violated"
            )
          vcolInProgress += sigma
          val dsigma: Chain[CellT, CoefficientT] = Chain.from(sigma.boundary[CoefficientT])
          val stop: CellT => Boolean =
            if paired.contains(sigma) then
              val myPivot = pivotOf(sigma)
              (c: CellT) => c == myPivot
            else (_: CellT) => false
          val fallback: CellT => Option[Chain[CellT, CoefficientT]] =
            (c: CellT) => if paired.contains(c) then Some(vcolOf(c)) else None
          val (_, log) = Chain.reduceByUntil(dsigma, boundaries, Chain.empty, stop, fallback)
          // Mirrors CellularHomologyContext.advanceOne's own vcol fold exactly (see its doc for the full
          // derivation): a term eliminated via `boundaries` (CLEARED) corresponds to a real dimension-shift
          // relationship (`boundaries(pivot) = boundary(vcolOf(killer(pivot)))`) that sigma's own vcol must
          // correct for. A term eliminated via the fallback above (PAIRED) is a pure same-dimension basis
          // rewrite with no such relationship -- it doesn't change what dsigma's reduction VALUE is, only how
          // it's expressed -- so it needs NO correction here at all, not a different one.
          val vcol = log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (eliminated, coeff)) =>
            if paired.contains(eliminated) then acc
            else
              val k = killer.getOrElse(
                eliminated,
                throw new IllegalStateException(s"vcolOf: eliminated pivot $eliminated has no recorded killer")
              )
              acc - (coeff ⊠ vcolOf(k))
          }
          vcol.collapseAll()
          vcolInProgress -= sigma
          vcolCache(sigma) = vcol
          vcol

    /** Representative-annotated barcode, per `.claude/CLAUDE.md`'s coefficients-and-representatives design principle --
      * mirrors `CellularHomologyContext.barcodeAt`'s output shape exactly (`List[PersistenceBar[Double, Chain[CellT,
      * CoefficientT]]]`), and attaches a REAL representative to every reported bar (finite or essential, any dimension
      * `<= maxDim`) -- computed by REUSING this class's own already-computed reduction state, not by running a second,
      * independent engine over the same cells (see `vcolOf`'s own doc, and `.claude/WORKLOG-chunks-representatives.md`
      * for the full derivation, including an earlier delegate-based design that was tried, rejected, and replaced with
      * this one).
      *
      * Finite bars (any dimension): the `Chain` already stored in `barcode` by `recordPair`/`unionFindDim01` --
      * `dsigmaReduced`, or `Chain(dyingVertex)` at dimension 0. This costs nothing extra: `R_sigma = boundary(V_sigma)`
      * always (an inductive consequence of `d^2 = 0` plus how `recordPair`'s `generators`-equivalent chain is built),
      * so whatever chunks' own local/global reduction produced is already a genuine cycle, independent of which
      * specific elimination path (local `processCell`, or `compress`/`globalReduce`'s compression shortcuts) produced
      * it -- verified by `PersistenceInChunksSpec`'s tie-heavy-clique sweep, where local `processCell` alone can't
      * reach the pivot (forcing genuinely globally-resolved pairs), checking `boundary(rep) == 0` over a signed field.
      *
      * Essential bars: dimension 0 (`Chain(rootVertex)`) and dimension 1 (spanning-forest-path cycles) come from
      * `essentialRepresentatives`, populated eagerly by `unionFindDim01`. Dimension >= 2 is populated lazily, here, via
      * `vcolOf` -- cached into `essentialRepresentatives` on first request so a repeated `barcodeAt` call doesn't
      * recompute it.
      */
    def barcodeAt(f: Double): List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] =
      advanceAll()

      def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
        if lower && v == Double.NegativeInfinity then NegativeInfinity()
        else if !lower && v == Double.PositiveInfinity then PositiveInfinity()
        else if lower then ClosedEndpoint(v)
        else OpenEndpoint(v)

      val finite: List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] =
        barcode.toList.flatMap { case (dim, bars) =>
          bars.toList.collect {
            case (lower, upper, chain) if lower <= f =>
              // The chain STORED here (dsigmaReduced from recordPair, or Chain(dyingVertex) from
              // unionFindDim01) is trustworthy as-is only at dim 0 -- for dim >= 1, `compress`'s own
              // "inactive row" shortcut (eliminationFallback's `Some(Chain(l))` self-cancel branch) can DROP
              // terms from what's stored, which is provably safe for the algorithm's own PIVOT correctness
              // (the paper's own clearing argument) but NOT for the stored VALUE remaining a genuine cycle --
              // `PersistenceInChunksSpec` finds `boundary(dsigmaReduced) != 0` on globally-resolved
              // (compress/globalReduce-heavy) fixtures, exactly the case a purely locally-resolved fixture
              // can't exercise. `chain.leadingCell` (the pivot identity) remains trustworthy regardless --
              // that's what clearing's correctness argument actually guarantees -- so it's used here only to
              // look up the REAL representative, not as the representative itself.
              val rep =
                if dim == 0 then chain
                else
                  val pivot = chain.leadingCell.get
                  if dim == 1 then essentialRepresentatives(pivot) // the tree-path cycle unionFindDim01 built
                  // for this edge back when it was still cycle-forming/essential, before recordPair promoted
                  // it out of essentialSimplices -- that promotion never removes the representative itself.
                  else vcolOf(pivot)
              new PersistenceBar(dim, endpoint(true)(lower), endpoint(false)(upper min f), Some(rep))
          }
        }

      val essential: List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] =
        essentialSimplices.toList.filter(_.dim <= maxDim).map { sigma =>
          val lower =
            stream.filtrationValue.applyOrElse(sigma, (_: CellT) => Double.NegativeInfinity)
          val rep = essentialRepresentatives.getOrElseUpdate(sigma, vcolOf(sigma))
          new PersistenceBar(
            sigma.dim,
            endpoint(true)(lower),
            PositiveInfinity(),
            Some(rep)
          )
        }

      finite ++ essential

    def recordPair(sigma: CellT, dsigmaReduced: Chain[CellT, CoefficientT]): Unit =
      val pivot = dsigmaReduced.leadingCell.get
      boundaries(pivot) = dsigmaReduced
      cleared += pivot
      paired += sigma
      killer(pivot) = sigma
      killerOf(sigma) = pivot
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
            Rk.rawEntries.iterator.foreach { case (i, _) =>
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
    // downstream); a paired row ALWAYS substitutes via its own V-column (vcolOf), unconditional on
    // activity -- see below for why the earlier active/inactive self-cancel split was wrong for this
    // branch specifically.
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
    //
    // The paired branch's earlier "self-cancel if inactive, else None (legitimate final pivot)" logic was
    // ITSELF a second, distinct bug (`.claude/WORKLOG-chunks-pairing-bug.md`): `Chain(l)` (trivial
    // self-cancel) is NOT a valid substitute for a paired cell in general -- it just drops l from the
    // accumulator, which is only correct if l's own true contribution happens to be exactly l itself.
    // Compare CellularHomologyContext.advanceOne's own fallback, `negativeVCols.get`: a REAL, possibly
    // multi-term V-column (leading term = the cell itself), not a placeholder. `vcolOf` (built earlier in
    // this class for barcodeAt's own representative tracking) computes exactly this same quantity for a
    // paired cell, so reusing it here closes the gap without reintroducing full incremental V-column
    // bookkeeping into the local/global passes. Safe to call mid-algorithm (not just post-advanceAll,
    // vcolOf's original use) specifically BECAUSE advanceAll's own reconciliation step (see its own doc,
    // right before markActiveEntries) guarantees every cell's cleared/paired classification -- and hence
    // every dependency vcolOf might recurse into -- is already final by the time compress/globalReduce (and
    // therefore eliminationFallback) ever run: nothing below reopens or reclassifies an already-paired cell.
    // Dropping the "active" gate entirely for this branch matches CellularHomologyContext, which has no
    // equivalent concept at all -- `negativeVCols.get` is consulted unconditionally whenever a paired cell
    // is hit as a term needing elimination.
    def eliminationFallback(l: CellT): Option[Chain[CellT, CoefficientT]] =
      if cleared.contains(l) then
        if activeRows.getOrElse(l, false) then killer.get(l).map(j => R.getOrElse(j, Chain.empty))
        else Some(Chain(l))
      else if paired.contains(l) then Some(vcolOf(l))
      else None

    // Algorithm 4: global column compression from clear-and-compress paper.
    // The paper writes this over Z/2; over a general field we have to scale the
    // killer column by the right ratio.
    //
    // Implemented via Chain.reduceByUntil's own fixpoint loop (the same primitive processCell/globalReduce
    // use), NOT a hand-rolled single pass over Rk.rawEntries.toSeq -- an earlier version took that static
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
      // Dimensions 0/1 are fully resolved by raw union-find (cleared/paired/killer/barcode(0)/
      // essentialSimplices all populated directly) -- see unionFindDim01's own doc. Both loops below
      // therefore start at delta=2, not delta=0: dimension 1 (edges) was the actually-expensive part of the
      // general algorithm (dimension 0 was already free -- a vertex's boundary is always empty), and nothing
      // above dimension 1 reads any state this pre-pass leaves unpopulated (see unionFindDim01's doc for the
      // two specific facts checked before relying on that).
      unionFindDim01()

      val n: Int = allCells.size
      val m: Int = (n + chunkSize - 1) / chunkSize

      val chunks: IndexedSeq[IndexedSeq[CellT]] =
        allCells.grouped(chunkSize).toIndexedSeq

      // Algorithm 2: local_reduction from clear-and-compress paper. Walks to internalMaxDim (maxDim + 1), not
      // maxDim -- see the class doc above for why the extra dimension is needed.
      for delta <- internalMaxDim.to(2, -1) do
        for r <- 1.to(2) do
          for b <- (r - 1).until(m) do // parallelizable!
            val floorIdx: Int = math.max(0, (b - r + 1) * chunkSize)
            val stop: CellT => Boolean =
              sigma => cellIndex.getOrElse(sigma, -1) < floorIdx
            for sigma <- chunks(b) if sigma.dim == delta do processCell(sigma, stop)

      // Resolve any cell left "in limbo" by the local phase above: processCell found a nonzero
      // R value (this cell genuinely has unresolved content, i.e. is NOT locally essential) but
      // deferred recordPair because its chosen pivot fell outside that round's local window
      // (stop fired every time it was visited). Left unresolved, such a cell is neither cleared
      // nor paired -- indistinguishable, to eliminationFallback's catch-all `else None` case,
      // from a cell that's genuinely essential (R actively removed). That catch-all treats
      // "else None" as "no content, safe for anyone to claim as their own pivot" -- true for a
      // genuinely essential cell, false for one merely pending its own global resolution. Because
      // the global phase below processes dimensions top-down (like the local phase), a HIGHER-
      // dimension cell's globalReduce can reach a lower-dimension in-limbo cell as a substituted
      // term *before* that lower cell's own compress/globalReduce ever runs -- wrongly claiming
      // it as ITS OWN final pivot and stealing it from its rightful killer relationship (a real, traced
      // bug: `.claude/WORKLOG-benchmark-and-chunks-bug.md` section 5 has the fixture).
      //
      // Fixed by re-reducing every in-limbo cell's raw boundary, with no local window, in filtration
      // order -- exactly what the local phase itself would have done for it eventually, just without
      // deferring. Re-deriving dsigma fresh and reducing against the current (accumulated) boundaries
      // -- rather than recordPair-ing the stale stored R value directly -- matters when two in-limbo
      // cells share a dimension: resolving the earlier one first can newly clear a pivot the later
      // one's own raw boundary also contains, exactly the same sequential same-dimension resolution
      // the ordinary (non-deferred) local pass already relies on.
      //
      // NOT a plain `processCell(sigma, stop)` call (which reduces against `boundaries` alone, no
      // fallback): the ordinary local phase never needs a fallback, because it runs strictly
      // dimension-descending, so a dimension-d cell's local reduction can never encounter an
      // already-`paired` (dimension < d) term. This reconciliation step is different: it runs once,
      // across ALL dimensions, AFTER the whole descending local phase -- so a higher-dimension in-limbo
      // cell resolved here CAN cascade into an already-paired lower-dimension cell (a cleared pivot's own
      // stored boundary can itself contain paired terms), and boundaries-only reduction has no way to
      // eliminate one it stumbles into, wrongly stopping on it as a final pivot instead. Using
      // eliminationFallback here (same as compress/globalReduce) closes that: a paired term now
      // substitutes via its own V-column (vcolOf) instead of becoming an illegitimate terminus. See
      // eliminationFallback's own doc for why calling vcolOf here, before the global phase has run, is
      // safe.
      for sigma <- allCells if R.contains(sigma) && !cleared.contains(sigma) && !paired.contains(sigma) do
        val dsigma: Chain[CellT, CoefficientT] = Chain.from(sigma.boundary[CoefficientT])
        val (dsigmaReduced, _) =
          Chain.reduceByUntil(dsigma, boundaries, Chain.empty, (_: CellT) => false, fallback = eliminationFallback)
        if dsigmaReduced.isZero() then
          R -= sigma
          essentialSimplices += sigma
        else
          R(sigma) = dsigmaReduced
          recordPair(sigma, dsigmaReduced)

      // Algorithm 3: mark_active_entries from clear-and-compress paper
      markActiveEntries()

      // Algorithm 5 (Persistence in chunks): per dim top-down, compress unpaired
      // global columns then reduce them. Clearing keeps positives' columns at zero.
      // Walks to internalMaxDim (maxDim + 1), not maxDim -- see the class doc above for why.
      for delta <- internalMaxDim.to(2, -1) do
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
      mutable.Map.empty,
      mutable.Map.empty
    )

/** Thin `Simplex`-specific wrapper around `CellularPersistenceInChunksContext`, exactly mirroring
  * `SimplicialHomologyContext`'s relationship to `CellularHomologyContext` above -- every existing call site
  * (`PersistenceInChunksContext[Int, Double](...)` etc.) keeps working unchanged, since the generic engine itself has
  * no `Simplex`-specific behavior anywhere in its body: everything goes through the generic `OrderedCell` interface
  * (`.dim`, `.boundary[CoefficientT]`), so genericizing was a pure type-annotation change, not a behavior change.
  * `Simplex[VertexT] is OrderedCell` resolves automatically here from `Ordering[VertexT]` alone
  * (`defaultSimplexIsOrderedCell`, `SimplexOrderedCell.scala`), same as `SimplicialHomologyContext` already relies on.
  * See `.claude/WORKLOG-simplicial-set-filtration.md`.
  */
class PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5)
    extends CellularPersistenceInChunksContext[Simplex[VertexT], CoefficientT](maxDim) {}

/** '''Test/reference oracle only -- not a production engine, and not what `TDA4j.scala`'s `engine="ripser"` calls.'''
  * `PackedRipserCohomologyContext` (`PackedRipserCohomology.scala`) is the production Ripser engine: same algorithm,
  * method for method, keyed on a packed `(Double, Long)` pair instead of a materialized `Simplex[Int]`, measured faster
  * and dramatically leaner on memory on real paper data. This class's remaining job is narrower than "a slower
  * production alternative": it's what `PackedRipserCohomologySpec` cross-validates the packed engine's
  * `DiameterIndex`-specific machinery against (its index-only `equals`/`hashCode`, its index-keyed
  * `basis`/`generators`/`cleared` maps) -- representation bugs no other spec would catch. It does NOT independently
  * validate the Ripser ALGORITHM itself (both engines share `SimplexIndexing`); that job belongs to
  * `SimplicialHomologyContext`, a genuinely different, boundary-based algorithm with no shared code path.
  *
  * Persistent cohomology via Ulrich Bauer's Ripser algorithm (arXiv:1908.02518), specialized to `Simplex[Int]`
  * Vietoris-Rips/clique complexes via the combinatorial number system (`SimplexIndexing`) -- a deliberate narrowing
  * from `CellularHomologyContext`'s generic `CellT: OrderedCell`, agreed with the project lead (`.claude/
  * WORKLOG-naive-homology.md`). One-shot: computes the full barcode in a single pass, no incremental querying.
  *
  * Includes clearing (see the `cleared` set in `persistentCohomology`): NOT merely a performance optimization on top of
  * an already-correct baseline -- a version without it produces spurious essential classes from dimension-d simplices
  * already claimed as pivots one dimension down, violating Proposition 3.1's "not a pivot anywhere" clause
  * (`.claude/WORKLOG-cohomology.md`). Do not "simplify" this against intuition without rereading that derivation.
  *
  * Also includes a PARTIAL apparent-pairs optimization (see `zeroApparentCofacet` and its use in
  * `persistentCohomology`) -- partial, and that qualifier matters: what's implemented is Definition 3.2 apparent-pair
  * identification wired into the reduction loop to SKIP `Chain.reduceBy`'s reduction pass for an apparent sigma (it's
  * guaranteed to be a no-op by Proposition 3.9/Lemma 3.3), NOT Ripser's further, larger optimization of never building
  * an apparent sigma's coboundary at all unless some other column's reduction actually needs it. `coboundaryOf(sigma)`
  * is still called in full on the shortcut path -- `basis(tau)` needs the COMPLETE reduced column (all of sigma's
  * cofacets, not just tau), not a truncated single-term stand-in. Measured a 1.35x-1.8x wall-clock win on n=12-20 point
  * random VR complexes at maxDimension=2 (growing with n): `Chain.reduceBy`'s recursive per-pivot `SortedMap` fold, not
  * the coboundary enumeration, dominates this loop's cost, so skipping just the reduction pass is still worthwhile.
  * Unlike Ripser's own C++ implementation, this shortcut does NOT need Ripser's `assemble_columns_to_reduce` exclusion
  * step or its `compute_pairs` on-the-fly substitution fallback -- this engine never removes a simplex from the set it
  * iterates over at a given dimension, so the true (mutual) Definition 3.2 apparent-pair check alone is sufficient for
  * THIS optimization to be safe. Getting the larger, lazy optimization would require also changing how
  * `persistentCohomology` enumerates each dimension's simplices in the first place (currently an eager
  * `(0 until binomial(n, d+1))` over the whole complex, unlike Ripser's own incrementally- assembled
  * `columns_to_reduce`) -- a separate, larger, not-yet-attempted project. See `.claude/WORKLOG-cohomology.md` for the
  * full derivation, grounded directly in Ripser's own `ripser.cpp` source and arXiv:1908.02518's Definition 3.2/3.11
  * and Proposition 3.9/Lemma 3.3.
  *
  * `useApparentPairs` (default `true`) exists so `ApparentPairsBenchmarkSpec` can isolate this optimization's own
  * effect: with it `false`, `persistentCohomology` always falls through to the ordinary `Chain.reduceBy` path, byte for
  * byte the same output as when it's `true` -- see `zeroApparentCofacet`'s doc for why the shortcut is provably a
  * no-op. Not intended as a user-facing tuning knob outside benchmarking.
  */
/** `maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`, not `Double.PositiveInfinity`: beyond that
  * radius, every vertex is within range of every other, so the complex is a cone from that point on and provably
  * contributes no further homology (Ripser paper, p. 412). Cutting there computes the exact same barcode over fewer
  * simplices -- confirmed against `RipserCohomologySpec`'s "thresholded barcode = untruncated barcode restricted to [0,
  * t]" test machinery -- unlike a genuine finite `maxFiltrationValue` below the enclosing radius, which DOES drop real
  * bars and remains an explicit, deliberate truncation the caller opts into. Still matches the production Ripser
  * engines' sparse-Rips convention, still NOT `AlphaShapeDQP`'s always-untruncated one. Pass `Double.PositiveInfinity`
  * explicitly for the old always-unbounded behavior.
  *
  * `memoizeFiltrationValue` (default `false`) is unaffected by this: Ripser's own historical design goal was memory
  * frugality, not raw speed, and a global cache of every filtration value ever touched runs directly against that on
  * large complexes. The actual replacement is `insertionDiameter`'s incremental diameter formula (below), which
  * eliminates the O(d^2) `MaximumDistanceFiltrationValue` recomputation for cofacet enumeration entirely, rather than
  * paying for it once and caching the answer.
  */
/** `maxDimension` means "top HOMOLOGICAL DEGREE reported," not "top simplex dimension built" -- fixed at the source
  * (previously only worked around at the MATLAB facade layer, which built `requestedMaxDimension + 1` internally and
  * filtered the extra dimension back out; see `.claude/WORKLOG-maxdim-semantics-fix.md`). Before this fix,
  * `coboundaryOf`/`zeroPivotCofacet` refused to look past `sigma.dim + 1 > maxDimension`, i.e. `sigma.dim ==
  * maxDimension` always got a trivially-empty coboundary and therefore always came out essential -- a well-known
  * truncation artifact (H_k needs (k+1)-chains to resolve correctly), not real information about H_maxDimension. Fixed
  * by relaxing that guard to `sigma.dim > maxDimension`: a real `(maxDimension + 1)`-simplex is now enumerated on the
  * fly, transiently, whenever needed to resolve a dimension-`maxDimension` pairing -- never materialized into its own
  * `currentLevel`/reduced as its own column, so `totalSimplexCount` and the main loop's own bounds are unchanged; only
  * the two guards moved. Any external caller previously passing `maxDimension + 1` and filtering out
  * `dim == maxDimension + 1` bars itself should now pass the real requested degree directly and drop that workaround.
  */
class RipserCohomologyContext[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true,
  // None means "not explicitly set," resolved to metricSpace.minimumEnclosingRadius just below -- an ordinary
  // Option default, not a magic-value sentinel: None is a constant, so it doesn't hit Scala 3's restriction on
  // a default referencing an earlier same-list parameter (metricSpace) the way a literal
  // `= metricSpace.minimumEnclosingRadius` default would. See the class doc above this class for why the
  // resolved default itself changed from Double.PositiveInfinity to metricSpace.minimumEnclosingRadius.
  maxFiltrationValue: Option[Double] = None,
  memoizeFiltrationValue: Boolean = false
):
  import barcode.*

  private val resolvedMaxFiltrationValue: Double =
    maxFiltrationValue.getOrElse(metricSpace.minimumEnclosingRadius)

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  private val rawFiltrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  /** Optionally-memoized wrapper around `rawFiltrationValue`: `MaximumDistanceFiltrationValue.apply` recomputes an
    * O(d^2) max over pairwise vertex distances from scratch on every call, with no caching of its own. Gated behind
    * `memoizeFiltrationValue` (default `false` -- see the class doc above for why): the enumeration/assembly path
    * (`insertionDiameter`, `sparseCofacets`, `persistentCohomology`'s own per-dimension loop) never goes through this
    * at all, carrying diameters incrementally instead, so this field's remaining callers are `cohomologyOrdering`
    * (consulted on every `SortedMap`/`PriorityQueue` comparison inside `Chain.reduceBy`'s reduction machinery) plus the
    * handful of once-per-simplex lookups in `coboundaryOf`/`zeroPivotCofacet`/`zeroPivotFacet`/bar-endpoint reporting.
    * See `.claude/WORKLOG-lazy-enumeration.md` for the measured cost of leaving this `false`.
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
    *
    * The actual array-indexing loop is `PackedRipserCohomology.scala`'s top-level `insertionDiameter`, shared with
    * `PackedRipserCohomologyContext`: this class hoists `sigma`'s vertex array from an already-materialized
    * `Simplex[Int]` at each of its own call sites, where the packed engine decodes it from a combinatorial index first,
    * but the loop itself is the same function.
    */
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
          DiameterSimplex(
            insertionDiameter(metricSpace, vertices, sigma.diameter, v),
            (sigma.simplex.underlying + v).asSimplex
          )
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
  /** Built directly on `SimplexIndexing.CofacetCursor`, not `si.cofacetIterator` + `si(idx, ...)` decode
    * (`.claude/WORKLOG-ripser-profiling.md`): the vertex-less `cofacetIterator` would force every candidate to be fully
    * decoded back into a `Simplex[Int]` and then linearly scanned just to recover the ONE vertex `CofacetCursor`
    * already hands over directly as `cur.vertex`, on top of `cofacetIteratorWithVertex`'s own `(Int, Long)` tuple
    * allocation per candidate -- once the single largest remaining allocation source measured in this engine. `tau` is
    * built by inserting `cur.vertex` directly into `sigma`'s own vertex set (`sigma.underlying + v`, O(log d)), the
    * same incremental-insertion `sparseCofacets` above already uses.
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
        if insertionDiameter(metricSpace, vertices, sigmaFv, v) <= resolvedMaxFiltrationValue then
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
    Chain.from(c.rawEntries.flatMap { case (cell, coeff) =>
      coboundaryOf(cell).rawEntries.map { case (tau, sign) => (tau, fr.times(coeff, sign)) }
    })

  /** `sigma`'s cofacet tied at `sigma`'s own filtration value with the LARGEST combinatorial index, i.e. the "oldest
    * cofacet" in Definition 3.2/3.11's sense (`cohomologyOrdering` sorts a larger index as older). Built directly
    * against `si.cofacetIterator`'s full (unrestricted) enumeration, NOT a vertex-insertion-restricted one like
    * `Cofacets.scala`'s `apparentVertex` -- see CLAUDE.md: restricting to cofacets formed by inserting a vertex
    * strictly greater than sigma's own maximum silently misses a real tied cofacet whenever sigma already contains
    * `vertexCount - 1` (a false negative, not merely an inefficiency). Guarded at `sigma.dim > maxDimension`, the same
    * boundary `coboundaryOf` uses and for the same reason (see its doc): a real tied cofacet at
    * `sigma.dim == maxDimension` must be found too, or the apparent-pairs shortcut would silently miss genuine pairs at
    * the requested top dimension. `SimplexIndexing`'s raw iterators have no notion of any dimension cap on their own,
    * hence the explicit guard. Tau's diameter is computed via `insertionDiameter`'s O(d) incremental formula, not
    * `filtrationValue(tau)`'s O(d^2) recompute. No SEPARATE `maxFiltrationValue` guard is needed here (unlike
    * `coboundaryOf`, which considers every cofacet, not just tied ones): this method only ever selects a tau tied at
    * `sigma`'s own value, and `sigma` is only ever called with here if it's already within the threshold (guaranteed by
    * construction -- see `sparseCofacets`), so any tied tau is automatically within threshold too.
    */
  /** A hand-rolled `while` loop over `CofacetCursor` directly (`.claude/WORKLOG-ripser-profiling.md`), not
    * `.filter(...).maxByOption((tau, _) => si(tau))`: `maxByOption` boxes every `Long` key comparison, and `si(tau)`
    * would RE-ENCODE a simplex whose combinatorial index (`idx`, from `si.cofacetIterator`) is already in hand, a fully
    * redundant round trip through `searchRow`/`binomialEntry` (`SimplexIndexingSpec`'s round-trip property confirms
    * decode-then-encode is the identity, so reusing `idx` directly is safe). This method only ever needs the SINGLE
    * winning candidate, so `tau` is never built until the loop finishes -- only `bestVertex`/`bestIdx` (primitives) are
    * tracked per candidate, and `sigma.underlying + bestVertex` runs once, for the winner.
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
        val tauFv = insertionDiameter(metricSpace, vertices, d, cur.vertex)
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
    * distance, which isn't tracked) -- so this remains a `filtrationValue(sigma)` call per candidate. Left as a scope
    * boundary, not an oversight: see `.claude/WORKLOG-lazy-enumeration.md`.
    */
  /** Hand-rolled for the same two reasons as `zeroPivotCofacet` above: `.minByOption(sigma => si(sigma))` boxes every
    * `Long` comparison, AND `si(sigma)` re-encodes a simplex just decoded from `idx`, which already IS `sigma`'s own
    * index. The ONE genuinely necessary encode call, `si(tau)` (seeding `facetIterator` with `tau`'s own index, since a
    * facet iterator needs to know what it's removing a vertex FROM), stays -- it happens once per `zeroPivotFacet`
    * call, not once per candidate, so it was never part of either cost.
    */
  /** Built on `FacetCursor` directly: `FacetCursor.vertex` is the vertex REMOVED to reach `.index` (verified against
    * `decodeToArray` independently by `SimplexIndexingSpec`'s `FacetCursor` property), so each candidate's vertex set
    * is built by removing ONE vertex from `tau`'s own already-materialized `underlying` (`tau.underlying - v`, O(log
    * d)) rather than a full combinatorial decode. `filtrationValue(sigma)` itself still needs the full candidate vertex
    * set on every candidate, unlike the cofacet direction's `insertionDiameter` -- no incremental shortcut exists for
    * removing a vertex's diameter contribution, so `sigma` can't be deferred to just the winner the way
    * `zeroPivotCofacet` defers `tau`.
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
    * pool of simplices it ever separately reduces, which forces it to also handle tau turning up as some *other*
    * column's intermediate pivot via a substitution fallback. This engine does neither: `persistentCohomology`'s loop
    * below still visits every non-cleared simplex, so the mutual pair's `sigma` is *always* the first (youngest,
    * smallest-index) simplex whose raw, unreduced coboundary can possibly have `tau` as its leading term under
    * `cohomologyOrdering` -- any tied-diameter cofacet of any simplex necessarily is that simplex's chain minimum, by
    * Vietoris-Rips monotonicity -- so `tau` can never already be claimed in `basis` by anything else by the time
    * `sigma`'s turn comes up. See `.claude/WORKLOG-cohomology.md` for the full derivation, including why this makes the
    * shortcut in `persistentCohomology` provably behavior-preserving rather than merely empirically-checked.
    */
  private def zeroApparentCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    for
      tau <- zeroPivotCofacet(sigma)
      partner <- zeroPivotFacet(tau)
      if partner == sigma
    yield tau

  /** Mirror of `zeroApparentCofacet`, entered from the other side: `Some(sigma)` iff `tau` has a facet `sigma` such
    * that `(sigma, tau)` is a genuine (mutual) Definition 3.2 apparent pair. This is the lookup Ripser's
    * `compute_pairs` performs when some OTHER column's reduction reaches `tau` as an unresolved pivot: the substitution
    * is a fresh recomputation every time, with NO cache anywhere in Ripser's own implementation. Mirrored here for the
    * same reason, not out of caution: `zeroApparentCofacet`'s soundness proof only establishes that `sigma` is the
    * first simplex whose RAW, unreduced coboundary can reach `tau` -- it says nothing about whether some other column's
    * own mid-cascade, already-partially-reduced working chain could reach `tau` as an intermediate pivot before
    * `sigma`'s own turn in the outer sweep. A map recording "who claimed this pair first" would need to answer that
    * question to be trustworthy; a pure recomputation from Definition 3.2 doesn't, because `sigma` is the mutual
    * apparent partner of `tau` as a fact about filtration values and combinatorial indices alone, independent of when
    * or how `tau` was reached -- a recorded `tau -> sigma` map was considered and rejected for exactly this reason. See
    * `.claude/WORKLOG-lazy-enumeration.md`.
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

    // On-the-fly apparent-pair substitution (Ripser's `compute_pairs`, no cache -- see zeroApparentFacet's
    // doc and `.claude/WORKLOG-lazy-enumeration.md`). Consulted by `Chain.reduceBy` below ONLY when a
    // working chain's leading pivot has no `basis` entry -- which, since apparent pairs never write one
    // (see the `Some(tau)` branch below), is exactly the case for an apparent pair's tau whenever some
    // OTHER column's reduction happens to reach it. This is what lets `persistentCohomology` skip
    // `coboundaryOf(sigma)` ENTIRELY for every apparent pair nobody else's reduction ever touches, not
    // merely skip the reduction pass while still eagerly computing `coboundaryOf(sigma)` to populate
    // `basis(tau)` "just in case."
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
    // list every higher dimension is built from -- see `.claude/WORKLOG-lazy-enumeration.md`.
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
            // `coboundaryOf(sigma)` is NOT computed here at all, and `basis(tau)` is deliberately NEVER
            // written. If some OTHER column's reduction later reaches tau as an unresolved pivot,
            // `basisFallback` above recomputes `coboundaryOf(sigma)` fresh at that point instead --
            // Ripser's own `compute_pairs` substitution, no cache (see zeroApparentFacet's doc and
            // `.claude/WORKLOG-lazy-enumeration.md`). This is what turns the apparent-pairs shortcut from
            // "skip the reduction pass, still pay for the full coboundary enumeration" (a measured
            // 1.35x-1.8x win on its own) into "skip the coboundary enumeration too, for every apparent
            // pair nobody else's reduction ever reaches."
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
              log.rawEntries.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
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
      // see `.claude/WORKLOG-lazy-enumeration.md`, confirmed from ripser.cpp's own
      // `assemble_columns_to_reduce`: `next_simplices.push_back(...)` runs unconditionally, BEFORE the
      // `is_in_zero_apparent_pair`/already-a-pivot exclusion checks that shrink `columns_to_reduce`.
      // Clearing controls which simplices get independently REDUCED at a dimension, never which simplices
      // are a valid source for generating the next dimension's cofacets -- a cleared simplex's own higher
      // cofacets still genuinely exist in the complex. Getting this backwards would silently omit real
      // simplices from every dimension above the first one with a cleared/apparent-paired member.
      if d < maxDimension then currentLevel = simplicesAtD.iterator.flatMap(sparseCofacets).toSeq

    bars.toList
