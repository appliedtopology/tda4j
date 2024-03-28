package org.appliedtopology.tda4j

import org.apache.commons.numbers.fraction.GeneralizedContinuedFraction.Coefficient

import collection.immutable.SortedMap
import math.Ordering.Implicits.sortedSetOrdering
import scala.annotation.targetName
import scala.collection.mutable

import math.Fractional.Implicits.given

object MatrixAlgebra{
  /** These givens and imports are good for when you write for a specific
   * use case - however, this is in the middle of the library code and
   * we need to parametrize by cell- and coefficient types.
   * 
   * You could do one of the following solution approaches:
   * 1. Change the definition of `reduceBasis` to only work for
   * `Chain[Simplex,Double]`, and we can generalize it later
   * when the code is more mature.
   * 2. Remove all these given instances. You could then write them
   * in again inside the method - once you know what CellT and
   * CoefficientT need to be. In order to do things like your
   * `given Fractional[CoefficientT]` line here, you would want
   * to use something like
   * `given Fractional[Double] = summon[Fractional[Double]]`
   * where `summon` is the keyword for "I know there is supposed to
   * be a thing with this type out there. Give it to me now."
   * 3. Import library implicits that fill in the blanks. This is
   * what your compiler error is telling you when it is suggesting
   * that you import things from `math.Fractional.Implicits`. All
   * the `given` instances for Fractional to create `+`,`-`, etc all
   * exist in the library, you just have to actually import those 
   * definitions. I added an import at the top that does the job.
   * 
   * To figure out what exactly is going wrong with the compilations,
   * it can be very helpful to add a bunch of type annotation to the code.
   * Look at how I changed the lines inside the loop in `reduceBasis` to 
   * see how that works; you can annotate any variable with what type you
   * expect it to have - and the compiler will tell you when your understanding
   * is different from the type inference.
   * 
   * Finally, when you call to `basis.updated`, this is a method that very
   * much embraces the functional programming paradigm (Okasaki's book that
   * Daniel borrowed is a good source for these ideas), where you consider
   * creating new objects to be a cheap operation, often doable without actually
   * moving memory around much at all, but you have strong preferences for
   * things that don't change existing objects. You'd have immutable types,
   * and instead of e.g. changing the thing in place you would just run a 
   * function that builds the new thing and put that in its place.
   * So `basis.updated` will leave basis unchanged, but its return value is
   * what would have happened if you were to update `basis`.
   * 
   * For the kind of "I have this collection and I want to keep modifying it"
   * programming that is more relevant here, you will want collection types
   * from the `scala.collection.mutable` library and not the 
   * `scala.collection.immutable` library. And if you have a mutable list,
   * (really a `Buffer`) there is a function `.update(n: Int, new:T)` that
   * will change a specific entry.
   * Or you can just write `buffer(i) = newThing` and it will do the right things.
    */ 
  given sc: SimplexContext[Int]()
  import sc.*
  given Conversion[Simplex, MapChain[Simplex, Double]] =
    MapChain.apply
  given Fractional[Double] = math.Numeric.DoubleIsFractional
  given Ordering[Int] = math.Ordering.Int
  given rm: RingModule[MapChain[Simplex, Double], Double] =
    MapChainOps[Simplex, Double]()
  import rm.*

