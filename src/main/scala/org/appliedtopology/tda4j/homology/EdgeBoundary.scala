package org.appliedtopology.tda4j

/** The shape of a 1-cell's boundary, for the engines that pair degree 0 by union-find over the edges: that is valid
  * when every edge either joins two vertices, with boundary `c·(v - u)`, or closes a cycle by itself.
  */
// Restricted (reason 3 of the openness rules): public top-level names here would join every user's wildcard import.
private[tda4j] object EdgeBoundary:
  /** No entries, a loop's `(v, c), (v, -c)`, or coefficients that vanish in this field: the edge closes a cycle by
    * itself.
    */
  val ZeroBoundary = 0

  /** `c·(v - u)` with `u ≠ v` and `c` a unit, as entries 0 and 1. */
  val JoinsTwo = 1

  /** Anything else, which an algebraic chain complex may have and union-find cannot express. */
  val Irregular = 2

  // Plain Ints and no allocation on the common two-entry path: this runs once per edge of the complex.
  def shape[CellT, CoefficientT: Field](endpoints: Seq[(CellT, CoefficientT)]): Int =
    val fld = summon[CoefficientT is Field]
    def isZero(x: CoefficientT): Boolean = fld.isEqual(x, fld.zero)
    endpoints.length match
      case 0 => ZeroBoundary
      case 1 => if isZero(endpoints(0)._2) then ZeroBoundary else Irregular
      case 2 =>
        val (a, ca) = endpoints(0)
        val (b, cb) = endpoints(1)
        if a == b then if isZero(fld.plus(ca, cb)) then ZeroBoundary else Irregular
        else if isZero(ca) then if isZero(cb) then ZeroBoundary else Irregular
        else if isZero(cb) then Irregular
        else if isZero(fld.plus(ca, cb)) then JoinsTwo
        else Irregular
      case _ =>
        if endpoints.groupMapReduce(_._1)(_._2)(fld.plus).valuesIterator.forall(isZero) then ZeroBoundary
        else Irregular
