package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SimplexSpec.{beGreaterThan, contain, s2}
import org.specs2.Specification
import org.specs2.execute.Result

object SimplexSpec extends Specification :
  def is =
    s2"""
Checking the `Simplex` class properties.

A `Simplex` should
  have a non-zero size $e1
  have a boundary $e2
"""

  def s : Simplex = Simplex(1, 2, 3)

  def e1: Result = s.size must beGreaterThan(0)

  def e2: Result = s.boundary() must contain(Simplex(1, 2), Simplex(1, 3), Simplex(2, 3))