  //parameterized by cell and coefficient, returns list of chains....
  def reduceBasis[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
  (basis: List[Chain[CellT,CoefficientT]]): List[Chain[CellT,CoefficientT]] = {
    
    //How many cols we workin with??
    val nCols = basis.length

    //For each column.....
    for (col <- 0 until nCols) {

      //Get current col/chain....
      val current: Chain[CellT,CoefficientT] = basis(col)
      //Get leading term & coeff (index & entry) of this col...
      //make sure leading term doesn't return things w coeff 0
      var (term, coeff) : (CellT,CoefficientT) = current.leadingTerm

      //Use while loop to look ahead in "row" (look at coeffs in other chains for 'term')
      var lookAheadBy = 1
      while ((col + lookAheadBy) < nCols) {

        //Select (col + lookAheadBy)th column
        var next = basis.apply(col + lookAheadBy)
        //Get coeff /matrix-entry at index of interest...
        //make sure that getCoeff returns 0 when term not present
        var pivotSimpCoeff: CoefficientT = next.getCoefficient(term)

        //If there's a nonzero entry/collision
        if (pivotSimpCoeff != 0) { // you want to use the Fractional[CoefficientT].zero here

          //Calculate scalar you'll need to "zero out" the problem column at the 'term' column index
          var multBy: CoefficientT = pivotSimpCoeff / coeff
          //Preform elimination (forming a new chain)
          var newChain: CoefficientT = next - scale(multBy,current)
          //Replace problem column with new chain...
          basis.updated(col + lookAheadBy, newChain)

        }
        //If not collision, look ahead....
        else {
          lookAheadBy += 1
        }
      }

    }
    basis
  }

  //returns map(?)
  def invertMatrix[CellT <: Cell[CellT]:Ordering,CellS <: Cell[CellS]:Ordering,CoefficientT:Fractional]
  (basis: Map[CellS, Chain[CellT,CoefficientT]]): Map[CellT, Chain[CellS, CoefficientT]] = {
    ??? // this is a placeholder to avoid compilation errors without having to actually figure out what you need to write.
  }



}

trait Chain[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional] {
  def leadingCell: CellT = leadingTerm._1
  def leadingCoefficient: CoefficientT = leadingTerm._2
  def leadingTerm: (CellT, CoefficientT)
  def getCoefficient(cell: CellT): CoefficientT
}
object Chain {
  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    cc: (CellT, CoefficientT)*
  ): Chain[CellT, CoefficientT] = MapChain(cc: _*)

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    c: CellT
  ): Chain[CellT, CoefficientT] = MapChain(c)
}

/** The Chain class is a representation of a formal linear combination of the n
  * cells in a cell complex. Note that CellT is a type parameter subtype of
  * Cell, a trait in this library. We have Scala look for a type parameter of
  * cellOrdering that matches as best as possible an Ordering on CellT.
  *
  * (Note: write a fully fleshed out explanation in comments after code def.
  * write up)
  *
  * @tparam CellT
  *   Type of the cells in the chain complex. For example `AbstractSimplex[Int]`
  *   etc.
  * @tparam CoefficientT
  *   Type of the coefficients of the chain complex. For example `Double` or an
  *   implicit 'FiniteField : Fractional[Int]'
  * @param chainMap
  *   Internal storage of the sorted map of the elements
  */
