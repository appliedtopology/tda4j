package org.appliedtopology.tda4j

/** Test support: kept so existing specs read `SSetBetti(x, p)`; it is just [[BettiNumbers]]. */
object SSetBetti:
  def apply[G](x: FiniteSimplicialSet[G], prime: Int): Vector[Int] = BettiNumbers(x, prime)
