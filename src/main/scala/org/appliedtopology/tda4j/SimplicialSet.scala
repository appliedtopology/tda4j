package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.unicode

import scala.annotation.tailrec
import scala.math.Fractional.Implicits.infixFractionalOps

trait SimplicialSetElement {
  def base: SimplicialSetElement
  def dim: Int
  def degeneracies: List[Int]
  override def toString: String = unicode.unicodeSuperScript(s"∆$dim")
}

def simplicialGenerator(generatorDim: Int): SimplicialSetElement =
  new SimplicialSetElement:
    override def base: SimplicialSetElement = this
    override def dim: Int = generatorDim
    override def degeneracies: List[Int] = List.empty

case class SimplicialWrapper[T: OrderedCell](wrapped: T) extends SimplicialSetElement {
  override def base: SimplicialSetElement = this
  override def dim: Int = wrapped.dim
  override def degeneracies: List[Int] = List.empty

  override def toString: String = s"wrapped[${wrapped}]"
}

case class DegenerateSimplicialSetElement private (base: SimplicialSetElement, degeneracies: List[Int])
    extends SimplicialSetElement:
  override def dim: Int = base.dim + degeneracies.size
  override def toString: String =
    degeneracies
      .map(d => "s" + unicode.unicodeSubScript(d.toString))
      .mkString("", "  ", " ") + s"${base.toString}"
  // Handle cases where the base is also a simplicial set element
  def join(): DegenerateSimplicialSetElement = base match {
    case oldbase: DegenerateSimplicialSetElement =>
      val newbase: SimplicialSetElement = oldbase.join()
      new DegenerateSimplicialSetElement(
        newbase.base,
        DegenerateSimplicialSetElement.normalizeDegeneracies(newbase.degeneracies, degeneracies)
      )
    case _ => this
  }

object DegenerateSimplicialSetElement {
  @tailrec
  def normalizeDegeneracies(normalized: List[Int], left: List[Int]): List[Int] = left.reverse match {
    case Nil => normalized
    case d :: ds =>
      val (upper, lower): (List[Int], List[Int]) = normalized.partition(d < _ + 1)
      normalizeDegeneracies(upper.map(_ + 1) ++ (d :: lower), ds.reverse)
  }

  def apply(base: SimplicialSetElement, degeneracies: List[Int]): DegenerateSimplicialSetElement =
    new DegenerateSimplicialSetElement(base, normalizeDegeneracies(List.empty, degeneracies))
}

trait SimplicialSetLike {
  def generators: Seq[SimplicialSetElement]
  def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement]
  def contains(sse: SimplicialSetElement): Boolean =
    generators.exists(g => g.base == sse.base)

  def all_n_cells(n: Int): List[SimplicialSetElement] =
    generators
      .filter(g => g.dim <= n)
      .flatMap { g =>
        n.to(0, -1)
          .combinations(n - g.dim)
          .map(deg => DegenerateSimplicialSetElement(g.base, (deg ++ g.degeneracies).toList))
      }
      .toList

  given sseOrdering: Ordering[SimplicialSetElement] =
    Ordering.by((sse: SimplicialSetElement) => sse.dim).orElseBy(_.hashCode())
  given normalizedHomologyCell(using seOrd: Ordering[SimplicialSetElement]): OrderedCell[SimplicialSetElement] with {
    extension (t: SimplicialSetElement) {
      override def boundary[CoefficientT](using
                                          fr: Fractional[CoefficientT]
                                         ): Chain[SimplicialSetElement, CoefficientT] =
        if (t.dim == 0) Chain()
        else {
          val pmOne = List(fr.one, fr.negate(fr.one))
          if (t.degeneracies.isEmpty)
            Chain.from(
              (0 to t.base.dim)
                .map(i => face(i)(t))
                .zipWithIndex
                .filter((s, i) => s.degeneracies.isEmpty)
                .map((s, i) => s -> pmOne(i % 2))
            )
          else Chain()
        }
      override def dim: Int = t.base.dim + t.degeneracies.size
    }
    override def compare(x: SimplicialSetElement, y: SimplicialSetElement): Int =
      seOrd.compare(x, y)
  }

  def f_vector(maxDim: Int = 10): Seq[Int] =
    (0 to maxDim)
      .map(
        generators
          .takeWhile(_.dim <= maxDim)
          .groupBy(g => g.dim)
          .orElse(_ => LazyList.empty)
      )
      .map(_.size)

  def full_f_vector(maxDim: Int = 10): Seq[Int] =
    (0 to maxDim)
      .map(all_n_cells)
      .map(_.size)

  def eulerCharacteristic(maxDim: Int = 10): Int =
    f_vector(maxDim)
      .zipWithIndex
      .map{(f,i) => List(1,-1)(i%2) * f}
      .sum
}

