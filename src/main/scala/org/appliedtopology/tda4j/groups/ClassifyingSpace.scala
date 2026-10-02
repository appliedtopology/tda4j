package org.appliedtopology.tda4j
package groups

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.homology.CellularPersistenceInChunksEngine
import org.appliedtopology.tda4j.streams.FilteredSimplicialSetStream

/** A non-degenerate simplex of the nerve `BG` of a finite group: the tuple `(g_1, ..., g_n)` of NON-identity elements
  * (a tuple containing the identity is degenerate). The empty tuple is the single vertex.
  */
final case class NerveSimplex(entries: Vector[Int]):
  def dim: Int = entries.length

object NerveSimplex:
  given Ordering[NerveSimplex] = Ordering.by[NerveSimplex, Vector[Int]](_.entries)(using Ordering.Implicits.seqOrdering[Vector, Int])

/** The classifying space `BG` of a finite group as a simplicial set (the nerve of `G` as a one-object category), TRUNCATED
  * above a chosen dimension, and its filtration by a chain of subgroups -- which makes persistent group (co)homology
  * an ordinary persistence computation.
  *
  * `BG` has `(|G| - 1)^n` non-degenerate `n`-simplices, so this is only feasible for small groups and low degrees
  * (`.claude/DESIGN-persistent-group-cohomology.md` tabulates it). Faces: `d_0` drops the first entry, `d_n` the last,
  * and `d_i` for `0 < i < n` multiplies entries `i` and `i + 1`; if that product is the identity, the result is
  * degenerate -- `s_{i-1}` applied to the tuple with both entries removed.
  */
object ClassifyingSpace:

  /** `B(H)` for the subgroup `H` (default: all of `g`), with simplices up to dimension `maxDim`. */
  def apply(
    g: FiniteGroup,
    maxDim: Int,
    subgroup: Option[Set[Int]] = None
  ): FiniteSimplicialSet[NerveSimplex] =
    require(maxDim >= 0)
    val members = subgroup.getOrElse((0 until g.order).toSet)
    require(members.contains(g.identity), "a subgroup contains the identity")
    val nonIdentity = members.filter(_ != g.identity).toVector.sorted
    val byDim: IndexedSeq[Set[NerveSimplex]] =
      IndexedSeq.iterate(Set(NerveSimplex(Vector.empty)), maxDim + 1) { previous =>
        for s <- previous; x <- nonIdentity yield NerveSimplex(s.entries :+ x)
      }

    def faces(s: NerveSimplex): IndexedSeq[SSetElement[NerveSimplex]] =
      val t = s.entries
      val n = t.length
      if n == 0 then IndexedSeq.empty
      else
        IndexedSeq.tabulate(n + 1) { i =>
          if i == 0 then SSetElement(Nil, NerveSimplex(t.tail))
          else if i == n then SSetElement(Nil, NerveSimplex(t.init))
          else
            val product = g.multiply(t(i - 1), t(i))
            if product == g.identity then SSetElement(List(i - 1), NerveSimplex(t.patch(i - 1, Nil, 2)))
            else SSetElement(Nil, NerveSimplex(t.patch(i - 1, Seq(product), 2)))
        }
    new FiniteSimplicialSet(byDim, faces)

  /** The filtration value of a simplex: the index of the first subgroup in `chain` containing all its entries (a
    * face multiplies adjacent entries, so a face never enters later than its coface: monotone by construction).
    * `chain` must be increasing and end with a subgroup containing every element used.
    */
  def filtrationBy(chain: Seq[Set[Int]]): NerveSimplex => Double =
    s => chain.indexWhere(h => s.entries.forall(h.contains)).toDouble.ensuring(_ >= 0, "chain does not cover the simplex")

  /** The persistence diagram `(degree, birth, death)` of the group HOMOLOGY `H_n(H_i; F_p)` as `H_i` runs up a chain
    * of subgroups `H_0 <= H_1 <= ... <= G` (birth/death are chain positions; `+Infinity` = survives to the top), for
    * `n <= maxDegree`. Over a finite field, group cohomology has the same dimensions.
    */
  def persistentGroupHomology(
    g: FiniteGroup,
    chain: Seq[Set[Int]],
    maxDegree: Int,
    prime: Int
  ): List[(Int, Double, Double)] =
    require(chain.nonEmpty && chain.zip(chain.drop(1)).forall((a, b) => a.subsetOf(b)), "chain must be increasing")
    val top = chain.last
    val sset = apply(g, maxDegree + 1, Some(top))
    val field = new FiniteField(prime)
    import field.given
    given (NerveSimplex is OrderedCell) = sset.cellInstance
    val stream = FilteredSimplicialSetStream(sset, PartialFunction.fromFunction(filtrationBy(chain)))
    // Dimension maxDegree + 1 is present only so that degree maxDegree classes die correctly; its own bars are incomplete.
    CellularPersistenceInChunksEngine[NerveSimplex, field.Fp]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
      .filter((dim, birth, death) => dim <= maxDegree && death > birth)
      .sorted

  /** Dimension of `H_n(G; F_p)` for `n = 0..maxDegree`: the classes that are never killed. */
  def bettiNumbers(g: FiniteGroup, maxDegree: Int, prime: Int): Vector[Int] =
    val diagram = persistentGroupHomology(g, Seq((0 until g.order).toSet), maxDegree, prime)
    Vector.tabulate(maxDegree + 1)(n => diagram.count((dim, _, death) => dim == n && death.isPosInfinity))
