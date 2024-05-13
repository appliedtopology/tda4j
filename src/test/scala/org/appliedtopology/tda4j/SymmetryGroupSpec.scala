package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import java.util.NoSuchElementException
import scala.collection.SortedSet
import scala.collection.immutable
import scala.collection.immutable.{BitSet, Range}
import scala.collection.mutable.ListBuffer
import math.Ordering.Implicits.*

class SymmetryGroupSpec extends mutable.Specification {
  sequential
  """This is the specification for our expectations on the symmetry group
    |action interfaces and their usages.
    |""".stripMargin

  val hc3s: HyperCubeSymmetry = HyperCubeSymmetry(3)

  "In the 3 bit Hyper Cube symmetry group" >> {
    "[0,1] is representative" >> {
      val s = Simplex(0, 1)
      s === hc3s.orbit(s).min
      s === hc3s.representative(s)
      hc3s.isRepresentative(s) must beTrue
    }
    "[0,2] is not representative" >> {
      val s = Simplex(0, 2)
      s !== hc3s.orbit(s).min
      s !== hc3s.representative(s)
      hc3s.isRepresentative(s) must beFalse
    }
  }

  val el: ExpandList[Int, Int] = ExpandList(
    Seq(
      Simplex(0),
      Simplex(1),
      Simplex(3),
      Simplex(7),
      Simplex(0, 1),
      Simplex(0, 3),
      Simplex(0, 1, 3)
    ),
    hc3s
  )

  val it: Iterator[Simplex[Int]] = el.iterator

  val allsimplices: List[Simplex[Int]] = el.indices.map(el(_)).toList
  val itallsimplices: ListBuffer[Simplex[Int]] =
    collection.mutable.ListBuffer[Simplex[Int]]()

  "ExpandList Iterator does not throw exception while it has content" >> {
    el.indices.foreach(_ => itallsimplices.append(it.next()))
  }

  "ExpandList Iterator reflects content" >> {
    allsimplices === itallsimplices.toList
  }

  "Iterator has no next" >> {
    it.hasNext must beFalse
  }

  "Another step with iterator throws exception" >> {
    it.next() must throwA[NoSuchElementException]
  }
}