/** Class contract: generators are ascending in dimension. This is assumed by things like n_skeleton
  * @param generators
  * @param faceMapping
  */
case class SimplicialSet(
  generators: LazyList[SimplicialSetElement],
  faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]]
) extends SimplicialSetLike {
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    case sse if (0 <= index) && (index <= sse.dim) =>
      val split = sse.degeneracies
        .groupBy(j => (index < j, index == j || index == j + 1, index > j + 1))
        .orElse(_ => List.empty)
      inline def INDEX_SMALL = (true, false, false)
      inline def INDEX_MATCH = (false, true, false)
      inline def INDEX_LARGE = (false, false, true)

      val newFace: Option[Int] =
        if (split(INDEX_MATCH).nonEmpty) None // degeneracy and face cancel out if i is j or j+1
        else
          Some(index - split(INDEX_LARGE).size) // face index decreases one for each smaller degeneracy it commutes past

      val newDegeneracies: List[Int] =
        split(INDEX_SMALL).map(
          _ - 1
        ) ++ // degeneracy indices decrease by one each time they commute with smaller face index
          split(INDEX_MATCH).drop(1) ++ // one of the up to two matching degeneracies go away
          split(INDEX_LARGE) // degeneracies unchanged once the face index is large enough

      newFace match {
        case None =>
          DegenerateSimplicialSetElement(sse.base, newDegeneracies)
        case Some(i) =>
          DegenerateSimplicialSetElement(faceMapping.apply(sse.base)(i), newDegeneracies).join()
      }
  }
}

object SimplicialSet {
  def apply(
    generators: List[SimplicialSetElement],
    faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]]
  ): SimplicialSet = {
    assert(generators.forall(g => faceMapping.isDefinedAt(g)))
    assert(generators.forall(g => if (g.dim > 0) faceMapping.apply(g).size == g.dim + 1 else true))
    new SimplicialSet(LazyList.from(generators), faceMapping)
  }
}

/** Notes on boundaries of degenerate elements
  *
  * Suppose w = s_j_ z Suppose k > j+1, i < j Then by the simplicial set laws: d_k_ s_j_ z = s_j d_k-1_ z d_i_ s_j_ z =
  * s_j-1_ d_i_ z d_j_ s_j_ z = d_j+1_ s_j_ z = z
  *
  * So from all this follows: ∂ s_j_ z = ∑ (-1)^n^ d_i_ s_j_ z = s_j_ (d_0_ - d_1_ + ... ± d_j-1_) z + (z - z) ± s_j_
  * (d_j+1_ - d_j+2_ + ... ± d_n_) z = s_j_ ∂ z (??? not sure it all lines up right here?)
  *
  * Goerss-Jardine III:2, esp. Theorem 2.1:
  *
  * Normalized Chain Complex NA is isomorphic to the quotient A/DA chain complex clearing out the degenerate simplices.
  */

/** Normalization is with respect to simplicial identities:
  *
  * Face(i)Face(j) = Face(j-1)Face(i) if i < j Face(i)Degeneracy(j) = Degeneracy(j-1)Face(i) if i < j
  * Face(i)Degeneracy(j) = Degeneracy(j)Face(i-1) if i > j+1 Face(j)Degeneracy(j) = 1 = Face(j+1)Degeneracy(j)
  * Degeneracy(i)Degeneracy(j) = Degeneracy(j+1)Degeneracy(j) if i < j+1
  *
  * With these identities we can process any sequence of applications of faces and degeneracies until faces in
  * decreasing order sit to the right (are applied first) and degeneracies are applied on top of these. Since each
  * nondegenerate simplex must know its faces, we only track degeneracies with a specific list. Now:
  * Face(i)Degeneracy(j)Degeneracy(k)Degeneracy(l) =
  * -reduce face index if i > j+1; reduce degeneracy index if i < j Degeneracy(j)Face(i-1)Degeneracy(k)Degeneracy(l) =
  * ...
  */

/** ************** Constructions
  */

