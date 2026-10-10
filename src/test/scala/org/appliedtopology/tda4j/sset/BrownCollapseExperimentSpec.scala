package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification
import scala.collection.mutable

/** EXPERIMENT (`.claude/WORKLOG-brown-collapse.md`): can the bar complex of a group be compressed by a discrete Morse
  * matching and still filter by a chain of subgroups? Brown's collapsing scheme ("The geometry of rewriting systems",
  * 1992) matches the cells of the nerve of a monoid presented by a complete rewriting system, leaving one critical cell
  * per Anick chain. A matched pair never straddles two filtration levels when the normal form of every element of each
  * subgroup uses only that subgroup's letters (a polycyclic presentation refining the chain), so the Morse complex,
  * filtered by its critical cells' own levels, should have the bar complex's barcode.
  *
  * Test-tree only: a rewriting system given by its normal forms, Brown's classification, and the Morse complex computed
  * by the flow (eliminate redundant cells by their collapsible partners' boundaries).
  */
object BrownCollapse:

  /** A finite monoid with a complete rewriting system, given by the normal form (a word in `letters`, which are element
    * indices) of every element; the rules are the minimal non-normal words, each rewritten to its normal form.
    */
  final case class Rewriting(monoid: FiniteMonoid, letters: IndexedSeq[Int], normalForm: Map[Int, Vector[Int]]):
    private val elementOfWord: Map[Vector[Int], Int] = normalForm.map(_.swap)
    private val rank: Map[Int, Int] = letters.zipWithIndex.toMap
    def isNormal(w: Vector[Int]): Boolean = elementOfWord.contains(w)
    def evaluate(w: Vector[Int]): Int = w.foldLeft(monoid.identity)(monoid.multiply)
    private def shortlexLess(u: Vector[Int], v: Vector[Int]): Boolean =
      u.length < v.length ||
        (u.length == v.length && Ordering.Implicits.seqOrdering[Vector, Int].lt(u.map(rank), v.map(rank)))

    /** Every minimal non-normal word (its longest proper prefix and suffix are normal), with its normal form. */
    lazy val rules: Seq[(Vector[Int], Vector[Int])] =
      for
        v <- normalForm.values.toSeq
        b <- letters
        w = v :+ b
        if !isNormal(w) && isNormal(w.tail)
      yield (w, normalForm(evaluate(w)))

    /** Empty when the normal forms are a complete rewriting system: one word per element, evaluating to it, closed
      * under taking factors, and every rule shortlex-decreasing (so rewriting terminates).
      */
    def validate(): Seq[String] =
      val words = normalForm.values.toSeq
      Seq(
        Option.when(normalForm.keySet != (0 until monoid.order).toSet)("not one normal form per element"),
        Option.when(elementOfWord.size != normalForm.size)("two elements share a normal form"),
        Option.when(normalForm(monoid.identity).nonEmpty || words.count(_.isEmpty) != 1)("only 1 has the empty word"),
        normalForm.find((x, w) => evaluate(w) != x).map((x, _) => s"the normal form of $x evaluates elsewhere"),
        letters.find(a => normalForm(a) != Vector(a)).map(a => s"letter $a is not its own normal form"),
        words
          .find(w => (0 until w.length).exists(i => (i + 1 to w.length).exists(j => !isNormal(w.slice(i, j)))))
          .map(w => s"a factor of $w is not normal"),
        rules.find((w, r) => !shortlexLess(r, w)).map((w, r) => s"rule $w -> $r does not decrease")
      ).flatten

  enum Role:
    case Critical
    case Redundant(coface: NerveSimplex)
    case Collapsible(face: NerveSimplex)

  /** Brown's collapsing scheme on the nerve of `rw.monoid`. */
  final class Collapse(val rw: Rewriting):
    val nerve: Nerve = Nerve(rw.monoid)
    private def nf(x: Int) = rw.normalForm(x)
    private def reducible(w: Vector[Int]) = !rw.isNormal(w)
    private val roles = mutable.HashMap.empty[NerveSimplex, Role]

    /** `[x_1|...|x_n]` is essential up to `i` when `x_1` is a letter and each `x_{j+1}` is the shortest word making
      * `x_j x_{j+1}` reducible. At the first `j` where that fails: if `x_j x_{j+1}` is irreducible the cell is
      * collapsible (partner: merge them); otherwise a proper prefix `u` of `x_{j+1}` already makes it reducible and the
      * cell is redundant (partner: split `x_{j+1} = u v`). A first entry longer than a letter is split after its first
      * letter. Essential all the way: critical.
      */
    def role(s: NerveSimplex): Role = roles.getOrElseUpdate(s, classify(s))

    private def classify(s: NerveSimplex): Role =
      val xs = s.entries
      if xs.isEmpty then Role.Critical
      else if nf(xs(0)).length > 1 then
        val w = nf(xs(0))
        Role.Redundant(NerveSimplex(Vector(w.head, rw.evaluate(w.tail)) ++ xs.tail))
      else
        (0 until xs.length - 1).iterator
          .map { i =>
            val (left, right) = (nf(xs(i)), nf(xs(i + 1)))
            if !reducible(left ++ right) then
              Some(Role.Collapsible(NerveSimplex(xs.patch(i, Seq(rw.evaluate(left ++ right)), 2))))
            else
              val k = (1 to right.length).find(k => reducible(left ++ right.take(k))).get
              Option.when(k < right.length)(
                Role.Redundant(
                  NerveSimplex(xs.patch(i + 1, Seq(rw.evaluate(right.take(k)), rw.evaluate(right.drop(k))), 1))
                )
              )
          }
          .collectFirst { case Some(r) => r }
          .getOrElse(Role.Critical)

    /** The normalized bar boundary with integer coefficients. */
    def boundary(s: NerveSimplex): Map[NerveSimplex, Long] =
      nerve
        .faces(s)
        .zipWithIndex
        .collect { case (SSetElement(Nil, t), i) => (t, if i % 2 == 0 then 1L else -1L) }
        .groupMapReduce(_._1)(_._2)(_ + _)
        .filter(_._2 != 0)

    def incidence(coface: NerveSimplex, face: NerveSimplex): Long = boundary(coface).getOrElse(face, 0L)

    /** The Morse boundary of the critical cell `c` (critical cells only), and the correction `Σ a τ` over collapsible
      * cells with `ι(c) = c + Σ a τ` the chain map back into the bar complex. Each step removes a redundant face `σ` by
      * adding a multiple of `∂τ`, `τ` its partner; the incidence is ±1, so coefficients stay integers.
      */
    def flow(c: NerveSimplex): (Map[NerveSimplex, Long], Map[NerveSimplex, Long]) =
      val x = mutable.HashMap.from(boundary(c))
      val correction = mutable.HashMap.empty[NerveSimplex, Long]
      var steps = 0
      def nextRedundant: Option[(NerveSimplex, NerveSimplex)] =
        x.keysIterator.map(s => (s, role(s))).collectFirst { case (s, Role.Redundant(t)) => (s, t) }
      var next = nextRedundant
      while next.isDefined do
        val (s, t) = next.get
        val inc = incidence(t, s)
        require(math.abs(inc) == 1, s"incidence of $s in $t is $inc")
        val coefficient = -x(s) * inc
        for (f, b) <- boundary(t) do x(f) = x.getOrElse(f, 0L) + coefficient * b
        correction(t) = correction.getOrElse(t, 0L) + coefficient
        x.filterInPlace((_, v) => v != 0)
        steps += 1
        require(steps < 1000000, "the flow does not terminate: the matching is not acyclic")
        next = nextRedundant
      (x.filter((s, _) => role(s) == Role.Critical).toMap, correction.filter(_._2 != 0).toMap)

  /** `n` copies of `one` (with sign): an integer in any field. */
  def fromLong[F: Field as fr](a: Long): F =
    val m = (1L to math.abs(a)).foldLeft(fr.zero)((acc, _) => acc + fr.one)
    if a < 0 then -m else m

  /** The critical cells of dimension `0..top`, each with the cell structure of the Morse complex, as a filtered stream.
    */
  final class MorseStream(
    val collapse: Collapse,
    top: Int,
    value: NerveSimplex => Double
  ):
    val critical: IndexedSeq[Vector[NerveSimplex]] =
      IndexedSeq.tabulate(top + 1)(n => collapse.nerve.generators(n).filter(collapse.role(_) == Role.Critical).toVector)
    val flows: Map[NerveSimplex, (Map[NerveSimplex, Long], Map[NerveSimplex, Long])] =
      critical.flatten.map(c => c -> collapse.flow(c)).toMap

    given morseCell: (NerveSimplex is OrderedCell) = new (NerveSimplex is OrderedCell):
      override lazy val ordering = summon[Ordering[NerveSimplex]]
      extension (c: NerveSimplex)
        override def dim = c.dim
        override def boundary[F: Field as fr]: Seq[(NerveSimplex, F)] =
          flows(c)._1.toSeq.map((s, a) => (s, fromLong[F](a)))

    val stream: StratifiedCellStream[NerveSimplex, Double] = new StratifiedCellStream[NerveSimplex, Double]:
      val filtrationValue: PartialFunction[NerveSimplex, Double] = PartialFunction.fromFunction(value)
      override val filtrationOrdering: Ordering[NerveSimplex] =
        FiltrationOrdering.canonical(filtrationValue, _.dim, summon[Ordering[NerveSimplex]])
      override def iterateDimension: PartialFunction[Int, Iterator[NerveSimplex]] =
        case d if d >= 0 && d < critical.length => critical(d).sorted(using filtrationOrdering.reverse).iterator
      export Filterable.DoubleIsFilterable.{largest, smallest}

    /** Whether every critical cell's Morse boundary enters no later than the cell itself. */
    def monotone: Boolean = critical.flatten.forall(c => flows(c)._1.keys.forall(s => value(s) <= value(c)))

    /** The Morse complex's barcode over `F_p` in degrees `0..top-1`, and whether every representative, mapped back by
      * `ι`, is a cycle of the bar complex made of cells present at the bar's birth.
      */
    def barcode(prime: Int): (List[(Int, Double, Double)], Boolean) =
      val field = new FiniteField(prime)
      import field.given
      val state = CellularPersistenceInChunksEngine[NerveSimplex, field.Fp](top - 1).persistentHomology(stream)
      val bars = state.barcodeAt(Double.PositiveInfinity)
      val repsValid = bars.forall { b =>
        b.annotation.exists { rep =>
          val lifted: Map[NerveSimplex, field.Fp] = rep.terms
            .flatMap((c, a) => ((c, 1L) +: flows(c)._2.toSeq).map((s, k) => (s, a * fromLong[field.Fp](k))))
            .groupMapReduce(_._1)(_._2)(_ + _)
          val bdry = lifted.toSeq
            .flatMap((s, a) => collapse.boundary(s).toSeq.map((f, k) => (f, a * fromLong[field.Fp](k))))
            .groupMapReduce(_._1)(_._2)(_ + _)
          lifted.exists(!_._2.isZero) && bdry.values.forall(_.isZero) &&
          lifted.filter(!_._2.isZero).keys.forall(s => value(s) <= b.birth)
        }
      }
      (bars.map(_.toTriple).sorted, repsValid)