class MapChain[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
/** chainMap is an immutable variable and constructor that uses Scala's SortedMap to make a key-value pairing of an CellT as the key and a
  * CoefficientT type as the value. Here, we'll use the Using keyword to check for any relevant types for CoefficientT.
  */ (val chainMap: SortedMap[CellT, CoefficientT])
    extends Chain[CellT, CoefficientT] {

  // Currently will throw errors around if `chainMap` is empty
  // TODO: should probably fail more gracefully
  override def leadingTerm: (CellT, CoefficientT) =
    chainMap.head

  override def getCoefficient(cell: CellT): CoefficientT = chainMap(cell)
  
  override def toString: String =
    chainMap.map((k, v) => s"${v.toString} *: ${k.toString}").mkString(" + ")

  // Define equality between chains.
  // an override of the equals method from the Any class.
  override def equals(other: Any): Boolean =
    // pattern matching block that checks the type of the "other" object.
    other match {
      // if it's a chain, check the chainMap is the same
      case that: MapChain[CellT, CoefficientT] =>
        this.chainMap == that.chainMap // Compare chainMap contents.
      // else they can't be equal!
      case _ => false
    }
  // Overriding the hashcode so both of our objects have the same one, buncha good reasons for this.
  override def hashCode(): Int = chainMap.hashCode()
}
object MapChain {

  // for first apply: he first `apply` takes a sequence of coefficient/cell pairs and creates a sorted map that fits the constructor to let us create chains that way.
  /** Both apply() functions assist in constructor execution.
    *
    * The 1st apply() is used to take Cell/Coefficient pairs, and converts them
    * to a sorted map using the Chain chainMap constructor. Why? So it works
    * with the constructor, so we can create chains.
    *
    * Here,the 2nd apply() works as a implicit cast function. Simply, it is used
    * to take a cell and transform it to a chain. In depth, the Chain chainMap
    * constructor is called to create a new Chain object, as indicated by 'new'
    * followed by Chain. The constructor then requires a Cell/Coefficient pair.
    * The "List(cell -> fr.one)" creates a list with at least a single key-value
    * of a Cell/Coefficient pair ready to be inserted into the chainMap. The key
    * will then product the value 1. Why 1? The Chain has to produce at least a
    * value of 1. Think of this as the 'base case' for Chain values.
    */

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    items: (CellT, CoefficientT)*
  ): MapChain[CellT, CoefficientT] =

    // Filter out terms with zero coefficients during construction
    val filteredItems = items.filter { case (_, coefficient) =>
      coefficient != 0
    }
    new MapChain[CellT, CoefficientT](SortedMap.from(filteredItems))

  // Original apply innards
  // new Chain[CellT, CoefficientT](SortedMap.from(items))

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT](cell: CellT)(using
    fr: Fractional[CoefficientT]
  ): MapChain[CellT, CoefficientT] =
    new MapChain[CellT, CoefficientT](SortedMap.from(List(cell -> fr.one)))
}

