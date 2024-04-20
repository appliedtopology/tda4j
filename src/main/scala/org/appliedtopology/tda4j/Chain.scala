package org.appliedtopology.tda4j {

  import collection.immutable.SortedMap
  import math.Ordering.Implicits.sortedSetOrdering
  import scala.annotation.targetName
  import scala.collection.mutable

  /** Trait that defines what it means to be a "Chain".
    */
  trait Chain[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional] {
    def leadingCell: Option[CellT] = leadingTerm._1

    def leadingCoefficient: CoefficientT = leadingTerm._2

    def leadingTerm: (Option[CellT], CoefficientT)
  }

  /** Lightweight trait to define what it means to be a topological "Cell".
    *
    * Using F-bounded types to ensure reflective typing. See https://tpolecat.github.io/2015/04/29/f-bounds.html See
    * https://dotty.epfl.ch/3.0.0/docs/reference/contextual/type-classes.html
    */
  trait Cell[CellT <: Cell[CellT]] {
    this: CellT =>

    /** The boundary map of the cell.
      *
      * @return
      *   A sequence of boundary cells.
      * @todo
      *   When a `Chain` trait or class has been created, this should change to return an appropriate `Chain` instead of
      *   the current `List`.
      */

    def boundary[CoefficientT: Fractional]: Chain[CellT, CoefficientT]
  }

  // Here we choose the underlying chain implementation to use

  export mapchain.{Chain, ChainElement, ChainOps}

  /*
  Implementation of the Chain trait using maps for internal storage.
   */
  package mapchain {

    object Chain {
      def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
        cc: (CellT, CoefficientT)*
      ): Chain[CellT, CoefficientT] = ChainElement(cc: _*)

      def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
        c: CellT
      ): Chain[CellT, CoefficientT] = ChainElement(c)
    }

    /** The Chain class is a representation of a formal linear combination of the n cells in a cell complex. Note that
      * CellT is a type parameter subtype of Cell, a trait in this library. We have Scala look for a type parameter of
      * cellOrdering that matches as best as possible an Ordering on CellT.
      *
      * (Note: write a fully fleshed out explanation in comments after code def. write up)
      *
      * @tparam CellT
      *   Type of the cells in the chain complex. For example `AbstractSimplex[Int]` etc.
      * @tparam CoefficientT
      *   Type of the coefficients of the chain complex. For example `Double` or an implicit 'FiniteField :
      *   Fractional[Int]'
      * @param chainMap
      *   Internal storage of the sorted map of the elements
      */
    class ChainElement[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
    /** chainMap is an immutable variable and constructor that uses Scala's SortedMap to make a key-value pairing of an CellT as the key and a
      * CoefficientT type as the value. Here, we'll use the Using keyword to check for any relevant types for CoefficientT.
      */ (val chainMap: SortedMap[CellT, CoefficientT])
        extends Chain[CellT, CoefficientT] {

      // Currently will throw errors around if `chainMap` is empty
      // TODO: should probably fail more gracefully
      override def leadingTerm: (Option[CellT], CoefficientT) =
        if (chainMap.isEmpty)
          Tuple2(None, summon[Fractional[CoefficientT]].zero)
        else
          chainMap.head.copy(_1 = Some(chainMap.head._1))

      override def toString: String =
        chainMap
          .map((k, v) => s"${v.toString} *: ${k.toString}")
          .mkString(" + ")

      // Define equality between chains.
      // an override of the equals method from the Any class.
      override def equals(other: Any): Boolean =
        // pattern matching block that checks the type of the "other" object.
        other match {
          // if it's a chain, check the chainMap is the same
          case that: ChainElement[CellT, CoefficientT] =>
            this.chainMap == that.chainMap // Compare chainMap contents.
          // else they can't be equal!
          case _ => false
        }

      // Overriding the hashcode so both of our objects have the same one, buncha good reasons for this.
      override def hashCode(): Int = chainMap.hashCode()
    }

    object ChainElement {

      // for first apply: he first `apply` takes a sequence of coefficient/cell pairs and creates a sorted map that fits the constructor to let us create chains that way.

      /** Both apply() functions assist in constructor execution.
        *
        * The 1st apply() is used to take Cell/Coefficient pairs, and converts them to a sorted map using the Chain
        * chainMap constructor. Why? So it works with the constructor, so we can create chains.
        *
        * Here,the 2nd apply() works as a implicit cast function. Simply, it is used to take a cell and transform it to
        * a chain. In depth, the Chain chainMap constructor is called to create a new Chain object, as indicated by
        * 'new' followed by Chain. The constructor then requires a Cell/Coefficient pair. The "List(cell -> fr.one)"
        * creates a list with at least a single key-value of a Cell/Coefficient pair ready to be inserted into the
        * chainMap. The key will then product the value 1. Why 1? The Chain has to produce at least a value of 1. Think
        * of this as the 'base case' for Chain values.
        */

      def apply[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional](
        items: (CellT, CoefficientT)*
      ): ChainElement[CellT, CoefficientT] =

        // Filter out terms with zero coefficients during construction
        val filteredItems = items.filter { case (_, coefficient) =>
          coefficient != 0
        }
        new ChainElement[CellT, CoefficientT](SortedMap.from(filteredItems))

      // Original apply innards
      // new Chain[CellT, CoefficientT](SortedMap.from(items))

      def apply[CellT <: Cell[CellT]: Ordering, CoefficientT](cell: CellT)(using
        fr: Fractional[CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        new ChainElement[CellT, CoefficientT](
          SortedMap.from(List(cell -> fr.one))
        )
    }

    class ChainOps[CellT <: Cell[CellT]: Ordering, CoefficientT: Fractional]
        extends RingModule[ChainElement[CellT, CoefficientT], CoefficientT] {

      import Numeric.Implicits._

      val zero: ChainElement[CellT, CoefficientT] = ChainElement()

      def plus(
        x: ChainElement[CellT, CoefficientT],
        y: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        ChainElement(
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
        y: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        new ChainElement(y.chainMap.transform((k, v) => x * v))

      override def negate(
        x: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        new ChainElement(x.chainMap.transform((k, v) => -v))
    }
  }

  /*
  Implementation of the Chain trait using heaps for internal storage and deferred arithmetic.
   */
  package heapchain {

    import scalaz._
    import Scalaz._
    import scalaz.std.given

    import collection.immutable

    def chainEntryOrder[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT](): Order[(CellT, CoefficientT)] =
      Order.orderBy[(CellT, CoefficientT), CellT](_._1)(Order.fromScalaOrdering[CellT])

    /** A heap-based chain class. Will delay accumulating and evaluating any actual arithmetic for as long as possible.
      *
      * NOTA BENE: both checking for equality and converting to string will force the entire heap while extracting
      * leading terms and cells will only force the leading terms and cells. Algebraic operations force nothing: entries
      * are just added to the heap without checking for any computations that they imply.
      */
    class ChainElement[
      CellT <: Cell[CellT]: scala.math.Ordering,
      CoefficientT
    ](var chainHeap: scalaz.Heap[(CellT, CoefficientT)])(using fr: Fractional[CoefficientT])
        extends Chain[CellT, CoefficientT] {

      import Numeric.Implicits._

      given Order[CellT] = Order.fromScalaOrdering[CellT]
      given Order[(CellT, CoefficientT)] = chainEntryOrder()

      val addCoefficientsSemigroup: Semigroup[(CellT, CoefficientT)] =
        Semigroup.instance((x, get) => (x._1, x._2 + get._2))

      def addCoefficientsMonoid(default: CellT): Monoid[(CellT, CoefficientT)] =
        Monoid.instance(addCoefficientsSemigroup.append, (default, fr.zero))

      def collapseHead(): ChainElement[CellT, CoefficientT] =
        if (chainHeap.isEmpty) return ChainElement()
        else {
          def collapseHeap(heap: Heap[(CellT, CoefficientT)]): Heap[(CellT, CoefficientT)] = {
            val (heads, tail) = heap.span { head =>
              chainEntryOrder().order(head, heap.minimum) == Ordering.EQ
            }

            if (heads.size > 0) {
              val newHead = heads.suml(addCoefficientsMonoid(heads.minimum._1))
              if (newHead._2 != fr.zero)
                tail.insert(newHead)
              else
                collapseHeap(tail)
            } else {
              collapseHeap(tail)
            }
          }
          new ChainElement(collapseHeap(chainHeap))
        }

      // Will collapse the actual head
      override def leadingTerm: (Option[CellT], CoefficientT) = {
        collapseHead()
        if (chainHeap.isEmpty)
          Tuple2(None, fr.zero)
        else
          chainHeap.minimum.copy(_1 = Some(chainHeap.minimum._1))
      }

      def toMap: immutable.Map[CellT, CoefficientT] =
        this.chainHeap.foldRight(immutable.Map[CellT, CoefficientT]()) { (pair, map) =>
          val (cell, x) = pair
          map.updatedWith(cell)(coeff => Some(x + coeff.getOrElse(fr.zero)))
        }

      def isZero: Boolean = {
        collapseHead()
        chainHeap.isEmpty || chainHeap.minimum._2 == summon[Fractional[CoefficientT]].zero
      }

      override def equals(obj: Any): Boolean = obj match {
        case that: ChainElement[CellT, CoefficientT] =>
          this.toMap == that.toMap
      }

      override def hashCode(): Int = chainHeap.hashCode()

      override def clone(): ChainElement[CellT, CoefficientT] =
        new ChainElement(chainHeap)

      override def toString: String =
        chainHeap.toStream
          .map((cell, coeff) => s"""${coeff} *> ${cell}""")
          .mkString(" + ")
    }

    object ChainElement {
      given [CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT]: Order[(CellT, CoefficientT)] =
        chainEntryOrder()

      def from[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT: Fractional](
        cc: List[(CellT, CoefficientT)]
      ) = new ChainElement[CellT, CoefficientT](Heap.fromData(cc))

      def apply[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT: Fractional](
        cc: List[(CellT, CoefficientT)]
      ): ChainElement[CellT, CoefficientT] = from(cc)

      def apply[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT: Fractional](
        cc: (CellT, CoefficientT)*
      ): ChainElement[CellT, CoefficientT] = from(cc.toList)

      def apply[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT](c: CellT)(using
        fr: Fractional[CoefficientT]
      ): ChainElement[CellT, CoefficientT] = from(List(c -> fr.one))
    }

    object Chain {
      def apply[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT: Fractional](
        cc: (CellT, CoefficientT)*
      ): Chain[CellT, CoefficientT] = ChainElement(cc: _*)

      def apply[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT: Fractional](
        c: CellT
      ): Chain[CellT, CoefficientT] = ChainElement(c)
    }

    class ChainOps[CellT <: Cell[CellT]: scala.math.Ordering, CoefficientT](using fr: Fractional[CoefficientT])
        extends RingModule[ChainElement[CellT, CoefficientT], CoefficientT]
        with Show[ChainElement[CellT, CoefficientT]] {

      import Numeric.Implicits._

      override val zero: ChainElement[CellT, CoefficientT] = ChainElement()

      override def plus(
        x: ChainElement[CellT, CoefficientT],
        y: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] = new ChainElement(x.chainHeap.union(y.chainHeap))

      override def scale(
        x: CoefficientT,
        y: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        new ChainElement(y.chainHeap.map((cell, coeff) => (cell, x * coeff))(y.given_Order_CellT_CoefficientT))

      override def negate(
        x: ChainElement[CellT, CoefficientT]
      ): ChainElement[CellT, CoefficientT] =
        scale(-fr.one, x)

      override def shows(x: ChainElement[CellT, CoefficientT]): String =
        x.chainHeap.toList.map((cell, coeff) => s"${coeff.toString} *> ${cell.toString}").mkString(" + ")

      override def show(x: ChainElement[CellT, CoefficientT]): Cord = Cord(shows(x))
    }
  }
}
