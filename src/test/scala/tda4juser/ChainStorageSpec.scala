package tda4juser

import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.util.Random

/** `Chain`'s storage is open to experiment from OUTSIDE the library: a subclass that passes its order and field to
  * `Chain` and implements only `entryIterator` must behave everywhere as the library's own chain with the same entries:
  * terms, zero test, leading term, equality both ways, arithmetic, boundary, and reductions with it as the basis column
  * and as the reduced chain. The entries repeat cells and sum some to zero, as deferred arithmetic leaves them.
  */
class ChainStorageSpec extends Specification:
  import org.appliedtopology.tda4j.*

  /** The plainest storage: the entries as given, with ordinary context bounds (where an experiment would start). */
  final class ListChain[CellT: Ordering, C: Field](stored: List[(CellT, C)]) extends Chain[CellT, C]:
    def entryIterator: Iterator[(CellT, C)] = stored.iterator

  "A chain storage written outside the library" should {
    "behave as the library's chain with the same entries" in {
      val f3 = FiniteField(3)
      import f3.given
      type C3 = Chain[Simplex[Int], f3.Fp]
      val rm = summon[RingModule { type Self = C3; type R = f3.Fp }]
      val rng = Random(5)
      def triangle(): Simplex[Int] = Simplex(rng.shuffle((0 until 6).toList).take(3).sorted*)
      def entries(n: Int): List[(Simplex[Int], f3.Fp)] = List.fill(n)((triangle(), f3.Fp(rng.nextInt(3))))
      val problems = (0 until 80).flatMap { trial =>
        // A repeated cell whose entries cancel (1 + 2 = 0 in F_3), and one whose entries add up.
        val (gone, kept) = (triangle(), triangle())
        val stored = rng.shuffle(
          entries(1 + rng.nextInt(8)) ++ List((gone, f3.Fp(1)), (gone, f3.Fp(2))) ++
            List((kept, f3.Fp(1)), (kept, f3.Fp(1)))
        )
        val mine: C3 = ListChain(stored)
        val theirs: C3 = Chain.from(stored)
        val other: C3 = Chain.from(entries(4))
        val scalar = f3.Fp(2)
        val reductions = theirs.leadingTerm._1.toList.flatMap { lead =>
          val z = rm.scalarMultiply(scalar, theirs)
          Seq(
            "reduceBy, as the basis column" ->
              (Chain.reduceBy(z, mutable.Map(lead -> mine), Chain.empty[Simplex[Int], f3.Fp]) ==
                Chain.reduceBy(z, mutable.Map(lead -> theirs), Chain.empty[Simplex[Int], f3.Fp])),
            "reduceBy, as the reduced chain" ->
              (Chain.reduceBy(mine, mutable.Map(lead -> theirs), Chain.empty[Simplex[Int], f3.Fp]) ==
                Chain.reduceBy(theirs, mutable.Map(lead -> theirs), Chain.empty[Simplex[Int], f3.Fp]))
          )
        }
        (Seq(
          "terms" -> (mine.terms == theirs.terms),
          "cells" -> (mine.cells == theirs.cells),
          "isZero" -> (mine.isZero() == theirs.isZero()),
          "leadingTerm" -> (mine.leadingTerm == theirs.leadingTerm && mine.leadingCell == theirs.leadingCell),
          "equality" -> (mine == theirs && theirs == mine),
          "plus" -> (rm.plus(mine, other) == rm.plus(theirs, other) && rm.plus(other, mine) == rm.plus(other, theirs)),
          "scalarMultiply" -> (rm.scalarMultiply(scalar, mine) == rm.scalarMultiply(scalar, theirs)),
          "minus" -> rm.minus(mine, theirs).isZero(),
          "boundary" -> (Chain.from(mine.boundary) == Chain.from(theirs.boundary))
        ) ++ reductions).collect { case (name, false) => s"trial $trial: $name" }
      }
      val empty: C3 = ListChain(Nil)
      (problems.take(5) must beEmpty) and
        (empty.isZero() must beTrue) and
        (empty == Chain.empty[Simplex[Int], f3.Fp] must beTrue) and
        (empty.leadingTerm must beEqualTo((None, f3.Fp(0))))
    }

    "hash as the library's chains with the same sum, so that they collapse in a Set" in {
      val f3 = FiniteField(3)
      import f3.given
      type C3 = Chain[Int, f3.Fp]
      object IntCells extends CellDecoder[Int]:
        def apply(key: Int): Int = key
      val rng = Random(11)
      val problems = (0 until 80).flatMap { trial =>
        // The sum: distinct cells with the residue 1 or 2 as coefficient.
        val sum = rng.shuffle((0 until 12).toList).take(1 + rng.nextInt(8)).map(cell => (cell, 1 + rng.nextInt(2)))
        val library: C3 = Chain.from(sum.map((cell, r) => (cell, f3.Fp(r))))
        // Written another way: the residue r as r - 3 (Fp(-2) is Fp(1)), one cell split in two entries (r = (r + 1) + 2),
        // and a cell that cancels (1 + 2 = 0).
        val (split, others) = (sum.head, sum.tail)
        val entries = rng.shuffle(
          others.map((cell, r) => (cell, f3.Fp(r - 3))) ++
            List((split._1, f3.Fp(split._2 + 1 - 3)), (split._1, f3.Fp(2 - 3)), (12, f3.Fp(1)), (12, f3.Fp(2)))
        )
        val user: C3 = ListChain(entries)
        val packed: C3 =
          val sorted = sum.sortBy(_._1)
          Chain.packed[Int, f3.Fp](
            sorted.map(_._1).toArray,
            sorted.map((_, r) => f3.Fp(r - 3).asInstanceOf[AnyRef]).toArray,
            IntCells
          )
        // Not a requirement, which constrains equal chains only: the coefficients are hashed.
        val changed: C3 = Chain.from(sum.map((cell, r) => (cell, f3.Fp(if cell == split._1 then 3 - r else r))))
        Seq(
          "the same sum" -> (user == library && packed == library),
          "hash codes" -> (user.## == library.## && packed.## == library.## && library.## == library.##),
          "one element of a Set" -> (Set(library, user, packed).size == 1),
          "a key of a Map" -> (Map(library -> trial).get(user).contains(trial)),
          "another coefficient" -> (changed != library && changed.## != library.##)
        ).collect { case (name, false) => s"trial $trial: $name" }
      }
      problems.take(5) must beEmpty
    }
  }
