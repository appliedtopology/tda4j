package org.appliedtopology.tda4j

import collection.{immutable, mutable}
import collection.immutable.{BitSet, SortedSet}
import math.Ordering.Implicits.*
import java.util.NoSuchElementException
import scala.collection.mutable.ListBuffer

given orderingBitSet : Ordering[BitSet] = math.Ordering.by(bs => bs.toSeq)

trait SymmetryGroup[KeyT, VertexT: Ordering]() {
  self =>

  def keys: Iterable[KeyT]

  def apply(groupElementKey: KeyT): (VertexT => VertexT)

  def orbit(
    simplex: AbstractSimplex[VertexT]
  ): Set[AbstractSimplex[VertexT]] =
    keys.map(k => simplex.map(apply(k))).toSet

  def representative(
    simplex: AbstractSimplex[VertexT]
  ): AbstractSimplex[VertexT] =
    keys.map(k => simplex.map(apply(k))).min

  def isRepresentative(simplex: AbstractSimplex[VertexT]): Boolean =
    simplex == representative(simplex)

}

class ExpandList[VertexT : Ordering, KeyT](
  val representatives : Seq[AbstractSimplex[VertexT]],
  val symmetry : SymmetryGroup[KeyT,VertexT]
) extends IndexedSeq[AbstractSimplex[VertexT]] {
  self =>
  val orbitSizes : Map[AbstractSimplex[VertexT], Int] =
    Map.from(representatives.map(r => r -> symmetry.orbit(r).size))
  val orbitRanges : Seq[Int] = representatives.map(orbitSizes(_)).scanLeft(0)(_+_)
  val repOrbitRanges : Seq[(AbstractSimplex[VertexT], Int)] = representatives.zip(orbitRanges)
  var currentRepresentative : Option[AbstractSimplex[VertexT]] = None
  var currentOrbit : Option[Seq[AbstractSimplex[VertexT]]] = None

  override def apply(i: Int): AbstractSimplex[VertexT] = {
    val targetRepresentativeRange = repOrbitRanges.filter(_._2 <= i).last
    if (!currentRepresentative.contains(targetRepresentativeRange._1)) {
      currentRepresentative = Some(targetRepresentativeRange._1)
      currentOrbit = Some(symmetry.orbit(currentRepresentative.get).toSeq)
    }

    val offset = i - targetRepresentativeRange._2
    currentOrbit.get(offset)
  }

  override def length: Int = orbitSizes.values.sum

  override def iterator: Iterator[AbstractSimplex[VertexT]] = new Iterator[AbstractSimplex[VertexT]] {
    var currentOrbitIx = 0
    var currentElement = 0
    var currentOrbit = symmetry.orbit(representatives(currentOrbitIx))

    var _hasNext : Boolean = true

    override def hasNext() : Boolean = return _hasNext

    override def next() : AbstractSimplex[VertexT] = {
      if(!hasNext()) {
        throw new NoSuchElementException("Iterator exhausted")
      }
      var retval = currentOrbit.toList(currentElement)
      if(currentElement < currentOrbit.size-1) {
        // We can keep feeding elements from the current orbit
        currentElement += 1
      } else if (currentOrbitIx < representatives.length-1) {
        // We need to move to the next orbit
        currentOrbitIx += 1
        currentOrbit = symmetry.orbit(representatives(currentOrbitIx))
        currentElement = 0
      } else {
        // Hit the end of the line
        _hasNext = false
      }
      return retval
    }
  }

  override def toString(): String = s"ExpandList(${orbitSizes.toString()}"
}

class SymmetricZomorodianIncremental[VertexT: Ordering, KeyT](val symmetry: SymmetryGroup[KeyT, VertexT])
    extends CliqueFinder[VertexT] {
  self =>
  val className = "SymmetricZomorodianIncremental"

  override def apply(
    metricSpace: FiniteMetricSpace[VertexT],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[AbstractSimplex[VertexT]] = {
    val edges = CliqueFinder.weightedEdges(metricSpace, maxFiltrationValue)

    def lowerNeighbors(v: VertexT): SortedSet[VertexT] =
      edges.get(v).neighbors.map(_.toOuter).filter(_ < v).to(SortedSet)

    given Ordering[AbstractSimplex[VertexT]] =
      CliqueFinder.simplexOrdering(metricSpace)

    // val V = mutable.SortedSet[AbstractSimplex[VertexT]]()
    val tasks = mutable.Stack[(SortedSet[VertexT], SortedSet[VertexT])]()
    val representatives = mutable.SortedSet[AbstractSimplex[VertexT]]()

    edges.nodes
      .map(_.toOuter)
      .foreach(u => tasks.push((SortedSet[VertexT](u), lowerNeighbors(u))))

    while (tasks.nonEmpty) {
      val task = tasks.pop()
      val tau = task._1
      val N = task._2
      val simplex = AbstractSimplex.from(tau)
      if (symmetry.isRepresentative(simplex))
        representatives += AbstractSimplex.from(tau)
      if (tau.size < maxDimension) {
        N.foreach { v =>
          val sigma = tau + v
          val M = N & lowerNeighbors(v)
          tasks.push((sigma, M))
        }
      }
    }

    // LazyList.concat(representatives.map(r => symmetry.orbit(r).unsorted.to(LazyList)).to(Seq) : _*)
    ExpandList[VertexT, KeyT](representatives.toSeq, symmetry)
  }
}



// Canonical example: hypercube with symmetries from permuting bit positions

class HyperCube(bitlength: Int) extends FiniteMetricSpace[immutable.BitSet] {
  val top : immutable.BitSet = immutable.BitSet.fromBitMask(Array((1L << bitlength)-1))

  override def distance(x: immutable.BitSet, y: immutable.BitSet): Double = {
    val xy = x xor y
    xy.count(xy(_))
  }

  override def contains(x: immutable.BitSet): Boolean = {
    val xexcess = x -- top
    !xexcess.exists(xexcess(_))
  }

  override def size: Int = 1 << bitlength

  override def elements: Iterable[immutable.BitSet] =
    Range.inclusive(0, size - 1).map(k => immutable.BitSet.fromBitMask(Array(k.toLong)))
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

class HyperCubeSymmetry(bitlength: Int) extends SymmetryGroup[Int, immutable.BitSet] {
  val permutations = Permutations(bitlength)
  val hypercube = HyperCube(bitlength)

  override def keys: Iterable[Int] = Range(0, permutations.size)

  def applyPermutation(permutationIndex: Int): (Int => Int) = k =>
    permutations(permutationIndex)(k)

  def apply(permutationIndex: Int): (immutable.BitSet => immutable.BitSet) = bs => {
    var pbs = hypercube.top.toList.map(permutations(permutationIndex)).map(bs)
    pbs.indices.filter(pbs(_)).to(BitSet)
  }
}
