package org.appliedtopology.tda4j

import org.specs2.{mutable, Specification}
import org.specs2.execute.Result

import org.appliedtopology.tda4j.given

import math.Ordering.Implicits.sortedSetOrdering

object SimplexSpec extends Specification {

  def is =
    s2"""
Checking the `Simplex` class properties.

A `Simplex` should
  have a non-zero size $e1
  have a boundary $e2
"""

  def s: Simplex[Int] = Simplex(1, 2, 3)

  def e1: Result = s.dim must beGreaterThan[Int](0)

  given (Double is Field) = Field.DoubleApproximated(1e-25)

  def e2: Result = s.boundary `must` be_==(
    Chain[Simplex[Int], Double](
      Simplex(2, 3) -> 1.0,
      Simplex(1, 3) -> -1.0,
      Simplex(1, 2) -> 1.0
    )
  )
}

//noinspection ScalaFileName
class SimplexTypeSpec extends mutable.Specification {
  "This is a specification for the type level interactions of the `Simplex` class".txt

  "The `Simplex` type should" >> {
    val s = Simplex(1, 2, 3)
    val t = Simplex(2, 3, 4)
    "be the return type of the Simplex constructor" >> {
      s must haveClass[Simplex[Int]]
    }
  }
  "we can create a simplex, and get the right type" >> {
    ∆('a', 'b', 'c') must haveClass[Simplex[Char]]
    ∆('a', 'b', 'c') must haveClass[Simplex[Char]]
  }
}
