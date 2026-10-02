package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/persistent-group-cohomology.md` and asserts every number the page quotes. */
class GroupHomologySpec extends Specification:
  sequential

  private case class Result(
    s3: Map[Int, Vector[Int]],
    chainOrders: Seq[Int],
    bars: Map[(Int, Double, Double), Int],
    barsOverF3: Map[(Int, Double, Double), Int],
    cells: Seq[Long]
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    // The dimensions of H_n(S_3; F_p), n = 0..4, for p = 2 and p = 3
    val s3 = groups.FiniteGroup.symmetric(3)
    val s3Homology = Seq(2, 3).map(p => p -> groups.ClassifyingSpace.bettiNumbers(s3, 4, p)).toMap

    // S_4, built from permutations, and a chain of subgroups: <(01)> < <(01),(23)> < D_8 < S_4
    val (s4, generators) = groups.FiniteGroup.permutationGroup(
      4,
      Seq(Seq(1, 0, 2, 3), Seq(0, 1, 3, 2), Seq(2, 3, 0, 1), Seq(1, 2, 3, 0))     // (01), (23), (02)(13) and the 4-cycle (0123)
    )
    val Seq(swap01, swap23, doubleSwap, _) = generators
    val chain = Seq(
      s4.subgroupGeneratedBy(Seq(swap01)),
      s4.subgroupGeneratedBy(Seq(swap01, swap23)),
      s4.subgroupGeneratedBy(Seq(swap01, swap23, doubleSwap)),
      (0 until s4.order).toSet
    )

    // The persistent homology of the classifying spaces of the subgroups in the chain, in degrees 0, 1, 2
    def bars(prime: Int) =
      groups.ClassifyingSpace.persistentGroupHomology(s4, chain, maxDegree = 2, prime = prime).groupBy(identity).view.mapValues(_.size).toMap

    // How many cells a classifying space has in each dimension: (|G| - 1)^n
    val cells = for g <- Seq(6, 24, 120); n <- Seq(2, 3, 4) yield BigInt(g - 1).pow(n).toLong

    Result(s3Homology, chain.map(_.size), bars(2), bars(3), cells)

  "persistent-group-cohomology.md" should {
    lazy val r = page()
    "give the homology of S_3 it quotes" in {
      println(r)
      (r.s3(2) must beEqualTo(Vector(1, 1, 1, 1, 1))).and(r.s3(3) must beEqualTo(Vector(1, 0, 0, 1, 1)))
    }
    "build the chain of subgroups of orders 2, 4, 8, 24" in { r.chainOrders must beEqualTo(Seq(2, 4, 8, 24)) }
    "give the S_4 barcode over F_2 it quotes" in {
      r.bars must beEqualTo(
        Map(
          (0, 0.0, Double.PositiveInfinity) -> 1,
          (1, 0.0, Double.PositiveInfinity) -> 1,
          (1, 1.0, 2.0) -> 1,
          (1, 2.0, 3.0) -> 1,
          (2, 0.0, Double.PositiveInfinity) -> 1,
          (2, 1.0, 2.0) -> 1,
          (2, 1.0, Double.PositiveInfinity) -> 1,
          (2, 2.0, 3.0) -> 1
        )
      )
    }
    "have nothing but the connected component over F_3 (every subgroup here is a 2-group)" in {
      r.barsOverF3 must beEqualTo(Map((0, 0.0, Double.PositiveInfinity) -> 1))
    }
    "count cells as (|G|-1)^n" in {
      r.cells must beEqualTo(Seq(25L, 125L, 625L, 529L, 12167L, 279841L, 14161L, 1685159L, 200533921L))
    }
  }
