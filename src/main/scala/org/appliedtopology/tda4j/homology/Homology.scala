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

class SimplicialHomologyEngine[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]()
    extends CellularHomologyEngine[Simplex[VertexT], CoefficientT, FiltrationT] {}

/** Thin wrapper mirroring `SimplicialHomologyEngine`'s own relationship to `CellularHomologyEngine` -- `Cube` needed
  * nothing new from the naive engine (it is already generic over `CellT: OrderedCell`), so this exists purely for the
  * same ergonomic reason `SimplicialHomologyEngine` does: a concrete, easily-discoverable name instead of writing out
  * `CellularHomologyEngine[Cube, CoefficientT, FiltrationT]` at every call site.
  */
class CubicalHomologyEngine[CoefficientT: Field, FiltrationT: Ordering]()
    extends CellularHomologyEngine[Cube, CoefficientT, FiltrationT] {}

/** Naive persistent homology via the standard single-pivot-table reduction algorithm: process cells in filtration
  * order, reduce each cell's boundary against the pivots recorded so far, and every cell either opens a class (reduced
  * boundary is zero) or closes one (reduced boundary is nonzero, and its leading cell -- the pivot -- is necessarily a
  * previously-opened, still-unpaired cell).
  *
  * No clearing, no chunking, no cohomology/twist optimization: this is the reference-grade baseline the other algorithm
  * in this file (`PersistenceInChunksEngine`) can be cross-validated against.
  *
  * Correctness note for future maintainers: the `RingModule`/`Ordering[CellT]` instances used for chain arithmetic MUST
  * be summoned inside `HomologyState`, not at `CellularHomologyEngine` class scope. A `given Ordering[CellT]` derived
  * from a per-stream `filtrationOrdering` only exists once a stream is available (i.e. inside `HomologyState`);
  * summoning `Chain[CellT, CoefficientT] is RingModule` any earlier silently falls back to the generic,
  * filtration-blind `OrderedCell`-derived ordering and bakes it into that RingModule instance's closures permanently
  * (Scala resolves a given's own implicit parameters once, at the point the given is constructed, not at each later
  * call to its methods). A real, confirmed bug -- see `.claude/WORKLOG-naive-homology.md`.
  */
