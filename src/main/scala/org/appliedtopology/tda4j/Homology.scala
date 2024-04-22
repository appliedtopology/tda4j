package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.PersistenceBar
import collection.mutable

given [T: Ordering]: Ordering[AbstractSimplex[T]] = (new SimplexContext[T] {}).simplexOrdering
class SimplicialHomologyContext[VertexT: Ordering, CoefficientT: Fractional]()
    extends ChainOps[AbstractSimplex[VertexT], CoefficientT]()
    with SimplexContext[VertexT]() {

  import barcode._

  case class HomologyState(
    cycles: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
    boundaries: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]],
    coboundaries: mutable.Map[Simplex, Simplex],
    stream: SimplexStream[VertexT, Double],
    var current: Double,
    barcode: mutable.ArrayDeque[(Int, Double, Double)]
  ) {
    val simplexIterator = stream.iterator
    boundaries(Simplex()) = ChainElement(Simplex())

    def barcodeAt(f: Double): List[(Int, Double, Double)] = {
      advanceTo(f)
      (for
        (dim, lower, oldUpper): (Int, Double, Double) <- barcode.toList
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

    def reduceBy(
      z: ChainElement[Simplex, CoefficientT],
      basis: mutable.Map[Simplex, ChainElement[Simplex, CoefficientT]]
    ): (ChainElement[Simplex, CoefficientT], ChainElement[Simplex, CoefficientT]) =
      basis.keys.foldLeft((z, ChainElement[Simplex, CoefficientT]())) { (chainPair, basisKey) =>
        if (chainPair._1.leadingCell == Some(basisKey)) {
          val fr = summon[Fractional[CoefficientT]]
          val (z, reduction) = chainPair
          val reductionChain = fr.div(z.leadingCoefficient, basis(basisKey).leadingCoefficient) ⊠ basis(basisKey)
          val reductionLog = fr.div(z.leadingCoefficient, basis(basisKey).leadingCoefficient) ⊠ ChainElement(basisKey)
          (z - reductionChain, reduction + reductionLog)
        } else {
          chainPair
        }
      }

    def advanceTo(f: Double): Unit =
      while (simplexIterator.hasNext && f > current)
        if (simplexIterator.hasNext) {
          val fr = summon[Fractional[CoefficientT]]
          val sigma: Simplex = simplexIterator.next()
          val dsigma: ChainElement[Simplex, CoefficientT] =
            sigma.boundary[CoefficientT]: ChainElement[Simplex, CoefficientT]
          val (dsigmaReduced, reduction) = reduceBy(dsigma, boundaries)
          if (dsigmaReduced.isZero) {
            // adding a boundary to a boundary creates a new cycle
            cycles(sigma) = reduction.chainHeap.foldRight(ChainElement(sigma): ChainElement[Simplex, CoefficientT]) {
              (next, acc) =>
                val (spx, coeff) = next
                if (coboundaries.contains(spx))
                  acc + coeff ⊠ ChainElement(coboundaries(spx))
                else
                  acc
            }
          } else {
            // we have a new boundary witnessed
            boundaries(dsigmaReduced.leadingCell.get) = dsigmaReduced
            coboundaries(dsigmaReduced.leadingCell.get) = sigma

            val (_, cycleBasis) = reduceBy(dsigmaReduced, cycles)
            cycleBasis.leadingCell match {
              case None       => ()
              case Some(cell) => cycles.remove(cell)
            }

            val lower: Double = cycleBasis.leadingCell match {
              case None      => Double.NegativeInfinity
              case Some(spx) => stream.filtrationValue.orElse(_ => Double.NegativeInfinity)(spx)
            }
            val upper: Double = stream.filtrationValue.orElse(_ => Double.PositiveInfinity)(sigma)

            barcode.append((sigma.size - 2, lower, upper))
          }
          current = stream.filtrationValue(sigma)
        }
  }

  def persistentHomology(stream: SimplexStream[VertexT, Double]): HomologyState =
    HomologyState(
      mutable.Map.empty, // cycle basis
      mutable.Map.empty, // boundary batis
      mutable.Map.empty, // coboundary mapping
      stream, // simplex stream
      0: Double, // computation done up until filtrationValue
      mutable.ArrayDeque.empty
    ) // torsion part of barcode

}
