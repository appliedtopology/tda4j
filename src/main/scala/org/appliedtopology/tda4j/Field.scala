package org.appliedtopology.tda4j

trait Field:
  type Self

  def plus(x: Self, y: Self): Self
  def minus(x: Self, y: Self): Self
  def times(x: Self, y: Self): Self
  def divide(x: Self, y: Self): Self
  def isEqual(x: Self, y: Self): Boolean
  def negate(x: Self): Self
  def invert(x: Self): Self
  def zero: Self
  def one: Self

  extension (x: Self)
    infix def +(y: Self): Self = plus(x, y)
    infix def -(y: Self): Self = minus(x, y)
    infix def *(y: Self): Self = times(x, y)
    infix def /(y: Self): Self = divide(x, y)
    infix def eql(y: Self): Boolean = isEqual(x, y)
    def unary_- : Self = negate(x)
    def isZero: Boolean = x eql zero

object Field:
  def DoubleApproximated(epsilon: Double): Double is Field = new (Double is Field):
    val fr = summon[Fractional[Double]]

    override def plus(x: Double, y: Double): Double = fr.plus(x, y)

    override def minus(x: Double, y: Double): Double = fr.minus(x, y)

    override def times(x: Double, y: Double): Double = fr.times(x, y)

    override def divide(x: Double, y: Double): Double = fr.div(x, y)

    override def isEqual(x: Double, y: Double): Boolean = fr.abs(fr.minus(x, y)) < epsilon

    override def negate(x: Double): Double = fr.negate(x)

    override def invert(x: Double): Double = fr.div(fr.one, x)

    override def zero: Double = fr.zero

    override def one: Double = fr.one
