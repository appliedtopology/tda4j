package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.unicode
import scala.annotation.tailrec

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

case class SimplicialWrapper[T:OrderedCell](wrapped : T) extends SimplicialSetElement {
  override def base: SimplicialSetElement = this
  override def dim: Int = wrapped.dim
  override def degeneracies: List[Int] = List.empty
}


case class DegenerateSimplicialSetElement private (base: SimplicialSetElement, degeneracies: List[Int]) extends SimplicialSetElement:
  override def dim: Int = base.dim + degeneracies.size
  override def toString: String =
    degeneracies
      .map{d => "s" + unicode.unicodeSubScript(d.toString)}
      .mkString("", "  ", " ") + s"${base.toString}"
  // Handle cases where the base is also a simplicial set element
  def join(): DegenerateSimplicialSetElement = base match {
    case oldbase : DegenerateSimplicialSetElement =>
      val newbase : SimplicialSetElement = oldbase.join()
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
    case d :: ds => {
      val (upper, lower): (List[Int], List[Int]) = normalized.partition(d < _ + 1)
      normalizeDegeneracies(upper.map(_ + 1) ++ (d :: lower), ds.reverse)
    }
  }

  def apply(base: SimplicialSetElement, degeneracies: List[Int]): DegenerateSimplicialSetElement =
    new DegenerateSimplicialSetElement(base, normalizeDegeneracies(List.empty, degeneracies))
}

trait SimplicialSetLike {
  def generators : Seq[SimplicialSetElement]
  def face(index: Int): PartialFunction[SimplicialSetElement,SimplicialSetElement]
  def contains(sse: SimplicialSetElement): Boolean

  def all_n_cells(n: Int): List[SimplicialSetElement] =
    generators
      .filter { g => g.dim <= n }
      .flatMap { g =>
        n.to(0, -1)
          .combinations(n - g.dim)
          .map { deg => DegenerateSimplicialSetElement(g.base, (deg ++ g.degeneracies).toList) }
      }.toList
}

/**
 * Class contract: generators are ascending in dimension. This is assumed by things like n_skeleton
 * @param generators
 * @param faceMapping
 */
case class SimplicialSet (generators: LazyList[SimplicialSetElement],
                         faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]])
  extends SimplicialSetLike {
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    case sse if (0 <= index) && (index <= sse.dim) => {
      val split = sse
        .degeneracies
        .groupBy { j => (index < j, index == j || index == j + 1, index > j + 1) }
        .orElse { _ => List.empty }
      inline def INDEX_SMALL = (true, false, false)
      inline def INDEX_MATCH = (false, true, false)
      inline def INDEX_LARGE = (false, false, true)

      val newFace: Option[Int] =
        if (split(INDEX_MATCH).nonEmpty) None // degeneracy and face cancel out if i is j or j+1
        else Some(index - split(INDEX_LARGE).size) // face index decreases one for each smaller degeneracy it commutes past

      val newDegeneracies: List[Int] =
        split(INDEX_SMALL).map(_ - 1) ++ // degeneracy indices decrease by one each time they commute with smaller face index
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

  given sseOrdering : Ordering[SimplicialSetElement] =
    Ordering.by{(sse : SimplicialSetElement) => sse.dim}.orElseBy(_.hashCode())
  given normalizedHomologyCell(using seOrd : Ordering[SimplicialSetElement]) : OrderedCell[SimplicialSetElement] with {
    extension (t: SimplicialSetElement) {
      override def boundary[CoefficientT](using fr: Fractional[CoefficientT]): Chain[SimplicialSetElement, CoefficientT] =
        if(t.dim == 0) Chain() else {
        val pmOne = List(fr.one, fr.negate(fr.one))
        if (t.degeneracies.isEmpty) Chain.from(
          (0 to t.base.dim)
            .map { i => face(i)(t) }
            .zipWithIndex
            .filter { (s,i) => s.degeneracies.isEmpty }
            .map { (s, i) => s -> pmOne(i%2) }
          )
        else Chain()
      }
      override def dim: Int = t.base.dim + t.degeneracies.size
    }

    override def compare(x: SimplicialSetElement, y: SimplicialSetElement): Int =
      seOrd.compare(x,y)
  }

  override def contains(sse: SimplicialSetElement): Boolean =
    generators.exists{g => g.base == sse.base}

  def f_vector(maxDim : Int = 10): Seq[Int] =
    (0 to maxDim)
      .map(generators
        .takeWhile(_.dim <= maxDim)
        .groupBy{g => g.dim}
        .orElse{ _ => LazyList.empty }
      )
      .map(_.size)
}


object SimplicialSet {
  def apply(generators: List[SimplicialSetElement],
            faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]]): SimplicialSet = {
    assert(generators.forall { g => faceMapping.isDefinedAt(g) })
    assert(generators.forall { g => if (g.dim > 0) faceMapping.apply(g).size == g.dim + 1 else true })
    new SimplicialSet(LazyList.from(generators), faceMapping)
  }
}