import math.Ordering.Implicits.sortedSetOrdering
class Singular[VertexT: Ordering](val underlying: Seq[Simplex[VertexT]]) extends SimplicialSetLike {
  val allSimplices = underlying.toSet.flatMap(spx => spx.vertices.subsets.filter(_.nonEmpty).map(Simplex.from))
  val generators: Seq[SimplicialWrapper[Simplex[VertexT]]] =
    allSimplices.toSeq.sortBy(_.dim).map(spx => SimplicialWrapper(spx))
  val faceMapping: Map[SimplicialSetElement, List[SimplicialSetElement]] = Map.from(
    generators.map { spx =>
      spx ->
        (0 to spx.dim).map { i =>
          SimplicialWrapper(Simplex(spx.wrapped.vertices.drop(i)))
        }.toList
    }
  )
  val simplicialSet = SimplicialSet(LazyList.from(generators), faceMapping)

  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] =
    simplicialSet.face(index)
  override def contains(sse: SimplicialSetElement): Boolean =
    simplicialSet.contains(sse)
}

def normalizedCellStream[FiltrationT: Ordering: Filterable](
  ss: SimplicialSetLike,
  filtrationValueO: Option[PartialFunction[SimplicialSetElement, FiltrationT]] = None
): CellStream[SimplicialSetElement, FiltrationT] = {
  val filterable: Filterable[FiltrationT] = summon[Filterable[FiltrationT]]
  def filtrationValues = filtrationValueO.getOrElse({ case _ =>
    filterable.smallest
  })
  given sseCell: Cell[SimplicialSetElement] = ss.normalizedHomologyCell
  given sseOrd: Ordering[SimplicialSetElement] = Ordering
    .by[SimplicialSetElement, FiltrationT] { (sse: SimplicialSetElement) =>
      filtrationValues.applyOrElse(sse, { _ => filterable.smallest }) }
    .orElseBy(_.dim)
  new CellStream[SimplicialSetElement, FiltrationT] {
      export filterable.{largest, smallest}

      override def filtrationValue: PartialFunction[SimplicialSetElement, FiltrationT] = filtrationValues

      override def filtrationOrdering: Ordering[SimplicialSetElement] = sseOrd

      override def iterator: Iterator[SimplicialSetElement] =
        ss.generators.sorted(filtrationOrdering).iterator
    }
  }


def nSkeleton(n: Int)(ss: SimplicialSet): SimplicialSet =
  SimplicialSet(ss.generators.takeWhile(_.dim <= n), ss.faceMapping)

def alternateLazyLists[A](left: LazyList[A], right: LazyList[A]): LazyList[A] =
  if (left.isEmpty) right
  else left.head #:: alternateLazyLists(right, left.tail)

case class Coproduct(left: SimplicialSet, right: SimplicialSet) extends SimplicialSetLike {
  override def generators: LazyList[SimplicialSetElement] = alternateLazyLists(left.generators, right.generators)
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] =
    left.face(index).orElse(right.face(index))
  override def contains(sse: SimplicialSetElement): Boolean =
    left.contains(sse) || right.contains(sse)
}

/** For now this is only written for finitely generated simplicial sets.
  *
  * Anything with potentially infinite generator sets will need special handling.
  */

case class ProductElement(left: SimplicialSetElement, right: SimplicialSetElement) extends SimplicialSetElement {
  assert(left.dim == right.dim)
  override def base: SimplicialSetElement = this
  override def dim: Int = left.dim
  override def degeneracies: List[Int] = List()

  override def toString: String =
    s"${left.toString} × ${right.toString}"
}

