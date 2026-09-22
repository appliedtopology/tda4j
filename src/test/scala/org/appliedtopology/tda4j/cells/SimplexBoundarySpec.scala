package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.{mutable, ScalaCheck}

/** `Simplex` boundary signs over a signed field, at sizes past 4 vertices. `Set1..Set4` iterate in insertion order, so
  * a boundary accidentally routed through an unordered `Set` looks correct up to 4 vertices and is hash-ordered from 5
  * on -- every sign-sensitive fixture elsewhere stops at 4 vertices, which is how exactly that bug went unnoticed.
  */
class SimplexBoundarySpec extends mutable.Specification with ScalaCheck:
  val GF3 = new FiniteField(3)
  import GF3.{Fp, given}

  def expectedBoundary(vertices: Seq[Int]): Seq[(Simplex[Int], Fp)] =
    vertices.indices.map { i =>
      (Simplex(vertices.patch(i, Nil, 1)*), if i % 2 == 0 then Fp(1) else Fp(-1))
    }

  "The boundary of a simplex lists faces d_0..d_n with alternating signs, for 1 to 9 vertices" >> {
    forall(1 to 9) { n =>
      val vertices = (0 until n).map(v => 3 * v + 1)
      val bd = Simplex(vertices*).boundary[Fp]
      (bd.map(_._1) must beEqualTo(expectedBoundary(vertices).drop(if n == 1 then 1 else 0).map(_._1))) and
        (bd.map(_._2.toUInt) must beEqualTo(expectedBoundary(vertices).map(_._2.toUInt).take(bd.size)))
    }
  }

  "d(d(x)) = 0 over F3 for simplices with up to 9 vertices" >> {
    val gen: Gen[Simplex[Int]] =
      for
        n <- Gen.choose(1, 9)
        vs <- Gen.pick(n, 0 until 40)
      yield Simplex(vs.toSeq*)
    forAll(gen) { spx =>
      Chain.from[Simplex[Int], Fp](Chain.from[Simplex[Int], Fp](spx.boundary[Fp]).boundary).isZero()
    }
  }
