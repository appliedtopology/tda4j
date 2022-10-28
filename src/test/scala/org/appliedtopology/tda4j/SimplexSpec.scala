package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SimplexSpec.{
  beFalse,
  beGreaterThan,
  beTrue,
  contain,
  s2
}
import org.specs2.Specification
import org.specs2.execute.Result

object SimplexSpec extends Specification {

  def is =
    s2"""
Checking the `Simplex` class properties.

A `Simplex` should
  have a non-zero size $e1
  have a boundary $e2
  be able to add a vertex (and then have that vertex) $eAddVertex
  be able to remove a vertex (and then not have that vertex) $eRemoveVertex
"""

  def s: Simplex = Simplex(1, 2, 3)

  def sAdd4: Simplex = s.incl(4)
  def sDel2: Simplex = s.excl(2)

  def e1: Result = s.size must beGreaterThan[Int](0)

  def e2: Result =
    s.boundary() must contain(Simplex(1, 2), Simplex(1, 3), Simplex(2, 3))

  def eAddVertex: Result =
    sAdd4.contains(4) must beTrue

  def eRemoveVertex: Result =
    sDel2.contains(2) must beFalse
}
