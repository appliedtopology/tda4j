package org.appliedtopology.tda4j

import org.specs2.{mutable, Specification}
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

class SimplexTypeSpec extends mutable.Specification {
  "This is a specification for the type level interactions of the `Simplex` class".txt

  "The `Simplex` type should" >> {
    val s = Simplex(1, 2, 3)
    val t = Simplex(2, 3, 4)
    "be the return type of the Simplex constructor" >> {
      s must haveClass[Simplex]
    }
    "be the return type of the result of adding a vertex" >> {
      (s + 4) must haveClass[Simplex]
    }
    "be the return type of the result of removing a vertex" >> {
      (s - 2) must haveClass[Simplex]
    }
    "be the return type of intersecting two simplices" >> {
      (s & t) must haveClass[Simplex]
    }
    "be the return type of empty" >> {
      s.empty must haveClass[Simplex]
    }
    "be the return type of filter" >> {
      (s.filter(_ % 2 != 0)) must haveClass[Simplex]
    }
    "be the return type of init" >> {
      s.init must haveClass[Simplex]
    }
    "be the return type of flatMap" >> {
      (s.flatMap(Simplex(_, 10))) must haveClass[Simplex]
    }
    "be the content type of grouped" >> {
      s.grouped(2).next must haveClass[Simplex]
    }
    "be the return type of partition" >> {
      (s.partition(_ % 2 == 0)) must haveClass[(Simplex, Simplex)]
    }
    "be the return type of range, rangeFrom, rangeTo" >> {
      (s.range(0, 2)) must haveClass[Simplex]
      (s.rangeFrom(1)) must haveClass[Simplex]
      (s.rangeTo(2)) must haveClass[Simplex]
    }
    "be the return type of slice" >> {
      (s.slice(1, 2)) must haveClass[Simplex]
    }
    "be the element type of sliding" >> {
      (s.sliding(2).next) must haveClass[Simplex]
    }
    "be the element type of span" >> {
      (s.span(_ < 2)) must haveClass[(Simplex, Simplex)]
    }
    "be the element type of splitAt" >> {
      (s.splitAt(1)) must haveClass[(Simplex, Simplex)]
    }
    "be the element type of subsets" >> {
      (s.subsets().next) must haveClass[Simplex]
      (s.subsets(2).next) must haveClass[Simplex]
    }
  }
}
