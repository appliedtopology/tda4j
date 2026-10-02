package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.homology.BettiNumbers

/** Test support: kept so existing specs read `SSetBetti(x, p)`; it is just [[BettiNumbers]]. */
object SSetBetti:
  def apply[G](x: FiniteSimplicialSet[G], prime: Int): Vector[Int] = BettiNumbers(x, prime)
