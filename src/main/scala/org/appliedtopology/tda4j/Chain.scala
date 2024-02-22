package org.appliedtopology.tda4j

import collection.immutable.SortedMap
import math.Ordering.Implicits.sortedSetOrdering
import scala.annotation.targetName

/**
 * The Chain class is a representation of a formal linear combination of the n cells in a cell complex.
 * Note that CellT is a type parameter subtype of Cell, a trait in this library. We have Scala look for
 * a type parameter of cellOrdering that matches as best as possible an Ordering on CellT.
 *
 * (Note: write a fully fleshed out explanation in comments after code def. write up)
 *
 *
 * @tparam CellT Type of the cells in the chain complex. For example `AbstractSimplex[Int]` etc.
 * @tparam CoefficientT Type of the coefficients of the chain complex. For example `Double` or an  implicit 'FiniteField : Fractional[Int]'
 * @param chainMap Internal storage of the sorted map of the elements
 *
 *
 *
 */
class Chain[CellT <: Cell[CellT] : Ordering , CoefficientT]
/**
 * chainMap is an immutable variable and constructor that uses Scala's SortedMap to make a key-value pairing of an CellT as the key and a
 * CoefficientT type as the value. Here, we'll use the Using keyword to check for any relevant types for CoefficientT.
 */
  (val chainMap : SortedMap[CellT, CoefficientT])(using fr : Fractional[CoefficientT]) {

  /**
   * Negate takes in a Chain instance and outputs a new Chain instance which takes in a chainMap (remember chainMap
   * is using SortedMap 'under the hood', thus already has a key/value pair). transform() is used on this function's
   * instance of chainMap to transform the key/value pairings in chainMap to the negative versions of the values
   * in the value pairs.
    * @return new Chain of negated values in key/value pairs
   */
  def negate: Chain[CellT, CoefficientT] = new Chain(chainMap.transform((k, v) => fr.negate(v)))


  /**
   * Unary uses negate() for unary negation
   *
   * @return negation
   */
  def unary_- : Chain[CellT, CoefficientT] = negate

  /**
   * scalarMultiply returns a new chain containing chainMap and its key/value pairing. In the chainMap,
   * transform() is used on the value of the key/value pairing. On the value, it is transformed by multiplying
   * each key by the CoefficientT, using the times() method of the Fractional trait, extending the Numeric library.
   * @param c: method instance of CoefficientT
   * @return new Chain with values in key/value pairing multiplied by c
   */

  def scalarMultiply(c: CoefficientT): Chain[CellT, CoefficientT] =
    new Chain(chainMap.transform((k, v) => fr.times(v, c)))

  def *: : CoefficientT => Chain[CellT, CoefficientT] = scalarMultiply

  /**
   * add() adds the method instance of the keys of a chainMap to the classes chainMap keys.
   * It then adds the result of this to a map which maps that key to the values of the class
   * and the instance method's chainMap.
   * @param that: Chain object composed of a Cell/Coefficient pair
   * @return Chain object composed of a Cell/Coefficient pair
   */
  def add(that: Chain[CellT, CoefficientT]): Chain[CellT, CoefficientT] =
    Chain((chainMap.keySet | that.chainMap.keySet).map(k =>
      (k, fr.plus(chainMap.getOrElse(k, fr.zero), that.chainMap.getOrElse(k, fr.zero)))).toSeq *)

  def + : Chain[CellT, CoefficientT] => Chain[CellT, CoefficientT] = add

  def subtract(that: Chain[CellT, CoefficientT]): Chain[CellT, CoefficientT] = this + (-that)

  def - : Chain[CellT, CoefficientT] => Chain[CellT, CoefficientT] = subtract

  override def toString: String = chainMap.map((k, v) => s"${v.toString} *: ${k.toString}").mkString(" + ")

  //Define equality between chains.
  //an override of the equals method from the Any class.
  override def equals(other: Any): Boolean = {
    //pattern matching block that checks the type of the "other" object.
    other match {
      //if it's a chain, check the chainMap is the same
      case that: Chain[CellT, CoefficientT] =>
        this.chainMap == that.chainMap // Compare chainMap contents.
      //else they can't be equal!
      case _ => false
    }
  }
  //Overriding the hashcode so both of our objects have the same one, buncha good reasons for this.
  override def hashCode(): Int = chainMap.hashCode()
}
object Chain {

  // for first apply: he first `apply` takes a sequence of coefficient/cell pairs and creates a sorted map that fits the constructor to let us create chains that way.
  /**
   * Both apply() functions assist in constructor execution.
   *
   *
   * The 1st apply() is used to take Cell/Coefficient pairs, and converts them to a sorted map using the Chain
   * chainMap constructor. Why? So it works with the constructor, so we can create chains.
   *
   * Here,the 2nd apply() works as a implicit cast function. Simply, it is used to take a cell
   * and transform it to a chain.
   * In depth, the Chain chainMap constructor is called to create a new Chain object, as indicated by 'new' followed by Chain.
   * The constructor then requires a Cell/Coefficient pair.
   * The "List(cell -> fr.one)" creates a list with at least a single key-value of a Cell/Coefficient pair
   * ready to be inserted into the chainMap. The key will then product the value 1. Why 1? The Chain has to produce at
   * least a value of 1. Think of this as the 'base case' for Chain values.
   *
   *
   *
   *
   *
   */

  def apply[CellT <: Cell[CellT] : Ordering, CoefficientT : Fractional] (items : (CellT, CoefficientT)*) : Chain[CellT, CoefficientT] =

    //Filter out terms with zero coefficients during construction
    val filteredItems = items.filter { case (_, coefficient) => coefficient != 0 }
    new Chain[CellT, CoefficientT](SortedMap.from(filteredItems))

    //Original apply innards
    //new Chain[CellT, CoefficientT](SortedMap.from(items))

  def apply[CellT <: Cell[CellT] : Ordering, CoefficientT] (cell : CellT)(using fr : Fractional[CoefficientT]) : Chain[CellT, CoefficientT] =
    new Chain[CellT, CoefficientT](SortedMap.from(List(cell -> fr.one)))
}


/** Lightweight trait to define what it means to be a topological "Cell".
 *
 * Using F-bounded types to ensure reflective typing.
 * See https://tpolecat.github.io/2015/04/29/f-bounds.html
 * See https://dotty.epfl.ch/3.0.0/docs/reference/contextual/type-classes.html
 */
trait Cell[CellT <: Cell[CellT]] {
  this: CellT =>
  /** The boundary map of the cell.
   *
   * @return
   * A sequence of boundary cells.
   * @todo
   * When a `Chain` trait or class has been created, this should change to
   * return an appropriate `Chain` instead of the current `List`.
   */

  def boundary[CoefficientT: Fractional]: Chain[CellT, CoefficientT]

}
