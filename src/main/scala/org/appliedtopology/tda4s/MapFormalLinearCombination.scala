package org.appliedtopology.tda4s

import language.experimental.modularity

class MapFormalLinearCombination[Cell, Coeff: Field](
  private val terms: Map[Cell, Coeff] = Map.empty
) extends FormalLinearCombination[Cell, Coeff]:

  private val field = summon[Coeff is Field]

  def coefficient(cell: Cell): Option[Coeff] = terms.get(cell)

  def support: Set[Cell] = terms.keySet

  def add(other: FormalLinearCombination[Cell, Coeff]): FormalLinearCombination[Cell, Coeff] =
    other match
      case o: MapFormalLinearCombination[Cell, Coeff] =>
        val newTerms = (terms.keySet ++ o.terms.keySet).foldLeft(Map.empty[Cell, Coeff]) { (acc, cell) =>
          val thisCoeff = terms.getOrElse(cell, field.zero)
          val otherCoeff = o.terms.getOrElse(cell, field.zero)
          val sum = field.add(thisCoeff, otherCoeff)
          if sum != field.zero then acc + (cell -> sum) else acc
        }
        MapFormalLinearCombination(newTerms)

  def scale(scalar: Coeff): FormalLinearCombination[Cell, Coeff] =
    if scalar == field.zero then MapFormalLinearCombination(Map.empty)
    else MapFormalLinearCombination(terms.map((cell, coeff) => (cell, field.mul(coeff, scalar))))

  def flatMap(f: Cell => FormalLinearCombination[Cell, Coeff]): FormalLinearCombination[Cell, Coeff] =
    support.foldLeft(MapFormalLinearCombination[Cell, Coeff](Map.empty)) { (acc : FormalLinearCombination[Cell, Coeff], cell) =>
      val coeff = coefficient(cell).getOrElse(field.zero)
      val mapped = f(cell).scale(coeff)
      acc.add(mapped)
    }

  def isZero: Boolean =
    terms.isEmpty || terms.values.forall(_ == field.zero)

