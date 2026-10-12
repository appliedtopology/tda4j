package org.appliedtopology.tda4j

import org.specs2.mutable

import scala.collection.mutable as m
import scala.util.Random

/** A packed chain (how the grid engines store representatives: cells as integer keys, decoded when read) must behave
  * exactly as the heap chain with the same terms: the same terms in the same order, leading term, zero test, equality
  * both ways, arithmetic, boundary, and reductions with a packed chain as the basis column or as the reduced chain.
  * Integer cells are taken under an order AND its reverse, since a packed chain is stored in its own order, whatever
  * that is; cube cells under `cubeOrdering`, the fast engine's. Coefficients in F_2, F_3, F_17 and the reals.
  */
class PackedChainSpec extends mutable.Specification:
  object IntCells extends CellDecoder[Int]:
    def apply(key: Int): Int = key

  /** Whether a chain's terms come in its own order, strictly ascending: what a packed chain must store. */
  def ascending[C](c: Chain[Int, C]): Boolean =
    c.terms.map(_._1).sliding(2).forall(w => w.size < 2 || c.cellOrdering.lt(w(0), w(1)))

  /** The packed chain of `terms` (distinct cells, nonzero coefficients), stored in the order in scope. */
  def pack[C: Field](terms: Seq[(Int, C)])(using order: Ordering[Int]): Chain[Int, C] =
    val sorted = terms.sortBy(_._1)
    Chain.packed[Int, C](sorted.map(_._1).toArray, sorted.map(_._2.asInstanceOf[AnyRef]).toArray, IntCells)

  /** Every way a packed chain differs from the heap chain with the same random terms under `order`. */
  def differences[C](seed: Long, order: Ordering[Int], coefficient: Random => C)(using fr: C is Field): Seq[String] =
    given Ordering[Int] = order
    val rng = Random(seed)
    val rm = summon[RingModule { type Self = Chain[Int, C]; type R = C }]
    def randomTerms(size: Int, cells: Seq[Int]): List[(Int, C)] =
      rng.shuffle(cells.toList).take(size).map(k => (k, coefficient(rng)))
    val terms = randomTerms(1 + rng.nextInt(8), 0 until 20)
    val packed = pack(terms)
    val heap = Chain.from[Int, C](rng.shuffle(terms))
    val lead = heap.leadingCell.get
    val scalar = coefficient(rng)
    // Cells after the leading one, so that `lead` stays the pivot of the sums below.
    val other = Chain.from[Int, C](randomTerms(rng.nextInt(6), (0 until 30).filter(order.gt(_, lead))))
    val empty = pack(Seq.empty[(Int, C)])
    val misordered = Chain.packed[Int, C](
      terms.sortBy(_._1)(using order.reverse).map(_._1).toArray,
      terms.sortBy(_._1)(using order.reverse).map(_._2.asInstanceOf[AnyRef]).toArray,
      IntCells
    )
    val z = rm.plus(rm.scale(scalar, heap), other)
    val column = rm.plus(heap, other)
    val termsBefore = packed.terms
    Seq(
      "terms" -> (packed.terms == heap.terms),
      "cells" -> (packed.cells == heap.cells),
      "isZero" -> (packed.isZero() == heap.isZero()),
      "leadingTerm" -> (packed.leadingTerm == heap.leadingTerm),
      "equals" -> (packed == heap && heap == packed && packed == pack(terms)),
      "rawEntries" -> (packed.rawEntries.toSet == heap.rawEntries.toSet),
      "plus" -> (rm.plus(packed, other) == rm.plus(heap, other) && rm.plus(other, packed) == rm.plus(other, heap)),
      "plus itself" -> (rm.plus(packed, packed) == rm.plus(heap, heap)),
      "scale" -> (rm.scale(scalar, packed) == rm.scale(scalar, heap)),
      "negate" -> (rm.negate(packed) == rm.negate(heap)),
      "minus" -> rm.minus(packed, heap).isZero(),
      // A packed basis column: its leading coefficient drives the reduction step.
      "reduceBy, packed basis" ->
        (Chain.reduceBy(z, m.Map(lead -> packed), Chain.empty[Int, C]) ==
          Chain.reduceBy(z, m.Map(lead -> heap), Chain.empty[Int, C])),
      "reduceBy, packed reduced chain" ->
        (Chain.reduceBy(packed, m.Map(lead -> column), Chain.empty[Int, C]) ==
          Chain.reduceBy(heap, m.Map(lead -> column), Chain.empty[Int, C])),
      "empty" -> (empty.isZero() && empty.terms.isEmpty && empty.leadingTerm == (None, fr.zero) &&
        empty == Chain.empty[Int, C] && Chain.empty[Int, C] == empty),
      // Equality does not see the order (formal sums), so the stored order is checked on its own: what the engine
      // specs check of every representative, to catch an engine that sorts them wrongly.
      "stored in its own order" -> (ascending(packed) && (terms.size < 2 || !ascending(misordered))),
      "reads leave it packed and unchanged" -> (packed.isPacked && packed.terms == termsBefore)
    ).collect { case (name, false) => name }

  "A packed chain behaves as the heap chain with the same terms" >> {
    val f2 = FiniteField(2)
    val f3 = FiniteField(3)
    val f17 = FiniteField(17)
    val orders = Seq("ascending" -> Ordering.Int, "descending" -> Ordering.Int.reverse)
    // Nonzero integer-valued reals: sums stay exact, so `==` on coefficients is safe.
    def real(r: Random): Double = Seq(-3.0, -2.0, -1.0, 1.0, 2.0, 3.0)(r.nextInt(6))
    val problems = for
      (orderName, order) <- orders
      seed <- 0L until 60L
      problem <- Seq(
        { import f2.given; differences[f2.Fp](seed, order, _ => f2.Fp(1)).map("F_2 " + _) },
        { import f3.given; differences[f3.Fp](seed, order, r => f3.Fp(1 + r.nextInt(2))).map("F_3 " + _) },
        { import f17.given; differences[f17.Fp](seed, order, r => f17.Fp(1 + r.nextInt(16))).map("F_17 " + _) }, {
          given Double is Field = Field.DoubleApproximated(1e-9)
          differences[Double](seed, order, real).map("reals " + _)
        }
      ).flatten
    yield s"$orderName, seed $seed: $problem"
    problems.take(5) must beEmpty
  }

  "Grid cells: a key decodes to its cube, and keys order as cubeOrdering does" >> {
    val shape = Array(3, 2, 4)
    val extent = shape.map(n => 2 * n + 1)
    val cubes = GridCubes(shape)
    def expected(k: Int): Cube =
      Cube.fromVector(Vector(k / (extent(1) * extent(2)), (k / extent(2)) % extent(1), k % extent(2)))
    val rng = Random(7)
    val pairs = Seq.fill(3000)((rng.nextInt(cubes.size), rng.nextInt(cubes.size)))
    (cubes.size must beEqualTo(extent.product)) and
      ((0 until cubes.size).forall(k => cubes(k) == expected(k) && cubes.keyOf(cubes(k)) == k) must beTrue) and
      (pairs.forall((a, b) =>
        Integer.signum(Integer.compare(a, b)) == Integer.signum(cubeOrdering.compare(cubes(a), cubes(b)))
      ) must beTrue)
  }

  "A packed chain of cubes has the heap chain's boundary" >> {
    given Ordering[Cube] = cubeOrdering
    val f3 = FiniteField(3)
    import f3.given
    val cubes = GridCubes(Array(3, 4))
    val rng = Random(11)
    val problems = (0 until 60).flatMap { trial =>
      val keys = rng.shuffle((0 until cubes.size).toList).take(1 + rng.nextInt(10)).sorted
      val coefficients = keys.map(_ => f3.Fp(1 + rng.nextInt(2)))
      val packed = Chain.packed[Cube, f3.Fp](keys.toArray, coefficients.map(_.asInstanceOf[AnyRef]).toArray, cubes)
      val heap = Chain.from[Cube, f3.Fp](keys.zip(coefficients).map((k, x) => (cubes(k), x)))
      Option.when(packed != heap || Chain.from(packed.boundary) != Chain.from(heap.boundary))(s"trial $trial")
    }
    problems must beEmpty
  }
