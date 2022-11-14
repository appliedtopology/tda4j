package org.appliedtopology.tda4j


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
 * @param Cell
 *
 *
 *
 */
class Chain[CellT <: Cell, CoefficientT : Fractional](using cellOrdering : Ordering[CellT]) {

}

/** Lightweight trait to define what it means to be a topological "Cell".
 */
trait Cell {

  /** The boundary map of the cell.
   *
   * @return
   *   A sequence of boundary cells.
   * @todo
   *   When a `Chain` trait or class has been created, this should change to
   *   return an appropriate `Chain` instead of the current `List`.
   */


  def boundary[CoefficientT : Fractional](): Chain[Cell, CoefficientT]
}
