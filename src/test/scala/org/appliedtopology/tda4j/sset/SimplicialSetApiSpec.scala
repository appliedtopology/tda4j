package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*
import org.specs2.mutable.Specification

/** The consolidated simplicial-set surface: the `SimplicialSet` catalog, and enumeration / face maps / homology as
  * methods of `FiniteSimplicialSet`. Each check is an invariant the plausible wrong implementation fails -- counts
  * against the Eilenberg-Zilber formula, Euler characteristic against Betti numbers over TWO fields, the simplicial
  * identities on every simplex -- not just "it returns something".
  */
class SimplicialSetApiSpec extends Specification:

  private def binomial(n: Int, k: Int): Int =
    if k < 0 || k > n then 0 else (1 to k).foldLeft(1L)((acc, i) => acc * (n - k + i) / i).toInt

  private val samples: Seq[(String, FiniteSimplicialSet[?])] = Seq(
    "S^2" -> SimplicialSet.sphere(2),
    "S^3" -> SimplicialSet.sphere(3),
    "torus" -> SimplicialSet.torus,
    "Klein bottle" -> SimplicialSet.kleinBottle,
    "RP^3" -> SimplicialSet.realProjectiveSpace(3),
    "CP^2" -> SimplicialSet.complexProjectivePlane,
    "Δ^2" -> SimplicialSet.simplex(2)
  )

  "enumeration" should {
    "list generators per dimension in the set's own order, and all of them dimension by dimension" in {
      val t = SimplicialSet.torus
      import TorusGenerator.*
      (t.generators(1) must beEqualTo(IndexedSeq(A, B, C)))
        .and(t.generators(7) must beEmpty)
        .and(t.allGenerators must beEqualTo(IndexedSeq(Vertex, A, B, C, U, L)))
        .and(t.dimension must beEqualTo(2))
        .and(SimplicialSet.empty.dimension must beEqualTo(-1))
    }
    "be exactly generators(n) plus every s_j of an (n-1)-simplex (independent of the enumeration), Σ C(n, n-p) of them" in {
      samples.forall { (_, x0) =>
        val x = x0.asInstanceOf[FiniteSimplicialSet[Any]]
        (1 to 4).forall { n =>
          val closure = x.generators(n).map(SSetElement(Nil, _)).toSet ++
            x.simplices(n - 1).flatMap(e => (0 until n).map(j => x.degeneracy(j, e)))
          val formula = x.fVector.zipWithIndex.map((count, p) => count * binomial(n, n - p)).sum
          x.simplices(n).toSet == closure && x.simplices(n).size == formula
        }
      } must beTrue
    }
    "never repeat a simplex, and give each the dimension it was asked for" in {
      samples.forall { (_, x) =>
        (0 to 4).forall { n =>
          val all = x.simplices(n)
          all.distinct.size == all.size &&
          all.forall(e => x.dimOf(e.generator) + e.word.length == n)
        }
      } must beTrue
    }
  }

  "the Euler characteristic" should {
    "be the alternating Betti sum over F_2 AND over F_3 (torsion moves Betti numbers, never χ)" in {
      samples.map { (name, x) =>
        def alternating(betti: Vector[Int]) = betti.zipWithIndex.map((b, n) => if n % 2 == 0 then b else -b).sum
        (name, x.eulerCharacteristic, alternating(x.bettiNumbers(2)), alternating(x.bettiNumbers(3)))
      } must beEqualTo(samples.map { (name, x) =>
        (name, x.eulerCharacteristic, x.eulerCharacteristic, x.eulerCharacteristic)
      })
    }
    "take the known values" in
      (SimplicialSet.torus.eulerCharacteristic must beEqualTo(0))
        .and(SimplicialSet.kleinBottle.eulerCharacteristic must beEqualTo(0))
        .and(SimplicialSet.realProjectiveSpace(2).eulerCharacteristic must beEqualTo(1))
        .and(SimplicialSet.complexProjectivePlane.eulerCharacteristic must beEqualTo(3))
        .and(SimplicialSet.sphere(3).eulerCharacteristic must beEqualTo(0))
  }

  "face and degeneracy maps" should {
    "satisfy d_j s_j = d_(j+1) s_j = id and d_i s_j = s_(j-1) d_i (i < j) on every simplex up to dimension 3" in {
      samples.forall { (_, x0) =>
        val x = x0.asInstanceOf[FiniteSimplicialSet[Any]]
        (0 to 3).forall { n =>
          x.simplices(n).forall { e =>
            (0 to n).forall { j =>
              val s = x.degeneracy(j, e)
              x.face(j, s) == e && x.face(j + 1, s) == e &&
              (0 until j).forall(i => n == 0 || x.face(i, s) == x.degeneracy(j - 1, x.face(i, e)))
            }
          }
        }
      } must beTrue
    }
  }

  "the catalog" should {
    "give RP^n with Betti numbers all 1 over F_2 but only H_0 (and H_n for odd n) over F_3" in
      (SimplicialSet.realProjectiveSpace(2).bettiNumbers(2) must beEqualTo(Vector(1, 1, 1)))
        .and(SimplicialSet.realProjectiveSpace(2).bettiNumbers(3) must beEqualTo(Vector(1, 0, 0)))
        .and(SimplicialSet.realProjectiveSpace(3).bettiNumbers(3) must beEqualTo(Vector(1, 0, 0, 1)))
    "give the classifying space of Z/2, whose F_2 homology is F_2 in every degree" in {
      BettiNumbers(SimplicialSet.classifyingSpace(FiniteGroup.cyclic(2)), 3, 2) must beEqualTo(Vector(1, 1, 1, 1))
    }
    "build a hand-written set with SimplicialSet(generatorsByDim, faces)" in {
      enum G derives CanEqual:
        case V, E
      given Ordering[G] = Ordering.by(_.ordinal)
      val circle = SimplicialSet[G](
        IndexedSeq(Set(G.V), Set(G.E)),
        {
          case G.V => IndexedSeq.empty
          case G.E => IndexedSeq(SSetElement(Nil, G.V), SSetElement(Nil, G.V))
        }
      )
      (circle.validate() must beEmpty).and(circle.bettiNumbers(2) must beEqualTo(Vector(1, 1)))
    }
  }

  "persistent homology of a filtered simplicial set" should {
    "work with `import x.given` and `x.filtered(...)`, as the user guide shows" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      enum G derives CanEqual:
        case V, E
      given Ordering[G] = Ordering.by(_.ordinal)
      val circle = SimplicialSet[G](
        IndexedSeq(Set(G.V), Set(G.E)),
        {
          case G.V => IndexedSeq.empty
          case G.E => IndexedSeq(SSetElement(Nil, G.V), SSetElement(Nil, G.V))
        }
      )
      import circle.given
      val diagram = CellularHomologyEngine[G, Double, Double]()
        .persistentHomology(circle.filtered { case G.V => 0.0; case G.E => 1.0 })
        .diagramAt(Double.PositiveInfinity)
      diagram.toSet must beEqualTo(Set((0, 0.0, Double.PositiveInfinity), (1, 1.0, Double.PositiveInfinity)))
    }
  }

  "the numbers quoted on _docs/user-guide/topological-spaces/simplicial-sets.md" should {
    "hold for the torus walkthrough" in {
      val t = SimplicialSet.torus
      import TorusGenerator.*
      (t.dimension must beEqualTo(2))
        .and(t.fVector must beEqualTo(Vector(1, 3, 2)))
        .and(t.generators(1) must beEqualTo(Vector(A, B, C)))
        .and(t.allGenerators must beEqualTo(Vector(Vertex, A, B, C, U, L)))
        .and(t.faces(U) must beEqualTo(IndexedSeq(B, C, A).map(SSetElement(Nil, _))))
        .and(t.simplices(2).size must beEqualTo(9))
        .and(t.eulerCharacteristic must beEqualTo(0))
        .and(t.isConnected must beTrue)
        .and(t.validate() must beEmpty)
    }
    "hold for new spaces from old" in {
      val circle = SimplicialSet.sphere(1)
      val v = MinimalSphereGenerator.Vertex
      val torus = circle.product(circle)
      (torus.fVector must beEqualTo(Vector(1, 3, 2)))
        .and(torus.bettiNumbers(2) must beEqualTo(Vector(1, 2, 1)))
        .and(torus.bettiNumbers(3) must beEqualTo(Vector(1, 2, 1)))
        .and(circle.wedge(v, circle, v).bettiNumbers(2) must beEqualTo(Vector(1, 2)))
        .and(circle.suspension.bettiNumbers(3) must beEqualTo(Vector(1, 0, 1)))
        .and(circle.coproduct(circle).bettiNumbers(2) must beEqualTo(Vector(2, 2)))
    }
    "hold for the homology examples" in
      (SimplicialSet.kleinBottle.bettiNumbers(2) must beEqualTo(Vector(1, 2, 1)))
        .and(SimplicialSet.kleinBottle.bettiNumbers(3) must beEqualTo(Vector(1, 1, 0)))
        .and(SimplicialSet.realProjectiveSpace(2).bettiNumbers(3) must beEqualTo(Vector(1, 0, 0)))
  }
