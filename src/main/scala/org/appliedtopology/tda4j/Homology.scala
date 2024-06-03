package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.PersistenceBar

import collection.{immutable, mutable}
import scala.annotation.tailrec

//class ReducedSimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional, FiltrationT: Ordering]()
//  extends CellularHomologyContext[Simplex[VertexT], CoefficientT, FiltrationT]() {}

class SimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional, FiltrationT: Ordering]()
    extends CellularHomologyContext[Simplex[VertexT], CoefficientT, FiltrationT] {}

class CellularHomologyContext[CellT : OrderedCell, CoefficientT: Fractional, FiltrationT: Ordering] extends ChainOps[CellT, CoefficientT]() {

  import barcode._

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
  ) {
    given Ordering[CellT] = stream.filtrationOrdering
    import Ordering.Implicits.infixOrderingOps
    given filtration: Filtration[CellT, FiltrationT] = stream

    val CellIterator: collection.BufferedIterator[CellT] = stream.iterator.buffered

    def diagramAt(
      f: FiltrationT
    ): List[(Int, FiltrationT, FiltrationT)] = {
      advanceTo(f)
      (for
        (dim, lower, oldUpper, cycle): (
          Int,
          FiltrationT,
          FiltrationT,
          Chain[CellT, CoefficientT]
        ) <- barcode.toList
        if lower <= f
        upper = oldUpper.min(f)
      yield (dim, lower, upper)) ++ (
        for
          (sigma, z) <- cycles
          dim = sigma.dim
          lower = stream.filtrationValue.applyOrElse(sigma, _ => filtration.smallest)
        yield (dim, lower, filtration.largest)
      )
    }

    def barcodeAt(f: FiltrationT): List[PersistenceBar[FiltrationT, Nothing]] =
      diagramAt(f).map { (dim, l, u) =>
        val lower: BarcodeEndpoint[FiltrationT] = l match {
          case i if i == filtration.smallest => NegativeInfinity()
          case f: FiltrationT                => ClosedEndpoint(f)
        }
        val upper: BarcodeEndpoint[FiltrationT] = u match {
          case i if i == filtration.largest => PositiveInfinity()
          case f: FiltrationT               => OpenEndpoint(f)
        }
        new PersistenceBar(dim, lower, upper, None)
      }

    @tailrec
    private def reduceBy(
      z: Chain[CellT, CoefficientT],
      basis: mutable.Map[CellT, Chain[CellT, CoefficientT]],
      reductionLog: Chain[CellT, CoefficientT] = Chain()
    )(using fr: Fractional[CoefficientT]): (Chain[CellT, CoefficientT], Chain[CellT, CoefficientT]) =
      z.leadingCell match {
        case None => (z, reductionLog)
        case Some(sigma) =>
          if (basis.contains(sigma)) {
            val redCoeff = fr.div(z.leadingCoefficient, basis(sigma).leadingCoefficient)
            reduceBy(z - redCoeff ⊠ basis(sigma), basis, reductionLog + redCoeff ⊠ Chain(sigma))
          } else (z, reductionLog)
      }

    def advanceOne(): Unit =
      if (CellIterator.hasNext) {
        val fr = summon[Fractional[CoefficientT]]
        val sigma: CellT = CellIterator.next()
        val dsigma: Chain[CellT, CoefficientT] =
          sigma.boundary[CoefficientT]: Chain[CellT, CoefficientT]
        val (dsigmaReduced, reduction) = reduceBy(dsigma, boundaries)
        val coboundary = reduction.items.foldRight(fr.negate(fr.one) ⊠ Chain(sigma)) { (next, acc) =>
          val (spx, coeff) = next
          if (coboundaries.contains(spx))
            acc + coeff ⊠ coboundaries(spx)
          else
            acc
        }
        if (dsigmaReduced.isZero()) {
          // adding a boundary to a boundary creates a new cycle as sigma + whatever whose boundary eliminated dsigma
          cycles(coboundary.leadingCell.get) = coboundary
          cyclesBornBy(coboundary.leadingCell.get) = sigma
        } else {
          // we have a new boundary witnessed
          boundaries(dsigmaReduced.leadingCell.get) = dsigmaReduced
          boundariesBornBy(dsigmaReduced.leadingCell.get) = sigma
          coboundaries(dsigmaReduced.leadingCell.get) = coboundary

          val (_, cycleBasis) = reduceBy(dsigmaReduced, cycles)
          val representativeCycle: Chain[CellT, CoefficientT] = cycleBasis.leadingCell match {
            case None       => Chain()
            case Some(cell) => cycles(cell)
          }
          cycleBasis.leadingCell match {
            case None       => ()
            case Some(cell) => cycles.remove(cell)
          }

          val lower: FiltrationT = cycleBasis.leadingCell match {
            case None => filtration.smallest
            case Some(spx) =>
              stream.filtrationValue.orElse(_ => filtration.smallest).compose(cyclesBornBy)(spx)
          }
          val upper: FiltrationT =
            stream.filtrationValue.orElse(_ => filtration.largest)(sigma)

          barcode.append((sigma.dim - 1, lower, upper, representativeCycle))
        }
        current = stream.filtrationValue.lift(sigma).getOrElse(stream.smallest)
      }

    def advanceTo(f: FiltrationT): Unit =
      while (CellIterator.hasNext && f > stream.filtrationValue.lift(CellIterator.head).getOrElse(stream.smallest))
        advanceOne()

    def advanceAll(): Unit =
      while (CellIterator.hasNext)
        advanceOne()

  }

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
}

