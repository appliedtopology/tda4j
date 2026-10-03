package org.appliedtopology.tda4j
package groups

import org.appliedtopology.tda4j.{given, *}

/** A non-degenerate simplex of the nerve `BG` of a finite group: the tuple `(g_1, ..., g_n)` of NON-identity elements
  * (a tuple containing the identity is degenerate). The empty tuple is the single vertex.
  */
final case class NerveSimplex(entries: Vector[Int]):
  def dim: Int = entries.length

object NerveSimplex:
  given Ordering[NerveSimplex] =
    Ordering.by[NerveSimplex, Vector[Int]](_.entries)(using Ordering.Implicits.seqOrdering[Vector, Int])

/** The nerve of a finite monoid (as a one-object category): an INFINITE simplicial set with `(|M|-1)^n` non-degenerate
  * `n`-simplices, produced lazily; `skeleton(n)` materializes dimensions `0..n`. For a group this is the classifying
  * space `BG`; `subset` restricts to the nerve of a submonoid (e.g. a subgroup `H`, giving `BH`).
  *
  * Faces: `d_0` drops the first entry, `d_n` the last, and `d_i` for `0 < i < n` multiplies entries `i` and `i + 1`; if
  * that product is the identity, the result is degenerate -- `s_{i-1}` applied to the tuple with both entries removed.
  */
final class Nerve(monoid: FiniteMonoid, subset: Option[Set[Int]] = None) extends SimplicialSet[NerveSimplex]:
  private val members = subset.getOrElse((0 until monoid.order).toSet)
  require(members.contains(monoid.identity), "a submonoid contains the identity")
  require(
    members.forall(a => members.forall(b => members.contains(monoid.multiply(a, b)))),
    "subset is not closed under multiplication"
  )
  private val nonIdentity: Vector[Int] = members.filter(_ != monoid.identity).toVector.sorted

  override def ord: Ordering[NerveSimplex] = summon[Ordering[NerveSimplex]]

  override def generatorsAt(n: Int): Iterable[NerveSimplex] =
    if n < 0 then Iterable.empty
    else
      (0 until n).foldLeft(Iterable(NerveSimplex(Vector.empty)))((previous, _) =>
        for s <- previous; x <- nonIdentity yield NerveSimplex(s.entries :+ x)
      )

  override def dimOf(s: NerveSimplex): Int = s.dim

  override val faces: NerveSimplex => IndexedSeq[SSetElement[NerveSimplex]] = s =>
    val t = s.entries
    val n = t.length
    if n == 0 then IndexedSeq.empty
    else
      IndexedSeq.tabulate(n + 1) { i =>
        if i == 0 then SSetElement(Nil, NerveSimplex(t.tail))
        else if i == n then SSetElement(Nil, NerveSimplex(t.init))
        else
          val product = monoid.multiply(t(i - 1), t(i))
          if product == monoid.identity then SSetElement(List(i - 1), NerveSimplex(t.patch(i - 1, Nil, 2)))
          else SSetElement(Nil, NerveSimplex(t.patch(i - 1, Seq(product), 2)))
      }

/** The classifying space `BG` of a finite group `G` (the [[Nerve]] of `G`), truncated, and its filtration by a chain of
  * subgroups -- which makes persistent group (co)homology an ordinary persistence computation.
  *
  * `BG` has `(|G| - 1)^n` non-degenerate `n`-simplices, so this is only feasible for small groups and low degrees
  * (`.claude/DESIGN-persistent-group-cohomology.md` tabulates it).
  */
object ClassifyingSpace:

  /** `B(H)` for the subgroup `H` (default: all of `g`), with simplices up to dimension `maxDim` (`= nerve.skeleton`).
    */
  def apply(
    g: FiniteGroup,
    maxDim: Int,
    subgroup: Option[Set[Int]] = None
  ): FiniteSimplicialSet[NerveSimplex] =
    require(maxDim >= 0)
    nerve(g, subgroup).skeleton(maxDim)

  /** The full, infinite `BH` (`H` = `subgroup`, default all of `g`). */
  def nerve(g: FiniteGroup, subgroup: Option[Set[Int]] = None): Nerve = Nerve(g, subgroup)

  /** The filtration value of a simplex: the index of the first subgroup in `chain` containing all its entries (a face
    * multiplies adjacent entries, so a face never enters later than its coface: monotone by construction). `chain` must
    * be increasing and end with a subgroup containing every element used.
    */
  def filtrationBy(chain: Seq[Set[Int]]): NerveSimplex => Double =
    s =>
      chain.indexWhere(h => s.entries.forall(h.contains)).toDouble.ensuring(_ >= 0, "chain does not cover the simplex")

  /** The persistence diagram `(degree, birth, death)` of the group HOMOLOGY `H_n(H_i; F_p)` as `H_i` runs up a chain of
    * subgroups `H_0 <= H_1 <= ... <= G` (birth/death are chain positions; `+Infinity` = survives to the top), for
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

  /** Dimension of `H_n(X; F_p)` for `n = 0..maxDegree` of a nerve (group or monoid); see [[BettiNumbers]]. */
  def bettiNumbers(nerve: Nerve, maxDegree: Int, prime: Int): Vector[Int] = BettiNumbers(nerve, maxDegree, prime)

  /** Dimension of `H_n(G; F_p)` for `n = 0..maxDegree`: the classes that are never killed. */
  def bettiNumbers(g: FiniteGroup, maxDegree: Int, prime: Int): Vector[Int] =
    val diagram = persistentGroupHomology(g, Seq((0 until g.order).toSet), maxDegree, prime)
    Vector.tabulate(maxDegree + 1)(n => diagram.count((dim, _, death) => dim == n && death.isPosInfinity))
