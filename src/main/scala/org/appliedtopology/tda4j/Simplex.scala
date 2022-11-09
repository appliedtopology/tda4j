package org.appliedtopology.tda4j

import scala.collection.{SortedIterableFactory, SortedSetFactoryDefaults, StrictOptimizedSortedSetOps, mutable}
import scala.collection.immutable.{SortedSet, SortedSetOps, TreeSet}
import scala.math.Ordering.Double.IeeeOrdering
import scala.math.Ordering.IntOrdering

/**
 * Lightweight trait to define what it means to be a topological "Cell".
 */
trait Cell {
  /**
   * The boundary map of the cell.
   *
   * @return A sequence of boundary cells.
   * @todo When a `Chain` trait or class has been created, this should change to return an
   *       appropriate `Chain` instead of the current `List`.
   */
  def boundary() : Seq[Cell]
}

/**
 * Lightweight trait to capture the property of having a filtration value.
 *
 * @tparam FiltrationT Filtration value, used for priority sorting of filtered simplex streams.
 */
trait Filtered[FiltrationT] {
  val filtrationValue : FiltrationT
}

/**
 * Lightweight trait to capture lower bounds of possibly interesting filtration value
 * types. This is in order to make the creation of filtered simplices without giving
 * explicit filtration values possible - so that, eg, [[FilteredAbstractSimplex.empty]]
 * can be implemented.
 *
 * @tparam FiltrationT Type of filtration values.
 */
trait LowerBounded[FiltrationT] {
  val minimumValue : FiltrationT
}

/**
 * Implicit lower bound for filtration values of type Double.
 */
implicit object lowerBoundedDouble extends LowerBounded[Double] {
  override val minimumValue: Double = Double.NegativeInfinity
}

/**
 * Filtered abstract simplexes. This is the fundamental building block of the entire [[TDA4j]] library.
 * Inherits most topological structure from the [[AbstractSimplex]] class.
 *
 * @param filtrationValue Filtration value for this simplex - a parameter value guaranteeing the existence
 *                        of the simplex at this parameter value and larger values of the parameter.
 * @param vertices Vertices of the simplex.
 * @param ordering$V$0 (implicit) total order of the vertices.
 * @param lowerBounded$F$0 (implicit) lower bound with default value for the filtration values
 * @tparam VertexT Vertex type
 * @tparam FiltrationT Filtration value, used for priority sorting of filtered simplex streams.
 */
class FilteredAbstractSimplex[VertexT : Ordering, FiltrationT : LowerBounded]
  (override val filtrationValue: FiltrationT, vertices: VertexT*)
  extends AbstractSimplex[VertexT](vertices : _*)
  with Filtered[FiltrationT]
  with SortedSet[VertexT]
  with Cell {
  self =>

  // ***** Overriding for inheriting and extending standard library constructions
  override def className = "FilteredAbstractSimplex"

  override def incl(elem: VertexT): FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex[VertexT,FiltrationT](self.filtrationValue, self.vertexSet.incl(elem).to(Seq) : _*)

  override def excl(elem: VertexT): FilteredAbstractSimplex[VertexT, FiltrationT] =
    FilteredAbstractSimplex[VertexT, FiltrationT](self.filtrationValue, self.vertexSet.excl(elem).to(Seq): _*)

  override def rangeImpl(
                          from: Option[VertexT],
                          until: Option[VertexT]
                        ): FilteredAbstractSimplex[VertexT, FiltrationT] =
    FilteredAbstractSimplex[VertexT, FiltrationT](filtrationValue, self.vertexSet.rangeImpl(from, until).toSeq: _*)

  override def intersect(that: collection.Set[VertexT]): FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex(filtrationValue, self.vertexSet.intersect(that).toSeq: _*)

  override def empty: FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex.empty

  override def filter(pred: VertexT => Boolean): FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex(filtrationValue, self.vertexSet.filter(pred).toSeq : _*)

  override def init: FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex(filtrationValue, self.vertexSet.init.toSeq : _*)

  override def inits: Iterator[FilteredAbstractSimplex[VertexT,FiltrationT]] =
    self.vertexSet.inits.map(vtx => FilteredAbstractSimplex(filtrationValue, vtx.toSeq : _*))

  override def grouped(size: Int): Iterator[FilteredAbstractSimplex[VertexT,FiltrationT]] =
    self.vertexSet.grouped(size).map(vtx => FilteredAbstractSimplex(filtrationValue, vtx.toSeq : _*))

  override def slice(from: Int, until: Int): FilteredAbstractSimplex[VertexT,FiltrationT] =
    FilteredAbstractSimplex(filtrationValue, self.vertexSet.slice(from, until).toSeq : _*)

  override def sliding(size: Int, step: Int): Iterator[FilteredAbstractSimplex[VertexT,FiltrationT]] =
    self.vertexSet.sliding(size, step).map(vtx => FilteredAbstractSimplex(filtrationValue, vtx.toSeq : _*))

  override def subsets(len: Int): Iterator[FilteredAbstractSimplex[VertexT,FiltrationT]] =
    self.vertexSet.subsets(len).map(vtx => FilteredAbstractSimplex(filtrationValue, vtx.toSeq : _*))

  override def subsets(): Iterator[FilteredAbstractSimplex[VertexT, FiltrationT]] =
    self.vertexSet.subsets().map(vtx => FilteredAbstractSimplex(filtrationValue, vtx.toSeq: _*))

  def equals(that: FilteredAbstractSimplex[VertexT, FiltrationT]): Boolean =
    AbstractSimplex.from[VertexT](self.iterator).equals(AbstractSimplex.from[VertexT](that.iterator))

  def equals(that: AbstractSimplex[VertexT]): Boolean =
    AbstractSimplex.from[VertexT](self.iterator).equals(that)
}

