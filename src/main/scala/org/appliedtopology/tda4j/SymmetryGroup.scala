package org.appliedtopology.tda4j

import collection.{immutable, mutable}
import collection.immutable.{BitSet, SortedSet}
import math.Ordering.Implicits.*
import java.util.NoSuchElementException
import scala.collection.mutable.ListBuffer

/** A `given` instance that allows us to automatically sort bitsets lexicographically. */
given orderingBitSet : Ordering[BitSet] = math.Ordering.by(bs => bs.toSeq)

/** A trait encoding the interface for a group of symmetries acting on the vertex set
 * of a simplicial complex. The group and its action needs to be fully known and
 * implemented.
 *
 * This structure assumes that you can provide a group action on vertices, and will
 * from that deduce a group action on simplices by acting pointwise: `g.[a,b,c] = [ga,gb,gc]`,
 * collapsing any degeneracies.
 *
 * A fundamentally important part of the symmetry group action for our applications
 * is to be able to pick out **canonical** representatives for each orbit, and to
 * recognize when a simplex is such a canonical representative.
 *
 * @tparam KeyT The type used to enumerate the group elements.
 * @tparam VertexT The type of the vertices.
 */
trait SymmetryGroup[KeyT, VertexT: Ordering]() {
  self =>

  /** List of all keys that can be used to address individual group elements. */
  def keys: Iterable[KeyT]

  /** Pulling out an element from the symmetry group produces a function that takes
   * vertices to vertices.
   *
   * @param groupElementKey The key addressing the specific group element intended.
   * @return A self-map on the vertex set.
   */
  def apply(groupElementKey: KeyT): (VertexT => VertexT)

  /** Applies all group elements to a single starting simplex, thus tracing out the
   * full orbit of that starting simplex.
   *
   * @param simplex Starting simplex.
   * @return A set of simplices in the orbit of `simplex`.
   */
  def orbit(
    simplex: AbstractSimplex[VertexT]
  ): Set[AbstractSimplex[VertexT]] =
    keys.map(k => simplex.map(apply(k))).toSet

  /** Find the canonical representative for the orbit that contains `simplex`.
   *
   * @param simplex Query simplex.
   * @return The canonical representative simplex of the orbit of `simplex`.
   */
  def representative(
    simplex: AbstractSimplex[VertexT]
  ): AbstractSimplex[VertexT] =
    keys.map(k => simplex.map(apply(k))).min

  /** Check if `simplex` is the canonical representative of its own orbit.
   *
   * @param simplex Query simplex.
   * @return
   */
  def isRepresentative(simplex: AbstractSimplex[VertexT]): Boolean =
    simplex == representative(simplex)

}

/** A symmetry-aware compressed indexed sequence container that keeps track of
 * representatives of each orbit, and generates the rest of the orbit as and when
 * needed, including an iterator structure that also only generates the rest of
 * the orbit when needed, and a method for checking whether a particular simplex
 * **is** an orbit representatives in a way that does not hold on to orbit elements
 * (thus allowing them to get instantly garbage collected).
 *
 * @param representatives A sequence of simplices, each representative for its own orbit
 * @param symmetry A `SymmetryGroup` object carrying information about symmetries
 * @param ordering$VertexT$0 We depend on a total order of simplices, which we generate
 *                           from a total order of vertices. This carries that ordering.
 * @tparam VertexT The type of the vertices.
 * @tparam KeyT The type of the group element indices.
 */