case class Product(left: SimplicialSet, right: SimplicialSet) extends SimplicialSetLike {
  given productOrdering: Ordering[ProductElement] =
    Ordering
      .by((pe: ProductElement) => pe.left)(left.sseOrdering)
      .orElseBy((pe: ProductElement) => pe.right)(right.sseOrdering)
  given productCell: OrderedCell[ProductElement] with {
    extension (t: ProductElement)
      override def boundary[CoefficientT: Fractional]: Chain[ProductElement, CoefficientT] = {
        val leftBoundary: Chain[SimplicialSetElement, CoefficientT] = {
          import left.{normalizedHomologyCell, given}
          t.left.boundary[CoefficientT]
        }
        val rightBoundary: Chain[SimplicialSetElement, CoefficientT] = {
          import right.{normalizedHomologyCell, given}
          t.right.boundary[CoefficientT]
        }
        val productTerms = for
          (sseL, cL) <- leftBoundary.items
          (sseR, cR) <- rightBoundary.items
        yield ProductElement(sseL, sseR) -> (cL * cR)
        Chain.from(productTerms)
      }

    extension (t: ProductElement) override def dim: Int = t.left.dim

    override def compare(x: ProductElement, y: ProductElement): Int =
      productOrdering.compare(x, y)
  }
  val generatorPairs: LazyList[(SimplicialSetElement, SimplicialSetElement)] = for
    gL <- left.generators
    gR <- right.generators
  yield (gL, gR)
  def productGenerators(dimension: Int): LazyList[SimplicialSetElement] =
    generatorPairs
      .filter((gL, gR) => gL.dim + gR.dim >= dimension)
      .filter((gL, gR) => gL.dim.max(gR.dim) <= dimension)
      .flatMap { (gL, gR) =>
        val degeneracyIndexPool = (0 to dimension).reverse
        val totalCount = (dimension - gL.dim) + (dimension - gR.dim)
        degeneracyIndexPool
          .combinations(totalCount)
          .flatMap { degeneracies =>
            degeneracies
              .combinations(dimension - gL.dim)
              .map(comb => (comb, degeneracies.diff(comb)))
              .filter{(dL, dR) =>
                dL.reverse.zipWithIndex.forall { (d, i) => d <= gL.dim + i } &&
                dR.reverse.zipWithIndex.forall { (d, i) => d <= gR.dim + i }
              }
              .map { (dL, dR) =>
                SimplicialWrapper(
                  ProductElement(
                    DegenerateSimplicialSetElement(gL, dL.toList),
                    DegenerateSimplicialSetElement(gR, dR.toList)
                  )
                )
              }
          }
      }
  val maxdim = (left.generators.map(_.dim).max) + (right.generators.map(_.dim).max)
  override def generators: Seq[SimplicialSetElement] =
    (0 to maxdim).flatMap(productGenerators)
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    // see e.g. https://ncatlab.org/nlab/show/product+of+simplices#ProductsOfSimplicialSets prop 2.1.
    case sse @ SimplicialWrapper(ProductElement(sseL, sseR)) =>
      SimplicialWrapper(ProductElement(left.face(index)(sseL), right.face(index)(sseR)))
  }
  override def contains(sse: SimplicialSetElement): Boolean =
    generators.contains(sse.base)
}

case class SubSimplicialSet(
  ss: SimplicialSetLike,
  ambient: SimplicialSetLike,
  inclusion: PartialFunction[SimplicialSetElement, SimplicialSetElement]
) extends SimplicialSetLike {
  override def generators: LazyList[SimplicialSetElement] = ss.generators
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = ss.face(index)
  override def contains(sse: SimplicialSetElement): Boolean = ss.contains(sse)
}


case class QuotientSimplicialSet(
  superSet: SimplicialSetLike,
  quotientSet: SimplicialSetLike,
                                )

/** The Pushout of a diagram
 *
 * $left \xrightarrow{f} center \xleftarrow{g} right$
 * is the subset of the product $left \times right$ of elements that hit the same
 * value in $center$.
 *
 * @param left
 * @param center
 * @param right
 * @param f
 * @param g
 */
case class Pushout(
  left: SimplicialSetLike, center: SimplicialSetLike, right: SimplicialSetLike,
  f: PartialFunction[SimplicialSetElement, SimplicialSetElement],
  g: PartialFunction[SimplicialSetElement, SimplicialSetElement]
                   ) extends SimplicialSetLike {
  lazy val ambient: SimplicialSetLike = Product(left, right)
  
  override def generators: Seq[SimplicialSetElement] =
    for
      p : ProductElement <- ambient.generators
      if(f(p.left) == g(p.right))
    yield
      p
      
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] =
    {
      case ProductElement(leftS, rightS) => ProductElement(left.face(index)(leftS), right.face(index)(rightS))
    }
}


/** ************** Examples
  */

def sphere(dim: Int): SimplicialSet = {
  val v0 = simplicialGenerator(0)
  val wn = simplicialGenerator(dim)
  val dv0 = DegenerateSimplicialSetElement(v0, (dim - 2).to(0, -1).toList)
  SimplicialSet(List(v0, wn), Map(v0 -> List.empty, wn -> List.fill(dim + 1)(dv0)))
}
