package org.appliedtopology.tda4s

import language.experimental.modularity

trait FormalLinearCombination[Cell, Coeff: Field]:
  infix def add(other: FormalLinearCombination[Cell, Coeff]): FormalLinearCombination[Cell, Coeff]
  infix def scale(scalar: Coeff): FormalLinearCombination[Cell, Coeff]
  def coefficient(cell: Cell): Option[Coeff]
  def support: Set[Cell]
  def flatMap(f: Cell => FormalLinearCombination[Cell, Coeff]): FormalLinearCombination[Cell, Coeff]
  def isZero: Boolean

  extension (f: FormalLinearCombination[Cell, Coeff])
    def +(other: FormalLinearCombination[Cell, Coeff]): FormalLinearCombination[Cell, Coeff] = f.add(other)
    def *(scalar: Coeff): FormalLinearCombination[Cell, Coeff] = f.scale(scalar)