class ExpandList[VertexT : Ordering, KeyT](
  val representatives : Seq[AbstractSimplex[VertexT]],
  val symmetry : SymmetryGroup[KeyT,VertexT]
) extends IndexedSeq[AbstractSimplex[VertexT]] {
  self =>

  /** `orbitSizes` contains each representative paired with the size of its orbit, for fast lookup. */
  val orbitSizes : Map[AbstractSimplex[VertexT], Int] =
    Map.from(representatives.map(r => r -> symmetry.orbit(r).size))
  /** `orbitRanges` contains accumulated indices of the starts of each new orbit. */
  val orbitRanges : Seq[Int] = representatives.map(orbitSizes(_)).scanLeft(0)(_+_)
  /** `repOrbitRanges` pairs the orbit start indices with their corresponding orbit representative. */
  val repOrbitRanges : Seq[(AbstractSimplex[VertexT], Int)] = representatives.zip(orbitRanges)
  /** `currentRepresentative` and `currentOrbit` carry the currently cached representative and orbit pair. */
  var currentRepresentative : Option[AbstractSimplex[VertexT]] = None
  var currentOrbit : Option[Seq[AbstractSimplex[VertexT]]] = None

  /** Apply for an `IndexedSeq` will pick out the `i`th element in the sequence.
   * We do this by counting through the orbit sizes until we find the right orbit,
   * then generating that orbit and returning the element.
   * We do a kind of one-shot memoization - the most recently generated orbit stays in memory
   * until a different orbit is needed.
   *
   * @param i Index of the requested element
   * @return The element at position `i` among all the simplices represented.
   */
  override def apply(i: Int): AbstractSimplex[VertexT] = {
    val targetRepresentativeRange = repOrbitRanges.filter(_._2 <= i).last
    if (!currentRepresentative.contains(targetRepresentativeRange._1)) {
      currentRepresentative = Some(targetRepresentativeRange._1)
      currentOrbit = Some(symmetry.orbit(currentRepresentative.get).toSeq)
    }

    val offset = i - targetRepresentativeRange._2
    currentOrbit.get(offset)
  }

  /** The number of elements in the compressed list is the sum of orbit sizes.
   *
   * @return Number of elements represented.
   */
  override def length: Int = orbitSizes.values.sum

  /** Returns an iterator that traverses the collection. Each call builds a new generator.
   * The iterator maintains an internal state that tracks the current orbit, and traverses
   * each orbit in the order that `SymmetryGroup.orbit` returns them in.
   *
   * @return Iterator that traverses the entire represented set.
   */
  override def iterator: Iterator[AbstractSimplex[VertexT]] = new Iterator[AbstractSimplex[VertexT]] {
    var currentOrbitIx = 0
    var currentElement = 0
    var currentOrbit: Set[AbstractSimplex[VertexT]] = symmetry.orbit(representatives(currentOrbitIx))

    /** Internal state tracking whether we have reason to believe we have exhausted the list. */
    var _hasNext : Boolean = true

    /** Uses an internal Boolean state to track whether another element can be returned.
     *
     * @return
     */
    override def hasNext() : Boolean = return _hasNext

    /** This method does all the work of the iterator. This code assumes that the `ExpandList` is not empty.
     * Any time an element is requested, the internal state `_hasNext` is checked.
     * If `false`, throw `NoSuchElementException`.
     * If `true`, the current element is returned, the pointer advanced.
     * If the pointer (composite with `currentOrbitIx` and `currentElement`) hits the end of an orbit,
     * it generates the next orbit.
     * If it hits the end of the last orbit, it sets the `_hasNext` bit.
     *
     * @return The next element in the `ExpandList`.
     */
    override def next() : AbstractSimplex[VertexT] = {
      if(!hasNext()) {
        throw new NoSuchElementException("Iterator exhausted")
      }
      val retval = currentOrbit.toList(currentElement)
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

  /** Printing that does not itself trigger a full traversal of the entire `ExpandList` just to print things out.
   * @return
   */
  override def toString(): String = s"ExpandList(${orbitSizes.toString()}"
}

/** Symmetry-aware version of Zomorodian's incremental algorithm for generating Vietoris-Rips complexes.
 * The algorithm object needs access to a `SymmetryGroup` instance that encodes all we know about the
 * symmetries.
 *
 * @param symmetry Symmetry group details.
 * @param ordering$VertexT$0 We need to sort simplices, so we need to sort vertices.
 * @tparam VertexT Type of the vertices of the complex.
 * @tparam KeyT Type of the indices for the group elements in `symmetry`.
 */
class SymmetricZomorodianIncremental[VertexT: Ordering, KeyT](val symmetry: SymmetryGroup[KeyT, VertexT])
    extends CliqueFinder[VertexT] {
  self =>
  val className = "SymmetricZomorodianIncremental"

  /** Executing the algorithm on a metric space on the vertices on which the symmetry group acts, with a chosen
   * maximum filtration value and a chosen maximum homological dimension. Will go to some length (using `ExpandList`
   * above) to make the sequence of simplices returned relatively lazy so that simplices can be seen and released
   * and the garbage collector can keep overall memory pressure low throughout.
   *
   * Key difference from [[ZomorodianIncremental]] is the following lines:
   * {{{
   * val simplex = AbstractSimplex.from(tau)
   *  if (symmetry.isRepresentative(simplex))
   *  representatives += AbstractSimplex.from(tau)
   * }}}
   * Here, each simplex that the algorithm generates is checked for whether it is representative for its own orbit,
   * and only the simplices that are are retained (in the mutable list `representatives` for later access).
   *
   * @param metricSpace Metric space object on the vertices compatible with the symmetries on the vertices.
   * @param maxFiltrationValue Maximum filtration value to consider.
   * @param maxDimension Maximum homological dimension to consider.
   * @return
   */
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

    ExpandList[VertexT, KeyT](representatives.toSeq, symmetry)
  }
}



// Canonical example: hypercube with symmetries from permuting bit positions

/** The `HyperCube` example of a symmetric point set. Vertices are all binary strings of length `bitlength`.
 * Distances on the hypercube are Hamming distances between binary strings, ie how many bits differ between
 * the two strings.
 *
 * Binary strings are throughout represented as [[immutable.BitSet]].
 *
 * @param bitlength The dimension of the hypercube.
 */
class HyperCube(bitlength: Int) extends FiniteMetricSpace[immutable.BitSet] {
  val top : immutable.BitSet = immutable.BitSet.fromBitMask(Array((1L << bitlength)-1))

  /** Distance between two binary strings.
   *
   * @param x
   * Index of first point
   * @param y
   * Index of second point
   * @return
   * Distance between x and y
   */
  override def distance(x: immutable.BitSet, y: immutable.BitSet): Double = {
    val xy = x xor y
    xy.count(xy(_))
  }

  /** Check whether an [[immutable.BitSet]] represents a point in the hypercube. In practice,
   * checks that the bitset contains no entries above the bitlength.
   *
   * @param x
   * @return
   */
  override def contains(x: immutable.BitSet): Boolean = {
    val xexcess = x -- top
    !xexcess.exists(xexcess(_))
  }

  /** Size of the hypercube: 2^bitlength^. Computed by left-shifting.
   *
   * @return Number of vertices in the metric space.
   */
  override def size: Int = 1 << bitlength

  /** Returns all the elements of the metric space.
   *
   * @return
   * Iterable that returns all points in the metric space
   */
  override def elements: Iterable[immutable.BitSet] =
    Range.inclusive(0, size - 1).map(k => immutable.BitSet.fromBitMask(Array(k.toLong)))
}

/** This class enumerates permutations in order to allow permutations of bit-positions to fill out the
 * symmetry group of the hypercube.
 *
 * @param elementCount How many objects are permuted?
 */
class Permutations(elementCount: Int) {
  /** Naive factorial implementation, to measure sizes of blocks when generating permutations.
   *
   * @param m
   * @return
   */
  def factorial(m: Int): Int = m match {
    case 0 => 1
    case _ => m * factorial(m - 1)
  }

  val size: Int = factorial(elementCount)

  /** Find the `n`th permutation in an enumeration of all permutations.
   *
   * @param n
   * @return Image of the permutation as a [[List]].
   */
  def apply(n: Int): List[Int] = {
    val source = ListBuffer(Range(0, elementCount).toList: _*)
    val retval = ListBuffer[Int]()

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

/** Symmetry group of the hypercube under permutations of bit positions.
 * No rotations of the hypercube included here.
 *
 * @param bitlength Dimension of the hypercube.
 */
class HyperCubeSymmetry(bitlength: Int) extends SymmetryGroup[Int, immutable.BitSet] {
  val permutations: Permutations = Permutations(bitlength)
  val hypercube: HyperCube = HyperCube(bitlength)

  override def keys: Iterable[Int] = Range(0, permutations.size)

  /** Picks out the `permutationIndex`th permutation from Sn and builds a function that transforms integers
   * out of the permutation.
   *
   * @param permutationIndex
   * @return
   */
  def applyPermutation(permutationIndex: Int): (Int => Int) = k =>
    permutations(permutationIndex)(k)

  /** Picks out the `permutationIndex`th permutation from Sn and builds a function that permutes bits of
   * a [[immutable.BitSet]] out of the permutation.
   *
   * @param permutationIndex
   * @return
   */
  def apply(permutationIndex: Int): (immutable.BitSet => immutable.BitSet) = bs => {
    val pbs = hypercube.top.toList.map(permutations(permutationIndex)).map(bs)
    pbs.indices.filter(pbs(_)).to(BitSet)
  }
}
