package org.appliedtopology.tda4j

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

    val CellIterator: collection.BufferedIterator[CellT] = stream.iterator.buffered

    private def cellFiltrationValue(cell: CellT, fallback: FiltrationT): FiltrationT =
      stream.filtrationValue.applyOrElse(cell, (_: CellT) => fallback)

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
        // which collapses duplicates by construction on every insertion.
        val (reduced, log) = Chain.reduceBy(dsigma, boundaries, Chain.empty)
        // V-column: sigma minus, for every pivot the reduction subtracted off, that pivot's own
        // producing cell's V-column. If ∂sigma reduced to zero this chain is itself the new cycle
        // representative; either way it becomes the generator future cells reduce through if sigma
        // itself goes on to become a pivot. Collapsed explicitly before use for the same reason as
        // above: this fold also accumulates through raw Chain subtraction.
        val vcol: Chain[CellT, CoefficientT] = log.items.foldLeft(Chain(sigma)) { case (acc, (pivot, coeff)) =>
          // Every entry reduceBy's log can name is a key reduceBy itself just matched against
          // `boundaries`, and `generators` is always written in lockstep with `boundaries` (see the
          // else-branch below) -- so a missing entry here means the pivot bookkeeping has drifted out
          // of sync, not a legitimate case to default through silently.
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

class PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5):
  val chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]
  import chainRM.*

  case class HomologyState(
    boundaries: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
    stream: StratifiedCellStream[Simplex[VertexT], Double],
    barcode: mutable.Map[Int, immutable.Queue[(Double, Double, Chain[Simplex[VertexT], CoefficientT])]]
  ):

    given Ordering[Simplex[VertexT]] = stream.filtrationOrdering

    // top-down state:
    //   cleared ------------- simplices paired as pivots (positive side); their column is implicitly zero
    //   paired -------------- simplices paired as σ (negative side); already recorded a bar
    //   essentialSimplices -- so-far unpaired classes
    val cleared: mutable.Set[Simplex[VertexT]] = mutable.Set.empty
    val paired: mutable.Set[Simplex[VertexT]] = mutable.Set.empty
    val essentialSimplices: mutable.Set[Simplex[VertexT]] = mutable.Set.empty

    // start from max dimension instead for clearing's sake
    /*
    val maxDim: Int =
      var d = 0
      while stream.iterateDimension.isDefinedAt(d + 1) do d += 1
      d
     */

    // build index map to support chunk boundary calculation.
    // Note: stream.iterator (the default StratifiedCellStream impl) infinite-loops because it
    // filters Iterator.from(0) with a finite predicate. Walk dimensions explicitly instead.
    val allCells: Vector[Simplex[VertexT]] =
      0.to(maxDim)
        .iterator
        .flatMap { d =>
          stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)
        }
        .toVector
    val cellIndex: Map[Simplex[VertexT], Int] = allCells.zipWithIndex.toMap
    val chunkSize: Int = math.max(1, math.sqrt(allCells.size.toDouble).floor.toInt)

    // killer column index for each local pivot
    val killer: mutable.Map[Simplex[VertexT], Simplex[VertexT]] = mutable.Map.empty
    // R supplies R_k for unpaired column k, to be used in marking active entries
    val R: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]] = mutable.Map.empty
    // active entries
    val activeRows: mutable.Map[Simplex[VertexT], Boolean] = mutable.Map.empty

    def diagramAt(f: Double): List[(Int, Double, Double)] =
      advanceAll()

      val pairs: List[(Int, Double, Double)] =
        barcode.toList.flatMap { case (dim, bars) =>
          bars.toList.collect {
            case (lower, upper, _) if lower <= f => (dim, lower, upper min f)
          }
        }

      val essentialBars: List[(Int, Double, Double)] =
        essentialSimplices.toList.map { sigma =>
          val lower =
            stream.filtrationValue.applyOrElse(sigma, (_: Simplex[VertexT]) => Double.NegativeInfinity)
          (sigma.dim, lower, Double.PositiveInfinity)
        }

      pairs ++ essentialBars

    def recordPair(sigma: Simplex[VertexT], dsigmaReduced: Chain[Simplex[VertexT], CoefficientT]): Unit =
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
        stream.filtrationValue.applyOrElse(pivot, (_: Simplex[VertexT]) => Double.NegativeInfinity)
      val upper =
        stream.filtrationValue.applyOrElse(sigma, (_: Simplex[VertexT]) => Double.PositiveInfinity)
      val barDim = pivot.dim
      barcode(barDim) = barcode
        .getOrElse(barDim, immutable.Queue.empty)
        .appended((lower, upper, dsigmaReduced))

    def processCell(sigma: Simplex[VertexT], stop: Simplex[VertexT] => Boolean): Unit =
      if cleared.contains(sigma) || paired.contains(sigma) then ()
      else
        // rebuild the boundary chain under the local filtration ordering — the chain
        // returned by sigma.boundary is ordered by Simplex.scala's default (lex) ordering,
        // not stream.filtrationOrdering, which gives wrong pivots in top-down.
        val dsigma: Chain[Simplex[VertexT], CoefficientT] =
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
      val activeColumns: mutable.Map[Simplex[VertexT], Boolean] = mutable.Map.empty

      def markColumn(k: Simplex[VertexT]): Boolean =
        activeColumns.get(k) match
          case Some(b) => b
          case None    =>
            activeColumns(k) = false
            var isActive = false
            val Rk = R.getOrElse(k, Chain.empty)
            Rk.items.iterator.takeWhile(_ => !isActive).foreach { case (i, _) =>
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

    // Algorithm 4: global column compression from clear-and-compress paper.
    // The paper writes this over Z/2; over a general field we have to scale the
    // killer column by the right ratio.
    def compress(k: Simplex[VertexT]): Unit =
      val fr = summon[CoefficientT is Field]
      var Rk: Chain[Simplex[VertexT], CoefficientT] = R.getOrElse(k, Chain.empty)
      val entries: Seq[(Simplex[VertexT], CoefficientT)] = Rk.items.toSeq.sortBy(_._1)
      for (l, currentCoeff) <- entries do
        if cleared.contains(l) || paired.contains(l) then
          if !activeRows.getOrElse(l, false) then
            // l is inactive - zero out its entry.
            Rk = Rk - currentCoeff ⊠ Chain(l)
          else
            // l is active - add the killer column
            killer.get(l).foreach { j =>
              val Rj = R.getOrElse(j, Chain.empty) // (l,j) is persistence pair
              val redCoeff = fr.divide(currentCoeff, Rj.leadingCoefficient)
              Rk = Rk - redCoeff ⊠ Rj
            }
      R(k) = Rk

    // Algorithm 5 (lines 9-16): reduce the (now-compressed) global column k and record any pair found.
    def globalReduce(sigma: Simplex[VertexT]): Unit =
      if cleared.contains(sigma) || paired.contains(sigma) then return
      // If R(sigma) was never stored, sigma was locally essential and has nothing to reduce.
      R.get(sigma) match
        case None         => () // stays in essentialSimplices unless a higher-dim sigma globally pairs with it
        case Some(rSigma) =>
          val noStop: Simplex[VertexT] => Boolean = _ => false
          val (dsigmaReduced, _) = Chain.reduceByUntil(rSigma, boundaries, Chain.empty, noStop)
          R(sigma) = dsigmaReduced
          if dsigmaReduced.isZero() then
            R.remove(sigma)
            essentialSimplices += sigma
          else recordPair(sigma, dsigmaReduced)

    def advanceAll(): Unit =
      val n: Int = allCells.size
      val m: Int = (n + chunkSize - 1) / chunkSize

      val chunks: IndexedSeq[IndexedSeq[Simplex[VertexT]]] =
        allCells.grouped(chunkSize).toIndexedSeq

      // Algorithm 2: local_reduction from clear-and-compress paper
      for delta <- maxDim.to(0, -1) do
        for r <- 1.to(2) do
          for b <- (r - 1).until(m) do // parallelizable!
            val floorIdx: Int = math.max(0, (b - r + 1) * chunkSize)
            val stop: Simplex[VertexT] => Boolean =
              sigma => cellIndex.getOrElse(sigma, -1) < floorIdx
            for sigma <- chunks(b) if sigma.dim == delta do processCell(sigma, stop)

      // Algorithm 3: mark_active_entries from clear-and-compress paper
      markActiveEntries()

      // Algorithm 5 (Persistence in chunks): per dim top-down, compress unpaired
      // global columns then reduce them. Clearing keeps positives' columns at zero.
      for delta <- maxDim.to(0, -1) do
        val cellsAtDim =
          stream.iterateDimension.applyOrElse(delta, (_: Int) => Iterator.empty).toVector
        // step 2: compress unpaired global columns
        for sigma <- cellsAtDim do
          if !cleared.contains(sigma) && !paired.contains(sigma) && R.contains(sigma) then compress(sigma)
        // step 3: reduce the compressed global columns and record pairs
        for sigma <- cellsAtDim do globalReduce(sigma)

  def persistentHomology(stream: => StratifiedCellStream[Simplex[VertexT], Double]): HomologyState =
    HomologyState(
      mutable.Map.empty,
      stream,
      mutable.Map.empty
    )

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
    val chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]
    import chainRM.*

    // first off, all vertices are immediately cycles
    cycles.addAll(stream.iterateDimension(0).map(cell => cell -> Chain(cell)))
    cyclesBornBy.addAll(cycles.map((cell, chain) => cell -> cell))

    // secondly, we can read off homology completely from a minimal spanning tree
    val kruskal = new Kruskal[Simplex[VertexT]](
      cycles.keys.toSeq,
      { (x: Simplex[VertexT], y: Simplex[VertexT]) =>
        stream.filtrationValue(x | y)
      }
    )(using stream.filtrationOrdering)

    kruskal.mstIterator.foreach { (src, tgt) =>
      val edge: Simplex[VertexT] = src | tgt

      // the edge src -- tgt will connect src to tgt thus removing one of the cycles
      val dEdge: Chain[Simplex[VertexT], CoefficientT] = Chain.from(edge.boundary)
      val dyingVertex = dEdge.leadingCell.get
      boundaries.addOne(dEdge.leadingCell.get -> dEdge)
      coboundaries.addOne(dyingVertex, dEdge)
      barcode(0) =
        barcode(0).appended((stream.filtrationValue(dyingVertex), stream.filtrationValue(edge), cycles(dyingVertex)))
      cycles.remove(dyingVertex)
    }

    kruskal.cyclesIterator.foreach { (src, tgt) =>
      val edge: Simplex[VertexT] = src | tgt

      // the edge src -- tgt will connect src to tgt thus closing a loop
      val dEdge: Chain[Simplex[VertexT], CoefficientT] = Chain.from(edge.boundary)
      // TODO is it worth it to have a more complex UnionFind that allows us to get the entire path along the MST?
      val (reduced, reductionLog): (Chain[Simplex[VertexT], CoefficientT], Chain[Simplex[VertexT], CoefficientT]) =
        Chain.reduceBy(dEdge, boundaries, Chain.empty)
      val fr = summon[CoefficientT is Field]
      val coboundary: Chain[Simplex[VertexT], CoefficientT] =
        reductionLog.items.foldRight(fr.negate(fr.one) ⊠ Chain(edge)) { (item, acc) =>
          val (spx, coeff) = item
          if coboundaries.contains(spx) then acc + coeff ⊠ coboundaries(spx)
          else acc
        }
      cycles(coboundary.leadingCell.get) = coboundary
      cyclesBornBy(coboundary.leadingCell.get) = edge
    }

    // setup is done, we should be ready to start dimension 2
    currentDim = 1
    current = Double.PositiveInfinity

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

          barcode(currentDim) = barcode(currentDim).appended((lower, upper, representativeCycle))
        current = stream.filtrationValue.lift(sigma).getOrElse(stream.smallest)
      else
        currentDim += 1
        currentIterator = stream.iterateDimension
          .applyOrElse(currentDim, _ => Iterator.empty)
          .buffered
        current = Double.NegativeInfinity

    def advanceTo(dim: Int, f: Double = Double.PositiveInfinity): Unit =
      while currentIterator.hasNext &&
        currentDim <= dim &&
        f > current
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
class RipserCohomologyContext[CoefficientT: Field](
  metricSpace: FiniteMetricSpace[Int],
  maxDimension: Int,
  useApparentPairs: Boolean = true
):
  import barcode.*

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
  val filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  private val fr = summon[CoefficientT is Field]

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
      val fc = java.lang.Double.compare(filtrationValue(x), filtrationValue(y))
      if fc != 0 then fc else java.lang.Integer.compare(si(y), si(x))

  /** Coboundary of sigma, implicitly restricted to the truncated (maxDimension-skeleton) complex: empty at
    * `sigma.dim == maxDimension` by construction (no cofacets are ever enumerated beyond `maxDimension`), which is
    * exactly what makes dimension-`maxDimension` classes come out essential rather than needing a special case. Sign
    * convention dual to `Simplex.scala`'s boundary: `(-1)^`(number of sigma's vertices smaller than the inserted
    * vertex).
    */
  def coboundaryOf(sigma: Simplex[Int]): Chain[Simplex[Int], CoefficientT] =
    if sigma.dim + 1 > maxDimension then Chain.empty
    else
      Chain.from(
        si.cofacetIterator(sigma)
          .map { cofacetIdx =>
            val tau = si(cofacetIdx, sigma.size + 1)
            val inserted = (tau.underlying diff sigma.underlying).head
            val position = sigma.underlying.count(_ < inserted)
            val sign = if position % 2 == 0 then fr.one else fr.negate(fr.one)
            (tau, sign)
          }
          .toSeq
      )

  /** Coboundary of a whole chain, linearly extending `coboundaryOf`. Unlike `Chain.scala`'s `.boundary` extension
    * (intrinsic to a cell), a coboundary is extrinsic -- it depends on which higher-dimensional simplices exist in this
    * (possibly truncated) complex -- so it lives here rather than as a general-purpose `Chain` extension. Used by tests
    * to check that a representative essential cocycle genuinely has zero coboundary; trivially true at
    * `sigma.dim == maxDimension` (see `coboundaryOf`), so that check is only meaningful below the top dimension.
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
    * whenever sigma already contains `vertexCount - 1` (a false negative, not merely an inefficiency). Truncated to
    * `maxDimension` exactly like `coboundaryOf`, for the same reason: `SimplexIndexing`'s raw iterators have no notion
    * of any dimension cap on their own.
    */
  private def zeroPivotCofacet(sigma: Simplex[Int]): Option[Simplex[Int]] =
    if sigma.dim + 1 > maxDimension then None
    else
      val d = filtrationValue(sigma)
      si.cofacetIterator(sigma)
        .map(idx => si(idx, sigma.size + 1))
        .filter(tau => filtrationValue(tau) == d)
        .maxByOption(tau => si(tau))

  /** `tau`'s facet tied at `tau`'s own filtration value with the SMALLEST combinatorial index, i.e. the "youngest
    * facet" in Definition 3.2/3.11's sense. No `maxDimension` guard needed: a facet is always one dimension lower than
    * `tau`, which is already within the truncated complex by construction (it only exists as some `sigma`'s candidate
    * cofacet, already dimension-checked by `zeroPivotCofacet` above).
    */
  private def zeroPivotFacet(tau: Simplex[Int]): Option[Simplex[Int]] =
    val d = filtrationValue(tau)
    si.facetIterator(si(tau), tau.size)
      .map(idx => si(idx, tau.size - 1))
      .filter(sigma => filtrationValue(sigma) == d)
      .minByOption(sigma => si(sigma))

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

  def persistentCohomology(): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    // Summoned here, not any earlier -- see class doc above and CellularHomologyContext's class doc for
    // why a Chain[...] is RingModule instance's summon-time Ordering[CellT] scoping matters.
    val chainRM = summon[Chain[Simplex[Int], CoefficientT] is RingModule]
    import chainRM.*

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

    for d <- 0 to maxDimension do
      // Youngest first: Algorithm 1 processes columns in increasing [matrix] order, which under the
      // reversed-order coboundary matrix means decreasing real filtration order. Structural, not a
      // performance tweak -- see WORKLOG-cohomology.md.
      val simplicesAtD: Seq[Simplex[Int]] =
        (0 until binomial(metricSpace.size, d + 1))
          .map(idx => si(idx, d + 1))
          .sorted(using cohomologyOrdering.reverse)

      // Pivot (dimension d+1 simplex) -> reduced coboundary column, reset per dimension: dimension d's
      // coboundary matrix delta: C^d -> C^{d+1} is reduced independently of every other dimension's.
      val basis: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty
      // Pivot -> the V-column (a dimension-d chain) of whichever d-simplex claimed that pivot.
      val generators: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty

      for sigma <- simplicesAtD if !cleared.contains(sigma) do
        (if useApparentPairs then zeroApparentCofacet(sigma) else None) match
          case Some(tau) =>
            // Apparent pair shortcut (Definition 3.2/Proposition 3.9): sigma's raw, UNREDUCED coboundary
            // already has tau as its leading term under cohomologyOrdering, and no earlier-processed
            // simplex can have already claimed tau in `basis` -- see `zeroApparentCofacet`'s doc and
            // WORKLOG-cohomology.md's "Apparent pairs: resolved" section for why. So `Chain.reduceBy`
            // is GUARANTEED to be a no-op here (log = Nil, reduced = z unchanged) and can be skipped --
            // but `z` itself must still be computed and stored as-is in `basis(tau)`, in full, not
            // truncated to just the (tau, sign) leading term: z can genuinely have OTHER, non-leading
            // terms too (other cofacets of sigma tied at the same filtration value as tau, or cofacets
            // at a strictly higher one), and those terms are exactly what a LATER column's own reduction
            // needs to pick up when it subtracts basis(tau) to cancel tau out of ITS working chain. An
            // earlier draft stored only the singleton {tau -> sign} here and silently dropped those
            // other terms, which produced a wrong death for a different, unrelated column later in the
            // same dimension (confirmed by a direct counterexample: two triangles tied at the same value
            // as their shared edge partner's own apparent-pair triangle, see WORKLOG-cohomology.md). This
            // still pays for the full `coboundaryOf(sigma)` enumeration (only the reduction pass is
            // skipped), but is a measured 1.35x-1.8x wall-clock win on n=12-20 point VR complexes anyway,
            // because `Chain.reduceBy`'s recursive per-step SortedMap fold -- not the enumeration -- is
            // this loop's dominant cost. See WORKLOG-cohomology.md for the numbers and for why the further
            // (lazy, enumeration-skipping) version Ripser itself uses is NOT implemented here.
            val z = coboundaryOf(sigma)
            val vcol = Chain[Simplex[Int], CoefficientT](sigma)
            basis(tau) = z
            generators(tau) = vcol
            cleared += tau
            bars.append(
              PersistenceBar(d, ClosedEndpoint(filtrationValue(sigma)), OpenEndpoint(filtrationValue(tau)), Some(vcol))
            )
          case None =>
            val z = coboundaryOf(sigma)
            // Chain.reduceBy (SortedMap-based), not hand-rolled reduction over raw Chain arithmetic -- see
            // WORKLOG-naive-homology.md's "critical performance bug" for why that silently reintroduces
            // superlinear blowup on real VR streams.
            val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty)
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
              bars.append(PersistenceBar(d, ClosedEndpoint(filtrationValue(sigma)), PositiveInfinity(), Some(vcol)))
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
                  ClosedEndpoint(filtrationValue(sigma)),
                  OpenEndpoint(filtrationValue(pivot)),
                  Some(vcol)
                )
              )

    bars.toList
