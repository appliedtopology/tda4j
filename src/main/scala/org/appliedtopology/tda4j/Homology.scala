package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.PersistenceBar

import collection.mutable
import scala.annotation.tailrec

given [T: Ordering]: Ordering[AbstractSimplex[T]] = (new SimplexContext[T] {}).simplexOrdering

class ReducedSimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional, FiltrationT: Ordering]() 
  extends CellularHomologyContext[AbstractSimplex[VertexT], CoefficientT, FiltrationT]()
  with SimplexContext[VertexT]() {}

class SimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional, FiltrationT : Ordering]() 
  extends CellularHomologyContext[AbstractSimplex[VertexT], CoefficientT, FiltrationT](Seq(AbstractSimplex[VertexT]()))
    with SimplexContext[VertexT]() {}

class CellularHomologyContext[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional, FiltrationT : Ordering](val cellPrefix : Seq[CellT] = Seq()) extends ChainOps[CellT, CoefficientT]() {

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

    val CellIterator: collection.BufferedIterator[CellT] = 
      Iterator.concat(cellPrefix, stream.iterator.to(Iterable)).buffered

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
          lower = stream.filtrationValue(sigma)
        yield (dim, lower, filtration.largest)
      )
    }

    def barcodeAt(f: FiltrationT): List[PersistenceBar[FiltrationT, Nothing]] =
      diagramAt(f).map { (dim, l, u) =>
        val lower: BarcodeEndpoint[FiltrationT] = l match {
          case i if i == filtration.smallest => NegativeInfinity()
          case f: FiltrationT      => ClosedEndpoint(f)
        }
        val upper: BarcodeEndpoint[FiltrationT] = u match {
          case i if i == filtration.largest => PositiveInfinity()
          case f: FiltrationT     => OpenEndpoint(f)
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
