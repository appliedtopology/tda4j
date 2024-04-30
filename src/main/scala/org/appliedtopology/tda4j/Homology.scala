package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.PersistenceBar

import collection.mutable
import scala.annotation.tailrec
import scalaz.{Ordering => _, _}, Scalaz._

given [T: Ordering]: Ordering[AbstractSimplex[T]] = (new SimplexContext[T] {}).simplexOrdering

class SimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional]()
    extends ChainOps[AbstractSimplex[VertexT], CoefficientT]()
    with SimplexContext[VertexT]() {

  type FiltrationT = Double // should change this later

  import barcode._

  case class HomologyState(
    cycles: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
    cyclesBornBy: mutable.Map[Simplex, Simplex],
    boundaries: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
    boundariesBornBy: mutable.Map[Simplex, Simplex],
    coboundaries: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
    stream: SimplexStream[VertexT, FiltrationT],
    var current: FiltrationT,
    barcode: mutable.ArrayDeque[
      (
        Int,
        FiltrationT | NegativeInfinity[FiltrationT],
        FiltrationT | PositiveInfinity[FiltrationT],
        ChainElement[Simplex, CoefficientT]
      )
    ]
  ) {
    given Ordering[Simplex] = stream.filtrationOrdering
    given Order[Simplex] = Order.fromScalaOrdering(stream.filtrationOrdering)
    given Order[(Simplex, CoefficientT)] = Order.orderBy(_._1)

    val simplexIterator : collection.BufferedIterator[Simplex] = stream.iterator.buffered
    boundaries(Simplex()) = ChainElement(Simplex())

    def diagramAt(
      f: FiltrationT
    ): List[(Int, FiltrationT | NegativeInfinity[FiltrationT], FiltrationT | PositiveInfinity[FiltrationT])] = {
      advanceTo(f)
      (for
        (dim, lowerQ, oldUpperQ, cycle): (
          Int,
          FiltrationT | NegativeInfinity[FiltrationT],
          FiltrationT | PositiveInfinity[FiltrationT],
          ChainElement[Simplex, CoefficientT]
        ) <- barcode.toList
        lower = lowerQ match {
          case NegativeInfinity() => Double.NegativeInfinity
          case l: Double          => l
        }
        oldUpper = oldUpperQ match {
          case NegativeInfinity() => Double.NegativeInfinity
          case l: Double          => l
        }
        if lower <= f
        upper = oldUpper.min(f)
      yield (dim, lower, upper)) ++ (
        for
          (sigma, z) <- cycles
          dim = sigma.size - 1
          lower = stream.filtrationValue(sigma)
        yield (dim, lower, Double.PositiveInfinity)
      )
    }

    def barcodeAt(f: FiltrationT): List[PersistenceBar[FiltrationT, Nothing]] =
      diagramAt(f).map { (dim, l, u) =>
        val lower: BarcodeEndpoint[FiltrationT] = l match {
          case NegativeInfinity() => NegativeInfinity()
          case f: FiltrationT     => ClosedEndpoint(f)
        }
        val upper: BarcodeEndpoint[FiltrationT] = u match {
          case PositiveInfinity() => PositiveInfinity()
          case f: FiltrationT     => OpenEndpoint(f)
        }
        new PersistenceBar(dim, lower, upper, None)
      }

    @tailrec
    private def reduceBy(
      z: ChainElement[Simplex, CoefficientT],
      basis: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
      reductionLog: ChainElement[Simplex, CoefficientT] = ChainElement()
    )(using fr: Fractional[CoefficientT]): (ChainElement[Simplex, CoefficientT], ChainElement[Simplex, CoefficientT]) =
      z.leadingCell match {
        case None => (z, reductionLog)
        case Some(sigma) =>
          if (basis.contains(sigma)) {
            val redCoeff = fr.div(z.leadingCoefficient, basis(sigma).leadingCoefficient)
            reduceBy(z - redCoeff ⊠ basis(sigma), basis, reductionLog + redCoeff ⊠ ChainElement(sigma))
          } else (z, reductionLog)
      }

    def advanceOne(): Unit =
      if (simplexIterator.hasNext) {
        val fr = summon[Fractional[CoefficientT]]
        val sigma: Simplex = simplexIterator.next()
        val dsigma: ChainElement[Simplex, CoefficientT] =
          sigma.boundary[CoefficientT]: ChainElement[Simplex, CoefficientT]
        val (dsigmaReduced, reduction) = reduceBy(dsigma, boundaries)
        val coboundary = reduction.chainHeap.foldRight(fr.negate(fr.one) ⊠ ChainElement(sigma)) { (next, acc) =>
          val (spx, coeff) = next
          if (coboundaries.contains(spx))
            acc + coeff ⊠ coboundaries(spx)
          else
            acc
        }
        if (dsigmaReduced.isZero) {
          // adding a boundary to a boundary creates a new cycle as sigma + whatever whose boundary eliminated dsigma
          cycles(coboundary.leadingCell.get) = coboundary
          cyclesBornBy(coboundary.leadingCell.get) = sigma
        } else {
          // we have a new boundary witnessed
          boundaries(dsigmaReduced.leadingCell.get) = dsigmaReduced
          boundariesBornBy(dsigmaReduced.leadingCell.get) = sigma
          coboundaries(dsigmaReduced.leadingCell.get) = coboundary

          val (_, cycleBasis) = reduceBy(dsigmaReduced, cycles)
          val representativeCycle: ChainElement[Simplex, CoefficientT] = cycleBasis.leadingCell match {
            case None       => ChainElement()
            case Some(cell) => cycles(cell)
          }
          cycleBasis.leadingCell match {
            case None       => ()
            case Some(cell) => cycles.remove(cell)
          }

          val lower: FiltrationT | NegativeInfinity[FiltrationT] = cycleBasis.leadingCell match {
            case None => NegativeInfinity[FiltrationT]()
            case Some(spx) =>
              stream.filtrationValue.orElse(_ => NegativeInfinity[FiltrationT]()).compose(cyclesBornBy)(spx)
          }
          val upper: FiltrationT | PositiveInfinity[FiltrationT] =
            stream.filtrationValue.orElse(_ => PositiveInfinity[FiltrationT]())(sigma)

          barcode.append((sigma.size - 2, lower, upper, representativeCycle))
        }
        current = stream.filtrationValue(sigma)
      }

    def advanceTo(f: FiltrationT): Unit =
      while (simplexIterator.hasNext && f > stream.filtrationValue(simplexIterator.head))
        advanceOne()

    def advanceAll(): Unit =
      while(simplexIterator.hasNext)
        advanceOne()

  }

  def persistentHomology(stream: => SimplexStream[VertexT, FiltrationT]): HomologyState =
    HomologyState(
      mutable.Map.empty, // cycle basis
      mutable.Map.empty, // cycle born by
      mutable.Map.empty, // boundary basis
      mutable.Map.empty, // boundary born by
      mutable.Map.empty, // coboundary mapping
      stream, // simplex stream
      0: FiltrationT, // computation done up until filtrationValue
      mutable.ArrayDeque.empty
    ) // torsion part of barcode

}