/** Notes on boundaries of degenerate elements
 *
 * Suppose w = s_j_ z
 * Suppose k > j+1, i < j
 * Then by the simplicial set laws:
 * d_k_ s_j_ z = s_j d_k-1_ z
 * d_i_ s_j_ z = s_j-1_ d_i_ z
 * d_j_ s_j_ z = d_j+1_ s_j_ z = z
 *
 * So from all this follows:
 * ∂ s_j_ z = ∑ (-1)^n^ d_i_ s_j_ z =
 *   s_j_ (d_0_ - d_1_ +  ... ± d_j-1_) z + (z - z) ± s_j_ (d_j+1_ - d_j+2_ + ... ± d_n_) z =
 *   s_j_ ∂ z (??? not sure it all lines up right here?)
 *
 *
 *   Goerss-Jardine III:2, esp. Theorem 2.1:
 *
 *   Normalized Chain Complex NA is isomorphic to the quotient A/DA chain complex clearing out the
 *   degenerate simplices.
 */


/** Normalization is with respect to simplicial identities:
 *
 * Face(i)Face(j) = Face(j-1)Face(i) if i < j
 * Face(i)Degeneracy(j) = Degeneracy(j-1)Face(i) if i < j
 * Face(i)Degeneracy(j) = Degeneracy(j)Face(i-1) if i > j+1
 * Face(j)Degeneracy(j) = 1 = Face(j+1)Degeneracy(j)
 * Degeneracy(i)Degeneracy(j) = Degeneracy(j+1)Degeneracy(j) if i < j+1
 *
 * With these identities we can process any sequence of applications of faces and degeneracies until faces in
 * decreasing order sit to the right (are applied first) and degeneracies are applied on top of these. Since each
 * nondegenerate simplex must know its faces, we only track degeneracies with a specific list. Now:
 * Face(i)Degeneracy(j)Degeneracy(k)Degeneracy(l) =
 * -reduce face index if i > j+1; reduce degeneracy index if i < j Degeneracy(j)Face(i-1)Degeneracy(k)Degeneracy(l) =
 * ...
 */

/****************
 * Constructions
 */

import math.Ordering.Implicits.sortedSetOrdering
class Singular[VertexT:Ordering](val underlying : Seq[Simplex[VertexT]]) extends SimplicialSetLike {
  val generators : Seq[SimplicialWrapper[Simplex[VertexT]]] = underlying.sortBy(_.dim).map { spx => SimplicialWrapper(spx) }
  val faceMapping : Map[SimplicialSetElement, List[SimplicialSetElement]] = Map.from(
    generators.map { spx => spx ->
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



def nSkeleton(n:Int)(ss: SimplicialSet) : SimplicialSet =
  SimplicialSet(ss.generators.takeWhile(_.dim <= n), ss.faceMapping)


def alternateLazyLists[A](left: LazyList[A], right: LazyList[A]): LazyList[A] =
  if(left.isEmpty) right
  else left.head #:: alternateLazyLists(right, left.tail)

case class DisjointUnion(left: SimplicialSet, right: SimplicialSet) extends SimplicialSetLike {
  override def generators: LazyList[SimplicialSetElement] = alternateLazyLists(left.generators, right.generators)
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    case sse if(left.face(index).isDefinedAt(sse)) => left.face(index)(sse)
    case sse if(right.face(index).isDefinedAt(sse)) => right.face(index)(sse)
  }
  override def contains(sse: SimplicialSetElement): Boolean =
    left.contains(sse) || right.contains(sse)
}


case class SubSimplicialSet(ss: SimplicialSet, ambient: SimplicialSet, inclusion: PartialFunction[SimplicialSetElement,SimplicialSetElement]) extends SimplicialSetLike:
  override def generators: LazyList[SimplicialSetElement] = ss.generators
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = ss.face(index)
  override def contains(sse: SimplicialSetElement): Boolean = ss.contains(sse)



/****************
 * Examples
 */

def sphere(dim: Int): SimplicialSet = {
  val v0 = simplicialGenerator(0)
  val wn = simplicialGenerator(dim)
  val dv0 = DegenerateSimplicialSetElement(v0, (dim - 2).to(0, -1).toList)
  SimplicialSet(List(v0, wn), Map(v0 -> List.empty, wn -> List.fill(dim + 1)(dv0)))
}

