package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import math.Ordering.Implicits.sortedSetOrdering
import scala.math.Numeric.FloatIsFractional

class ChainSpec extends mutable.Specification {
  """This is the specification for testing the Chain implementation.
    |""".stripMargin.txt

  given Conversion[AbstractSimplex[Int], Chain[AbstractSimplex[Int], Double]] = Chain.apply
  given Fractional[Double] = math.Numeric.DoubleIsFractional
  given Ordering[Int] = math.Ordering.Int

  "The `Chain` type should" >> {
    val z1 = Chain(Simplex(1,2,3))
    val z2 = Chain(Simplex(1,2) -> 1.0, Simplex(1,3) -> -1.0, Simplex(2,3) -> 1.0)
    "be the return type of Chain applied to a single simplex" >> {
      z1 must haveClass[Chain[Simplex, Double]]
    }
    "be the return type of Chain applied to several simplex/coefficient pairs" >> {
      z2 must haveClass[Chain[Simplex, Double]]
    }
  }
}