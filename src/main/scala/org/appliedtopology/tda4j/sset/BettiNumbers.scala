package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** Betti numbers of a simplicial set over a prime field `F_p`: `dim H_n(X; F_p)` for each degree `n`, the number of
  * homology classes that are never killed. Computed with the chunks persistence engine on the unfiltered complex (every
  * generator at filtration value `0`), so it is the ordinary homology of `X`.
  *
  * Integer homology is not computed; comparing `F_2`, `F_3`, ... shows small torsion.
  */
object BettiNumbers:

  private def requirePrime(prime: Int): Unit =
    require(prime > 1 && BigInt(prime).isProbablePrime(certainty = 100), s"$prime is not a prime")

  /** `dim H_n(X; F_p)` for `n = 0 .. top dimension` of a finite simplicial set (empty vector for the empty set). */
  def apply[G](x: FiniteSimplicialSet[G], prime: Int): Vector[Int] =
    requirePrime(prime)
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

  /** `dim H_n(X; F_p)` for `n = 0 .. maxDegree` of a possibly infinite simplicial set, through its
    * `(maxDegree + 1)`-skeleton (a skeleton's homology is right only below its top degree, which is why one more
    * dimension is built and dropped).
    */
  def apply[G](x: SimplicialSet[G], maxDegree: Int, prime: Int): Vector[Int] =
    require(maxDegree >= 0, s"maxDegree must be >= 0, got $maxDegree")
    apply(x.skeleton(maxDegree + 1), prime).take(maxDegree + 1)
