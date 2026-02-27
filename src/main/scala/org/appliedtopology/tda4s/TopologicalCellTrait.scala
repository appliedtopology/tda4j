package org.appliedtopology.tda4s

import language.experimental.modularity

trait TopologicalCell:
  type Self
  def boundary[F : Field](cell: Self): FormalLinearCombination[Self, F]

  extension (s: Self)
    def ∂[F : Field]: FormalLinearCombination[Self, F] = boundary[F](s)
    