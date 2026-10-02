package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Asserts every number `_docs/tutorials/persistent-group-cohomology.md` quotes, on the values its "whole script" fence
  * defines (see `build.sbt`).
  */
class GroupHomologySpec extends Specification:
  sequential

  private val page = PersistentGroupCohomologyScript

  "persistent-group-cohomology.md" should {
    "give the homology of S_3 it quotes" in
      (page.s3Homology(2) must beEqualTo(Vector(1, 1, 1, 1, 1)))
        .and(page.s3Homology(3) must beEqualTo(Vector(1, 0, 0, 1, 1)))
    "build the chain of subgroups of orders 2, 4, 8, 24" in { page.chain.map(_.size) must beEqualTo(Seq(2, 4, 8, 24)) }
    "give the S_4 barcode over F_2 it quotes" in {
      page.overF2 must beEqualTo(
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
      page.overF3 must beEqualTo(Map((0, 0.0, Double.PositiveInfinity) -> 1))
    }
    "count cells as (|G|-1)^n" in {
      val cells = for g <- Seq(6, 24, 120); n <- Seq(2, 3, 4) yield BigInt(g - 1).pow(n).toLong
      cells must beEqualTo(Seq(25L, 125L, 625L, 529L, 12167L, 279841L, 14161L, 1685159L, 200533921L))
    }
  }
