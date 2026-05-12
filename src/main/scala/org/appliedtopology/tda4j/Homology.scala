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

class CellularHomologyContext[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering]:

  val chainRM = summon[Chain[CellT, CoefficientT] is RingModule]
  import chainRM.*

  import barcode.*

  case class HomologyState(
    cycles: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    cyclesBornBy: mutable.Map[CellT, CellT],
    boundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    boundariesBornBy: mutable.Map[CellT, CellT],
    coboundaries: mutable.Map[CellT, Chain[CellT, CoefficientT]],
    stream: CellStream[CellT, FiltrationT],
    var current: FiltrationT,
    barcode: mutable.ArrayDeque[
      (
        Int,
        FiltrationT,
        FiltrationT,
        Chain[CellT, CoefficientT]
      )
    ]
  ):
    given Ordering[CellT] = stream.filtrationOrdering
    import Ordering.Implicits.infixOrderingOps
    given filtration: Filtration[CellT, FiltrationT] = stream

    val CellIterator: collection.BufferedIterator[CellT] = stream.iterator.buffered

    def diagramAt(
      f: FiltrationT
    ): List[(Int, FiltrationT, FiltrationT)] =
      advanceTo(f)
      (for
        (dim: Int, lower: FiltrationT, oldUpper: FiltrationT, cycle: Chain[CellT, CoefficientT]) <- barcode.toList
        if lower <= f
        upper = oldUpper.min(f)
      yield (dim, lower, upper)) ++ (
        for
          (sigma, z) <- cycles
          dim = sigma.dim
          lower = stream.filtrationValue.applyOrElse(sigma, _ => filtration.smallest)
        yield (dim, lower, filtration.largest)
      )

    def barcodeAt(f: FiltrationT): List[PersistenceBar[FiltrationT, Nothing]] =
      diagramAt(f).map { (dim, l, u) =>
        val lower: BarcodeEndpoint[FiltrationT] = l match
          case i if i == filtration.smallest => NegativeInfinity()
          case f: FiltrationT                => ClosedEndpoint(f)
        val upper: BarcodeEndpoint[FiltrationT] = u match
          case i if i == filtration.largest => PositiveInfinity()
          case f: FiltrationT               => OpenEndpoint(f)
        new PersistenceBar(dim, lower, upper, None)
      }

    @tailrec
    private def reduceBy(
      z: Chain[CellT, CoefficientT],
      basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
      reductionLog: Chain[CellT, CoefficientT] = Chain()
    )(using fr: (CoefficientT is Field)): (Chain[CellT, CoefficientT], Chain[CellT, CoefficientT]) =
      z.leadingCell match
        case None => (z, reductionLog)
        case Some(sigma) =>
          if basis.contains(sigma) then
            val redCoeff = fr.divide(z.leadingCoefficient, basis(sigma).leadingCoefficient)
            reduceBy(z - redCoeff ⊠ basis(sigma), basis, reductionLog + redCoeff ⊠ Chain(sigma))
          else (z, reductionLog)

    def advanceOne(): Unit =
      if CellIterator.hasNext then
        val fr = summon[CoefficientT is Field]
        val sigma: CellT = CellIterator.next()
        val dsigma: Chain[CellT, CoefficientT] =
          sigma.boundary[CoefficientT]: Chain[CellT, CoefficientT]
        val (dsigmaReduced, reduction) = reduceBy(dsigma, boundaries)
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

          val (_, cycleBasis) = reduceBy(dsigmaReduced, cycles)
          val representativeCycle: Chain[CellT, CoefficientT] = cycleBasis.leadingCell match
            case None       => Chain()
            case Some(cell) => cycles(cell)
          cycleBasis.leadingCell match
            case None       => ()
            case Some(cell) => cycles.remove(cell)

          val lower: FiltrationT = cycleBasis.leadingCell match
            case None => filtration.smallest
            case Some(spx) =>
              stream.filtrationValue.orElse(_ => filtration.smallest).compose(cyclesBornBy)(spx)
          val upper: FiltrationT =
            stream.filtrationValue.orElse(_ => filtration.largest)(sigma)

          barcode.append((sigma.dim - 1, lower, upper, representativeCycle))
        current = stream.filtrationValue.lift(sigma).getOrElse(stream.smallest)

    def advanceTo(f: FiltrationT): Unit =
      while CellIterator.hasNext && f > stream.filtrationValue.lift(CellIterator.head).getOrElse(stream.smallest) do
        advanceOne()

    def advanceAll(): Unit =
      while CellIterator.hasNext do advanceOne()

  def persistentHomology(stream: => CellStream[CellT, FiltrationT]): HomologyState =
    HomologyState(
      mutable.Map.empty, // cycle basis
      mutable.Map.empty, // cycle born by
      mutable.Map.empty, // boundary basis
      mutable.Map.empty, // boundary born by
      mutable.Map.empty, // coboundary mapping
      stream, // simplex stream
      stream.smallest: FiltrationT, // computation done up until filtrationValue
      mutable.ArrayDeque.empty
    ) // torsion part of barcode

class PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field]:
  val chainRM = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule]
  import chainRM.*

  case class HomologyState(
    boundaries: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
    stream: StratifiedCellStream[Simplex[VertexT], Double],
    var current: Double,
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
    val maxDim: Int =
      var d = 0
      while stream.iterateDimension.isDefinedAt(d + 1) do d += 1
      d

    // build index map to support chunk boundary calculation.
    // Note: stream.iterator (the default StratifiedCellStream impl) infinite-loops because it
    // filters Iterator.from(0) with a finite predicate. Walk dimensions explicitly instead.
    val allCells: Vector[Simplex[VertexT]] =
      0.to(maxDim).iterator.flatMap { d =>
        stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)
      }.toVector
    val cellIndex: Map[Simplex[VertexT], Int] = allCells.zipWithIndex.toMap
    val chunkSize: Int = math.max(1, math.sqrt(allCells.size.toDouble).floor.toInt)

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

    def processCell(sigma: Simplex[VertexT], stop: Simplex[VertexT] => Boolean): Unit =
      if cleared.contains(sigma) || paired.contains(sigma) then ()
      else
        // rebuild the boundary chain under the local filtration ordering — the chain
        // returned by sigma.boundary is ordered by Simplex.scala's default (lex) ordering,
        // not stream.filtrationOrdering, which gives wrong pivots in top-down.
        val dsigma: Chain[Simplex[VertexT], CoefficientT] =
          Chain.from(sigma.boundary[CoefficientT].items)
        val (dsigmaReduced, _) =
          Chain.reduceByUntil(dsigma, boundaries, Chain.empty, stop)
        if dsigmaReduced.isZero() then
          essentialSimplices += sigma
        else
          val pivot = dsigmaReduced.leadingCell.get
          // only record when the pivot is local (i.e. stop didn't fire)
          if !stop(pivot) then
            boundaries(pivot) = dsigmaReduced
            cleared += pivot
            paired += sigma
            // if the pivot was previously marked essential (e.g. by an earlier local pass),
            // promote it to a paired class now
            essentialSimplices -= pivot
            val lower =
              stream.filtrationValue.applyOrElse(pivot, (_: Simplex[VertexT]) => Double.NegativeInfinity)
            val upper =
              stream.filtrationValue.applyOrElse(sigma, (_: Simplex[VertexT]) => Double.PositiveInfinity)
            val barDim = pivot.dim
            barcode(barDim) =
              barcode.getOrElse(barDim, immutable.Queue.empty)
                .appended((lower, upper, dsigmaReduced))

    def advanceAll(): Unit =
      val n: Int = allCells.size
      val m: Int = (n + chunkSize - 1) / chunkSize

      val cellsByDimChunk: Map[Int, IndexedSeq[IndexedSeq[Simplex[VertexT]]]] =
        0.to(maxDim).map { d =>
          val atDim =
            stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toVector
          val chunked: IndexedSeq[IndexedSeq[Simplex[VertexT]]] =
            0.until(m).map { b =>
              atDim.filter(s => cellIndex(s) / chunkSize == b)
            }
          d -> chunked
        }.toMap

      // Algorithm 2: local_reduction from clear-and-compress paper
      for delta <- maxDim.to(0, -1) do
        for r <- 1.to(2) do
          for b <- (r - 1).until(m) do // parallelizable!
            val floorIdx: Int = math.max(0, (b - r + 1) * chunkSize)
            val stop: Simplex[VertexT] => Boolean =
              sigma => cellIndex.getOrElse(sigma, -1) < floorIdx
            for sigma <- cellsByDimChunk(delta)(b) do
              processCell(sigma, stop)

      // Global fallback: pick up pairs that span more than the local horizon.
      // Stand-in for Algorithm 5's compress + global reduction.
      val noStop: Simplex[VertexT] => Boolean = _ => false
      for delta <- maxDim.to(0, -1) do
        val cellsAtDim =
          stream.iterateDimension.applyOrElse(delta, (_: Int) => Iterator.empty).toVector
        for sigma <- cellsAtDim do
          processCell(sigma, noStop)

  def persistentHomology(stream: => StratifiedCellStream[Simplex[VertexT], Double]): HomologyState =
    HomologyState(
      mutable.Map.empty,
      stream,
      stream.smallest,
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
        stream.filtrationValue(x | y) }
    )(using stream.filtrationOrdering)

    kruskal.mstIterator.foreach { (src, tgt) =>
      val edge: Simplex[VertexT] = src | tgt

      // the edge src -- tgt will connect src to tgt thus removing one of the cycles
      val dEdge : Chain[Simplex[VertexT],CoefficientT] = edge.boundary
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
      val dEdge : Chain[Simplex[VertexT], CoefficientT] = edge.boundary
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
        val dsigma: Chain[Simplex[VertexT], CoefficientT] = sigma.boundary
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
            case None => Double.NegativeInfinity
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

/*
class RipserHomology[CoefficientT: Field](metricSpace: FiniteMetricSpace[Int]):
  val sparseMetricSpace = SparseMetricSpace(metricSpace, metricSpace.minimumEnclosingRadius)

  val cofaceStream: EnumeratingCofaceSimplexStream = EnumeratingCofaceSimplexStream(sparseMetricSpace)

  given (Simplex[Int] is OrderedCell) = Simplex_is_OrderedCell[Int](cofaceStream.filtrationOrdering.orElse(simplexOrdering))

  case class Bar(dim: Int, birth: Double, death: Double)

  val barcodes: mutable.Map[Int, List[Bar]] = mutable.Map.empty

  val cocycleMaps : mutable.Map[Int, mutable.Map[Simplex[Int], Chain[Simplex[Int],CoefficientT]]] = mutable.Map.empty
  val coboundaryMaps : mutable.Map[Int, mutable.Map[Simplex[Int], Chain[Simplex[Int],CoefficientT]]] = mutable.Map.empty

  lazy val kruskal = Kruskal(sparseMetricSpace)

  @tailrec
  private def reducingCycles(
                      reducedCycles: Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]],
                      unprocessedCycles: List[Chain[Simplex[Int], CoefficientT]],
                      bailout: Int = 0
                    ): Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] =
    if (bailout > 1000) {
      println(s"Bailing out, $bailout\n${unprocessedCycles(0)}")
      reducedCycles
    }
    else unprocessedCycles match {
      case (z :: rest) => {
        if (z.isZero())
          reducingCycles(reducedCycles, rest, bailout + 1)
        else {
          println(s"Reducing... $z")
          val sigma = z.leadingCell.get
          if (reducedCycles.contains(sigma)) {
            val z0 = reducedCycles(sigma)
            val z1 = z - (z0 <* (z.leadingCoefficient / z0.leadingCoefficient))
            z1.collapseAll()
            reducingCycles(reducedCycles, z1 :: rest, bailout + 1)
          } else {
            z.collapseAll()
            val zs = z.items.flatMap { (sc) =>
              val (s, c) = sc
              if (reducedCycles.contains(s)) {
                val w = reducedCycles(s)
                Some(w <* (-c / w.leadingCoefficient))
              } else None
            }
            val zred = zs.foldLeft(z)(_ + _)
            zred.collapseAll()
            reducingCycles(reducedCycles.updated(zred.leadingCell.get, zred), rest, bailout + 1)
          }
        }
      }
      case _ => reducedCycles
    }

  def computeNextBarcode(): List[Bar] = {
    def cofacets(spx: Simplex[Int]): List[Chain[Simplex[Int], CoefficientT]] = {
      val c1 : List[(Simplex[Int], Int)] = metricSpace
        .elements
        .flatMap((x) => if spx.contains(x) then None else Some((spx.incl(x), spx.count(_ < x))))
        .toList
        .sortBy((item) => cofaceStream.filtrationValue(item._1))

      c1.map { (item) =>
          val (w, pos) = item
          (pos % 2) match {
            case 0 => Chain(w)
            case 1 => -Chain(w)
          }
        }
        .toList
    }
    if barcodes.isEmpty then // start with dim 0
      barcodes(0) = (for (i, j) <- kruskal.mstIterator
      yield Bar(0, 0, metricSpace.distance(i, j))).toList.prepended(Bar(0, 0, Double.PositiveInfinity))
      barcodes(0)
    else
      if (barcodes.keySet.max == 0) {
        // using the cycles iterator implicitly already skips all the skippable 1-simplices
        // _because_ we're already avoiding the entire minimum spanning tree
        // everything that remains creates a 1-cocycle
        val cocycles = kruskal.cyclesIterator.toList.map((e) => Chain(∆(e._1, e._2)))
        
        val cocycleMap: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] =
          mutable.Map.from(cocycles.flatMap((ch) => ch.leadingCell.map((c) => c -> ch)))
        val coboundaryMap: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty

        cocycleMaps(1) = cocycleMap
        coboundaryMaps(1) = coboundaryMap
        val nextCocycles: mutable.Map[Simplex[Int], Chain[Simplex[Int], CoefficientT]] = mutable.Map.empty
        cocycleMaps(2) = nextCocycles
      }
      val d = barcodes.keySet.max+1
      val cocycles : List[Chain[Simplex[Int], CoefficientT]] = cocycleMaps(d).values.toList
      val skippable: List[Simplex[Int]] = coboundaryMaps.getOrElseUpdate(d, mutable.Map.empty).values.flatMap(_.leadingCell).toList

      val cocycleMap = cocycleMaps.getOrElseUpdate(d, mutable.Map.empty)
      val coboundaryMap = coboundaryMaps.getOrElseUpdate(d, mutable.Map.empty)
      val nextCocycles = cocycleMaps.getOrElseUpdate(d+1, mutable.Map.empty)
      val currentBarcode: mutable.ArrayDeque[Bar] = mutable.ArrayDeque.empty

      for cocycle <- cocycles do
        val spx : Simplex[Int] = cocycle.leadingCell.get
        val cofacetIterator = CofacetIterator(spx, sparseMetricSpace)
        // TODO Check with Ulrich Bauer carefully that this is the right thing to check
        if (cofacetIterator.apparentVertex.exists(w => !spx.exists(v => v > w))) {
          given (Chain[Simplex[Int], CoefficientT] is RingModule{type R = CoefficientT}) = summon[Chain[Simplex[Int], CoefficientT] is RingModule{type R = CoefficientT}]
          val cofacets : Iterator[Chain[Simplex[Int], CoefficientT]] = cofacetIterator.map(w => spx.count(_<w) % 2 match {
            case 0 => Chain(spx+w)
            case 1 => -Chain(spx+w)
          })
          val cofacetHead = cofacets.next()
          var coboundary = cofacets.foldLeft(cofacetHead)(_ + _)
          coboundary.collapseAll()
          val cancellations = for
            (z, c) <- coboundary.items
            if coboundaryMap.contains(z)
            dz = coboundaryMap(z)
          yield
            dz <* (-c / dz.leadingCoefficient)
          val reduced = cancellations.foldLeft(coboundary)(_ + _)
          if reduced.isZero() then // new coboundary is a coboundary; create a cocycle
            val newCocycleItems = for
              (z,c) <- coboundary.items
              if coboundaryMap.contains(z)
            yield
              Chain(z) <* (-c/coboundaryMap(z).leadingCoefficient)
            val newCocycle = newCocycleItems.tail.foldLeft(newCocycleItems.head)(_+_)
            if (!newCocycle.isZero())
              nextCocycles(newCocycle.leadingCell.get) = newCocycle
          else // new coboundary cobounds cocycle
            cocycleMap.remove(cocycle.leadingCell.get)
            currentBarcode.addOne(
              Bar(cocycle.leadingCell.get.dim,
                cofaceStream.filtrationValue(cocycle.leadingCell.get),
                cofaceStream.filtrationValue(reduced.leadingCell.get)))
            coboundaryMap(reduced.leadingCell.get) = reduced
        }
      barcodes(d) = currentBarcode.toList
      barcodes(d)
  }


def computePersistentHomology[Vertex, Filtration, CoefficientT: Field](
                                                                        simplexStream: SimplexStream[Vertex, Filtration]
                                                                      ): (
  mutable.Map[Simplex[Vertex], Chain[Simplex[Vertex], CoefficientT]],
    mutable.Map[Simplex[Vertex], Chain[Simplex[Vertex], CoefficientT]]
  ) = {

  // Initialize two mutable structures to hold cycles and boundaries
  val cycles: mutable.Map[Simplex[Vertex], Chain[Simplex[Vertex], CoefficientT]] = mutable.Map.empty

  // Use a Map to track boundaries, associating each simplex with its generated boundary chain
  val boundaries: mutable.Map[Simplex[Vertex], Chain[Simplex[Vertex], CoefficientT]] = mutable.Map.empty

  // Process each simplex in the simplex stream
  for (simplex <- simplexStream) {
    val boundary = simplex.boundary() // Compute the boundary of the simplex
    var activeChain = boundary.collapseAll() // Simplify the boundary chain

    // Map to track reduction coefficients and the simplices associated with each boundary chain
    val reductionCoefficients = mutable.Map[Simplex[Vertex], CoefficientT]()

    // Step 1: Reduce activeChain using the boundary chains
    for ((boundarySimplex, boundaryChain) <- boundaries) {
      val leadingCell = boundaryChain.leadingCell
      if (leadingCell.isDefined && activeChain.leadingCell.contains(leadingCell.get)) {
        val boundaryLeading = boundaryChain.leadingCoefficient
        val activeLeading = activeChain.leadingCoefficient
        val coef = activeLeading / boundaryLeading

        // Reduce the activeChain by the boundary chain
        activeChain = activeChain - (boundaryChain <* coef)

        // Track the simplex that generated the boundary chain and the coefficient used
        reductionCoefficients(boundarySimplex) = coef
      }
    }

    // Step 2: Check if the activeChain is zero (a boundary)
    if (activeChain.isZero) {
      // New boundary generated by this simplex
      val newBoundaryChain = reductionCoefficients.foldLeft(Chain(simplex)) { (chain, entry) =>
        val (boundarySimplex, coef) = entry
        chain - (Chain(boundarySimplex) <* coef) // Subtract scaled boundary-contributing simplices
      }
      boundaries(simplex) = newBoundaryChain
    } else {
      // Step 3: Reduce activeChain using cycles and track reduction coefficients
      val cycleReductionCoefficients = mutable.Map[Simplex[Vertex], CoefficientT]()
      for ((leadingSimplex, cycleChain) <- cycles) {
        val cycleLeadingCell = cycleChain.leadingCell
        if (cycleLeadingCell.isDefined && activeChain.leadingCell.contains(cycleLeadingCell.get)) {
          val coef = activeChain.leadingCoefficient / cycleChain.leadingCoefficient
          cycleReductionCoefficients(leadingSimplex) = coef
          activeChain = activeChain - (cycleChain <* coef)
        }
      }

      // Track and manage the cycle and boundary sets
      if (activeChain.isZero) {
        // Among cycles used in reduction, pick the one with the most recently occurring leading cell
        val mostRecent = cycleReductionCoefficients.keys
          .maxByOption(simplex => cycles.keysIterator.indexOf(simplex))
        mostRecent.foreach { leadingSimplex =>
          val movedCycle = cycles.remove(leadingSimplex).get
          boundaries(leadingSimplex) = movedCycle
        }
      } else {
        // Add the remaining (non-zero) active chain as a new cycle
        val newCycle = Chain(simplex) + activeChain
        cycles(simplex) = newCycle
      }
    }
  }

  (cycles, boundaries)
}
    */
