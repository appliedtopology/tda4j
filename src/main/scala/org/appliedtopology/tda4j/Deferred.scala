package org.appliedtopology.tda4j


import scala.math.Numeric.DoubleIsFractional

/** This functionality is an experiment in using algebraic effect structures for flexible coefficient choices. The idea
  * is that each actual computation that is needed is built up as an AST, and simplified; and a coefficient choice is an
  * effect handler that evaluates the remainder in the field of coefficients to be used.
  */

trait FractionalHandler {
  type T
  val fractional: Fractional[T]
  def eval(fractionalExpr: FractionalExpr): T
}

object doubleHandler extends FractionalHandler {
  import FractionalExpr.*
  override type T = Double

  override val fractional: Fractional[Double] = DoubleIsFractional

  override def eval(fractionalExpr: FractionalExpr): Double = fractionalExpr match {
    case Zero        => 0.0
    case One         => 1.0
    case Negate(x)   => -eval(x)
    case Inv(x)      => 1 / eval(x)
    case Plus(x, y)  => eval(x) + eval(y)
    case Minus(x, y) => eval(x) - eval(y)
    case Times(x, y) => eval(x) * eval(y)
    case Div(x, y)   => eval(x) / eval(y)
  }
}

sealed trait FractionalExpr
object FractionalExpr {
  case object Zero extends FractionalExpr
  case object One extends FractionalExpr
  case class Plus(x: FractionalExpr, y: FractionalExpr) extends FractionalExpr
  case class Minus(x: FractionalExpr, y: FractionalExpr) extends FractionalExpr
  case class Negate(x: FractionalExpr) extends FractionalExpr
  case class Times(x: FractionalExpr, y: FractionalExpr) extends FractionalExpr
  case class Div(x: FractionalExpr, y: FractionalExpr) extends FractionalExpr
  case class Inv(x: FractionalExpr) extends FractionalExpr
  case class Sum(xs: List[FractionalExpr]) extends FractionalExpr
  case class Prod(xs: List[FractionalExpr]) extends FractionalExpr
  case class Succ(x: FractionalExpr) extends FractionalExpr
}

given Fractional[FractionalExpr] = new Fractional[FractionalExpr] {
  import FractionalExpr.*

  override def div(x: FractionalExpr, y: FractionalExpr): FractionalExpr = Div(x, y)
  override def plus(x: FractionalExpr, y: FractionalExpr): FractionalExpr = Plus(x, y)
  override def minus(x: FractionalExpr, y: FractionalExpr): FractionalExpr = Minus(x, y)
  override def times(x: FractionalExpr, y: FractionalExpr): FractionalExpr = Times(x, y)
  override def negate(x: FractionalExpr): FractionalExpr = Negate(x)
  override def fromInt(x: Int): FractionalExpr =
    if (x < 0) Negate(fromInt(-x))
    else Plus(One, fromInt(x - 1))
  override def parseString(str: String): Option[FractionalExpr] = None
  override def toInt(x: FractionalExpr): Int = doubleHandler.eval(x).round.toInt
  override def toLong(x: FractionalExpr): Long = doubleHandler.eval(x).round
  override def toFloat(x: FractionalExpr): Float = doubleHandler.eval(x).toFloat
  override def toDouble(x: FractionalExpr): Double = doubleHandler.eval(x)
  override def compare(x: FractionalExpr, y: FractionalExpr): Int =
    doubleHandler.fractional.compare(doubleHandler.eval(x), doubleHandler.eval(y))
}
