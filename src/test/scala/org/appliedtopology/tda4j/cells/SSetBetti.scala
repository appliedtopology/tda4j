package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.homology.CellularPersistenceInChunksEngine
import org.appliedtopology.tda4j.streams.FilteredSimplicialSetStream

/** Test support: dimensions of `H_n(X; F_p)`, `n = 0..top`, of a (complete, finite) simplicial set. */
object SSetBetti:
  def apply[G](x: FiniteSimplicialSet[G], prime: Int): Vector[Int] =
    if x.generatorsByDim.isEmpty then Vector.empty
    else
      val field = new FiniteField(prime)
      import field.given
      given (G is OrderedCell) = x.cellInstance
      val stream = FilteredSimplicialSetStream(x, PartialFunction.fromFunction((_: G) => 0.0))
      val diagram = CellularPersistenceInChunksEngine[G, field.Fp]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
      Vector.tabulate(x.generatorsByDim.length)(n => diagram.count((dim, _, death) => dim == n && death.isPosInfinity))