class CellularHomologyEngine[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

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

    // Summoned here, not at CellularHomologyEngine scope -- see class doc above.
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
    // through `Chain.reduceBy`'s existing `fallback` hook (the same mechanism `RipserCohomologyEngine` uses
    // for its own apparent-pairs substitution) lets reduction substitute and continue past a negative cell
    // exactly as it already does past a positive one. See `.claude/WORKLOG-reference-engine-fix.md`.
    val negativeVCols: mutable.Map[CellT, Chain[CellT, CoefficientT]] = mutable.Map.empty

    def advanceOne(): Unit =
      if cellIterator.hasNext then
        val sigma: CellT = cellIterator.next()
        val dsigma: Chain[CellT, CoefficientT] = Chain.from(sigma.boundary[CoefficientT])
        // Chain.reduceBy (the SortedMap-based object-level primitive shared with
        // PersistenceInChunksEngine), not a hand-rolled reduction over raw Chain arithmetic: `-`/`⊠`
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
  * fix and for the same reason as `RipserCohomologyEngine`'s own `maxDimension` (see
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
class CellularPersistenceInChunksEngine[CellT: OrderedCell, CoefficientT: Field](maxDim: Int = 5):
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
            // CellularHomologyEngine's own `cycles` map holds at dimension 0
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
    // `CellularHomologyEngine.advanceOne`'s own audited V-column formula and on
    // `PackedRipserCohomologyEngine.persistentCohomology`'s inline `generators` tracking (the project lead's
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
    // gap `CellularHomologyEngine.advanceOne`'s own `negativeVCols` fallback closes for the naive engine --
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
          // Mirrors CellularHomologyEngine.advanceOne's own vcol fold exactly (see its doc for the full
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
      * mirrors `CellularHomologyEngine.barcodeAt`'s output shape exactly (`List[PersistenceBar[Double, Chain[CellT,
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
            // root-causing a real, confirmed PersistenceInChunksEngine bug (see eliminationFallback's own
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
    // ordering bug itself was fixed) exposed this as a second, unrelated PersistenceInChunksEngine bug
    // reproducing even on the well-established EnumeratingCofaceSimplexStream (see CLAUDE.md): it silently
    // dropped essential classes at a bounded maxDim whenever this cross-step gap was hit.
    //
    // The paired branch's earlier "self-cancel if inactive, else None (legitimate final pivot)" logic was
    // ITSELF a second, distinct bug (`.claude/WORKLOG-chunks-pairing-bug.md`): `Chain(l)` (trivial
    // self-cancel) is NOT a valid substitute for a paired cell in general -- it just drops l from the
    // accumulator, which is only correct if l's own true contribution happens to be exactly l itself.
    // Compare CellularHomologyEngine.advanceOne's own fallback, `negativeVCols.get`: a REAL, possibly
    // multi-term V-column (leading term = the cell itself), not a placeholder. `vcolOf` (built earlier in
    // this class for barcodeAt's own representative tracking) computes exactly this same quantity for a
    // paired cell, so reusing it here closes the gap without reintroducing full incremental V-column
    // bookkeeping into the local/global passes. Safe to call mid-algorithm (not just post-advanceAll,
    // vcolOf's original use) specifically BECAUSE advanceAll's own reconciliation step (see its own doc,
    // right before markActiveEntries) guarantees every cell's cleared/paired classification -- and hence
    // every dependency vcolOf might recurse into -- is already final by the time compress/globalReduce (and
    // therefore eliminationFallback) ever run: nothing below reopens or reclassifies an already-paired cell.
    // Dropping the "active" gate entirely for this branch matches CellularHomologyEngine, which has no
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

/** Thin `Simplex`-specific wrapper around `CellularPersistenceInChunksEngine`, exactly mirroring
  * `SimplicialHomologyEngine`'s relationship to `CellularHomologyEngine` above -- every existing call site
  * (`PersistenceInChunksEngine[Int, Double](...)` etc.) keeps working unchanged, since the generic engine itself has no
  * `Simplex`-specific behavior anywhere in its body: everything goes through the generic `OrderedCell` interface
  * (`.dim`, `.boundary[CoefficientT]`), so genericizing was a pure type-annotation change, not a behavior change.
  * `Simplex[VertexT] is OrderedCell` resolves automatically here from `Ordering[VertexT]` alone
  * (`defaultSimplexIsOrderedCell`, `SimplexOrderedCell.scala`), same as `SimplicialHomologyEngine` already relies on.
  * See `.claude/WORKLOG-simplicial-set-filtration.md`.
  */
class PersistenceInChunksEngine[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5)
    extends CellularPersistenceInChunksEngine[Simplex[VertexT], CoefficientT](maxDim) {}

/** Thin `Cube`-specific wrapper, exactly mirroring `PersistenceInChunksEngine` above --
  * `CellularPersistenceInChunksEngine[Cube, ...]` (including its own `unionFindDim01` dimension-0/1 fast path) already
  * has no `Cube`-specific behavior needed anywhere: `Cube is OrderedCell` (`defaultCubeIsOrderedCell`,
  * `CubicalOrderedCell.scala`) resolves automatically, so this class is a pure ergonomic convenience, not new
  * capability -- `CellularPersistenceInChunksEngine[Cube, ...]` was already cross-validated against
  * `CubicalHomologyEngine` directly (`CubicalStreamSpec`'s own tie-heavy-fixture and random-image cross-validation
  * sections, including the union-find fast path specifically), the exact validation
  * `.claude/DESIGN-fast-cubical-engine.md`'s own Phase 1 called for, just not yet under this name.
  * `.claude/WORKLOG-fast-cubical-engine.md`.
  */
class CubicalPersistenceInChunksEngine[CoefficientT: Field](maxDim: Int = 5)
    extends CellularPersistenceInChunksEngine[Cube, CoefficientT](maxDim) {}
