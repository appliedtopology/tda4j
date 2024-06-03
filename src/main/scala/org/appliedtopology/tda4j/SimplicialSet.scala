package org.appliedtopology.tda4j

import scala.annotation.tailrec

trait SimplicialSetElement {
  def base: SimplicialSetElement
  def dim: Int
  def degeneracies: List[Int]
}

def simplicialGenerator(generatorDim: Int): SimplicialSetElement =
  new SimplicialSetElement:
    override def base: SimplicialSetElement = this
    override def dim: Int = generatorDim
    override def degeneracies: List[Int] = List.empty

case class DegenerateSimplicialSetElement private (base: SimplicialSetElement, degeneracies: List[Int]) extends SimplicialSetElement:
  override def dim: Int = base.dim + degeneracies.size

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
  def generators : List[SimplicialSetElement]
  def face(index: Int): PartialFunction[SimplicialSetElement,SimplicialSetElement]
  def contains(sse: SimplicialSetElement): Boolean

  def all_n_cells(n: Int): List[SimplicialSetElement] =
    generators
      .filter { g => g.dim <= n }
      .flatMap { g =>
        n.to(0, -1)
          .combinations(n - g.dim)
          .map { deg => DegenerateSimplicialSetElement(g.base, (deg ++ g.degeneracies).toList) }
      }
}

case class SimplicialSet private (generators: List[SimplicialSetElement],
                         faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]])
  extends SimplicialSetLike {
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    case sse if (0 <= index) && (index <= sse.dim) => {
      val split = sse.degeneracies.groupBy { j => (index < j, index == j || index == j + 1, index > j + 1) }
      inline def INDEX_SMALL = (true, false, false)
      inline def INDEX_MATCH = (false, true, false)
      inline def INDEX_LARGE = (false, false, true)

      val newFace: Option[Int] =
        if (split(INDEX_MATCH).nonEmpty) None // degeneracy and face cancel out if i is j or j+1
        else Some(index - split(INDEX_LARGE).size) // face index decreases one for each smaller degeneracy it commutes past

      val newDegeneracies: List[Int] =
        split(INDEX_SMALL).map(_ - 1) ++ // degeneracy indices decrease by one each time they commute with smaller face index
          split(INDEX_MATCH).tail ++ // one of the up to two matching degeneracies go away
          split(INDEX_LARGE) // degeneracies unchanged once the face index is large enough

      newFace match {
        case None =>
          DegenerateSimplicialSetElement(sse.base, newDegeneracies)
        case Some(i) =>
          DegenerateSimplicialSetElement(faceMapping.apply(sse.base)(i), newDegeneracies)
      }
    }
  }

  override def contains(sse: SimplicialSetElement): Boolean =
    generators.exists{g => g.base == sse.base}
}


object SimplicialSet {
  def apply(generators: List[SimplicialSetElement],
            faceMapping: PartialFunction[SimplicialSetElement, List[SimplicialSetElement]]): SimplicialSet = {
    assert(generators.forall { g => faceMapping.isDefinedAt(g) })
    assert(generators.forall { g => if (g.dim > 0) faceMapping.apply(g).size == g.dim + 1 else true })
    new SimplicialSet(generators, faceMapping)
  }
}


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

def nSkeleton(n:Int)(ss: SimplicialSet) : SimplicialSet =
  SimplicialSet(ss.generators.filter(_.dim <= n), ss.faceMapping)

case class DisjointUnion(left: SimplicialSet, right: SimplicialSet) extends SimplicialSetLike {
  override def generators: List[SimplicialSetElement] = left.generators ++ right.generators
  override def face(index: Int): PartialFunction[SimplicialSetElement, SimplicialSetElement] = {
    case sse if(left.face(index).isDefinedAt(sse)) => left.face(index)(sse)
    case sse if(right.face(index).isDefinedAt(sse)) => right.face(index)(sse)
  }
  override def contains(sse: SimplicialSetElement): Boolean =
    left.contains(sse) || right.contains(sse)
}


case class SubSimplicialSet(ss: SimplicialSet, ambient: SimplicialSet, inclusion: PartialFunction[SimplicialSetElement,SimplicialSetElement]) extends SimplicialSetLike:
  override def generators: List[SimplicialSetElement] = ss.generators
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

