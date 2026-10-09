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
        val stored = rng.shuffle(entries(1 + rng.nextInt(8)) ++ List((gone, f3.Fp(1)), (gone, f3.Fp(2))) ++
          List((kept, f3.Fp(1)), (kept, f3.Fp(1))))
        val mine: C3 = ListChain(stored)
        val theirs: C3 = Chain.from(stored)
        val other: C3 = Chain.from(entries(4))
        val scalar = f3.Fp(2)
        val reductions = theirs.leadingTerm._1.toList.flatMap { lead =>
          val z = rm.scale(scalar, theirs)
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
          "scale" -> (rm.scale(scalar, mine) == rm.scale(scalar, theirs)),
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
  }