class BrownCollapseExperimentSpec extends Specification:
  import BrownCollapse.*
  sequential

  private val inf = Double.PositiveInfinity

  // Z/4 = {0, 1, 2, 3} (residues). Polycyclic: g1 = 1, g2 = 2, normal forms g1^a g2^b, refining Z/2 = {0, 2} < Z/4.
  private val z4 = FiniteGroup.cyclic(4)
  private val z4pc =
    Rewriting(z4, IndexedSeq(1, 2), Map(0 -> Vector(), 1 -> Vector(1), 2 -> Vector(2), 3 -> Vector(1, 2)))
  // One generator a = 1, normal forms a^k: element 2 = "aa" uses a letter outside Z/2.
  private val z4one =
    Rewriting(z4, IndexedSeq(1), Map(0 -> Vector(), 1 -> Vector(1), 2 -> Vector(1, 1), 3 -> Vector(1, 1, 1)))
  private val z4chain = Seq(Set(0, 2), Set(0, 1, 2, 3))

  // Z/8, polycyclic g1 = 1, g2 = 2, g3 = 4, refining Z/2 = {0, 4} < Z/4 = {0, 2, 4, 6} < Z/8.
  private val z8 = FiniteGroup.cyclic(8)
  private val z8pc = Rewriting(
    z8,
    IndexedSeq(1, 2, 4),
    (0 until 8).map(x => x -> Vector(1, 2, 4).filter(bit => (x & bit) != 0)).toMap
  )
  private val z8chain = Seq(Set(0, 4), Set(0, 2, 4, 6), (0 until 8).toSet)

  // S_3: g1 = (0 1), g2 = (0 1 2), h = g2^2; normal forms g1^a (1 | g2 | h), refining Z/3 = {1, g2, h} < S_3. All
  // powers are letters so that every rule is length-non-increasing: g2 g1 -> g1 h, h g1 -> g1 g2, ...
  private val s3 = FiniteGroup.symmetric(3)
  private val g1 = FiniteGroup.elementOfPermutation(s3, Seq(1, 0, 2))
  private val g2 = FiniteGroup.elementOfPermutation(s3, Seq(1, 2, 0))
  private val h = s3.multiply(g2, g2)
  private val s3pc =
    val words = for a <- Seq(Vector(), Vector(g1)); b <- Seq(Vector(), Vector(g2), Vector(h)) yield a ++ b
    Rewriting(s3, IndexedSeq(g1, g2, h), words.map(w => w.foldLeft(s3.identity)(s3.multiply) -> w).toMap)
  private val s3chain = Seq(Set(s3.identity, g2, h), (0 until 6).toSet)

  private def levels(chain: Seq[Set[Int]]) = ClassifyingSpace.filtrationBy(chain)

  /** The bar complex's own barcode (the oracle side), degrees 0..maxDegree. */
  private def barBarcode(rw: Rewriting, chain: Seq[Set[Int]], maxDegree: Int, p: Int) =
    Nerve(rw.monoid).persistentHomology(levels(chain), maxDegree, p).triples.sorted

  "the rewriting systems" should {
    "be complete" in {
      Seq(z4pc, z4one, z8pc, s3pc).flatMap(_.validate()) must beEmpty
    }
    "have the rules worked out by hand" in
      (z4pc.rules.toSet must beEqualTo(
        Set(Vector(1, 1) -> Vector(2), Vector(2, 1) -> Vector(1, 2), Vector(2, 2) -> Vector())
      ))
        .and(z4one.rules must beEqualTo(Seq(Vector(1, 1, 1, 1) -> Vector())))
        .and(s3pc.rules.size must beEqualTo(7))
  }

  "Brown's matching" should {
    "be an involution with incidence ±1 on every cell up to dimension 5" in {
      Seq(z4pc, z4one, z8pc, s3pc).forall { rw =>
        val collapse = Collapse(rw)
        (0 to 5).forall { n =>
          collapse.nerve.generators(n).forall { s =>
            collapse.role(s) match
              case Role.Critical     => true
              case Role.Redundant(t) =>
                collapse.role(t) == Role.Collapsible(s) && math.abs(collapse.incidence(t, s)) == 1
              case Role.Collapsible(f) => collapse.role(f) == Role.Redundant(s)
          }
        }
      } must beTrue
    }
    "leave n + 1 critical n-cells for the polycyclic Z/4, one for the one-generator Z/4, against 3^n in the bar complex" in {
      def counts(rw: Rewriting) =
        val collapse = Collapse(rw)
        (0 to 5).map(n => collapse.nerve.generators(n).count(collapse.role(_) == Role.Critical))
      (counts(z4pc) must beEqualTo(Seq(1, 2, 3, 4, 5, 6))).and(counts(z4one) must beEqualTo(Seq(1, 1, 1, 1, 1, 1)))
    }
    "never straddle two levels of the chain under a polycyclic presentation refining it, and straddle under the other" in {
      def straddles(rw: Rewriting, chain: Seq[Set[Int]]) =
        val collapse = Collapse(rw)
        (0 to 4).map { n =>
          collapse.nerve.generators(n).count { s =>
            collapse.role(s) match
              case Role.Redundant(t) => levels(chain)(t) != levels(chain)(s)
              case _                 => false
          }
        }.sum
      (straddles(z4pc, z4chain) must beEqualTo(0))
        .and(straddles(z8pc, z8chain) must beEqualTo(0))
        .and(straddles(s3pc, s3chain) must beEqualTo(0))
        .and(straddles(z4one, z4chain) must beGreaterThan(0))
    }
  }

  "the bar complex (the oracle side)" should {
    "have the barcodes derived by hand" in
      // Z/2 < Z/4 over F_2: odd degrees H(Z/n;Z) ⊗ F_2 under ×2 = 0 (a class dies, one is born); even degrees Tor, iso.
      // Z/3 < S_3 over F_3: H_*(S_3; F_3) = 1, 0, 0, 1, 1 and Z/3 (a Sylow subgroup) surjects. Over F_2: Z/3 acyclic.
      (barBarcode(z4pc, z4chain, 4, 2) must beEqualTo(
        List((0, 0.0, inf), (1, 0.0, 1.0), (1, 1.0, inf), (2, 0.0, inf), (3, 0.0, 1.0), (3, 1.0, inf), (4, 0.0, inf))
      ))
        .and(
          barBarcode(s3pc, s3chain, 4, 3) must beEqualTo(
            List((0, 0.0, inf), (1, 0.0, 1.0), (2, 0.0, 1.0), (3, 0.0, inf), (4, 0.0, inf))
          )
        )
        .and(
          barBarcode(s3pc, s3chain, 4, 2) must beEqualTo(
            List((0, 0.0, inf), (1, 1.0, inf), (2, 1.0, inf), (3, 1.0, inf), (4, 1.0, inf))
          )
        )
  }

  "the Morse complex filtered by its critical cells' levels" should {
    "have the bar complex's barcode, with representatives that lift to cycles present at birth" in {
      val cases =
        Seq((z4pc, z4chain, 2), (z4pc, z4chain, 3), (z8pc, z8chain, 2), (s3pc, s3chain, 2), (s3pc, s3chain, 3))
      cases.map { (rw, chain, p) =>
        val morse = MorseStream(Collapse(rw), 5, levels(chain))
        val (bars, repsValid) = morse.barcode(p)
        (morse.monotone, bars, repsValid)
      } must beEqualTo(cases.map((rw, chain, p) => (true, barBarcode(rw, chain, 4, p), true)))
    }
    "with the one-generator presentation, still have the right homology but not the right barcode" in {
      val morse = MorseStream(Collapse(z4one), 5, levels(z4chain))
      val (bars, _) = morse.barcode(2)
      val essentialCounts = (0 to 4).map(n => bars.count(b => b._1 == n && b._3.isPosInfinity))
      (essentialCounts must beEqualTo(Seq(1, 1, 1, 1, 1)))
        .and(bars must not(beEqualTo(barBarcode(z4one, z4chain, 4, 2))))
    }
  }
