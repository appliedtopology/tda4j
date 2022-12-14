package org.appliedtopology.tda4j

import collection.immutable.SortedMap
import math.Ordering.Implicits.sortedSetOrdering

/**
 * The Chain class is a representation of a formal linear combination of the n cells in a cell complex.
 * Note that CellT is a type parameter subtype of Cell, a trait in this library. We have Scala look for
 * a type parameter of cellOrdering that matches as best as possible an Ordering on CellT
 *
 * (Note: write a fully fleshed out explanation in comments after code def. write up)
 *
 *
 * @tparam CellT Type of the cells in the chain complex. For example `AbstractSimplex[Int]` etc.
 * @tparam CoefficientT Type of the coefficients of the chain complex. For example `Double` or `IntModp`
 * @param chainMap Internal storage of the sorted map of the elements
 *
 *
 *
 */
class Chain[CellT <: Cell[CellT] : Ordering , CoefficientT]
  (val chainMap : SortedMap[CellT, CoefficientT])(using fr : Fractional[CoefficientT]) {

  def negate : Chain[CellT,CoefficientT] = new Chain(chainMap.transform((k,v) => fr.negate(v)))
  def unary_- = negate

  def scalarMultiply(c : CoefficientT) : Chain[CellT, CoefficientT] =
    new Chain(chainMap.transform((k,v) => fr.times(v,c)))
  def *: = scalarMultiply

  def add(that : Chain[CellT, CoefficientT]) : Chain[CellT, CoefficientT] =
    Chain((chainMap.keySet | that.chainMap.keySet).map(k =>
      (k, fr.plus(chainMap.getOrElse(k, fr.zero), that.chainMap.getOrElse(k, fr.zero)))).toSeq*)
  def + = add

  def subtract(that : Chain[CellT, CoefficientT]) : Chain[CellT, CoefficientT] = this + (-that)
  def - = subtract
  override def toString: String = chainMap.map((k,v) => s"${v.toString} *: ${k.toString}").mkString(" + ")
}
object Chain {
  def apply[CellT <: Cell[CellT] : Ordering, CoefficientT : Fractional](items : (CellT, CoefficientT)*) =
    new Chain[CellT, CoefficientT](SortedMap.from(items))

  def apply[CellT <: Cell[CellT] : Ordering, CoefficientT](cell : CellT)(using fr : Fractional[CoefficientT]) =
    new Chain[CellT, CoefficientT](SortedMap.from(List(cell -> fr.one)))
}


/** Lightweight trait to define what it means to be a topological "Cell".
 *
 * Using F-bounded types to ensure reflective typing.
 * See https://tpolecat.github.io/2015/04/29/f-bounds.html
 * See https://dotty.epfl.ch/3.0.0/docs/reference/contextual/type-classes.html
 */
trait Cell[CellT <: Cell[CellT]] {
  this : CellT =>
  /** The boundary map of the cell.
   *
   * @return
   *   A sequence of boundary cells.
   * @todo
   *   When a `Chain` trait or class has been created, this should change to
   *   return an appropriate `Chain` instead of the current `List`.
   */

  def boundary[CoefficientT : Fractional] : Chain[CellT, CoefficientT]
}
