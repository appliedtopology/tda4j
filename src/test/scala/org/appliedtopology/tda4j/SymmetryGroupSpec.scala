package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import scala.collection.SortedSet
import scala.collection.immutable.Range
import scala.collection.mutable.{BitSet, ListBuffer}

import math.Ordering.Implicits.sortedSetOrdering

class SymmetryGroupSpec extends mutable.Specification {
  """This is the specification for our expectations on the symmetry group
    |action interfaces and their usages.
    |""".stripMargin

  var hc3s = HyperCubeSymmetry(3)

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
}

// Canonical example: hypercube with symmetries from permuting bit positions

class HyperCube(bitlength: Int) extends FiniteMetricSpace[Int] {
  override def distance(x: Int, y: Int): Double =
    (x ^ y).toBinaryString.filter(_ == '1').size

  override def contains(x: Int): Boolean = false

  override def size: Int = 1 << bitlength

  override def elements: Iterable[Int] = Range.inclusive(0, size - 1)
}

class Permutations(elementCount: Int) {
  def factorial(m: Int): Int = m match {
    case 0 => 1
    case _ => m * factorial(m - 1)
  }

  val size: Int = factorial(elementCount)

  def apply(n: Int): List[Int] = {
    var source = ListBuffer(Range(0, elementCount).toList: _*)
    var retval = ListBuffer[Int]()

    var pos = n
    var div = 0
    var point = 0

    while (source.nonEmpty) {
      div = math.floorDiv(pos, factorial(source.size - 1))
      pos = math.floorMod(pos, factorial(source.size - 1))
      point = source.remove(div)
      retval.append(point)
    }
    return retval.toList
  }
}

//123
//132
//213
//231
//312
//321

class HyperCubeSymmetry(bitlength: Int) extends SymmetryGroup[Int, Int] {
  val permutations = Permutations(bitlength)
  val allbits: BitSet = BitSet.fromBitMask(Array((1L << bitlength) - 1))

  override def keys: Iterable[Int] = Range(0, permutations.size)

  def applyPermutation(permutationIndex: Int): (Int => Int) = k =>
    permutations(permutationIndex)(k)

  def apply(permutationIndex: Int): (Int => Int) = k => {
    var bs = BitSet.fromBitMask(Array(k.toLong))
    var pbs = allbits.toList.map(permutations(permutationIndex)).map(bs)
    BitSet(pbs.indices.filter(pbs(_)): _*).toBitMask(0).toInt
  }
}