class MapChainOps[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
    extends RingModule[MapChain[CellT, CoefficientT], CoefficientT] {
  import Numeric.Implicits._

  val zero: org.appliedtopology.tda4j.MapChain[CellT, CoefficientT] = MapChain()

  def plus(
    x: MapChain[CellT, CoefficientT],
    y: MapChain[CellT, CoefficientT]
  ): MapChain[CellT, CoefficientT] =
    MapChain(
      (for k <- (x.chainMap.keySet | y.chainMap.keySet)
      yield {
        val fr = summon[Fractional[CoefficientT]]
        val xv: CoefficientT = x.chainMap.getOrElse(k, fr.zero)
        val yv: CoefficientT = y.chainMap.getOrElse(k, fr.zero)
        k -> fr.plus(xv, yv)
      }).toSeq: _*
    )

  def scale(
    x: CoefficientT,
    y: MapChain[CellT, CoefficientT]
  ): MapChain[CellT, CoefficientT] =
    new MapChain(y.chainMap.transform((k, v) => x * v))

  override def negate(
    x: MapChain[CellT, CoefficientT]
  ): MapChain[CellT, CoefficientT] =
    new MapChain(x.chainMap.transform((k, v) => -v))
}

/** Lightweight trait to define what it means to be a topological "Cell".
  *
  * Using F-bounded types to ensure reflective typing. See
  * https://tpolecat.github.io/2015/04/29/f-bounds.html See
  * https://dotty.epfl.ch/3.0.0/docs/reference/contextual/type-classes.html
  */
trait Cell[CellT <: Cell[CellT]] {
  this: CellT =>

  /** The boundary map of the cell.
    *
    * @return
    *   A sequence of boundary cells.
    * @todo
    *   When a `Chain` trait or class has been created, this should change to
    *   return an appropriate `Chain` instead of the current `List`.
    */

  def boundary[CoefficientT: Fractional]: Chain[CellT, CoefficientT]

}

/** A heap-based chain class. Will delay accumulating and evaluating any actual
  * arithmetic for as long as possible.
  *
  * NOTA BENE: both checking for equality and converting to string will force
  * the entire heap while extracting leading terms and cells will only force the
  * leading terms and cells. Algebraic operations force nothing: entries are
  * just added to the heap without checking for any computations that they
  * imply.
  */
class HeapChain[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
  cc: IterableOnce[(CellT, CoefficientT)]
) extends Chain[CellT, CoefficientT] {

  import Numeric.Implicits._

  /* The core storage of the `HeapChain` is a heap of pairs (cell, coefficient)
   * that are only ordered by their cell entry.
   */
  val chainHeap: mutable.PriorityQueue[(CellT, CoefficientT)] = {
    given Ordering[(CellT, CoefficientT)] = Ordering.by(_._1)

    mutable.PriorityQueue.from(cc)
  }

  // Will collapse the actual head
  override def leadingTerm: (CellT, CoefficientT) = {
    def assembleHead() = {
      var (headCell, headCoeff) = chainHeap.dequeue()
      while (chainHeap.head._1 == headCell) {
        val nextHead = chainHeap.dequeue()
        headCoeff += nextHead._2
      }
      (headCell, headCoeff)
    }
    var head: (CellT, CoefficientT) = assembleHead()
    val fr = summon[Fractional[CoefficientT]]
    while (head._2 == fr.zero)
      head = assembleHead()
    chainHeap.addOne(head)
    chainHeap.head
  }

  // this may be an expensive way to interact with a HeapChain.
  override def getCoefficient(cell: CellT)(using fractional: Fractional[CoefficientT]): 
    CoefficientT = 
    chainHeap
      .view
      .filter(_._1 == cell)
      .map(_._2)
      .sum
    
  def toMap: Map[CellT, CoefficientT] =
    this.chainHeap.groupMapReduce[CellT, CoefficientT](_._1)(_._2)(
      _ + _
    ) // sum coefficients

  def mapInPlace(
    f: ((CellT, CoefficientT)) => (CellT, CoefficientT)
  ): this.type = {
    chainHeap.mapInPlace(f)
    this
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: HeapChain[CellT, CoefficientT] =>
      this.toMap == that.toMap
  }

  override def hashCode(): Int = chainHeap.hashCode()

  override def clone(): HeapChain[CellT, CoefficientT] =
    new HeapChain(chainHeap.iterator)

  override def toString: String =
    chainHeap.iterator
      .map((cell, coeff) => s"""${coeff} *> ${cell}""")
      .mkString(" + ")
}

object HeapChain {
  def from[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    cc: IterableOnce[(CellT, CoefficientT)]
  ) =
    new HeapChain(cc)

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    cc: IterableOnce[(CellT, CoefficientT)]
  ): HeapChain[CellT, CoefficientT] = from(cc)

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
    cc: (CellT, CoefficientT)*
  ): HeapChain[CellT, CoefficientT] = from(cc)

  def apply[CellT <: Cell[CellT]: Ordering, CoefficientT](c: CellT)(using
    fr: Fractional[CoefficientT]
  ): HeapChain[CellT, CoefficientT] = from(Seq((c, fr.one)))
}

class HeapChainOps[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
    extends RingModule[HeapChain[CellT, CoefficientT], CoefficientT] {
  import Numeric.Implicits._

  override val zero: HeapChain[CellT, CoefficientT] = new HeapChain(Seq())

  override def plus(
    x: HeapChain[CellT, CoefficientT],
    y: HeapChain[CellT, CoefficientT]
  ): HeapChain[CellT, CoefficientT] =
    HeapChain.from(Iterator.concat(x.chainHeap, y.chainHeap))

  override def scale(
    x: CoefficientT,
    y: HeapChain[CellT, CoefficientT]
  ): HeapChain[CellT, CoefficientT] =
    y.clone().mapInPlace((cell, coeff) => (cell, coeff * x))

  override def negate(
    x: HeapChain[CellT, CoefficientT]
  ): HeapChain[CellT, CoefficientT] = {
    val fr = summon[Fractional[CoefficientT]]
    scale(fr.negate(fr.one), x)
  }
}