/**
 * Companion object for the [[FilteredAbstractSimplex]] class. Contains factory methods for the
 * creation of new `FilteredAbstractSimplex` objects.
 */
object FilteredAbstractSimplex {
  /**
   * Static constructor of FilteredAbstractSimplex[V,F].
   *
   * Use like:
   * {{{
   *   val s : FilteredAbstractSimplex[Int,Double] = FilteredAbstractSimplex(0.5, 1, 2, 3)
   * }}}
   * @param filtrationValue Filtration value of the simplex.
   * @param vertices Vertices of the simplex.
   * @tparam VertexT Vertex type
   * @tparam FiltrationT Filtration value type
   * @return An instantiated filtered abstract simplex
   */
  def apply[VertexT:Ordering,FiltrationT:LowerBounded](filtrationValue : FiltrationT, vertices: VertexT*) =
    new FilteredAbstractSimplex[VertexT,FiltrationT](filtrationValue, vertices : _*)

  /**
   * Create an empty filtered abstract simplex at the lower bound.
   *
   * @param lowerBounded Lower bound / default value of the filtration type
   * @tparam VertexT Vertex type
   * @tparam FiltrationT Filtration value type
   * @return `FilteredAbstractSimplex[V,F](lowerBounded.minimumValue)`
   */
  def empty[VertexT: Ordering, FiltrationT](using lowerBounded: LowerBounded[FiltrationT]): FilteredAbstractSimplex[VertexT, FiltrationT] =
    new FilteredAbstractSimplex[VertexT, FiltrationT](lowerBounded.minimumValue)

  /**
   * Create a filtered abstract simplex from an iterator with vertices.
   *
   * @param source Iterator with vertices
   * @tparam VertexT Vertex type
   * @tparam FiltrationT Filtration value type
   * @return FilteredAbstractSimplex instantiated at the lower bound default value
   */
  def from[VertexT: Ordering, FiltrationT:LowerBounded](source: IterableOnce[VertexT]): FilteredAbstractSimplex[VertexT, FiltrationT] =
    (newBuilder[VertexT, FiltrationT] ++= source).result()

  /**
   * Builder for a filtered abstract simplex at the lower bound default filtration value.
   * Overrides almost everything in `mutable.ImmutableBuilder` to deal with the fact that the
   * vertex accumulator and the finished simplex are not compatible types.
   * This in turns was necessary because the `SortedSetOps` and similar mixin traits cannot handle
   * type parameters that are irrelevant to the `SortedSetOps` specification.
   *
   * @param lowerBounded Lower bound / default value of the filtration type
   * @tparam VertexT Vertex type
   * @tparam FiltrationT Filtration value type
   * @return Builder that generates a FilteredAbstractSimplex[V,F] from the accumulated vertices.
   */
  def newBuilder[VertexT: Ordering, FiltrationT](using lowerBounded: LowerBounded[FiltrationT]): mutable.Builder[VertexT, FilteredAbstractSimplex[VertexT, FiltrationT]] =
    new mutable.ImmutableBuilder[VertexT, FilteredAbstractSimplex[VertexT, FiltrationT]](empty) {
      protected var actualelems : mutable.Set[VertexT] = mutable.Set[VertexT]()

      override def result(): FilteredAbstractSimplex[VertexT,FiltrationT] =
        new FilteredAbstractSimplex[VertexT,FiltrationT](lowerBounded.minimumValue, actualelems.to(Seq) : _*)

      override def clear() : Unit = { actualelems = mutable.Set[VertexT]() }

      def addOne(elem: VertexT): this.type =
        actualelems += elem;
        this
    }
}