class SimplicialHomologyByDimensionContext[VertexT: Ordering, CoefficientT: Fractional]
    extends ChainOps[Simplex[VertexT], CoefficientT]() {
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
  ) {
    // first off, all vertices are immediately cycles
    cycles.addAll(stream.iterateDimension(0).map(cell => cell -> Chain(cell)))
    cyclesBornBy.addAll(cycles.map((cell, chain) => cell -> cell))

    // secondly, we can read off homology completely from a minimal spanning tree
    val kruskal = new Kruskal[Simplex[VertexT]](
      cycles.keys.toSeq,
      { (x: Simplex[VertexT], y: Simplex[VertexT]) => stream.filtrationValue(Simplex.from(x.vertices ++ y.vertices)) }
    )(using stream.filtrationOrdering)

    kruskal.mstIterator.foreach { (src, tgt) =>
      val edge: Simplex[VertexT] = Simplex.from(src.vertices ++ tgt.vertices)

      // the edge src -- tgt will connect src to tgt thus removing one of the cycles
      val dEdge = edge.boundary
      val dyingVertex = dEdge.leadingCell.get
      boundaries.addOne(dEdge.leadingCell.get -> dEdge)
      coboundaries.addOne(dyingVertex, dEdge)
      barcode(0) =
        barcode(0).appended((stream.filtrationValue(dyingVertex), stream.filtrationValue(edge), cycles(dyingVertex)))
      cycles.remove(dyingVertex)
    }

    kruskal.cyclesIterator.foreach { (src, tgt) =>
      val edge: Simplex[VertexT] = Simplex.from(src.vertices ++ tgt.vertices)

      // the edge src -- tgt will connect src to tgt thus closing a loop
      val dEdge = edge.boundary
      // TODO is it worth it to have a more complex UnionFind that allows us to get the entire path along the MST?
      val (reduced, reductionLog): (Chain[Simplex[VertexT], CoefficientT], Chain[Simplex[VertexT], CoefficientT]) =
        reduceBy(dEdge, boundaries)
      val fr = summon[Fractional[CoefficientT]]
      val coboundary: Chain[Simplex[VertexT], CoefficientT] =
        reductionLog.items.foldRight(fr.negate(fr.one) ⊠ Chain(edge)) { (item, acc) =>
          val (spx, coeff) = item
          if (coboundaries.contains(spx))
            acc + coeff ⊠ coboundaries(spx)
          else
            acc
        }
      cycles(coboundary.leadingCell.get) = coboundary
      cyclesBornBy(coboundary.leadingCell.get) = edge
    }

    // setup is done, we should be ready to start dimension 2
    currentDim = 1
    current = Double.PositiveInfinity

    @tailrec
    private def reduceBy(
                          z: Chain[Simplex[VertexT], CoefficientT],
                          basis: mutable.Map[Simplex[VertexT], Chain[Simplex[VertexT], CoefficientT]],
                          reductionLog: Chain[Simplex[VertexT], CoefficientT] = Chain()
                        )(using
                          fr: Fractional[CoefficientT]
                        ): (Chain[Simplex[VertexT], CoefficientT], Chain[Simplex[VertexT], CoefficientT]) =
      z.leadingCell match {
        case None => (z, reductionLog)
        case Some(sigma) =>
          if (basis.contains(sigma)) {
            val redCoeff = fr.div(z.leadingCoefficient, basis(sigma).leadingCoefficient)
            reduceBy(z - redCoeff ⊠ basis(sigma), basis, reductionLog + redCoeff ⊠ Chain(sigma))
          } else (z, reductionLog)
      }

    def advanceOne(): Unit =
      if (currentIterator.hasNext) {
        val fr = summon[Fractional[CoefficientT]]
        val sigma = currentIterator.next()
        val dsigma : Chain[Simplex[VertexT], CoefficientT] = sigma.boundary
        val (dsigmaReduced, reduction) = reduceBy(dsigma, boundaries)
        val coboundary = reduction.items.foldRight(fr.negate(fr.one) ⊠ Chain(sigma)) { (next, acc) =>
          val (spx, coeff) = next
          if (coboundaries.contains(spx))
            acc + coeff ⊠ coboundaries(spx)
          else
            acc
        }
        if (dsigmaReduced.isZero()) {
          // adding a boundary to a boundary creates a new cycle as sigma + whatever whose boundary eliminated dsigma
          cycles(coboundary.leadingCell.get) = coboundary
          cyclesBornBy(coboundary.leadingCell.get) = sigma
        } else {
          // we have a new boundary witnessed
          boundaries(dsigmaReduced.leadingCell.get) = dsigmaReduced
          boundariesBornBy(dsigmaReduced.leadingCell.get) = sigma
          coboundaries(dsigmaReduced.leadingCell.get) = coboundary

          val (_, cycleBasis) = reduceBy(dsigmaReduced, cycles)
          val representativeCycle: Chain[Simplex[VertexT], CoefficientT] = cycleBasis.leadingCell match {
            case None => Chain()
            case Some(cell) => cycles(cell)
          }
          cycleBasis.leadingCell match {
            case None => ()
            case Some(cell) => cycles.remove(cell)
          }

          val lower: Double = cycleBasis.leadingCell match {
            case None => Double.NegativeInfinity
            case Some(spx) =>
              stream.filtrationValue.orElse{(_) => Double.NegativeInfinity}.compose(cyclesBornBy)(spx)
          }
          val upper: Double =
            stream.filtrationValue.orElse{(_) => Double.PositiveInfinity}(sigma)

          barcode(currentDim) = barcode(currentDim).appended((lower, upper, representativeCycle))
        }
        current = stream.filtrationValue.lift(sigma).getOrElse(stream.smallest)
      } else {
        currentDim += 1
        currentIterator = stream
          .iterateDimension
          .applyOrElse(currentDim, _ => Iterator.empty)
          .buffered
        current = Double.NegativeInfinity
      }

    def advanceTo(dim: Int, f: Double = Double.PositiveInfinity): Unit =
      while(currentIterator.hasNext &&
        currentDim <= dim &&
        f > current)
        advanceOne()
  }

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
}
