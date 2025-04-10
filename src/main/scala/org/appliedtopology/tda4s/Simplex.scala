package org.appliedtopology.tda4s

import language.experimental.modularity

case class Simplex(vertices: Set[Int])
object Simplex:
  def apply(v: Int*): Simplex = Simplex(v.toSet)
  
given Simplex is TopologicalCell = new TopologicalCell:
  type Self = Simplex

  override def boundary[F : Field](cell: Simplex): FormalLinearCombination[Simplex, F] = 
    val field = summon[F is Field]
    val faces = cell.vertices.toList
    val terms = faces.zipWithIndex.map { case (v, i) =>
      val sign = if i % 2 == 0 then field.one else field.sub(field.zero, field.one)
      val face = Simplex(cell.vertices - v)
      face -> sign
    }
    MapFormalLinearCombination(terms.toMap)