/**
 * Type alias creating `FilteredSimplex` as the type representing `FilteredAbstractSimplex[Int,Double]`
 *
 * @todo Figure out whether it would be better to make `FilteredSimplex` a class of its own and override
 *       everything to get the type to print out at `FilteredSimplex` everywhere instead of as the more
 *       verbose `FilteredAbstractSimplex`
 */
type FilteredSimplex = FilteredAbstractSimplex[Int,Double]
object FilteredSimplex {
  def apply(filtrationValue : Double, vertices : Int*) =
    new FilteredSimplex(filtrationValue, vertices : _*)
}

/**
 * Type alias creating `Simplex` as the type representing `AbstractSimplex[Int]`
 *
 * @todo Figure out whether it would be better to make `Simplex` a class of its own and override
 *       everything to get the type to print out at `Simplex` everywhere instead of as the more
 *       verbose `AbstractSimplex`
 */
type Simplex = AbstractSimplex[Int]

object Simplex extends AbstractSimplex[Int] {
  def apply(vertices: Int*) = new Simplex(vertices: _*)
}

/**
 * Class representing an abstract simplex. Abstract simplices are sets (of totally ordered vertices)
 * and thus are represented by a type that implements the interface of a `SortedSet` fully, and also
 * inherits from `Cell` so that the class has a `boundary` method.
 *
 * With this `SortedSet` interface in place, there is scope for using `AbstractSimplex` for combinatorial
 * algebraic topology tasks unrelated to persistence, and possibly for writing persistence algorithms
 * more smoothly.
 *
 * @param vertices Vertices of the simplex
 * @param ordering Ordering of the vertex type
 * @tparam VertexT Vertex type
 */
class AbstractSimplex[VertexT](vertices: VertexT*)(implicit val ordering: Ordering[VertexT])
    extends SortedSet[VertexT]
    with SortedSetOps[VertexT, AbstractSimplex, AbstractSimplex[VertexT]]
    with SortedSetFactoryDefaults[VertexT, AbstractSimplex, Set]
    with Cell
    {
  self => //gives methods access to the object that's calling it in the first place

  protected val vertexSet = TreeSet[VertexT](vertices: _*)(ordering)

  // ***** Simplex specific operations

      /**
       * Implementation of the face enumeration for the simplicial boundary map:
       * exclude one element at a time, and use alternating signs.
       *
       *  @return A sequence of boundary cells.
       *  @todo Change `List` to `Chain` once we have an implementation of `Chain`
       */
  override def boundary(): List[AbstractSimplex[VertexT]] =
    self.subsets(self.size - 1).to(List)

  // ***** Overriding for inheriting and extending standard library constructions
  override def className = "AbstractSimplex"

  override def iterator: Iterator[VertexT] = vertexSet.iterator

  override def excl(elem: VertexT): AbstractSimplex[VertexT] = new AbstractSimplex[VertexT](
    vertexSet.excl(elem).toSeq: _*
  )

  override def incl(elem: VertexT): AbstractSimplex[VertexT] = new AbstractSimplex[VertexT](
    vertexSet.incl(elem).toSeq: _*
  )

  override def contains(elem: VertexT): Boolean = vertexSet.contains(elem)

  override def rangeImpl(
                          from: Option[VertexT],
                          until: Option[VertexT]
  ): AbstractSimplex[VertexT] =
    new AbstractSimplex[VertexT](vertexSet.rangeImpl(from, until).toSeq: _*)

  override def iteratorFrom(start: VertexT): Iterator[VertexT] =
    vertexSet.iteratorFrom(start)

  override def sortedIterableFactory: SortedIterableFactory[AbstractSimplex] =
    AbstractSimplex
}

/** Simplex companion object with factory methods
  */
object AbstractSimplex extends SortedIterableFactory[AbstractSimplex] {
  override def empty[VertexT: Ordering]: AbstractSimplex[VertexT] = new AbstractSimplex[VertexT]()

  override def from[VertexT: Ordering](source: IterableOnce[VertexT]): AbstractSimplex[VertexT] =
    (newBuilder[VertexT] ++= source).result()

  override def newBuilder[VertexT: Ordering]: mutable.Builder[VertexT, AbstractSimplex[VertexT]] =
    new mutable.ImmutableBuilder[VertexT, AbstractSimplex[VertexT]](empty) {
      def addOne(elem: VertexT): this.type =
        elems += elem; this
    }
}
